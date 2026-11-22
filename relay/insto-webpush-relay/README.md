# Relé de Web Push para Insto (opcional)

**Para qué sirve:** que la orden firmada de Instinct viaje **cifrada dentro del timbre** (modalidad B de 1.19.0) y Insto
la ejecute sin leer la planilla. Sin el relé, el timbre igual viaja cifrado (modalidad A: Insto cifra para sí mismo el
timbre simple) y la orden se lee de la planilla. **No hace falta desplegarlo para que el timbre funcione.**

**Por qué existe:** UnifiedPush exige que cada mensaje vaya cifrado según [RFC 8291](https://www.rfc-editor.org/rfc/rfc8291).
Google Apps Script no tiene ECDH P-256 ni AES-GCM, y escribirlos a mano en el script sería criptografía casera. Este
Worker usa Web Crypto (estándar en Cloudflare Workers) y solo compone las primitivas como manda el RFC.

## Qué hace y qué no

- Recibe del Apps Script `POST {endpoint, p256dh, auth, payload[, ttl, urgency]}` con `Authorization: Bearer <RELAY_SECRET>`.
- Cifra `payload` (como mucho 3993 bytes) para esa suscripción y lo publica en `endpoint` con `Content-Encoding: aes128gcm`,
  `TTL` y `Urgency: high`. Responde `{ok, codigo}`.
- **Solo publica en los hosts de `ALLOWED_HOSTS`** (por defecto `ntfy.sh`) y por HTTPS: no es un relé abierto.
- No guarda nada, no tiene almacenamiento ni bindings y no registra el contenido, las claves ni el endpoint.
- **No autoriza nada.** La orden sigue firmada con HMAC con una clave que el relé no conoce: no puede crear ni cambiar
  órdenes. El cifrado protege el transporte; la firma autoriza la orden.
- Ve la orden en claro mientras la cifra (como la veía ntfy en 1.18.0). Si eso no te sirve, no lo despliegues: el timbre
  cifrado simple no expone nada.

## Despliegue (manual, en tu cuenta; plan gratuito)

Requiere Node 20+ y una cuenta gratuita de Cloudflare. Nada de esto se hace automáticamente.

```sh
cd relay/insto-webpush-relay
npm test                                   # las pruebas, sin red
npx wrangler login
npx wrangler secret put RELAY_SECRET       # un texto aleatorio de 32 caracteres o más, por ejemplo: openssl rand -base64 33
# Con tu propio servidor ntfy: editá ALLOWED_HOSTS en wrangler.toml (por ejemplo "ntfy.sh,push.midominio.ar").
npx wrangler deploy                        # te da la URL https://insto-webpush-relay.<tu-cuenta>.workers.dev
```

Después, en el Apps Script (`docs/instinct-bitacora.gs`):

```js
const RELAY_URL = 'https://insto-webpush-relay.<tu-cuenta>.workers.dev/';
const RELAY_SECRET = '<el mismo secreto>';
```

Implementá una versión nueva del script (**Implementar → Gestionar implementaciones → Editar → Nueva versión**) y, en
Insto, encendé **«Incluir la orden en el timbre»**. Insto le pasa al script sus claves públicas (`p256dh`, `auth`) y la
línea de cifrado de Ajustes pasa a «La orden viaja cifrada dentro del timbre (relé configurado)».

Para apagarlo: vaciá `RELAY_URL` en el script (o apagá la opción en Insto). El timbre sigue cifrado, sin la orden.

## Pruebas

`npm test` (o `node --test test/webpush.test.mjs`): reproduce byte a byte el ejemplo de la sección 5 de RFC 8291, descifra
lo que cifra, respeta 3993/4096 bytes y prueba el relé con un `fetch` simulado (secreto, hosts permitidos, claves y
payload inválidos, push caído). `test/make-vectors.mjs` genera los vectores que `InstinctPushCryptoTest` (Insto)
descifra con Tink, el mismo código que corre en el teléfono.

**Pendiente de prueba real:** estas pruebas no usan ntfy ni Cloudflare de verdad. La guía de prueba en un teléfono
está en `docs/architecture/instinct-push-reliability.md`.
