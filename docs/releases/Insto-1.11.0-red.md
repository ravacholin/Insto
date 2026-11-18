# Insto 1.11.0 · red y latencia

Código de versión 19. Esta rama está prevista para fusionarse primero.

El panel abre en segundo plano una conexión sin credenciales a Groq y, si está habilitado, a Gemini. Las solicitudes comparten el pool de OkHttp y negocian HTTP/2 cuando el proveedor lo admite. Se mantienen los límites de respuesta, de audio y de tiempo, y se cancela la llamada si se invalida la sesión.

Cuando el circuito habilita Decisions, la extracción de respaldo puede avanzar en paralelo. Se usa solo en las rutas que ya admitían el respaldo. La propuesta se valida y, cuando corresponde, requiere revisión. Un resultado de Groq que ya llegó no reemplaza una clasificación válida ni una verificación rechazada. El respaldo especulativo puede consumir cuota de Groq aunque su resultado se descarte.

## Mediciones

El script de ADB registra tiempos por etapa y el total. Las columnas de Groq y Gemini guardan las muestras de DNS y TLS que aparecen durante la captura de cada medición, incluido el precalentamiento. Un cero indica que no se observó un costo de conexión medible, como al reutilizar una conexión. GeminiDiagnostics conserva el último tiempo por proveedor en memoria.

En el caso con tiempos virtuales de 3 s para Decisions y 5 s para Groq, la propuesta de respaldo queda lista a los 5 s. La suma secuencial de esas demoras sería 8 s. Estos valores corresponden a los fakes.

Queda pendiente medir en el teléfono antes y después con `python3 scripts/measure-voice-latency.py --samples 10 --output antes.csv` y luego con `--output despues.csv`. Usá el mismo teléfono, red, claves y frases en compilaciones debug. Compará p50 y p95 del total y de la interpretación. El script se probó con un registro sintético. ADB no encontró dispositivos en este entorno.

## Verificación

`testDebugUnitTest` completo quedó bloqueado al descargar `org.robolectric:nativeruntime-dist-compat:1.0.18`. El espejo de Maven respondió HTTP 403.

En el teléfono queda probar apertura y cancelación del panel, grabación con VAD y permisos, respuestas lentas de ambos proveedores, reinicio del cronómetro y arranque de los servicios y el widget desde el APK minificado.
