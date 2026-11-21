# Insto 1.15.0 (código 24): la bitácora

Los acuses a Instinct pueden ir a una **bitácora en Google Sheets** en lugar del chat de WhatsApp, para no llenar la conversación de mensajes. Las órdenes `#insto` siguen llegando por WhatsApp exactamente como en 1.14.0: no cambia nada del camino de entrada, ni la lista blanca, ni las rutinas, ni las diferidas. Viene apagado: el canal por defecto sigue siendo WhatsApp. No suma dependencias.

Detalle completo: [Órdenes de Instinct → Bitácora](../INSTINCT-PROPUESTAS.md#bitácora-1150).

## Canal de estados

- Nuevo selector **Ajustes → Propuestas de Instinct → «Canal de estados»**: WhatsApp (por defecto), Bitácora o Ambos. Vive en `noBackupFilesDir`: una restauración nunca lo trae.
- «Responderle a Instinct» (Nunca / Solo errores y esperas / Siempre) sigue decidiendo **qué** se informa; el canal decide **por dónde**.
- Con Bitácora, Insto **nunca** lee ni usa la acción «Responder» de WhatsApp.

## Bitácora

- Configuración en la misma pantalla: URL del Apps Script (validada: `https://script.google.com/macros/s/…/exec`) y token. Se guardan en `noBackupFilesDir`, nunca entran en copias de seguridad, el token se muestra solo por sus últimos 4 caracteres y nunca se registra.
- Botón **«Probar bitácora»**: manda un evento `kind=prueba` y muestra el resultado.
- Script de referencia para la planilla: [`docs/instinct-bitacora.gs`](../instinct-bitacora.gs) (idempotente por id, lectura por `since`).
- Cada acuse es un evento `{id, at, kind, order, detail, message, protocol}`: UUID aleatorio, instante ISO-8601 con zona, el prefijo sin `#insto-`, la orden recortada a 80 caracteres, el mismo texto de resultado del chat, los primeros 12 hex del hash del dedupe (nunca el texto del mensaje) y «Protocolo Insto v3». La guardia anti-bucle se aplica a cada campo.
- Bandeja de salida persistente (`instinct-outbox.json`, escritura atómica, hasta 200 eventos; si se llena se descarta el más viejo y el diagnóstico lo dice). Un POST por lote de hasta 50 con OkHttp; Insto sigue el 302 de Apps Script con un GET y borra de la bandeja solo los ids aceptados.
- Reintentos con `JobScheduler` (red requerida, *backoff* exponencial), reprogramados en `BOOT_COMPLETED`. Timeout de 15 segundos. Un fallo nunca bloquea una orden ni dispara un envío por WhatsApp.
- Sin el límite de 10 minutos de «Responder»: confirmada, descartada, vencida, el disparo de una diferida y Deshacer se registran aunque haya pasado más tiempo. Una diferida guarda el identificador corto de su mensaje y se registra aunque el teléfono se haya reiniciado.
- Diagnóstico: «Bitácora: última escritura HH:MM · pendientes N» o «Bitácora: no se pudo escribir (motivo: …)».

## Catálogo: Protocolo Insto v3

- Con Bitácora o Ambos, el bloque para Instinct explica cómo leer la bitácora: `GET <URL>?token=<token>&since=<ISO>&limit=50` → `{ok, events:[{recibido, evento_id, ocurrido, tipo, orden, detalle, mensaje_id, protocolo}]}`.
- «Copiar instrucciones para Instinct» incluye la URL y el token. «Enviarlas a Instinct» por WhatsApp no los incluye: dice «(URL y token: pedíselos a Pablo)», y Insto comprueba que el texto no los contenga antes de mandarlo.
- Regla nueva para Instinct: antes de repetir o corregir una orden, consultá la bitácora. Las demás se mantienen.
- Copias de referencia: `docs/instinct-catalog.txt` (WhatsApp) y `docs/instinct-catalog-bitacora.txt` (bitácora, copia del chat); un test compara las dos con el código.

## Privacidad

Con Bitácora o Ambos sale del teléfono, solo hacia tu Apps Script: la orden recortada, el resultado, el tipo, la hora y el hash corto del mensaje. Nunca el texto del mensaje de Instinct ni datos del teléfono.

## Verificación

- `python3 scripts/test-phone-core.py`: suite JVM sin Android, con la prueba nueva `InstinctLogTest` (formato y recortes del evento, guardia anti-bucle en todos los campos, bandeja: idempotencia, borrado solo de aceptados, tope de 200 y escritura atómica; redirección 302 con un transporte falso de OkHttp, validación de la URL, canales del ledger y catálogo v3).
- `./gradlew testDebugUnitTest assembleDebug`: suite completa con Robolectric, con `InstinctLogFlowTest` (con Bitácora nunca se lee el `RemoteInput` de WhatsApp; el texto que va por WhatsApp nunca incluye el token ni la URL; vaciado de la bandeja y diagnóstico).
- No se probó contra un Apps Script real ni en un teléfono físico: la redirección de Google, las cuotas y el `JobScheduler` en Doze quedan para la prueba manual.
