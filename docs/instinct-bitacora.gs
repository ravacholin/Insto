/**
 * Bitácora de Insto (Protocolo Insto v6). Apps Script ligado a una planilla de Google.
 * Ver docs/INSTINCT-PROPUESTAS.md, «Bitácora (1.15.0)», «Estado del teléfono», «Buzón firmado» (1.16.0), «Timbre» (1.17.0),
 * y «Orden directa en el timbre» (1.18.0).
 * Es compatible con Insto 1.15.0 a 1.17.0: un POST o un GET sin las acciones nuevas se comportan exactamente igual que antes.
 *
 * 1. En la planilla: Extensiones → Apps Script. Pegá este archivo y cambiá TOKEN por un texto largo y aleatorio.
 * 2. Implementar → Nueva implementación → Aplicación web. Ejecutar como: yo. Quién tiene acceso: cualquier usuario.
 * 3. Copiá la URL que termina en /exec y pegala, con el token, en Insto → Ajustes → Propuestas de Instinct → Bitácora.
 *
 * POST {token, events:[{id, at, kind, order, detail, message, protocol}]}  → {ok, accepted:[ids]}
 *   Los ids repetidos se descartan y se confirman igual (idempotencia).
 * GET ?token=…&since=<ISO>&limit=50  → {ok, events:[{recibido, evento_id, ocurrido, tipo, orden, detalle, mensaje_id, protocolo}]}
 *   Los eventos recibidos desde «since», en orden, como máximo 50.
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
 * Un token incorrecto responde {ok:false, error:"token"}.
 */
const TOKEN = 'CAMBIÁ-ESTE-TOKEN';
const SHEET = 'Bitácora';
const HEADERS = ['recibido', 'evento_id', 'ocurrido', 'tipo', 'orden', 'detalle', 'mensaje_id', 'protocolo'];
const MAX_EVENTS = 50;
const MAILBOX = 'Buzón';
const MAILBOX_HEADERS = ['recibido', 'orden_id', 'at', 'lineas', 'firma', 'tomada'];
const MAX_ORDERS = 10;
const MAX_LINES = 3;
const DOORBELL_ENDPOINT = 'insto_timbre_endpoint';
const DOORBELL_LAST = 'insto_timbre_ultimo';
const DOORBELL_SPACING_MS = 5000;
const DOORBELL_MAX_CHARS = 500;
const DOORBELL_WITH_ORDER = 'insto_timbre_con_orden';
const DOORBELL_PAYLOAD = 'insto-timbre';
const DOORBELL_MAX_PAYLOAD_BYTES = 3500;
const MAX_LINE_CHARS = 300;

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
 * Toca el timbre: un POST al endpoint guardado, con «insto-timbre» o, si se pasa, la orden firmada ([payload]). Sin
 * «force», como mucho uno cada 5 segundos. Devuelve el código HTTP, 0 si no se tocó (sin endpoint o dentro de los 5
 * segundos) o -1 si el pedido falló.
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

/**
 * Ejecutala UNA vez desde el editor (elegila arriba, en la lista de funciones, y tocá «Ejecutar») para darle al script
 * el permiso de conectarse a servicios externos, que el timbre necesita. Google muestra el aviso de autorización.
 * No toca el timbre: solo hace un pedido a ntfy.sh.
 */
function autorizarTimbre() {
  UrlFetchApp.fetch('https://ntfy.sh/v1/health', { muteHttpExceptions: true });
}

/**
 * Guarda (o, vacío, borra) el endpoint del timbre. conOrden (1.18.0), si viene, se guarda y se repite en la respuesta,
 * para que Insto sepa que este script lo entendió; si no viene, queda el valor que había.
 */
function postDoorbell_(body) {
  const endpoint = typeof body.endpoint === 'string' ? body.endpoint.trim() : '';
  if (endpoint && (endpoint.length > DOORBELL_MAX_CHARS || !/^https:\/\/[^\s]+$/.test(endpoint))) return json_({ ok: false, error: 'endpoint' });
  const out = { ok: true, timbre: endpoint ? 'guardado' : 'borrado' };
  if (typeof body.conOrden === 'boolean') {
    props_().setProperty(DOORBELL_WITH_ORDER, body.conOrden ? '1' : '0');
    out.conOrden = body.conOrden;
  }
  if (endpoint) props_().setProperty(DOORBELL_ENDPOINT, endpoint);
  else props_().deleteProperty(DOORBELL_ENDPOINT);
  return json_(out);
}

/** «Probar timbre» desde Insto: toca el timbre ahora, sin esperar los 5 segundos. */
function postDoorbellTest_() {
  if (!props_().getProperty(DOORBELL_ENDPOINT)) return json_({ ok: false, error: 'sin-endpoint' });
  const rang = ringDetail_(true);
  const out = { ok: true, timbre: rang.code >= 200 && rang.code < 300, codigo: rang.code };
  if (rang.error) out.detalle = rang.error;
  return json_(out);
}

/** Marca como tomadas las órdenes que Insto ya leyó (válidas o no), para que no vuelvan. */
function postTaken_(body) {
  const ids = Array.isArray(body.ids) ? body.ids.map(String) : [];
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const sheet = mailbox_();
    const last = sheet.getLastRow();
    let n = 0;
    if (last > 1 && ids.length) {
      const range = sheet.getRange(2, 1, last - 1, MAILBOX_HEADERS.length);
      const rows = range.getValues();
      const now = new Date().toISOString();
      rows.forEach(function (r) { if (!r[5] && ids.indexOf(String(r[1])) >= 0) { r[5] = now; n++; } });
      if (n) range.setValues(rows.map(function (r) { return r.map(cell_); }));
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
