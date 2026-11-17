# Voz adaptativa y contexto explícito

Base: Insto 1.9.0, merge PR #15 (`9fcc034`). Implementación autorizada por el pedido del usuario.

## Objetivo
Reducir la espera al terminar órdenes cortas y permitir pedir acciones sobre texto seleccionado o compartido desde otra app.

## Diseño y alternativas
- Mantener WebRTC VAD y Groq Whisper: silencio final de 900 ms para intervenciones de menos de 4 s, 1600 ms entre 4 y 12 s, 2400 ms desde 12 s. La duración se mide entre el primer y último cuadro de voz; retomar voz reinicia el silencio. Son valores iniciales a validar en teléfono, no una medición de rendimiento. Se mantiene escucha inicial de 4 s, envío manual y cancelación.
- Streaming reduciría también el tiempo de transcripción, pero el endpoint actual de Groq recibe un archivo M4A finalizado. No abrir un segundo micrófono con SpeechRecognizer ni enviar archivos parciales inválidos. Documentar evaluación y medir primero.
- Recibir ACTION_PROCESS_TEXT y ACTION_SEND de texto. No leer portapapeles, accesibilidad ni pantalla implícitamente. Una URL compartida es texto, no el cuerpo de una página; no descargarla ni prometer acceso a documentos binarios.
- Contexto acotado a 10000 caracteres, visible y removible; rechazar exceso sin truncar fechas. No persistirlo. Mantenerlo al reintentar y rotar, reemplazarlo ante nuevo contenido y limpiarlo al cerrar.
- Enviar pedido y contexto como campos separados a Gemini. Texto externo es material de referencia y jamás una orden. Una consulta contextual se envía por texto a Instinct, incluso si el pedido llegó por voz, para no perder el referente. Un calendario contextual requiere extracción estructurada y revisión antes de ejecutar; las continuaciones conservan contexto y borrador.
- Mostrar métricas locales de silencio, finalización, transcripción e interpretación. Sin texto, audio ni claves en logs; no inferir tiempo hasta respuesta de WhatsApp.

## Verificación
Pruebas de umbrales, pausas, reinicio y cancelación; recepción de intents, límites, rotación y reemplazo; modelo con contexto, calendario revisable, despacho de voz contextual con texto, cancelación de respuestas obsoletas y regresión sin contexto. Build y suite Android completa. Validación física pendiente si no hay teléfono conectado.
