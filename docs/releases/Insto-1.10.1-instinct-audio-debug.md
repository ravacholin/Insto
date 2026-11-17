# Insto 1.10.1 — consultas a Instinct y confirmación de audio

Variante debug, versionCode 18. Base: main `f1d21dc895fa96ace89552b2d9cf9d998bbfccaa` (PR #17).

## Correcciones

- «¿Cuándo es la final de Boca por la Copa Sudamericana?» conserva la ruta a Instinct. Las preguntas no se rechazan por contener «cuándo» o «si». Se conserva la selección de intents para consultas nativas, como el estado de batería, y el rechazo de órdenes condicionales.
- Una consulta sin intent reconocible que pasa por el modelo de respaldo no agrega una confirmación antes de enviarse a Instinct. Los borradores de acciones del teléfono mantienen su revisión.
- La ventana de audio se identifica desde la preparación anterior a `startActivity`, conservando los eventos creados o recibidos durante la apertura. La automatización sigue esperando a que el lanzamiento termine correctamente.
- Una captura de ventanas válida y vacía se distingue de una captura fallida. Se mantienen los controles de destinatario, ventanas anteriores y envío único.
- El servicio reconoce botones positivos por su etiqueta accesible exacta, además de los identificadores conocidos de WhatsApp. Así puede continuar de OK a Enviar cuando cambian los IDs.

## Validación

- `python3 scripts/test-phone-core.py`: 203 casos sin fallos; 202 aprobados y una evaluación real de Gemini opt-in omitida. Incluye las políticas de confirmación y envío de audio.
- `assembleDebug compileDebugUnitTestKotlin`: BUILD SUCCESSFUL; compilan la aplicación y las regresiones Android del ViewModel y del servicio de Accesibilidad.
- La suite completa `testDebugUnitTest` está bloqueada localmente antes de ejecutar casos por HTTP 403 al descargar `org.robolectric:nativeruntime-dist-compat:1.0.18`. El intento sin ese renderizador opcional también quedó bloqueado al descargar la imagen de Android de Robolectric. No se cambiaron dependencias ni se deshabilitaron pruebas en el proyecto.
- No hay un teléfono conectado. La pulsación y recepción reales en WhatsApp quedan pendientes de validación física.
- APK: integridad ZIP, firma v2 y alineación ZIP de 16 KiB verificadas.

## APK

- [Descargar en Google Drive](https://drive.google.com/file/d/108eFwCX_98ys62NU5u9BPgH4T8H5WY7Q/view?usp=drivesdk).
- Archivo: `Insto-1.10.1-instinct-audio-debug.apk`; tamaño confirmado por metadatos de Drive: 18.571.268 bytes.
- SHA-256: `8cfd4b4731811892b2585575ab249cd79574991bf611db34f7a10c5781f81c69`.
- Application ID: `com.aistudio.pingassist.qvrtkm`; minSdk 24, targetSdk 36.
- Certificado SHA-256: `c98b95a94a8fec72aeab7d23d03419e90966fa38ca5fdc7e7b6cd152b758ce1f`.
- La clave de la entrega anterior no está disponible en este entorno. Este APK usa una clave debug local nueva: Android puede impedir instalarlo como actualización sobre la versión anterior. La clave privada no se publica ni se incluye en el repositorio.
