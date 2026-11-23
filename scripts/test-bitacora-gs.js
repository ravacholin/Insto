#!/usr/bin/env node
// Prueba docs/instinct-bitacora.gs fuera de Google: carga el script en un contexto aislado con dobles de
// SpreadsheetApp, PropertiesService, LockService, UrlFetchApp y ContentService. Sin dependencias.
// Uso: node scripts/test-bitacora-gs.js
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SOURCE = fs.readFileSync(path.join(__dirname, '..', 'docs', 'instinct-bitacora.gs'), 'utf8');

function fakeSheet() {
  const rows = [];
  return {
    rows,
    getRange(row, col, numRows, numCols) {
      if (typeof row === 'string') return { setNumberFormat() {} };
      return {
        getValues() {
          const out = [];
          for (let r = 0; r < (numRows || 1); r++) {
            const source = rows[row - 1 + r] || [];
            const line = [];
            for (let c = 0; c < (numCols || 1); c++) line.push(source[col - 1 + c] === undefined ? '' : source[col - 1 + c]);
            out.push(line);
          }
          return out;
        },
        setValues(values) {
          values.forEach((v, r) => {
            const target = rows[row - 1 + r] || (rows[row - 1 + r] = []);
            v.forEach((x, c) => { target[col - 1 + c] = x; });
          });
        },
      };
    },
    appendRow(values) { rows.push(values.slice()); },
    deleteRows(row, n) { rows.splice(row - 1, n); },
    getLastRow() { return rows.length; },
    setFrozenRows() {},
  };
}

function load(options) {
  const opts = options || {};
  const sheets = {};
  const props = {};
  const fetches = [];
  const env = {
    now: 1_790_000_000_000,
    fetchFails: false,
    fetchCode: 200,
    // El relé (1.19.0): la respuesta JSON que da, o relayFails para que falle.
    relayAnswer: { ok: true, codigo: 201 },
    relayFails: false,
  };
  const context = {
    SpreadsheetApp: {
      getActiveSpreadsheet() {
        return {
          getSheetByName(name) { return sheets[name] || null; },
          insertSheet(name) { sheets[name] = fakeSheet(); return sheets[name]; },
        };
      },
    },
    PropertiesService: {
      getScriptProperties() {
        return {
          getProperty(k) { return Object.prototype.hasOwnProperty.call(props, k) ? props[k] : null; },
          setProperty(k, v) { props[k] = String(v); },
          deleteProperty(k) { delete props[k]; },
        };
      },
    },
    LockService: { getScriptLock() { return { waitLock() {}, releaseLock() {} }; } },
    UrlFetchApp: {
      fetch(url, options) {
        fetches.push({ url, options });
        if (url === RELAY && env.relayFails) throw new Error('relé caído');
        if (url === RELAY) return { getResponseCode() { return 200; }, getContentText() { return JSON.stringify(env.relayAnswer); } };
        if (env.fetchFails) throw new Error('sin red');
        return { getResponseCode() { return env.fetchCode; }, getContentText() { return ''; } };
      },
    },
    Utilities: {
      // Como el de Google: base64 «web safe» a bytes con signo.
      base64DecodeWebSafe(text) { return Array.from(Buffer.from(text.replace(/-/g, '+').replace(/_/g, '/'), 'base64')).map((b) => (b > 127 ? b - 256 : b)); },
    },
    ContentService: {
      MimeType: { JSON: 'json' },
      createTextOutput(text) { return { text, setMimeType() { return this; } }; },
    },
    Date: class extends Date {
      constructor(...a) { if (a.length) super(...a); else super(env.now); }
      static now() { return env.now; }
    },
    JSON, Math, String, Array, Set, parseInt, isNaN,
  };
  vm.createContext(context);
  let source = SOURCE.replace("const TOKEN = 'CAMBIÁ-ESTE-TOKEN';", "const TOKEN = 'token-de-prueba';");
  if (opts.relay) source = source.replace("const RELAY_URL = '';", "const RELAY_URL = '" + RELAY + "';")
    .replace("const RELAY_SECRET = '';", "const RELAY_SECRET = '" + RELAY_SECRET + "';");
  vm.runInContext(source, context);
  const post = (body) => JSON.parse(context.doPost({ postData: { contents: JSON.stringify(body) } }).text);
  const get = (parameter) => JSON.parse(context.doGet({ parameter }).text);
  return { post, get, props, fetches, sheets, env };
}

const RELAY = 'https://insto-webpush-relay.ejemplo.workers.dev/';
const RELAY_SECRET = 'secreto-del-rele-de-prueba-0123456789';
const T = 'token-de-prueba';
const ENDPOINT = 'https://ntfy.sh/upAbCdEf123456?up=1';
const order = (id) => ({ token: T, action: 'orden', id, at: '2026-11-21T09:30:00-03:00', lines: ['#insto ver estado'], sig: '0'.repeat(64) });
const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('un POST sin action sigue anotando eventos como en 1.15.0', () => {
  const s = load();
  assert.deepStrictEqual(s.post({ token: T, events: [{ id: 'a', at: 'x', kind: 'ok', order: 'o', detail: 'd', message: 'm', protocol: 'p' }] }),
    { ok: true, accepted: ['a'] });
  assert.strictEqual(s.get({ token: T, since: '2020-01-01T00:00:00Z' }).events.length, 1);
  assert.strictEqual(s.fetches.length, 0);
});

test('un token incorrecto se rechaza también en las acciones nuevas', () => {
  const s = load();
  assert.deepStrictEqual(s.post({ token: 'malo', action: 'timbre', endpoint: ENDPOINT }), { ok: false, error: 'token' });
  assert.deepStrictEqual(s.post({ token: 'malo', action: 'probar-timbre' }), { ok: false, error: 'token' });
  assert.strictEqual(Object.keys(s.props).length, 0);
});

test('timbre guarda el endpoint en las propiedades, no en la planilla, y vacío lo borra', () => {
  const s = load();
  assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: ENDPOINT }), { ok: true, timbre: 'guardado' });
  assert.strictEqual(s.props.insto_timbre_endpoint, ENDPOINT);
  assert.ok(Object.values(s.sheets).every((sh) => !JSON.stringify(sh.rows).includes('ntfy')));
  assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: '' }), { ok: true, timbre: 'borrado' });
  assert.strictEqual(s.props.insto_timbre_endpoint, undefined);
});

test('timbre rechaza un endpoint que no es https o es demasiado largo', () => {
  const s = load();
  assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: 'http://ntfy.sh/up' }), { ok: false, error: 'endpoint' });
  assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: 'https://ntfy.sh/' + 'a'.repeat(500) }), { ok: false, error: 'endpoint' });
  assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: 'https://ntfy.sh/a b' }), { ok: false, error: 'endpoint' });
  assert.strictEqual(s.props.insto_timbre_endpoint, undefined);
});

test('una orden nueva toca el timbre y una repetida no', () => {
  const s = load();
  s.post({ token: T, action: 'timbre', endpoint: ENDPOINT });
  assert.deepStrictEqual(s.post(order('3b241101-e2bb-4255-8caf-4136c566a962')), { ok: true, id: '3b241101-e2bb-4255-8caf-4136c566a962' });
  assert.strictEqual(s.fetches.length, 1);
  assert.strictEqual(s.fetches[0].url, ENDPOINT);
  assert.deepStrictEqual(JSON.parse(JSON.stringify(s.fetches[0].options)), { method: 'post', payload: 'insto-timbre', muteHttpExceptions: true });
  s.env.now += 60_000;
  assert.deepStrictEqual(s.post(order('3b241101-e2bb-4255-8caf-4136c566a962')), { ok: true, id: '3b241101-e2bb-4255-8caf-4136c566a962' });
  assert.strictEqual(s.fetches.length, 1, 'la repetida no toca el timbre');
  assert.strictEqual(s.sheets['Buzón'].rows.length, 2, 'encabezado y una sola orden');
});

test('el timbre suena como mucho una vez cada 5 segundos', () => {
  const s = load();
  s.post({ token: T, action: 'timbre', endpoint: ENDPOINT });
  s.post(order('00000000-0000-4000-8000-000000000001'));
  s.env.now += 4_999;
  s.post(order('00000000-0000-4000-8000-000000000002'));
  assert.strictEqual(s.fetches.length, 1);
  s.env.now += 1;
  s.post(order('00000000-0000-4000-8000-000000000003'));
  assert.strictEqual(s.fetches.length, 2);
  assert.strictEqual(s.sheets['Buzón'].rows.length, 4, 'las tres quedan anotadas');
});

test('si el timbre falla, la orden queda anotada y la respuesta no cambia', () => {
  const s = load();
  s.post({ token: T, action: 'timbre', endpoint: ENDPOINT });
  s.env.fetchFails = true;
  assert.deepStrictEqual(s.post(order('00000000-0000-4000-8000-00000000000a')), { ok: true, id: '00000000-0000-4000-8000-00000000000a' });
  assert.strictEqual(s.get({ token: T, action: 'ordenes' }).ordenes.length, 1);
});

test('sin endpoint guardado, una orden se anota igual que en 1.16.0', () => {
  const s = load();
  assert.deepStrictEqual(s.post(order('00000000-0000-4000-8000-00000000000b')), { ok: true, id: '00000000-0000-4000-8000-00000000000b' });
  assert.strictEqual(s.fetches.length, 0);
});

test('probar-timbre toca el timbre ahora, sin esperar los 5 segundos', () => {
  const s = load();
  assert.deepStrictEqual(s.post({ token: T, action: 'probar-timbre' }), { ok: false, error: 'sin-endpoint' });
  s.post({ token: T, action: 'timbre', endpoint: ENDPOINT });
  s.post(order('00000000-0000-4000-8000-00000000000c'));
  assert.deepStrictEqual(s.post({ token: T, action: 'probar-timbre' }), { ok: true, timbre: true, codigo: 200 });
  assert.strictEqual(s.fetches.length, 2);
  s.env.fetchCode = 404;
  assert.deepStrictEqual(s.post({ token: T, action: 'probar-timbre' }), { ok: true, timbre: false, codigo: 404 });
  s.env.fetchFails = true;
  assert.deepStrictEqual(s.post({ token: T, action: 'probar-timbre' }), { ok: true, timbre: false, codigo: -1, detalle: 'sin red' });
});

test('tomadas y ordenes siguen igual', () => {
  const s = load();
  s.post(order('00000000-0000-4000-8000-00000000000d'));
  assert.deepStrictEqual(s.post({ token: T, action: 'tomadas', ids: ['00000000-0000-4000-8000-00000000000d'] }), { ok: true, tomadas: 1 });
  assert.strictEqual(s.get({ token: T, action: 'ordenes' }).ordenes.length, 0);
});

test('el buzón borra las filas de más de un día y «tomadas» solo toca esa columna', () => {
  const s = load();
  s.post(order('00000000-0000-4000-8000-0000000000a1'));
  s.post(order('00000000-0000-4000-8000-0000000000a2'));
  s.env.now += 25 * 60 * 60 * 1000;
  s.post(order('00000000-0000-4000-8000-0000000000a3'));
  const rows = s.sheets['Buzón'].rows;
  assert.strictEqual(rows.length, 2, 'encabezado y la orden reciente');
  assert.strictEqual(rows[1][1], '00000000-0000-4000-8000-0000000000a3');
  const firma = rows[1][4];
  assert.deepStrictEqual(s.post({ token: T, action: 'tomadas', ids: ['00000000-0000-4000-8000-0000000000a3'] }), { ok: true, tomadas: 1 });
  assert.strictEqual(rows[1][4], firma, 'las demás columnas quedan igual');
  assert.ok(rows[1][5]);
  assert.strictEqual(s.get({ token: T, action: 'ordenes' }).ordenes.length, 0);
});

// Orden directa en el timbre (1.18.0)

const ID = '3b241101-e2bb-4255-8caf-4136c566a962';

test('timbre con conOrden lo guarda y lo repite; sin conOrden conserva el valor (Insto 1.17.0)', () => {
  const s = load();
  assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: ENDPOINT, conOrden: true }), { ok: true, timbre: 'guardado', conOrden: true });
  assert.strictEqual(s.props.insto_timbre_con_orden, '1');
  assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: ENDPOINT }), { ok: true, timbre: 'guardado' });
  assert.strictEqual(s.props.insto_timbre_con_orden, '1', 'un Insto 1.17.0 no lo cambia');
  assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: ENDPOINT, conOrden: false }), { ok: true, timbre: 'guardado', conOrden: false });
  assert.strictEqual(s.props.insto_timbre_con_orden, '0');
  assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: '', conOrden: false }), { ok: true, timbre: 'borrado', conOrden: false });
  assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: 'http://x', conOrden: true }), { ok: false, error: 'endpoint' });
  assert.strictEqual(s.props.insto_timbre_con_orden, '0', 'un endpoint rechazado no cambia nada');
});

test('con conOrden, una orden nueva viaja firmada en el timbre, en JSON compacto', () => {
  const s = load();
  s.post({ token: T, action: 'timbre', endpoint: ENDPOINT, conOrden: true });
  const body = { token: T, action: 'orden', id: ID, at: '2026-11-21T09:30:00-03:00', lines: ['#insto poner el volumen al 30 por ciento', '#insto avisarme Te bajé el volumen.'], sig: 'a'.repeat(64) };
  assert.deepStrictEqual(s.post(body), { ok: true, id: ID });
  assert.strictEqual(s.fetches.length, 1);
  const options = s.fetches[0].options;
  assert.strictEqual(options.method, 'post');
  assert.strictEqual(options.muteHttpExceptions, true);
  assert.strictEqual(options.contentType, 'application/json; charset=utf-8');
  assert.strictEqual(options.payload, JSON.stringify({ v: 1, id: ID, at: body.at, lines: body.lines, sig: body.sig }));
  assert.deepStrictEqual(Object.keys(JSON.parse(options.payload)), ['v', 'id', 'at', 'lines', 'sig']);
  assert.ok(!options.payload.includes('token-de-prueba'), 'el timbre nunca lleva el token');
});

test('una orden demasiado grande (o con más de 3 líneas) toca el timbre sin la orden', () => {
  const s = load();
  s.post({ token: T, action: 'timbre', endpoint: ENDPOINT, conOrden: true });
  // Tres líneas de 300 caracteres no llegan a 3500 bytes ni con caracteres de 3 bytes; con caracteres que el JSON escapa
  // (\u0007 ocupa 6 bytes) sí pasan.
  const long = '#insto avisarme ' + '\u0007'.repeat(284);
  assert.strictEqual(long.length, 300);
  s.post({ token: T, action: 'orden', id: '00000000-0000-4000-8000-000000000101', at: '2026-11-21T09:30:00-03:00', lines: [long, long, long], sig: 'b'.repeat(64) });
  assert.strictEqual(s.fetches[0].options.payload, 'insto-timbre');
  assert.strictEqual(s.fetches[0].options.contentType, undefined);
  s.env.now += 5_000;
  s.post({ token: T, action: 'orden', id: '00000000-0000-4000-8000-000000000102', at: '2026-11-21T09:30:00-03:00', lines: ['#insto a', '#insto b', '#insto c', '#insto d'], sig: 'c'.repeat(64) });
  assert.strictEqual(s.fetches[1].options.payload, 'insto-timbre');
  s.env.now += 5_000;
  // Justo en el límite: 3500 bytes entra (dos líneas con escapes y una tercera que completa).
  const id3 = '00000000-0000-4000-8000-000000000103';
  const escaped = '#insto avisarme ' + '\u0007'.repeat(265);
  const payloadOf = (third) => JSON.stringify({ v: 1, id: id3, at: '2026-11-21T09:30:00-03:00', lines: [escaped, escaped, third], sig: 'd'.repeat(64) });
  const third = '#insto ' + 'x'.repeat(3500 - Buffer.byteLength(payloadOf('#insto '), 'utf8'));
  const lines = [escaped, escaped, third];
  const exact = payloadOf(third);
  assert.strictEqual(Buffer.byteLength(exact, 'utf8'), 3500);
  assert.ok(third.length <= 300, String(third.length));
  s.post({ token: T, action: 'orden', id: id3, at: '2026-11-21T09:30:00-03:00', lines, sig: 'd'.repeat(64) });
  assert.strictEqual(s.fetches[2].options.payload, exact);
  s.env.now += 5_000;
  // Un byte más: sin la orden.
  s.post({ token: T, action: 'orden', id: '00000000-0000-4000-8000-000000000104', at: '2026-11-21T09:30:00-03:00', lines: [escaped, escaped, third + 'x'], sig: 'd'.repeat(64) });
  assert.strictEqual(s.fetches[3].options.payload, 'insto-timbre');
});

test('con conOrden, una orden repetida no toca el timbre', () => {
  const s = load();
  s.post({ token: T, action: 'timbre', endpoint: ENDPOINT, conOrden: true });
  s.post(order(ID));
  s.env.now += 60_000;
  s.post(order(ID));
  assert.strictEqual(s.fetches.length, 1);
});

test('con conOrden apagado o nunca enviado (script viejo de Insto), el timbre sigue siendo «insto-timbre»', () => {
  const s = load();
  s.post({ token: T, action: 'timbre', endpoint: ENDPOINT });
  s.post(order('00000000-0000-4000-8000-000000000201'));
  assert.deepStrictEqual(JSON.parse(JSON.stringify(s.fetches[0].options)), { method: 'post', payload: 'insto-timbre', muteHttpExceptions: true });
  s.post({ token: T, action: 'timbre', endpoint: ENDPOINT, conOrden: false });
  s.env.now += 5_000;
  s.post(order('00000000-0000-4000-8000-000000000202'));
  assert.strictEqual(s.fetches[1].options.payload, 'insto-timbre');
});

test('con conOrden, el espaciado de 5 segundos sigue igual: la segunda orden queda para el sondeo', () => {
  const s = load();
  s.post({ token: T, action: 'timbre', endpoint: ENDPOINT, conOrden: true });
  s.post(order('00000000-0000-4000-8000-000000000301'));
  s.env.now += 1_000;
  s.post(order('00000000-0000-4000-8000-000000000302'));
  assert.strictEqual(s.fetches.length, 1);
  assert.strictEqual(s.get({ token: T, action: 'ordenes' }).ordenes.length, 2);
});

// Catálogo vivo (1.18.0)

const HASH_A = 'a'.repeat(64);
const HASH_B = 'b'.repeat(64);
const catalog = (hash, json) => ({ token: T, action: 'catalogo', hash, json });

test('sin catálogo publicado, GET action=catalogo devuelve null', () => {
  const s = load();
  assert.deepStrictEqual(s.get({ token: T, action: 'catalogo' }), { ok: true, catalogo: null });
  assert.deepStrictEqual(s.get({ token: 'malo', action: 'catalogo' }), { ok: false, error: 'token' });
});

test('POST catalogo guarda una sola fila y GET la devuelve tal cual', () => {
  const s = load();
  const json = JSON.stringify({ protocolo: 'Protocolo Insto v6', hash: HASH_A });
  assert.deepStrictEqual(s.post(catalog(HASH_A, json)), { ok: true, catalogo: 'guardado' });
  const got = s.get({ token: T, action: 'catalogo' }).catalogo;
  assert.strictEqual(got.hash, HASH_A);
  assert.strictEqual(got.json, json);
  assert.strictEqual(got.publicado, new Date(s.env.now).toISOString());
  s.env.now += 60_000;
  const other = JSON.stringify({ protocolo: 'Protocolo Insto v6', hash: HASH_B });
  assert.deepStrictEqual(s.post(catalog(HASH_B, other)), { ok: true, catalogo: 'guardado' });
  assert.strictEqual(s.sheets['Catálogo'].rows.length, 2, 'encabezado y una sola fila');
  assert.strictEqual(s.get({ token: T, action: 'catalogo' }).catalogo.json, other);
});

test('el mismo hash no vuelve a escribir', () => {
  const s = load();
  s.post(catalog(HASH_A, '{"x":1}'));
  const before = s.sheets['Catálogo'].rows[1].slice();
  s.env.now += 60_000;
  assert.deepStrictEqual(s.post(catalog(HASH_A, '{"x":2}')), { ok: true, catalogo: 'igual' });
  assert.deepStrictEqual(s.sheets['Catálogo'].rows[1], before);
});

test('un catálogo inválido o de más de 45 000 caracteres se rechaza sin tocar nada', () => {
  const s = load();
  s.post(catalog(HASH_A, '{"x":1}'));
  assert.deepStrictEqual(s.post(catalog(HASH_B, '{"x":"' + 'y'.repeat(45_000) + '"}')), { ok: false, error: 'catalogo' });
  assert.deepStrictEqual(s.post(catalog('no-es-hash', '{}')), { ok: false, error: 'catalogo' });
  assert.deepStrictEqual(s.post(catalog(HASH_B, '')), { ok: false, error: 'catalogo' });
  assert.deepStrictEqual(s.post({ token: T, action: 'catalogo', hash: HASH_B }), { ok: false, error: 'catalogo' });
  assert.strictEqual(s.get({ token: T, action: 'catalogo' }).catalogo.hash, HASH_A);
  assert.deepStrictEqual(s.post({ token: 'malo', action: 'catalogo', hash: HASH_B, json: '{}' }), { ok: false, error: 'token' });
});

test('un catálogo de 45 000 caracteres justos entra entero, como texto plano', () => {
  const s = load();
  const json = '{"x":"' + 'y'.repeat(45_000 - 8) + '"}';
  assert.strictEqual(json.length, 45_000);
  assert.deepStrictEqual(s.post(catalog(HASH_A, json)), { ok: true, catalogo: 'guardado' });
  assert.strictEqual(s.get({ token: T, action: 'catalogo' }).catalogo.json, json);
});

test('el catálogo no cambia los eventos, el buzón ni el estado', () => {
  const s = load();
  s.post(catalog(HASH_A, '{"x":1}'));
  assert.deepStrictEqual(s.get({ token: T, since: '2020-01-01T00:00:00Z' }), { ok: true, events: [] });
  assert.deepStrictEqual(s.get({ token: T, action: 'ordenes' }), { ok: true, ordenes: [] });
  assert.deepStrictEqual(s.get({ token: T, action: 'estado' }), { ok: true, estado: null });
});

// Timbre cifrado (1.19.0)

// Un cuerpo aes128gcm (el ejemplo de RFC 8291) y unas claves públicas de suscripción con el formato correcto.
const SEALED = 'DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN';
const P256DH = 'BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4';
const AUTH = 'BTBZMqHH6r4Tts7J_aSIgg';
const sealedDoorbell = (s, extra) => s.post(Object.assign({ token: T, action: 'timbre', endpoint: ENDPOINT, conOrden: false, cifrado: { timbre: SEALED } }, extra || {}));
const orderId = (n) => '3b241101-e2bb-4255-8caf-' + String(n).padStart(12, '0');

test('con cifrado, timbre guarda el timbre cifrado y responde lo que el script sabe hacer', () => {
  const s = load();
  assert.deepStrictEqual(sealedDoorbell(s), { ok: true, timbre: 'guardado', conOrden: false, cifrado: true, rele: false, cadaOrden: true });
  assert.strictEqual(s.props.insto_timbre_cifrado, SEALED);
  assert.strictEqual(s.props.insto_timbre_p256dh, undefined, 'sin la orden en el timbre, las claves no se guardan');
});

test('una orden nueva toca el timbre cifrado: cuerpo binario de Web Push con sus cabeceras', () => {
  const s = load();
  sealedDoorbell(s);
  s.post(order(orderId(1)));
  assert.strictEqual(s.fetches.length, 1);
  const ring = s.fetches[0];
  assert.strictEqual(ring.url, ENDPOINT);
  assert.strictEqual(ring.options.contentType, 'application/octet-stream');
  assert.strictEqual(ring.options.headers['Content-Encoding'], 'aes128gcm');
  assert.strictEqual(ring.options.headers.TTL, '300');
  assert.strictEqual(ring.options.headers.Urgency, 'high');
  assert.strictEqual(Buffer.from(ring.options.payload.map((b) => b & 255)).toString('base64url'), SEALED);
  assert.ok(!JSON.stringify(ring.options).includes('ver estado'), 'la orden no viaja en claro');
});

test('con el timbre cifrado, cada orden nueva toca el timbre sin la espera de 5 segundos, hasta 30 por minuto', () => {
  const s = load();
  sealedDoorbell(s);
  for (let i = 0; i < 31; i++) s.post(order(orderId(i)));
  assert.strictEqual(s.fetches.length, 30);
  s.env.now += 60000;
  s.post(order(orderId(99)));
  assert.strictEqual(s.fetches.length, 31);
  s.post(order(orderId(99)));
  assert.strictEqual(s.fetches.length, 31, 'una repetida no toca el timbre');
});

test('con la orden en el timbre y sin relé, suena el timbre cifrado simple, nunca la orden en claro', () => {
  const s = load();
  sealedDoorbell(s, { conOrden: true, cifrado: { timbre: SEALED, p256dh: P256DH, auth: AUTH } });
  s.post(order(orderId(1)));
  assert.strictEqual(s.fetches.length, 1);
  assert.strictEqual(s.fetches[0].url, ENDPOINT);
  assert.strictEqual(s.fetches[0].options.headers['Content-Encoding'], 'aes128gcm');
});

test('con relé, la orden firmada va al relé con el secreto y las claves; el script no la publica en claro', () => {
  const s = load({ relay: true });
  assert.strictEqual(sealedDoorbell(s, { conOrden: true, cifrado: { timbre: SEALED, p256dh: P256DH, auth: AUTH } }).rele, true);
  s.post(order(orderId(1)));
  assert.strictEqual(s.fetches.length, 1);
  const call = s.fetches[0];
  assert.strictEqual(call.url, RELAY);
  assert.strictEqual(call.options.headers.Authorization, 'Bearer ' + RELAY_SECRET);
  const body = JSON.parse(call.options.payload);
  assert.deepStrictEqual(Object.keys(body).sort(), ['auth', 'endpoint', 'p256dh', 'payload', 'ttl', 'urgency']);
  assert.strictEqual(body.endpoint, ENDPOINT);
  assert.strictEqual(body.p256dh, P256DH);
  assert.deepStrictEqual(JSON.parse(body.payload).lines, ['#insto ver estado']);
});

test('si el relé falla o rechaza, suena el timbre cifrado simple', () => {
  const s = load({ relay: true });
  sealedDoorbell(s, { conOrden: true, cifrado: { timbre: SEALED, p256dh: P256DH, auth: AUTH } });
  s.env.relayFails = true;
  s.post(order(orderId(1)));
  assert.deepStrictEqual(s.fetches.map((f) => f.url), [RELAY, ENDPOINT]);
  s.env.relayFails = false;
  s.env.relayAnswer = { ok: false, error: 'secreto' };
  s.post(order(orderId(2)));
  assert.deepStrictEqual(s.fetches.map((f) => f.url), [RELAY, ENDPOINT, RELAY, ENDPOINT]);
});

test('con relé configurado pero la orden en el timbre apagada, no se usa el relé', () => {
  const s = load({ relay: true });
  assert.strictEqual(sealedDoorbell(s).rele, false);
  s.post(order(orderId(1)));
  assert.deepStrictEqual(s.fetches.map((f) => f.url), [ENDPOINT]);
});

test('cifrado inválido se rechaza sin tocar nada; null o endpoint vacío borran el timbre cifrado y las claves', () => {
  const s = load();
  for (const cifrado of [{ timbre: 'corto' }, { timbre: SEALED + '!' }, { timbre: SEALED, p256dh: 'x' }, { timbre: SEALED, auth: 'x' }, 'texto', []]) {
    assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: ENDPOINT, cifrado }), { ok: false, error: 'cifrado' }, JSON.stringify(cifrado));
  }
  assert.strictEqual(Object.keys(s.props).length, 0);
  sealedDoorbell(s, { conOrden: true, cifrado: { timbre: SEALED, p256dh: P256DH, auth: AUTH } });
  assert.strictEqual(s.post({ token: T, action: 'timbre', endpoint: ENDPOINT, cifrado: null }).cifrado, false);
  assert.strictEqual(s.props.insto_timbre_cifrado, undefined);
  assert.strictEqual(s.props.insto_timbre_p256dh, undefined);
  sealedDoorbell(s, { conOrden: true, cifrado: { timbre: SEALED, p256dh: P256DH, auth: AUTH } });
  s.post({ token: T, action: 'timbre', endpoint: '', cifrado: null });
  assert.strictEqual(s.props.insto_timbre_cifrado, undefined);
  assert.strictEqual(s.props.insto_timbre_auth, undefined);
});

test('un Insto 1.18.0 (sin cifrado) no ve campos nuevos ni pierde el timbre cifrado guardado', () => {
  const s = load();
  sealedDoorbell(s);
  assert.deepStrictEqual(s.post({ token: T, action: 'timbre', endpoint: ENDPOINT, conOrden: false }), { ok: true, timbre: 'guardado', conOrden: false });
  assert.strictEqual(s.props.insto_timbre_cifrado, SEALED);
});

test('probar-timbre usa el timbre cifrado y lo dice', () => {
  const s = load();
  sealedDoorbell(s);
  assert.deepStrictEqual(s.post({ token: T, action: 'probar-timbre' }), { ok: true, timbre: true, codigo: 200, cifrado: true });
  assert.strictEqual(s.fetches[0].options.headers['Content-Encoding'], 'aes128gcm');
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok   ' + name); } catch (error) { failed++; console.log('FAIL ' + name + '\n     ' + (error && error.message)); }
}
console.log(`${tests.length - failed}/${tests.length} pruebas del Apps Script`);
process.exit(failed ? 1 : 0);
