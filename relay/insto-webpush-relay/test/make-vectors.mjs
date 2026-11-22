// node relay/insto-webpush-relay/test/make-vectors.mjs <salida.json>
// Vectores de interoperabilidad: mensajes cifrados por el relé (este código) para una suscripción de prueba. La prueba de
// Insto (InstinctPushCryptoTest) los descifra con Tink, el mismo descifrador que usa la biblioteca de UnifiedPush.
import { writeFileSync } from 'node:fs';
import { b64urlEncode, encrypt, MAX_PLAINTEXT_BYTES } from '../src/webpush.js';

const ua = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
const jwk = await crypto.subtle.exportKey('jwk', ua.privateKey);
const uaPublic = new Uint8Array(await crypto.subtle.exportKey('raw', ua.publicKey));
const auth = crypto.getRandomValues(new Uint8Array(16));
const order = JSON.stringify({ v: 1, id: '3b241101-e2bb-4255-8caf-4136c566a962', at: '2026-11-21T09:30:00-03:00',
  lines: ['#insto poner el volumen al 30 por ciento', '#insto avisarme Listo, ñandú.'], sig: 'a'.repeat(64) });
const texts = ['insto-timbre', order, 'x'.repeat(MAX_PLAINTEXT_BYTES)];
const cases = [];
for (const text of texts) {
  const body = await encrypt({ uaPublic, authSecret: auth, plaintext: new TextEncoder().encode(text) });
  cases.push({ plaintext: text, body: b64urlEncode(body) });
}
writeFileSync(process.argv[2], JSON.stringify({ receiver: { public: b64urlEncode(uaPublic), private: jwk.d }, auth: b64urlEncode(auth), cases }, null, 1) + '\n');
