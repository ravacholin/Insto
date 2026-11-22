// Cifrado de Web Push (RFC 8291, «aes128gcm» de RFC 8188) con Web Crypto y nada más: ECDH P-256, HKDF-SHA-256 y
// AES-128-GCM son primitivas estándar del entorno (Cloudflare Workers, Node 20+ y los navegadores). Este archivo solo
// las compone exactamente como manda la sección 3.4 de RFC 8291; no implementa ninguna primitiva criptográfica.

const encoder = new TextEncoder();

/** Tamaño de registro: el valor habitual de Web Push. Un solo registro alcanza para 4096 bytes. */
export const RECORD_SIZE = 4096;
/** UnifiedPush y Web Push: el mensaje cifrado ocupa como mucho 4096 bytes, así que el texto, 3993. */
export const MAX_PLAINTEXT_BYTES = 3993;
const HEADER_BYTES = 86;

export function b64urlDecode(text) {
  if (typeof text !== 'string' || !/^[A-Za-z0-9_-]*={0,2}$/.test(text)) throw new Error('base64url');
  const plain = text.replace(/=+$/, '').replace(/-/g, '+').replace(/_/g, '/');
  const padded = plain + '='.repeat((4 - (plain.length % 4)) % 4);
  const binary = atob(padded);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

export function b64urlEncode(bytes) {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let offset = 0;
  for (const p of parts) { out.set(p, offset); offset += p.length; }
  return out;
}

/** HKDF de RFC 5869 (extract y expand) con SHA-256, de Web Crypto. */
async function hkdf(salt, ikm, info, length) {
  const key = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt, info }, key, length * 8));
}

/** Una clave pública P-256 sin comprimir: 65 bytes que empiezan con 0x04. */
export function validPublicKey(bytes) {
  return bytes instanceof Uint8Array && bytes.length === 65 && bytes[0] === 4;
}

/**
 * Cifra [plaintext] para la suscripción ([uaPublic], 65 bytes; [authSecret], 16 bytes). Devuelve el cuerpo completo:
 * cabecera (salt · rs · idlen · keyid) y un único registro con el delimitador 0x02. [salt] y [asKeyPair] solo se pasan
 * en las pruebas, para reproducir el ejemplo de RFC 8291; en uso real son aleatorios y descartables.
 */
export async function encrypt({ uaPublic, authSecret, plaintext, salt, asKeyPair }) {
  if (!validPublicKey(uaPublic)) throw new Error('p256dh');
  if (!(authSecret instanceof Uint8Array) || authSecret.length !== 16) throw new Error('auth');
  if (!(plaintext instanceof Uint8Array) || plaintext.length < 1 || plaintext.length > MAX_PLAINTEXT_BYTES) throw new Error('payload');
  const saltBytes = salt || crypto.getRandomValues(new Uint8Array(16));
  const pair = asKeyPair || await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
  const asPublic = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey));
  const receiver = await crypto.subtle.importKey('raw', uaPublic, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const ecdhSecret = new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: receiver }, pair.privateKey, 256));
  // RFC 8291 §3.4: IKM = HKDF(auth_secret, ecdh_secret, "WebPush: info" || 0x00 || ua_public || as_public, 32)
  const keyInfo = concat(encoder.encode('WebPush: info\u0000'), uaPublic, asPublic);
  const ikm = await hkdf(authSecret, ecdhSecret, keyInfo, 32);
  // RFC 8188 §2.2 y §2.3: CEK y NONCE a partir de salt e IKM.
  const cek = await hkdf(saltBytes, ikm, encoder.encode('Content-Encoding: aes128gcm\u0000'), 16);
  const nonce = await hkdf(saltBytes, ikm, encoder.encode('Content-Encoding: nonce\u0000'), 12);
  const key = await crypto.subtle.importKey('raw', cek, 'AES-GCM', false, ['encrypt']);
  // Un único registro, el último: el texto y el delimitador 0x02, sin relleno.
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, tagLength: 128 },
    key, concat(plaintext, new Uint8Array([2]))));
  const header = new Uint8Array(HEADER_BYTES);
  header.set(saltBytes, 0);
  new DataView(header.buffer).setUint32(16, RECORD_SIZE);
  header[20] = 65;
  header.set(asPublic, 21);
  return concat(header, sealed);
}
