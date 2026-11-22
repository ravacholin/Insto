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
    getLastRow() { return rows.length; },
    setFrozenRows() {},
  };
}

function load() {
  const sheets = {};
  const props = {};
  const fetches = [];
  const env = {
    now: 1_790_000_000_000,
    fetchFails: false,
    fetchCode: 200,
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
        if (env.fetchFails) throw new Error('sin red');
        return { getResponseCode() { return env.fetchCode; } };
      },
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
  vm.runInContext(SOURCE.replace("const TOKEN = 'CAMBIÁ-ESTE-TOKEN';", "const TOKEN = 'token-de-prueba';"), context);
  const post = (body) => JSON.parse(context.doPost({ postData: { contents: JSON.stringify(body) } }).text);
  const get = (parameter) => JSON.parse(context.doGet({ parameter }).text);
  return { post, get, props, fetches, sheets, env };
}

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

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok   ' + name); } catch (error) { failed++; console.log('FAIL ' + name + '\n     ' + (error && error.message)); }
}
console.log(`${tests.length - failed}/${tests.length} pruebas del Apps Script`);
process.exit(failed ? 1 : 0);
