# Extracción de comandos con Gemini Flash-Lite

- La extracción estructurada pasa de `gemini-2.5-flash` a `gemini-3.5-flash-lite`. Usa `thinkingLevel: minimal` explícito, conserva el JSON Schema y mantiene el límite de 2048 tokens de salida, incluido el razonamiento.
- Ante HTTP 404 o un HTTP 400 que indica que el modelo no existe o no está soportado, se hace un solo intento con `gemini-3.1-flash-lite` dentro del mismo plazo total de ocho segundos. Los errores de acceso, cuota y tiempo de espera se devuelven sin reintento de modelo.
- El diagnóstico en memoria muestra el último modelo que devolvió una extracción válida.

La latencia real queda pendiente. Medir p50 y p95 con `scripts/measure-voice-latency.py` antes y después del cambio, usando el mismo teléfono y la misma red.
