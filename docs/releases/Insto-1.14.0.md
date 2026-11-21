# Insto 1.14.0 (código 23): Instinct se entera

Tres mejoras sobre «Instinct con manos» (1.13.0). Todas son opcionales, vienen apagadas y viven en `noBackupFilesDir`: una restauración de copia de seguridad nunca las enciende ni las trae. No suman dependencias. La única red sigue siendo la del SMN y Open-Meteo que ya existía.

Detalle completo: [Órdenes de Instinct](../INSTINCT-PROPUESTAS.md).

## 1. Insto le responde a Instinct

- Nuevo selector **Ajustes → Propuestas de Instinct → «Responderle a Instinct»**: Nunca (por defecto), Solo errores y esperas, Siempre.
- Insto contesta con la acción **«Responder»** de la misma notificación de WhatsApp que trajo las órdenes. No abre WhatsApp, no usa la Accesibilidad y funciona igual con el teléfono bloqueado. La acción se guarda solo en memoria y durante 10 minutos como máximo.
- Un mensaje por mensaje de Instinct, con una línea por orden: `#insto-ok`, `#insto-espera`, `#insto-error` o `#insto-incierto`, seguidos de la orden y del texto de resultado que Insto ya produce. Máximo 3 líneas y 600 caracteres.
- Si una orden quedó en espera, al confirmarla, descartarla o al vencer llega un segundo acuse breve (`#insto-ok`, `#insto-descartada`, `#insto-vencida`), si la acción sigue vigente.
- Guardia anti-bucle: ninguna línea del acuse puede empezar con «#insto ». Nuestro propio mensaje, cuando WhatsApp vuelve a publicar la notificación, nunca se procesa como orden.
- Si WhatsApp no ofrece «Responder» o cancela el envío, el acuse se descarta y el diagnóstico lo dice. Nunca se reintenta.
- Diagnóstico: nueva línea «Última respuesta a Instinct» («Se respondió a Instinct» / «No se pudo responder»), sin contenido.

## 2. Rutinas para Instinct y catálogo generado

- Instinct puede pedir `#insto ejecutar la rutina <nombre>`, solo para las rutinas que habilites una por una en **Ajustes → Propuestas de Instinct → Rutinas para Instinct** (apagadas por defecto).
- Solo se pueden habilitar rutinas de hasta 6 pasos en las que todos los pasos están en la lista blanca. Si no, el interruptor dice qué paso lo impide. Cada permiso guarda un hash de los pasos: si la rutina cambia, queda deshabilitada hasta que la vuelvas a encender.
- El nombre tiene que coincidir exactamente. Antes del primer paso se chequean todos: si uno no puede correr solo (por ejemplo, abre otra app sin Accesibilidad), la rutina entera espera tu confirmación. Cuenta como una sola orden en el freno anti-bucle.
- En segundo plano corre con `WorkflowRunner` (escritura previa a cada paso) y se detiene en el primer paso que falla, necesita algo tuyo o queda incierto; los demás se cancelan y nunca se reintentan solos.
- Aviso: «Instinct ejecutó la rutina Estudio: 3 de 3 pasos» o «…se detuvo en el paso 2: <motivo>». El acuse a Instinct lleva el mismo resumen.
- El bloque de instrucciones para Instinct se genera desde el código (`InstinctProtocolCatalog`, «Protocolo Insto v2»): reglas, un ejemplo por tipo permitido, rutinas habilitadas (solo nombres) y prefijos de acuse. Botones **«Copiar instrucciones para Instinct»** y, con una acción «Responder» vigente, **«Enviarlas a Instinct»**.
- Nueva orden de solo lectura `#insto ver catálogo`: no hace nada en el teléfono y, con los acuses activos, responde la versión del protocolo y las rutinas habilitadas.
- Un test verifica que cada ejemplo del catálogo es una orden válida y que la copia de referencia `docs/instinct-catalog.txt` coincide con lo que genera el código.

## 3. Órdenes diferidas, condiciones por clima y Deshacer

- **Diferidas:** `#insto a las HH:MM: <orden>` (24 h; si ya pasó hoy, es mañana) y `#insto en N minutos: <orden>` / `en N horas` (de 1 minuto a 24 horas). Los dos puntos son obligatorios: sin ellos la línea sigue siendo una orden inmediata, así que «poner una alarma a las 07:00» no cambia.
- La orden interior se valida al recibirla (si es inválida, `#insto-error`) y otra vez desde cero al dispararse (interruptor, huella de configuración, parser del momento). Acuse al programar: `#insto-programada <orden> → a las 22:00`.
- Se guardan en `noBackupFilesDir` con escritura atómica: como máximo 10 y 24 horas a futuro. Se disparan con `AlarmManager` (exacto solo si Android lo permite) hacia un receiver no exportado, y se reprograman al arrancar el teléfono y al cambiar la hora o la zona horaria.
- Cada programada se marca consumida en disco **antes** de ejecutarse: si el proceso muere en el medio, se avisa que pudo haberse ejecutado y no se repite. Si se dispara con más de 15 minutos de atraso, espera tu confirmación («Llegó tarde»).
- `#insto ver programadas` y `#insto cancelar programadas`; en Ajustes, la lista con hora y resumen y un botón para cancelar cada una. Apagar «Recibir órdenes» las cancela.
- **Condiciones por clima**, solo con hora y una por línea: `si llueve`, `si no llueve`, `si hace menos de N grados`, `si hace más de N grados`. «Llueve» = probabilidad ≥ 50 % en las próximas 6 horas; temperatura = observación actual. Se evalúan al dispararse con el SMN y su respaldo, que ahora exponen valores numéricos. Sin datos, con timeout o con un dato ambiguo, **no se ejecuta** y se responde «sin datos del clima».
- **Deshacer** en «Instinct hizo: …» para volumen, linterna, silencio/vibración/No molestar y modo temporal. No pisa un cambio manual posterior (lo dice). Vence a los 10 minutos, se usa una sola vez y lleva un token aleatorio. En una rutina revierte los pasos reversibles en orden inverso y lista los que no se pueden deshacer. Con los acuses activos, Instinct recibe `#insto-deshecho <orden>`.
- El catálogo generado suma las formas diferidas y condicionales, las consultas nuevas y los prefijos `#insto-programada` y `#insto-deshecho`; un test verifica que todos sus ejemplos son válidos.

## Verificación

- `python3 scripts/test-phone-core.py`: suite JVM sin Android, incluidas las pruebas puras nuevas (`InstinctRepliesTest`, `InstinctRoutinesTest`, `InstinctScheduleTest`).
- `./gradlew testDebugUnitTest assembleDebug`: suite completa con Robolectric, incluidas `InstinctReplyFlowTest`, `InstinctRoutineFlowTest` e `InstinctDeferredFlowTest`.
- No se probó en un teléfono físico ni con WhatsApp real: la acción «Responder», el disparo de alarmas en Doze y el SMN en vivo quedan para la prueba manual.
