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
