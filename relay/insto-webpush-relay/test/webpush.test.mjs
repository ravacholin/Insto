// node --test relay/insto-webpush-relay/test/webpush.test.mjs
// RFC 8291: el ejemplo de la sección 5 y el apéndice A, byte a byte, y el relé con un fetch simulado.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { b64urlDecode, b64urlEncode, encrypt, MAX_PLAINTEXT_BYTES } from '../src/webpush.js';
import { handle } from '../src/index.js';

const RFC = {
  plaintext: 'When I grow up, I want to be a watermelon',
  asPublic: 'BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8',
  asPrivate: 'yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw',
  uaPublic: 'BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4',
  uaPrivate: 'q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94',
  salt: 'DGv6ra1nlYgDCS1FRnbzlw',
  auth: 'BTBZMqHH6r4Tts7J_aSIgg',
  result: 'DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml' +
    'mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT' +
    'pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN',
};

/** Un par P-256 de Web Crypto a partir de la clave pública sin comprimir y la privada, en base64url. */
async function keyPair(publicB64, privateB64) {
  const pub = b64urlDecode(publicB64);
  const jwk = { kty: 'EC', crv: 'P-256', x: b64urlEncode(pub.slice(1, 33)), y: b64urlEncode(pub.slice(33)), d: privateB64, ext: true };
  const privateKey = await crypto.subtle.importKey('jwk', jwk, { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
  const publicKey = await crypto.subtle.importKey('raw', pub, { name: 'ECDH', namedCurve: 'P-256' }, true, []);
  return { privateKey, publicKey };
}

/** Descifrado RFC 8291 solo para las pruebas: el del teléfono es el de Tink, dentro de la biblioteca de UnifiedPush. */
async function decrypt(body, uaPair, authSecret) {
  const salt = body.slice(0, 16);
  const idlen = body[20];
  const asPublic = body.slice(21, 21 + idlen);
  const uaPublic = new Uint8Array(await crypto.subtle.exportKey('raw', uaPair.publicKey));
  const sender = await crypto.subtle.importKey('raw', asPublic, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const ecdh = new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: sender }, uaPair.privateKey, 256));
  const hk = async (s, ikm, info, n) => new Uint8Array(await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt: s, info },
    await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']), n * 8));
  const e = new TextEncoder();
  const info = new Uint8Array([...e.encode('WebPush: info\u0000'), ...uaPublic, ...asPublic]);
  const ikm = await hk(authSecret, ecdh, info, 32);
  const cek = await hk(salt, ikm, e.encode('Content-Encoding: aes128gcm\u0000'), 16);
  const nonce = await hk(salt, ikm, e.encode('Content-Encoding: nonce\u0000'), 12);
  const key = await crypto.subtle.importKey('raw', cek, 'AES-GCM', false, ['decrypt']);
  const padded = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce }, key, body.slice(21 + idlen)));
  let end = padded.length - 1;
  while (end >= 0 && padded[end] === 0) end--;
  assert.equal(padded[end], 2, 'último registro con el delimitador 0x02');
  return new TextDecoder().decode(padded.slice(0, end));
}

test('reproduce el ejemplo de RFC 8291 byte a byte', async () => {
  const sealed = await encrypt({
    uaPublic: b64urlDecode(RFC.uaPublic), authSecret: b64urlDecode(RFC.auth), plaintext: new TextEncoder().encode(RFC.plaintext),
    salt: b64urlDecode(RFC.salt), asKeyPair: await keyPair(RFC.asPublic, RFC.asPrivate),
  });
  assert.equal(b64urlEncode(sealed), RFC.result);
  // 86 de cabecera + 41 de texto + 1 delimitador + 16 de etiqueta. El ejemplo del RFC dice «Content-Length: 145», pero su
  // propio cuerpo decodifica a 144 bytes: se compara el cuerpo, que es lo que cuenta.
  assert.equal(sealed.length, 144);
});

test('el ejemplo de RFC 8291 se descifra con la clave del receptor', async () => {
  const text = await decrypt(b64urlDecode(RFC.result), await keyPair(RFC.uaPublic, RFC.uaPrivate), b64urlDecode(RFC.auth));
  assert.equal(text, RFC.plaintext);
});

test('cada cifrado usa sal y clave efímera nuevas, y se descifra', async () => {
  const ua = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const uaPublic = new Uint8Array(await crypto.subtle.exportKey('raw', ua.publicKey));
  const auth = crypto.getRandomValues(new Uint8Array(16));
  const plaintext = new TextEncoder().encode('{"v":1}');
  const a = await encrypt({ uaPublic, authSecret: auth, plaintext });
  const b = await encrypt({ uaPublic, authSecret: auth, plaintext });
  assert.notEqual(b64urlEncode(a), b64urlEncode(b));
  assert.deepEqual([...a.slice(16, 21)], [0, 0, 16, 0, 65]);
  assert.equal(await decrypt(a, ua, auth), '{"v":1}');
});

test('el tamaño máximo es 3993 bytes de texto, 4096 cifrados', async () => {
  const ua = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const uaPublic = new Uint8Array(await crypto.subtle.exportKey('raw', ua.publicKey));
  const auth = crypto.getRandomValues(new Uint8Array(16));
  const sealed = await encrypt({ uaPublic, authSecret: auth, plaintext: new Uint8Array(MAX_PLAINTEXT_BYTES).fill(97) });
  assert.equal(sealed.length, 4096);
  await assert.rejects(encrypt({ uaPublic, authSecret: auth, plaintext: new Uint8Array(MAX_PLAINTEXT_BYTES + 1) }));
  await assert.rejects(encrypt({ uaPublic: uaPublic.slice(1), authSecret: auth, plaintext: new Uint8Array(1) }));
  await assert.rejects(encrypt({ uaPublic, authSecret: auth.slice(1), plaintext: new Uint8Array(1) }));
});

// El relé

const SECRET = 'secreto-del-rele-de-prueba-0123456789';
const ENDPOINT = 'https://ntfy.sh/upAbCdEf0123456789?up=1';

async function subscription() {
  const ua = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const auth = crypto.getRandomValues(new Uint8Array(16));
  return { ua, auth, p256dh: b64urlEncode(new Uint8Array(await crypto.subtle.exportKey('raw', ua.publicKey))), authB64: b64urlEncode(auth) };
}

function request(body, { secret = SECRET, method = 'POST' } = {}) {
  return new Request('https://relay.example.workers.dev/', {
    method, headers: { Authorization: 'Bearer ' + secret, 'Content-Type': 'application/json' },
    body: method === 'POST' ? JSON.stringify(body) : undefined,
  });
}

test('el relé cifra, publica con las cabeceras de Web Push y responde el código', async () => {
  const s = await subscription();
  const sent = [];
  const send = async (url, init) => { sent.push({ url, init }); return new Response('', { status: 201 }); };
  const payload = JSON.stringify({ v: 1, id: '3b241101-e2bb-4255-8caf-4136c566a962', at: '2026-11-21T09:30:00-03:00', lines: ['#insto ver estado'], sig: '0'.repeat(64) });
  const res = await handle(request({ endpoint: ENDPOINT, p256dh: s.p256dh, auth: s.authB64, payload }), { RELAY_SECRET: SECRET }, send);
  assert.deepEqual(await res.json(), { ok: true, codigo: 201 });
  assert.equal(sent.length, 1);
  assert.equal(sent[0].url, ENDPOINT);
  assert.equal(sent[0].init.headers['Content-Encoding'], 'aes128gcm');
  assert.equal(sent[0].init.headers.Urgency, 'high');
  assert.equal(sent[0].init.headers.TTL, '300');
  assert.equal(await decrypt(sent[0].init.body, s.ua, s.auth), payload);
  assert.ok(!new TextDecoder('latin1').decode(sent[0].init.body).includes('#insto'), 'el contenido no viaja en claro');
});

test('el relé rechaza sin secreto, con otro secreto o sin configurar', async () => {
  const s = await subscription();
  const body = { endpoint: ENDPOINT, p256dh: s.p256dh, auth: s.authB64, payload: 'insto-timbre' };
  const never = async () => { throw new Error('no debía publicar'); };
  assert.equal((await handle(request(body, { secret: 'otro-secreto-que-no-es-el-del-rele-000' }), { RELAY_SECRET: SECRET }, never)).status, 401);
  assert.equal((await handle(request(body), { RELAY_SECRET: 'corto' }, never)).status, 503);
  assert.equal((await handle(request(body), {}, never)).status, 503);
  assert.equal((await handle(request(body, { method: 'GET' }), { RELAY_SECRET: SECRET }, never)).status, 405);
});

test('el relé solo publica en hosts permitidos y por https', async () => {
  const s = await subscription();
  const never = async () => { throw new Error('no debía publicar'); };
  for (const endpoint of ['https://evil.example/up', 'http://ntfy.sh/up', 'https://user:pw@ntfy.sh/up', 'no-es-url', 'https://ntfy.sh/' + 'a'.repeat(1000)]) {
    const res = await handle(request({ endpoint, p256dh: s.p256dh, auth: s.authB64, payload: 'x' }), { RELAY_SECRET: SECRET }, never);
    assert.equal(res.status, 400, endpoint);
  }
  const ok = async () => new Response('', { status: 200 });
  const own = await handle(request({ endpoint: 'https://push.mio.example/up1', p256dh: s.p256dh, auth: s.authB64, payload: 'x' }),
    { RELAY_SECRET: SECRET, ALLOWED_HOSTS: 'ntfy.sh, push.mio.example' }, ok);
  assert.equal(own.status, 200);
});

test('el relé rechaza claves, payload o cuerpo inválidos sin publicar', async () => {
  const s = await subscription();
  const never = async () => { throw new Error('no debía publicar'); };
  const env = { RELAY_SECRET: SECRET };
  assert.equal((await handle(request({ endpoint: ENDPOINT, p256dh: 'corta', auth: s.authB64, payload: 'x' }), env, never)).status, 400);
  assert.equal((await handle(request({ endpoint: ENDPOINT, p256dh: s.p256dh, auth: 'AAAA', payload: 'x' }), env, never)).status, 400);
  assert.equal((await handle(request({ endpoint: ENDPOINT, p256dh: s.p256dh, auth: s.authB64, payload: '' }), env, never)).status, 400);
  assert.equal((await handle(request({ endpoint: ENDPOINT, p256dh: s.p256dh, auth: s.authB64, payload: 'a'.repeat(MAX_PLAINTEXT_BYTES + 1) }), env, never)).status, 413);
  const bad = new Request('https://relay.example.workers.dev/', { method: 'POST', headers: { Authorization: 'Bearer ' + SECRET }, body: '{no' });
  assert.equal((await handle(bad, env, never)).status, 400);
});

test('si el push falla, el relé lo dice sin reintentar', async () => {
  const s = await subscription();
  const body = { endpoint: ENDPOINT, p256dh: s.p256dh, auth: s.authB64, payload: 'insto-timbre' };
  let calls = 0;
  const down = async () => { calls++; throw new Error('ntfy caído'); };
  assert.deepEqual(await (await handle(request(body), { RELAY_SECRET: SECRET }, down)).json(), { ok: false, error: 'push' });
  const gone = async () => { calls++; return new Response('', { status: 410 }); };
  assert.deepEqual(await (await handle(request(body), { RELAY_SECRET: SECRET }, gone)).json(), { ok: false, codigo: 410 });
  assert.equal(calls, 2);
});
