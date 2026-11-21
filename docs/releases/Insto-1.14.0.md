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
