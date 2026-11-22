# Insto 1.19.0 (código 28): timbre cifrado, sin bloqueos y sin duplicados

Un cambio de transporte, no de funciones: el timbre que avisa de las órdenes del buzón ahora usa la biblioteca oficial de UnifiedPush, viaja **cifrado según RFC 8291**, no bloquea Android y no pierde ni repite órdenes aunque el proceso de Insto muera. Para Instinct no cambia nada: Protocolo Insto v6, la misma firma `insto-v4`.

Detalle completo: [Timbre cifrado y transporte confiable](../INSTINCT-PROPUESTAS.md#timbre-cifrado-y-transporte-confiable-1190). Auditoría y diseño: [`architecture/instinct-push-reliability.md`](../architecture/instinct-push-reliability.md).

## Sin bloqueos

- `onReceive()` ya no espera hasta 20 segundos en el hilo principal: toma `goAsync()`, encola y vuelve. `finish()` se llama una sola vez.
- Recepción, cola, despacho y ejecución están separados; hilos y colas acotados; los jobs del timbre comparten un ejecutor.

## Confiable

- Lo recibido se guarda en una **cola en disco** antes del `MESSAGE_ACK`. Un job de rescate, el reinicio y la apertura de Insto la vacían; lo recuperado se revalida.
- Un registro único de ids para push, sondeo periódico, de respaldo, por WhatsApp y de seguimiento: una orden corre una sola vez aunque llegue por dos caminos a la vez.
- Una orden reclamada por un proceso que murió se informa `incierta` y no se repite. Un fallo de disco no ejecuta nada y se diagnostica como tal.

## UnifiedPush y cifrado

- Biblioteca `org.unifiedpush.android:connector` 3.3.6 (Apache 2.0, con Tink 1.24.0): token, registro, acuses, distribuidores de respaldo y claves Web Push en el Android Keystore. El registro manual de 1.18.0 se da de baja y se reemplaza.
- **Timbre cifrado simple:** Insto cifra el timbre para sí mismo y el Apps Script lo publica tal cual (`aes128gcm`). Sin infraestructura nueva.
- **Orden cifrada dentro del timbre (opcional, apagada):** un relé propio en Cloudflare Workers (plan gratuito, `relay/insto-webpush-relay`), que no se despliega solo. La firma HMAC sigue autorizando cada orden.
- Con el cifrado confirmado, un timbre en claro solo hace revisar el buzón. Con un script anterior, modo heredado marcado como no conforme.

## Más rápido

- La orden directa se ejecuta sin leer la planilla; después solo se avisa `tomadas` (sin sondeo de seguimiento con el script nuevo).
- El sondeo ejecuta antes de marcar «tomadas». El script de 1.19.0 toca el timbre por cada orden nueva (tope de 30 por minuto).

## Ajustes

Ajustes → Buzón → Timbre: línea de cifrado, «Detalle del timbre» y **«Probar de punta a punta»**, que separa registro, entrega, descifrado, verificación, despacho y resultado con una orden de solo lectura.

## Para actualizar

1. Pegá el `instinct-bitacora.gs` nuevo (conservá `const TOKEN`) e implementá una versión nueva.
2. Actualizá Insto y tocá «Probar de punta a punta».
3. Opcional: el relé, según `relay/insto-webpush-relay/README.md`.

## Verificación

- `./gradlew lintDebug testDebugUnitTest assembleDebug`: 1136 pruebas, 0 fallos (1 omitida, preexistente: `GeminiCorpusTest`).
- `python3 scripts/test-phone-core.py`: 703 pruebas. `node scripts/test-bitacora-gs.js`: 32. `npm test` del relé: 9, con el ejemplo de RFC 8291 byte a byte; los mensajes del relé se descifran con Tink en `InstinctPushCryptoTest`.
- **No probado con ntfy ni Cloudflare reales**: falta la prueba en el teléfono (procedimiento en el documento de arquitectura).
