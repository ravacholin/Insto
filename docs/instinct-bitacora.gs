/**
 * Bitácora de Insto (Protocolo Insto v6, Insto 1.19.0). Apps Script ligado a una planilla de Google.
 * Ver docs/INSTINCT-PROPUESTAS.md, «Bitácora (1.15.0)», «Estado del teléfono», «Buzón firmado» (1.16.0), «Timbre» (1.17.0),
 * «Orden directa en el timbre» y «Catálogo vivo» (1.18.0) y «Timbre cifrado» (1.19.0).
 * Es compatible con Insto 1.15.0 a 1.18.0: un POST o un GET sin las acciones nuevas se comportan exactamente igual que antes.
 *
 * 1. En la planilla: Extensiones → Apps Script. Pegá este archivo y cambiá TOKEN por un texto largo y aleatorio.
 * 2. Implementar → Nueva implementación → Aplicación web. Ejecutar como: yo. Quién tiene acceso: cualquier usuario.
 * 3. Copiá la URL que termina en /exec y pegala, con el token, en Insto → Ajustes → Propuestas de Instinct → Bitácora.
 *
 * POST {token, events:[{id, at, kind, order, detail, message, protocol}]}  → {ok, accepted:[ids]}
 *   Los ids repetidos se descartan y se confirman igual (idempotencia).
 * GET ?token=…&since=<ISO>&limit=50  → {ok, events:[{recibido, evento_id, ocurrido, tipo, orden, detalle, mensaje_id, protocolo}]}
 *   Los eventos recibidos desde «since», en orden, como máximo 50.
 *   Para «respuesta», evento_id es el id de ejecución y ocurrido es la hora de registro en UTC.
 *   orden contiene el texto de la pregunta o la acción. detalle contiene la respuesta o el resultado
 *   de la acción, con el id de la pregunta o la regla de origen.
 *   mensaje_id y protocolo quedan vacíos. El mismo evento_id se confirma sin volver a insertar la fila.
 * GET ?token=…&action=estado  → {ok, estado:{recibido, evento_id, ocurrido, tipo, orden, detalle, mensaje_id, protocolo} | null}
 *   El último evento de tipo «estado» que publicó Insto (1.16.0), o null si todavía no hay.
 *
 * Buzón firmado (1.16.0), en la hoja «Buzón». El script NO verifica la firma: no tiene la clave, solo guarda.
 * POST {token, action:"orden", id, at, lines:[…], sig}  → {ok, id}   (un id repetido se confirma y no se anota de nuevo)
 * GET ?token=…&action=ordenes&limit=10  → {ok, ordenes:[{recibido, orden_id, at, lineas:[…], firma}]}   (las no tomadas, en orden)
 * POST {token, action:"tomadas", ids:[…]}  → {ok, tomadas:N}
 *
 * Timbre (1.17.0): el endpoint de UnifiedPush (ntfy) del teléfono, guardado en las propiedades del script, nunca en la planilla.
 * POST {token, action:"timbre", endpoint[, conOrden]}  → {ok, timbre:"guardado"|"borrado"[, conOrden]}
 *   (endpoint vacío lo borra; https:// y 500 caracteres como máximo). conOrden (1.18.0), true o false, decide si el timbre
 *   lleva la orden firmada; si falta, se conserva el valor guardado (así un Insto 1.17.0 no lo cambia).
 * POST {token, action:"probar-timbre"}  → {ok, timbre:true|false, codigo}   ({ok:false, error:"sin-endpoint"} si no hay endpoint)
 * Después de anotar una orden NUEVA en el Buzón, el script toca el timbre: un POST al endpoint, como mucho uno cada 5
 * segundos. Con conOrden apagado (o si la orden ocupa más de 3500 bytes) el cuerpo es «insto-timbre» y no lleva la orden:
 * Insto la lee de la planilla. Con conOrden encendido (1.18.0) el cuerpo es la orden firmada, en JSON compacto
 * {"v":1,"id":…,"at":…,"lines":[…],"sig":…}: Insto verifica la firma igual que en el sondeo, así que ntfy puede leer las
 * líneas pero no cambiarlas. Si el timbre falla o se saltea, la orden queda anotada igual y la respuesta sigue siendo {ok, id}.
 *
 * Timbre cifrado (1.19.0). UnifiedPush exige que el mensaje vaya cifrado según RFC 8291, y Apps Script no tiene las
 * primitivas (ECDH P-256, AES-GCM) para hacerlo. Por eso:
 *   - Insto cifra para sí mismo el timbre simple y manda el resultado en «cifrado.timbre» (base64url): el script lo
 *     publica tal cual con Content-Encoding: aes128gcm. Es un mensaje RFC 8291 válido que solo dice «sonó».
 *   - Para que la orden viaje dentro del timbre cifrada (opcional), configurá RELAY_URL y RELAY_SECRET con tu relé de
 *     Cloudflare Workers (relay/insto-webpush-relay en el repositorio de Insto). Si el relé falla, suena el timbre simple.
 *   POST {token, action:"timbre", endpoint, conOrden, cifrado:{timbre[, p256dh, auth]} | null}
 *     → {ok, timbre, conOrden, cifrado:true|false, rele:true|false, cadaOrden:true}
 *   Con el timbre cifrado guardado, cada orden nueva toca el timbre (como mucho 30 por minuto), sin la espera de 5 s.
 *   Sin «cifrado» (Insto 1.18.0 o anterior) todo sigue como antes: el timbre va en claro y es «no conforme».
 *
 * Catálogo vivo (1.18.0), en la hoja «Catálogo» (una sola fila): las reglas vigentes que publica Insto, para Instinct.
 * POST {token, action:"catalogo", hash, json}  → {ok, catalogo:"guardado"|"igual"}   ({ok:false, error:"catalogo"} si falta
 *   algo o el json pasa de 45 000 caracteres)
 * GET ?token=…&action=catalogo  → {ok, catalogo:{publicado, hash, json} | null}
 * Un token incorrecto responde {ok:false, error:"token"}.
 */
const TOKEN = 'CAMBIÁ-ESTE-TOKEN';
// Relé opcional para la orden cifrada dentro del timbre (1.19.0). Vacíos: apagado (el timbre cifrado simple funciona igual).
const RELAY_URL = '';
const RELAY_SECRET = '';
const SHEET = 'Bitácora';
const HEADERS = ['recibido', 'evento_id', 'ocurrido', 'tipo', 'orden', 'detalle', 'mensaje_id', 'protocolo'];
const MAX_EVENTS = 50;
const MAILBOX = 'Buzón';
const MAILBOX_HEADERS = ['recibido', 'orden_id', 'at', 'lineas', 'firma', 'tomada'];
const MAX_ORDERS = 10;
/** Las órdenes vencen a los 15 minutos: pasado un día, una fila del buzón ya no sirve y solo hace lento al script. */
const MAILBOX_KEEP_MS = 24 * 60 * 60 * 1000;
const MAX_LINES = 3;
const DOORBELL_ENDPOINT = 'insto_timbre_endpoint';
const DOORBELL_LAST = 'insto_timbre_ultimo';
const DOORBELL_SPACING_MS = 5000;
const DOORBELL_MAX_CHARS = 500;
const DOORBELL_WITH_ORDER = 'insto_timbre_con_orden';
const DOORBELL_PAYLOAD = 'insto-timbre';
const DOORBELL_MAX_PAYLOAD_BYTES = 3500;
const DOORBELL_SEALED = 'insto_timbre_cifrado';
const DOORBELL_P256DH = 'insto_timbre_p256dh';
const DOORBELL_AUTH = 'insto_timbre_auth';
const DOORBELL_MINUTE = 'insto_timbre_minuto';
const DOORBELL_PER_MINUTE = 30;
const DOORBELL_TTL = '300';
// Un mensaje cifrado de Web Push ocupa como mucho 4096 bytes: en base64url, 5464 caracteres con relleno.
const DOORBELL_MAX_SEALED_CHARS = 5464;
const MAX_LINE_CHARS = 300;
const CATALOG = 'Catálogo';
const CATALOG_HEADERS = ['publicado', 'hash', 'json'];
const CATALOG_MAX_CHARS = 45000;

function sheet_() {
  const book = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = book.getSheetByName(SHEET);
  if (!sheet) {
    sheet = book.insertSheet(SHEET);
    // Texto plano: las fechas ISO no se convierten y nada se interpreta como fórmula.
    sheet.getRange('A:H').setNumberFormat('@');
    sheet.appendRow(HEADERS);
    sheet.setFrozenRows(1);
  }
  return sheet;
}

function mailbox_() {
  const book = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = book.getSheetByName(MAILBOX);
  if (!sheet) {
    sheet = book.insertSheet(MAILBOX);
    sheet.getRange('A:F').setNumberFormat('@');
    sheet.appendRow(MAILBOX_HEADERS);
    sheet.setFrozenRows(1);
  }
  return sheet;
}

function json_(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);
}

/** Un valor como texto, sin que la planilla lo tome por fórmula. */
function cell_(value) {
  const text = String(value == null ? '' : value).slice(0, 1000);
  return /^[=+\-@]/.test(text) ? "'" + text : text;
}

function iso_(value) {
  return value instanceof Date ? value.toISOString() : String(value);
}

/** Una orden firmada de Instinct. Se guarda tal cual; quien verifica la firma es Insto. */
function postOrder_(body) {
  if (typeof body.id !== 'string' || !body.id || typeof body.at !== 'string' || typeof body.sig !== 'string' ||
    !Array.isArray(body.lines) || !body.lines.length) return json_({ ok: false, error: 'orden' });
  // Unas pocas más de las que Insto lee: la firma cubre todas, Insto ejecuta como máximo 3.
  const lines = body.lines.slice(0, MAX_LINES * 3).map(function (l) { return String(l).slice(0, MAX_LINE_CHARS); });
  let isNew = false;
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const sheet = mailbox_();
    pruneMailbox_(sheet);
    const last = sheet.getLastRow();
    const known = last > 1 ? sheet.getRange(2, 2, last - 1, 1).getValues().map(function (r) { return String(r[0]); }) : [];
    if (known.indexOf(body.id) < 0) {
      sheet.appendRow([new Date().toISOString(), body.id, body.at, JSON.stringify(lines), body.sig, ''].map(cell_));
      isNew = true;
    }
  } finally {
    lock.releaseLock();
  }
  // Solo una orden nueva toca el timbre; una repetida no. Un fallo nunca cambia la respuesta.
  if (isNew) { try { ring_(false, withOrder_() ? orderPayload_(body) : null); } catch (_) { } }
  return json_({ ok: true, id: body.id });
}

/**
 * Borra del buzón las filas de más de un día (las primeras: se anotan en orden). Insto las rechazaría igual por vencidas,
 * y una hoja que crece sin límite hace que cada pedido tarde más, hasta pasar el tiempo de espera del teléfono.
 * Se llama con el lock tomado.
 */
function pruneMailbox_(sheet) {
  const last = sheet.getLastRow();
  if (last < 2) return 0;
  const limit = Date.now() - MAILBOX_KEEP_MS;
  const received = sheet.getRange(2, 1, last - 1, 1).getValues();
  let n = 0;
  while (n < received.length) {
    const v = received[n][0];
    const t = v instanceof Date ? v.getTime() : Date.parse(String(v).replace(/^'/, ''));
    if (isNaN(t) || t >= limit) break;
    n++;
  }
  if (n) sheet.deleteRows(2, n);
  return n;
}

/** Las propiedades del script: el endpoint del timbre no va a la planilla. */
function props_() {
  return PropertiesService.getScriptProperties();
}

/** «Incluir la orden en el timbre» (1.18.0): apagado salvo que Insto lo haya encendido. */
function withOrder_() {
  return props_().getProperty(DOORBELL_WITH_ORDER) === '1';
}

/** Los bytes de un texto en UTF-8, sin depender de Utilities (así también corre en la prueba sin Google). */
function utf8Bytes_(text) {
  let n = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c < 0x80) n += 1;
    else if (c < 0x800) n += 2;
    else if (c >= 0xd800 && c < 0xdc00 && i + 1 < text.length) { n += 4; i++; }
    else n += 3;
  }
  return n;
}

/**
 * La orden firmada como cuerpo del timbre, tal cual llegó: {"v":1,"id":…,"at":…,"lines":[…],"sig":…}. Null si Insto no la
 * podría aceptar así (más de 3 líneas o una línea de más de 300 caracteres) o si ocupa más de 3500 bytes: entonces el
 * timbre es «insto-timbre» y la orden se lee de la planilla, como siempre.
 */
function orderPayload_(body) {
  if (body.lines.length > MAX_LINES) return null;
  const lines = body.lines.map(String);
  if (lines.some(function (l) { return l.length > MAX_LINE_CHARS; })) return null;
  const payload = JSON.stringify({ v: 1, id: body.id, at: body.at, lines: lines, sig: body.sig });
  return utf8Bytes_(payload) <= DOORBELL_MAX_PAYLOAD_BYTES ? payload : null;
}

/**
 * Toca el timbre. Con el timbre cifrado de Insto 1.19.0: la orden ([payload]) cifrada por el relé si está configurado,
 * si no (o si el relé falla) el timbre cifrado simple; como mucho 30 por minuto. Sin él (Insto 1.18.0 o anterior), como
 * siempre: «insto-timbre» o la orden firmada, en claro, como mucho uno cada 5 segundos. Sin «force» se respetan los
 * límites. Devuelve el código HTTP, 0 si no se tocó (sin endpoint o por los límites) o -1 si el pedido falló.
 */
function ring_(force, payload) {
  return ringDetail_(force, payload).code;
}

/** Como ring_, con el error de Google cuando el pedido falla (por ejemplo, sin autorización para UrlFetchApp). */
function ringDetail_(force, payload) {
  const props = props_();
  const endpoint = props.getProperty(DOORBELL_ENDPOINT);
  if (!endpoint) return { code: 0 };
  const now = Date.now();
  const sealed = props.getProperty(DOORBELL_SEALED);
  if (sealed) {
    if (!force && !withinMinute_(props, now)) return { code: 0 };
    if (payload && relayReady_(props)) {
      const relayed = relay_(endpoint, payload, props);
      if (relayed.code >= 200 && relayed.code < 300) return relayed;
    }
    return postSealed_(endpoint, sealed);
  }
  const last = parseInt(props.getProperty(DOORBELL_LAST) || '0', 10) || 0;
  if (!force && now - last >= 0 && now - last < DOORBELL_SPACING_MS) return { code: 0 };
  props.setProperty(DOORBELL_LAST, String(now));
  try {
    const options = payload
      ? { method: 'post', contentType: 'application/json; charset=utf-8', payload: payload, muteHttpExceptions: true }
      : { method: 'post', payload: DOORBELL_PAYLOAD, muteHttpExceptions: true };
    const response = UrlFetchApp.fetch(endpoint, options);
    return { code: response.getResponseCode() };
  } catch (e) {
    return { code: -1, error: String((e && e.message) || e).slice(0, 200) };
  }
}

/** Cuenta el timbre en el minuto actual; false si ya hubo 30 (un bucle no agota la cuota de UrlFetchApp). */
function withinMinute_(props, now) {
  const minute = Math.floor(now / 60000);
  const parts = String(props.getProperty(DOORBELL_MINUTE) || '').split(':');
  const count = parseInt(parts[0], 10) === minute ? (parseInt(parts[1], 10) || 0) : 0;
  if (count >= DOORBELL_PER_MINUTE) return false;
  props.setProperty(DOORBELL_MINUTE, minute + ':' + (count + 1));
  return true;
}

/** El relé está listo: URL https, un secreto de al menos 32 caracteres y las claves públicas de Insto guardadas. */
function relayReady_(props) {
  return /^https:\/\/[^\s]+$/.test(RELAY_URL) && RELAY_SECRET.length >= 32 &&
    !!props.getProperty(DOORBELL_P256DH) && !!props.getProperty(DOORBELL_AUTH);
}

/** La orden firmada al relé, que la cifra (RFC 8291) y la publica. El relé no puede cambiarla: la firma lo impide. */
function relay_(endpoint, payload, props) {
  try {
    const response = UrlFetchApp.fetch(RELAY_URL, {
      method: 'post', contentType: 'application/json; charset=utf-8', muteHttpExceptions: true,
      headers: { Authorization: 'Bearer ' + RELAY_SECRET },
      payload: JSON.stringify({ endpoint: endpoint, p256dh: props.getProperty(DOORBELL_P256DH), auth: props.getProperty(DOORBELL_AUTH),
        payload: payload, ttl: parseInt(DOORBELL_TTL, 10), urgency: 'high' }),
    });
    let answer = null;
    try { answer = JSON.parse(response.getContentText()); } catch (_) { }
    if (answer && answer.ok === true && typeof answer.codigo === 'number') return { code: answer.codigo, relay: true };
    const why = answer && typeof answer.error === 'string' ? answer.error : (answer && typeof answer.codigo === 'number' ? 'HTTP ' + answer.codigo : 'HTTP ' + response.getResponseCode());
    return { code: -2, relay: true, error: why };
  } catch (e) {
    return { code: -2, relay: true, error: String((e && e.message) || e).slice(0, 200) };
  }
}

/** El timbre cifrado simple, tal cual lo cifró Insto: el cuerpo binario de Web Push con sus cabeceras. */
function postSealed_(endpoint, sealed) {
  try {
    const response = UrlFetchApp.fetch(endpoint, {
      method: 'post', contentType: 'application/octet-stream', muteHttpExceptions: true,
      headers: { 'Content-Encoding': 'aes128gcm', TTL: DOORBELL_TTL, Urgency: 'high' },
      payload: Utilities.base64DecodeWebSafe(sealed),
    });
    return { code: response.getResponseCode(), sealed: true };
  } catch (e) {
    return { code: -1, sealed: true, error: String((e && e.message) || e).slice(0, 200) };
  }
}

/**
 * «cifrado» (1.19.0): el timbre cifrado por Insto y, solo con la orden en el timbre, sus claves públicas para el relé.
 * null borra todo; si no viene, no se toca (un Insto 1.18.0 no lo manda). Devuelve false si algo no es válido.
 */
function saveSealed_(cifrado) {
  const props = props_();
  if (cifrado === null) {
    [DOORBELL_SEALED, DOORBELL_P256DH, DOORBELL_AUTH].forEach(function (k) { props.deleteProperty(k); });
    return true;
  }
  const timbre = cifrado.timbre;
  if (typeof timbre !== 'string' || timbre.length < 140 || timbre.length > DOORBELL_MAX_SEALED_CHARS || !/^[A-Za-z0-9_-]+={0,2}$/.test(timbre)) return false;
  const p256dh = cifrado.p256dh;
  const auth = cifrado.auth;
  if (p256dh !== undefined && (typeof p256dh !== 'string' || !/^[A-Za-z0-9_-]{87}$/.test(p256dh))) return false;
  if (auth !== undefined && (typeof auth !== 'string' || !/^[A-Za-z0-9_-]{22}$/.test(auth))) return false;
  props.setProperty(DOORBELL_SEALED, timbre);
  if (p256dh && auth) { props.setProperty(DOORBELL_P256DH, p256dh); props.setProperty(DOORBELL_AUTH, auth); }
  else { props.deleteProperty(DOORBELL_P256DH); props.deleteProperty(DOORBELL_AUTH); }
  return true;
}

/**
 * Ejecutala UNA vez desde el editor (elegila arriba, en la lista de funciones, y tocá «Ejecutar») para darle al script
 * el permiso de conectarse a servicios externos, que el timbre necesita. Google muestra el aviso de autorización.
 * No toca el timbre: solo hace un pedido a ntfy.sh.
 */
function autorizarTimbre() {
  UrlFetchApp.fetch('https://ntfy.sh/v1/health', { muteHttpExceptions: true });
}

/**
 * Ejecutala desde el editor (elegila en la lista de funciones y tocá «Ejecutar») para saber si el relé anda. Dice qué
 * falta en el «Registro de ejecución» y, si todo está, toca el timbre una vez a través del relé (un timbre simple, sin
 * orden: Insto solo revisa el buzón).
 */
function probarRele() {
  const text = releDiagnosis_();
  console.log(text);
  return text;
}

function releDiagnosis_() {
  const props = props_();
  if (!/^https:\/\/[^\s]+$/.test(RELAY_URL)) return 'RELAY_URL está vacía o no empieza con https://. Pegá la dirección que dio «wrangler deploy».';
  if (RELAY_SECRET.length < 32) return 'RELAY_SECRET tiene menos de 32 caracteres. Pegá la misma clave que guardaste con «wrangler secret put».';
  const endpoint = props.getProperty(DOORBELL_ENDPOINT);
  if (!endpoint) return 'El script no tiene el endpoint del timbre. En Insto, apagá y encendé el timbre.';
  if (!props.getProperty(DOORBELL_P256DH) || !props.getProperty(DOORBELL_AUTH))
    return 'Insto todavía no le pasó sus claves de cifrado al script. En Insto, apagá y encendé «Incluir la orden en el timbre».';
  if (props.getProperty(DOORBELL_WITH_ORDER) !== '1')
    return 'El relé está bien configurado, pero «Incluir la orden en el timbre» está apagado en Insto. Encendelo.';
  const r = relay_(endpoint, DOORBELL_PAYLOAD, props);
  if (r.code >= 200 && r.code < 300) return 'El relé anda: el timbre salió cifrado por el relé (HTTP ' + r.code + '). ' +
    'Si Insto sigue diciendo «Sin relé», en Insto apagá y encendé «Incluir la orden en el timbre» para que el script se lo confirme.';
  const why = {
    secreto: 'el relé rechazó la clave: RELAY_SECRET no es igual a la que guardaste con «wrangler secret put». Copiá la misma en los dos lados',
    'sin-configurar': 'al relé le falta la clave: ejecutá «npx wrangler secret put RELAY_SECRET» y después «npx wrangler deploy»',
    endpoint: 'el relé no puede publicar en el servidor de tu timbre: agregá su host a ALLOWED_HOSTS en wrangler.toml y volvé a hacer «npx wrangler deploy»',
    claves: 'las claves de Insto no son válidas: en Insto, apagá y encendé el timbre',
    push: 'el relé no pudo llegar al servidor del timbre (ntfy)',
  }[r.error] || ('el relé respondió con error' + (r.error ? ' (' + r.error + ')' : '') + (r.code > 0 ? ' (HTTP ' + r.code + ')' : ''));
  return 'El relé no anda: ' + why + '. Después de cambiar el script, implementá una versión nueva.';
}

/**
 * Guarda (o, vacío, borra) el endpoint del timbre. conOrden (1.18.0), si viene, se guarda y se repite en la respuesta,
 * para que Insto sepa que este script lo entendió; si no viene, queda el valor que había.
 */
function postDoorbell_(body) {
  const endpoint = typeof body.endpoint === 'string' ? body.endpoint.trim() : '';
  if (endpoint && (endpoint.length > DOORBELL_MAX_CHARS || !/^https:\/\/[^\s]+$/.test(endpoint))) return json_({ ok: false, error: 'endpoint' });
  const sealing = Object.prototype.hasOwnProperty.call(body, 'cifrado');
  if (sealing && body.cifrado !== null && (typeof body.cifrado !== 'object' || Array.isArray(body.cifrado))) return json_({ ok: false, error: 'cifrado' });
  if (sealing && !saveSealed_(endpoint ? body.cifrado : null)) return json_({ ok: false, error: 'cifrado' });
  const out = { ok: true, timbre: endpoint ? 'guardado' : 'borrado' };
  if (typeof body.conOrden === 'boolean') {
    props_().setProperty(DOORBELL_WITH_ORDER, body.conOrden ? '1' : '0');
    out.conOrden = body.conOrden;
  }
  if (endpoint) props_().setProperty(DOORBELL_ENDPOINT, endpoint);
  else { props_().deleteProperty(DOORBELL_ENDPOINT); saveSealed_(null); }
  // Solo a quien habla de cifrado (Insto 1.19.0): un Insto anterior recibe exactamente la respuesta de siempre.
  if (sealing) {
    out.cifrado = !!props_().getProperty(DOORBELL_SEALED);
    out.rele = out.cifrado && relayReady_(props_());
    out.cadaOrden = true;
  }
  return json_(out);
}

/** «Probar timbre» desde Insto: toca el timbre ahora, sin esperar los 5 segundos. */
function postDoorbellTest_() {
  if (!props_().getProperty(DOORBELL_ENDPOINT)) return json_({ ok: false, error: 'sin-endpoint' });
  const rang = ringDetail_(true);
  const out = { ok: true, timbre: rang.code >= 200 && rang.code < 300, codigo: rang.code };
  if (rang.sealed) out.cifrado = true;
  if (rang.error) out.detalle = rang.error;
  return json_(out);
}

function catalogSheet_() {
  const book = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = book.getSheetByName(CATALOG);
  if (!sheet) {
    sheet = book.insertSheet(CATALOG);
    sheet.getRange('A:C').setNumberFormat('@');
    sheet.appendRow(CATALOG_HEADERS);
    sheet.setFrozenRows(1);
  }
  return sheet;
}

/**
 * Catálogo vivo (1.18.0): reemplaza la única fila con el que publica Insto. Con el mismo hash no escribe nada. El json se
 * guarda como texto plano (cell_ con un límite propio: un catálogo pasa de los 1000 caracteres de las otras celdas).
 */
function postCatalog_(body) {
  const hash = typeof body.hash === 'string' ? body.hash : '';
  const text = typeof body.json === 'string' ? body.json : '';
  if (!/^[0-9a-f]{64}$/.test(hash) || !text || text.length > CATALOG_MAX_CHARS) return json_({ ok: false, error: 'catalogo' });
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const sheet = catalogSheet_();
    if (sheet.getLastRow() >= 2 && String(sheet.getRange(2, 2, 1, 1).getValues()[0][0]) === hash) return json_({ ok: true, catalogo: 'igual' });
    const row = [new Date().toISOString(), hash, text].map(function (v) { return plain_(v, CATALOG_MAX_CHARS); });
    sheet.getRange(2, 1, 1, CATALOG_HEADERS.length).setValues([row]);
    return json_({ ok: true, catalogo: 'guardado' });
  } finally {
    lock.releaseLock();
  }
}

/** Como cell_, con otro límite de largo. */
function plain_(value, max) {
  const text = String(value == null ? '' : value).slice(0, max);
  return /^[=+\-@]/.test(text) ? "'" + text : text;
}

/** El catálogo publicado, o null si Insto todavía no publicó ninguno. */
function getCatalog_() {
  const sheet = catalogSheet_();
  if (sheet.getLastRow() < 2) return json_({ ok: true, catalogo: null });
  const r = sheet.getRange(2, 1, 1, CATALOG_HEADERS.length).getValues()[0];
  if (!r[1]) return json_({ ok: true, catalogo: null });
  return json_({ ok: true, catalogo: { publicado: iso_(r[0]), hash: String(r[1]), json: String(r[2]).replace(/^'/, '') } });
}

/** Marca como tomadas las órdenes que Insto ya leyó (válidas o no), para que no vuelvan. */
function postTaken_(body) {
  const ids = Array.isArray(body.ids) ? body.ids.map(String) : [];
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const sheet = mailbox_();
    pruneMailbox_(sheet);
    const last = sheet.getLastRow();
    let n = 0;
    if (last > 1 && ids.length) {
      // Solo las columnas orden_id y tomada; se reescribe solo «tomada», no la hoja entera.
      const rows = sheet.getRange(2, 2, last - 1, 5).getValues();
      const now = new Date().toISOString();
      const taken = rows.map(function (r) {
        if (!r[4] && ids.indexOf(String(r[0])) >= 0) { n++; return [now]; }
        return [r[4]];
      });
      if (n) sheet.getRange(2, 6, last - 1, 1).setValues(taken);
    }
    return json_({ ok: true, tomadas: n });
  } finally {
    lock.releaseLock();
  }
}

/** Las órdenes no tomadas, en orden, como máximo 10. */
function getOrders_(p) {
  const limit = Math.min(Math.max(parseInt(p.limit || String(MAX_ORDERS), 10) || MAX_ORDERS, 1), MAX_ORDERS);
  const sheet = mailbox_();
  const last = sheet.getLastRow();
  const ordenes = [];
  if (last > 1) {
    const rows = sheet.getRange(2, 1, last - 1, MAILBOX_HEADERS.length).getValues();
    for (let i = 0; i < rows.length && ordenes.length < limit; i++) {
      const r = rows[i];
      if (r[5]) continue;
      let lineas = [];
      try { lineas = JSON.parse(String(r[3]).replace(/^'/, '')); } catch (_) { continue; }
      ordenes.push({ recibido: iso_(r[0]), orden_id: String(r[1]), at: String(r[2]), lineas: lineas, firma: String(r[4]) });
    }
  }
  return json_({ ok: true, ordenes: ordenes });
}

function doPost(e) {
  let body;
  try { body = JSON.parse(e.postData.contents); } catch (_) { return json_({ ok: false, error: 'json' }); }
  if (!body || body.token !== TOKEN) return json_({ ok: false, error: 'token' });
  if (body.action === 'orden') return postOrder_(body);
  if (body.action === 'tomadas') return postTaken_(body);
  if (body.action === 'timbre') return postDoorbell_(body);
  if (body.action === 'probar-timbre') return postDoorbellTest_();
  if (body.action === 'catalogo') return postCatalog_(body);
  // Sin «action»: los eventos de Insto, exactamente como en 1.15.0.
  const events = Array.isArray(body.events) ? body.events.slice(0, MAX_EVENTS) : [];
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const sheet = sheet_();
    const last = sheet.getLastRow();
    const known = new Set(last > 1 ? sheet.getRange(2, 2, last - 1, 1).getValues().map(function (r) { return String(r[0]); }) : []);
    const received = new Date().toISOString();
    const rows = [];
    const accepted = [];
    events.forEach(function (ev) {
      if (!ev || typeof ev.id !== 'string' || !ev.id) return;
      accepted.push(ev.id);
      if (known.has(ev.id)) return; // Repetido: se confirma, no se anota de nuevo.
      known.add(ev.id);
      rows.push([received, ev.id, ev.at, ev.kind, ev.order, ev.detail, ev.message, ev.protocol].map(cell_));
    });
    if (rows.length) sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, HEADERS.length).setValues(rows);
    return json_({ ok: true, accepted: accepted });
  } finally {
    lock.releaseLock();
  }
}

function row_(r) {
  return { recibido: iso_(r[0]), evento_id: String(r[1]), ocurrido: iso_(r[2]), tipo: String(r[3]), orden: String(r[4]),
    detalle: String(r[5]), mensaje_id: String(r[6]), protocolo: String(r[7]) };
}

/** El último evento «estado», buscando desde abajo; null si no hay. */
function lastState_() {
  const sheet = sheet_();
  const last = sheet.getLastRow();
  if (last < 2) return null;
  const rows = sheet.getRange(2, 1, last - 1, HEADERS.length).getValues();
  for (let i = rows.length - 1; i >= 0; i--) if (String(rows[i][3]) === 'estado') return row_(rows[i]);
  return null;
}

function doGet(e) {
  const p = (e && e.parameter) || {};
  if (p.token !== TOKEN) return json_({ ok: false, error: 'token' });
  if (p.action === 'estado') return json_({ ok: true, estado: lastState_() });
  if (p.action === 'ordenes') return getOrders_(p);
  if (p.action === 'catalogo') return getCatalog_();
  // Sin «action»: los eventos desde «since», exactamente como en 1.15.0.
  const since = p.since ? new Date(p.since).getTime() : 0;
  if (isNaN(since)) return json_({ ok: false, error: 'since' });
  const limit = Math.min(Math.max(parseInt(p.limit || String(MAX_EVENTS), 10) || MAX_EVENTS, 1), MAX_EVENTS);
  const sheet = sheet_();
  const last = sheet.getLastRow();
  if (last < 2) return json_({ ok: true, events: [] });
  const events = [];
  const rows = sheet.getRange(2, 1, last - 1, HEADERS.length).getValues();
  for (let i = 0; i < rows.length && events.length < limit; i++) {
    const r = rows[i];
    if (new Date(iso_(r[0])).getTime() < since) continue;
    events.push(row_(r));
  }
  return json_({ ok: true, events: events });
}
