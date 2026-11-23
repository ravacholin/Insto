# Insto 1.21.0 · Instinct pregunta, vos tocás un botón

Protocolo Insto v7, código de versión 30.

- Instinct puede mandar `#insto preguntar ¿Preferís café o té? | Café | Té`. Insto muestra hasta tres opciones en el canal «Insto · preguntas». El botón registra la elección y cierra la notificación.
- La respuesta, el descarte y el vencimiento quedan en el diario de ejecución y llegan a la bitácora de Google Sheets. La bitácora tiene que estar configurada para aceptar la pregunta. Los resultados de las preguntas usan ese mismo canal aunque el comando haya llegado por WhatsApp o estén apagados los acuses.
- En Ajustes → Propuestas de Instinct → Autonomía, «Preguntas» empieza en Solo. Con Confirmar podés revisar la pregunta en Insto antes de mostrarla. Nunca rechaza nuevos pedidos y descarta las preguntas que sigan pendientes. Las preguntas que vencen o descartás cuentan para el ajuste automático.
- Insto admite hasta tres preguntas pendientes y cinco por hora. Vencen en dos horas por defecto y el plazo se puede cambiar hasta un máximo de 24 horas. Si llegan durante las horas de silencio, quedan pendientes hasta que terminen o hasta que venzan.
- El catálogo vivo y las instrucciones para copiar ya incluyen `preguntar` y `probar preguntar`. El script de la bitácora anterior ya admite el nuevo tipo de evento.

## Qué quedó sin probar en un teléfono real

- Ver y tocar los botones con el teléfono bloqueado, incluido el estreno del canal y los ajustes de privacidad de la pantalla de bloqueo.
- Llegada durante las horas de silencio, toque tardío y reaparición después de que Android cierre Insto o se reinicie el teléfono.
- Entrega por los cuatro caminos y sincronización con una bitácora de Google real después de perder y recuperar la conexión.
- Instalación sobre 1.20.0 con un diario existente.
