# Instinct floating assistant

## Teléfono e Instinct: una sola entrada

El floating distingue automáticamente pedidos del teléfono y consultas generales, por texto o por una sola grabación. Se mantienen AudioRecord, WebRTC VAD, 300 ms de preroll y AAC-LC mono de 16 kHz en M4A. Tras tres segundos de silencio, o al tocar LISTO, finaliza esa grabación y envía una copia a Groq (`whisper-large-v3-turbo`, `language=es`). La transcripción se usa internamente para interpretar el pedido. Las acciones locales se ejecutan en Android; las consultas generales se envían a Instinct usando **el M4A original**, no texto. No hay selector de destino ni otro modo de micrófono. Los adjuntos siguen dirigidos a Instinct.

### Configuración personal de Groq

Abrir **Groq** en la cabecera del floating y cargar una clave propia. Si el primer pedido de voz no tiene clave, se conserva la grabación y aparece el acceso a configuración. Al guardar una clave, ese pedido vuelve a intentarse sin tener que grabarlo de nuevo. Guardar un reemplazo, volver sin cambios o borrar la clave son operaciones diferentes; solo guardar tras un error de clave dispara ese reintento.

La clave se cifra con AES/GCM y Android Keystore en `noBackupFilesDir`: no va en BuildConfig, APK, Git, logs ni backups. El campo está enmascarado y la pantalla bloquea capturas. No hace falta un backend para este uso personal. La configuración explica que cada grabación se transcribe en Groq antes de decidir destino. Requiere internet y está sujeto a las cuotas/precios de la cuenta; no se promete uso gratuito ilimitado.

El upload usa HTTPS sin seguir redirecciones, un máximo conservador de 24 MiB por grabación, respuesta limitada a 256 KiB, timeout total de 60 s y ningún reintento automático de red. Claves rechazadas, cuotas, respuestas vacías y errores de conexión conservan el audio para reintentar. Cerrar el panel cancela la petición y descarta datos no enviados; pasar a segundo plano durante la interpretación la cancela y conserva el audio. Las respuestas tardías no ejecutan órdenes.

### Pedidos disponibles

- `Poné un timer de diez minutos para fideos` o `temporizador de una hora y treinta minutos`.
- `Despertame a las siete y media de la mañana`, `alarma a las 07:00`, `alarma a las 07:00 todos los lunes y jueves`. Las horas ambiguas requieren aclaración y las que ya pasaron requieren confirmar la próxima ocurrencia. Las fechas o condiciones que no pueden expresarse fielmente con los intents públicos del Reloj se rechazan con explicación; no se ignoran detalles.
- `Abrí las alarmas` / `mostrá los temporizadores`. Crear se delega al Reloj; se confirma solo la entrega de la solicitud, no la creación verificada. Pausar o cancelar un timer específico requiere usar el Reloj.
- `Iniciá el cronómetro`, `pausá el cronómetro`, `continuá el cronómetro`, `reiniciá el cronómetro`, `mostrá el cronómetro`. Es un cronómetro propio persistente; reiniciar pide confirmación y lo deja detenido en cero. Usa tiempo monotónico e identidad de arranque, sin servicio de fondo. Tras reiniciar el dispositivo conserva el último tiempo guardado y queda pausado.
- `Abrí Spotify` (o el nombre de otra app lanzable y visible). Si hay varias coincidencias, ofrece opciones.
- `Subí el volumen`, `bajá el volumen`, `poné el volumen al cincuenta por ciento`: volumen multimedia.
- `Prendé la linterna` / `apagá la linterna`: pide permiso de cámara cuando corresponda.
- `Activá el wifi`, `abrí Bluetooth`, `abrí conectividad`: abre los ajustes que Android permite gestionar al usuario; no afirma haber cambiado un interruptor que no controla.

La interpretación inicial usa una gramática explícita en español, sin un segundo LLM. No es control arbitrario de cualquier aplicación. Consultas como `explicame cómo funciona un cronómetro` van a Instinct. Los pedidos locales incompletos piden aclaración dentro del panel; se responden por el mismo micrófono o por texto. Los rechazos de Android conservan el pedido para un reintento explícito, sin repetir un despacho incierto. Las acciones locales funcionan sin contacto, WhatsApp ni Accesibilidad. Un teléfono bloqueado requiere desbloqueo.

The normal assistant entrypoint always uses this panel, independent of legacy
activation-mode preferences. Existing explicit Open Chat shortcuts and optional
volume-key/legacy home-screen recording controls remain available.

## WhatsApp integration limits

There is no background WhatsApp sending API in this project. Dispatch targets
the configured phone number and installed WhatsApp flavor. Accessibility may
complete sending and return to the original app. Text automation starts explicitly
from our successful phone-targeted deep link, rather than relying on WhatsApp
Activity names or UI text-change events. It verifies the configured phone/name
in the chat header and the complete visible draft, then injects exactly one real
touch at the Send control's bounds with AccessibilityService.dispatchGesture.
The control does not need to advertise an accessibility ACTION_CLICK action.
A configured saved name is matched exactly; this is not independent proof of the
phone number when WhatsApp hides it. Chats with duplicate names and identical
drafts cannot be distinguished from that evidence alone. Device validation must
use the configured Instinct conversation, not claim arbitrary chat identity proof.
The keyboard's window is handled by also looking for WhatsApp's focused app window.

After the touch it checks that the composer cleared before confirming submission
and returning to the previous app. A touch with uncertain outcome is not repeated.
If enabled Accessibility is not actually connected, the app preserves the text
and reports that problem instead of silently opening a manual draft. Timeout
feedback says which verification failed. No notification is required. No ADB,
Shizuku, WhatsApp Business account, or notification-listener permission is added.

Audio still requires a matching phone header and unique attachment filename to
automatically click Send; previews that hide those fields remain manual. A
timeout hint asks the user to check WhatsApp; uncertain sends are never repeated.
If Accessibility is disabled, the panel provides an explicit setup button.
The handset test is required before claiming fully automatic delivery for its
installed WhatsApp version. Files handed to WhatsApp remain in private cache
for URI access; new recordings clean up matching files older than 24 hours.
Audio is an attachment, not guaranteed to display as a native WhatsApp PTT note.

## Verificación anterior (2026-11-15)

Build: `gradle assembleDebug` with JDK 21, Android SDK 36.1, Gradle 9.3.1 and the
project's existing signing configuration. This checkout does not include a
Gradle launcher; the verification environment downloaded Gradle externally.
A local debug keystore is generated for the APK and must not be committed.

Run `gradle testDebugUnitTest`. Pure tests cover 4 s initial silence, 3 s trailing
silence, manual Send, cancellation, recipient ambiguity, stale audio drafts and
Unicode message matching. Robolectric tests cover text deep links, panel focus,
button states and ViewModel capture cancellation. In the current environment,
Robolectric's Android runtime/native artifact downloads redirect to a blocked
Maven mirror; those tests can compile but cannot execute here.

On the Android 16 Motorola: verify assistant role/hotkey, background transparency,
keyboard insets, 4 s idle behavior, first syllables, pauses below/above 3 s,
manual Send, noise, privacy toggle, permission denial, close/Back, interruption,
rotation, repeated hotkey, absent WhatsApp, Accessibility disabled, destination,
attachment appearance and return to the original application. Check the actual
WhatsApp version's accessibility tree before asserting automatic confirmation.

### Results in this workspace

- `assembleDebug`: successful (JDK 21, Gradle 9.3.1, SDK 36.1).
- 14 pure JVM tests: passed (4 session policy, 4 dispatch guard, 3 text-touch attempt, 3 existing unit tests).
  An environment-only Gradle init script excluded the unavailable Robolectric native
  artifact for this filtered run; no project dependency was removed.
- All test sources, including Robolectric UI/session tests: compiled.
- `assembleDebugAndroidTest`: compiled an on-device AAC encode/extract/decode test
  (1-second tone, MPEG-4 signature, AAC track metadata, duration and decoded PCM).
  No device is attached, so this codec test has not executed here.
- Codec finalization and joins run on the IO dispatcher. Cancellation stops the
  mic immediately and discards off Main; session ownership prevents older workers
  from writing into a new session. A failed finalization returns the panel to idle.
- Full unfiltered suite: blocked resolving Robolectric native runtime from Maven's
  inaccessible mirror, before test execution.
- APK signature verification and ZIP alignment including 16 KiB page alignment: passed.
- Physical device/WhatsApp validation: not performed in this environment.

## Verificación de acciones del teléfono (2026-11-16)

- `clean assembleDebug compileDebugUnitTestKotlin`: correcto con Gradle 9.3.1, JDK 21 y SDK 36.1. El APK de prueba usa un keystore de debug generado en el entorno, no una firma de distribución.
- Firma v2 y alineación ZIP/16 KiB del APK: comprobadas.
- Suite completa `testDebugUnitTest`: bloqueada antes de ejecutar por descarga de `org.robolectric:nativeruntime-dist-compat:1.0.18`, que redirige a `gcp.mirrors.maven.org` y recibe 403.
- Pruebas de Robolectric de las acciones nuevas y enrutamiento: fuentes compiladas; ejecución bloqueada también al descargar `android-all-instrumented:9-robolectric-4913185-2-i7`. No se presentan como aprobadas.
- La ejecución de pruebas JVM puras usa un init script temporal que excluye solo el runtime nativo inaccesible; no se elimina ninguna dependencia del proyecto. Resultado: 30 pruebas aprobadas (6 intérprete, 5 Groq, 4 cronómetro y 15 regresiones existentes).
- Falta validación real con clave Groq y dispositivo Android: grabación → transcripción → acción local o WhatsApp original, permisos de linterna, Reloj instalado, bloqueo, interrupciones y retorno de configuración. No se usaron credenciales del usuario ni se hicieron llamadas reales a Groq.
