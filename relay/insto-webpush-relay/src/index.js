// Relé de Web Push para Insto (1.19.0, opcional). El Apps Script no tiene ECDH ni AES-GCM, así que no puede cifrar
// según RFC 8291; este Worker recibe del script la orden ya firmada con HMAC, la cifra para la suscripción de Insto y la
// publica en el endpoint de UnifiedPush (ntfy). No guarda nada, no registra el contenido y solo publica en los hosts
// permitidos. El cifrado protege el transporte; la firma HMAC, que el relé no conoce ni puede rehacer, autoriza la orden.
import { b64urlDecode, encrypt, MAX_PLAINTEXT_BYTES, validPublicKey } from './webpush.js';

const MAX_BODY_CHARS = 16384;
const URGENCIES = new Set(['very-low', 'low', 'normal', 'high']);
const encoder = new TextEncoder();

function json(status, value) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json; charset=utf-8' } });
}

/** Compara dos secretos sin filtrar cuánto coinciden: los resúmenes SHA-256, byte a byte, sin cortar antes. */
async function sameSecret(given, expected) {
  const [a, b] = await Promise.all([given, expected].map(async (t) =>
    new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(t)))));
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

/** Los hosts a los que el relé publica: ALLOWED_HOSTS separados por comas, ntfy.sh si no se configuró nada. */
function allowedHosts(env) {
  return new Set(String(env.ALLOWED_HOSTS || 'ntfy.sh').split(',').map((h) => h.trim().toLowerCase()).filter(Boolean));
}

function endpointAllowed(endpoint, env) {
  if (typeof endpoint !== 'string' || endpoint.length > 1000) return false;
  let url;
  try { url = new URL(endpoint); } catch (_) { return false; }
  return url.protocol === 'https:' && !url.username && !url.password && allowedHosts(env).has(url.hostname.toLowerCase());
}

/**
 * POST {endpoint, p256dh, auth, payload[, ttl, urgency]} con «Authorization: Bearer <RELAY_SECRET>».
 * Responde {ok, codigo} (el código HTTP del push) o {ok:false, error}. [send] es fetch; las pruebas lo reemplazan.
 */
export async function handle(request, env, send = fetch) {
  if (request.method !== 'POST') return json(405, { ok: false, error: 'metodo' });
  const secret = typeof env.RELAY_SECRET === 'string' ? env.RELAY_SECRET : '';
  if (secret.length < 32) return json(503, { ok: false, error: 'sin-configurar' });
  const header = request.headers.get('Authorization') || '';
  const given = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (!given || !(await sameSecret(given, secret))) return json(401, { ok: false, error: 'secreto' });
  const length = Number(request.headers.get('Content-Length') || '0');
  if (length > MAX_BODY_CHARS) return json(413, { ok: false, error: 'tamano' });
  const text = await request.text();
  if (text.length > MAX_BODY_CHARS) return json(413, { ok: false, error: 'tamano' });
  let body;
  try { body = JSON.parse(text); } catch (_) { return json(400, { ok: false, error: 'json' }); }
  if (!body || typeof body !== 'object') return json(400, { ok: false, error: 'json' });
  if (!endpointAllowed(body.endpoint, env)) return json(400, { ok: false, error: 'endpoint' });
  let uaPublic; let authSecret;
  try { uaPublic = b64urlDecode(body.p256dh); authSecret = b64urlDecode(body.auth); } catch (_) { return json(400, { ok: false, error: 'claves' }); }
  if (!validPublicKey(uaPublic) || authSecret.length !== 16) return json(400, { ok: false, error: 'claves' });
  if (typeof body.payload !== 'string' || !body.payload) return json(400, { ok: false, error: 'payload' });
  const plaintext = encoder.encode(body.payload);
  if (plaintext.length > MAX_PLAINTEXT_BYTES) return json(413, { ok: false, error: 'payload' });
  const ttl = Number.isInteger(body.ttl) && body.ttl >= 0 && body.ttl <= 86400 ? body.ttl : 300;
  const urgency = URGENCIES.has(body.urgency) ? body.urgency : 'high';
  let sealed;
  try { sealed = await encrypt({ uaPublic, authSecret, plaintext }); } catch (_) { return json(400, { ok: false, error: 'claves' }); }
  let response;
  try {
    response = await send(body.endpoint, {
      method: 'POST',
      headers: { 'Content-Encoding': 'aes128gcm', 'Content-Type': 'application/octet-stream', TTL: String(ttl), Urgency: urgency },
      body: sealed,
    });
  } catch (_) {
    return json(502, { ok: false, error: 'push' });
  }
  return json(200, { ok: response.status >= 200 && response.status < 300, codigo: response.status });
}

export default {
  fetch(request, env) { return handle(request, env); },
};
