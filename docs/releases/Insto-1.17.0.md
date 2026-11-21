# Insto 1.17.0 (código 26): timbre para el buzón, disparadores por estado y órdenes recurrentes

Dos cambios para que Instinct actúe a tiempo sin estar mirando el teléfono. Un **timbre** push hace que una orden del buzón se tome en segundos, y las órdenes pueden quedar **armadas** para que el teléfono las dispare solo: al terminar o empezar un evento, al conectar o desconectar auriculares, al desbloquear o todos los días hasta una fecha. Todo arranca apagado y, con los valores por defecto, funciona igual que en 1.16.0. No suma dependencias ni servidores. Protocolo Insto v5.

Detalle completo: [Órdenes de Instinct](../INSTINCT-PROPUESTAS.md): [Timbre](../INSTINCT-PROPUESTAS.md#timbre-1170), [Disparadores por estado](../INSTINCT-PROPUESTAS.md#disparadores-por-estado-1170) y [Órdenes recurrentes](../INSTINCT-PROPUESTAS.md#órdenes-recurrentes-1170).

## Timbre para el buzón

- **El push no lleva órdenes.** Es solo «hay algo en el buzón»: Insto lee la planilla y verifica la firma exactamente como en cualquier sondeo, así que el modelo de seguridad no cambia.
- **UnifiedPush con ntfy**, con el lado *connector* escrito a mano (especificación AND_3, sin la biblioteca ni Firebase). Insto detecta los distribuidores instalados; con varios, se elige uno; sin ninguno, dice «Instalá ntfy para usar el timbre».
- El receptor ignora los tokens que no registró y **el contenido de cada mensaje**: llame quien llame, lo único que hace es sondear el buzón.
- El endpoint vive en `noBackupFilesDir` (`instinct-doorbell-endpoint`), en Ajustes aparece enmascarado («••••» y los últimos 6 caracteres) y nunca va a Logcat, al diagnóstico, al catálogo ni a WhatsApp.
- **Apps Script:** acciones nuevas `timbre` (guarda el endpoint en las propiedades del script, no en la planilla) y `probar-timbre`. Al anotar una orden **nueva** (no una repetida), el script toca el timbre, como mucho una vez cada 5 segundos; si falla, la orden queda anotada igual. Un script viejo sigue funcionando: Insto dice «el script no confirmó el endpoint».
- **Ningún timbre se pierde:** separación mínima de 5 segundos; un timbre dentro de esa ventana se reprograma, uno durante un sondeo hace que ese sondeo vuelva a leer, y uno sin red deja un job (*expedited* desde Android 12).
- Mientras el timbre suena, el sondeo de respaldo pasa a 15 minutos para ahorrar cuota; a las 24 horas sin timbres vuelve al intervalo elegido.
- Ajustes → Buzón: «Timbre instantáneo (ntfy)», la línea de estado y «Probar timbre». Diagnóstico: «Timbre: último HH:MM · timbres N · sondeos por timbre N».

## Disparadores por estado

- `#insto al terminar el evento: …`, `al empezar el próximo evento: …`, `al conectar auriculares: …`, `al desconectar auriculares: …` y `al desbloquear: …`, con `hasta las HH:MM` opcional. Una sola condición por línea; cada disparador se usa una vez; vencen a las 12 horas (como mucho 24) y se registran como `vencida`.
- Los de calendario leen `CalendarContract.Instances` al recibir la orden (sin eventos de todo el día ni títulos) y otra vez al dispararse: si el evento se movió, se reprograma; si se borró, se descarta. Sin permiso o sin evento, se rechaza con el motivo.
- Auriculares y desbloqueo usan los callbacks que ya registraba el estado del teléfono: no hay receptores nuevos. Funcionan mientras «Insto · notificaciones» esté vivo.

## Órdenes recurrentes

- `#insto todos los días | los días hábiles | los fines de semana a las HH:MM hasta el DD/MM: …`, con la condición de clima opcional. `hasta el` es obligatorio, como mucho 30 días; hasta 5 recurrentes aparte de las 10 programadas.
- Cada ocurrencia se marca consumida en disco antes de ejecutarse, se revalida, pasa a Confirmar si llega con más de 15 minutos de atraso y deja programada la siguiente en la misma escritura. Una ocurrencia incierta no se repite, pero la recurrencia sigue.
- Con «Nunca» en su categoría, la recurrente se cancela entera y se registra.

## Consultas, almacenamiento y catálogo

- `#insto ver programadas` muestra un id corto de 6 caracteres y el disparador en palabras; `#insto cancelar programada <id>` cancela una (o una recurrente entera) sin consumir el freno.
- `instinct-scheduled.json` tiene versión (`{"version": 2, "orders": […]}`); los archivos de 1.16.0 se migran sin perder nada (lo que no tiene disparador es `At`).
- **Protocolo Insto v5:** las formas nuevas con un ejemplo validado por test cada una, y la regla «preferí dejar un disparador antes que vigilar el estado y mandar la orden después». Copias de referencia regeneradas. La firma del buzón sigue siendo `insto-v4`.

## Privacidad

Todo lo nuevo vive en `noBackupFilesDir` y nunca entra en copias de seguridad. Nada nuevo sale por WhatsApp: lo nuevo informa por la bitácora (y por el chat, si ese canal ya estaba en uso, como cualquier acuse). El endpoint del timbre solo sale hacia tu Apps Script; los disparadores de calendario guardan ids y horas, nunca títulos.

## Verificación

- `./gradlew lint testDebugUnitTest`, con `InstinctDoorbellFlowTest` (registro, timbre que sondea, token ajeno, ventana de 5 segundos, intervalo de respaldo, baja y script viejo) e `InstinctTriggerFlowTest` (auriculares una sola vez, evento movido, evento borrado, vencimiento, ocurrencia atrasada a Confirmar, cancelación por id corto y «Nunca»).
- `python3 scripts/test-phone-core.py`, con `InstinctDoorbellTest` e `InstinctTriggersTest` (gramática, `hasta` válido e inválido, recurrentes sin `hasta` o de más de 30 días, dos condiciones, próximas ocurrencias con cambio de semana y de zona horaria, migración del JSON de 1.16.0), y las pruebas del Apps Script en Node (`scripts/test-bitacora-gs.js`).
