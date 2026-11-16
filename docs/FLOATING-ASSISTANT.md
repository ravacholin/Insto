# Instinct floating assistant

## Teléfono e Instinct: una sola entrada

El floating distingue automáticamente pedidos del teléfono y consultas generales, por texto o por una sola grabación. Se mantienen AudioRecord, WebRTC VAD, 300 ms de preroll y AAC-LC mono de 16 kHz en M4A. Tras tres segundos de silencio, o al tocar LISTO, finaliza esa grabación y envía una copia a Groq (`whisper-large-v3-turbo`, `language=es`). La transcripción se usa internamente para interpretar el pedido. Las acciones locales se ejecutan en Android; las consultas generales se envían a Instinct usando **el M4A original**, no texto. No hay selector de destino ni otro modo de micrófono. Los adjuntos siguen dirigidos a Instinct.

### Configuración personal de Groq

Abrir **IA** en la cabecera del floating y cargar una clave propia de Groq. Si el primer pedido de voz no tiene clave, se conserva la grabación y aparece el acceso a configuración. Al guardar una clave, ese pedido vuelve a intentarse sin tener que grabarlo de nuevo. Guardar un reemplazo, volver sin cambios o borrar la clave son operaciones diferentes; solo guardar tras un error de clave dispara ese reintento.

La clave se cifra con AES/GCM y Android Keystore en `noBackupFilesDir`: no va en BuildConfig, APK, Git, logs ni backups. El campo está enmascarado y la pantalla bloquea capturas. No hace falta un backend para este uso personal. La configuración explica que cada grabación se transcribe en Groq antes de decidir destino. Requiere internet y está sujeto a las cuotas/precios de la cuenta; no se promete uso gratuito ilimitado.

El upload usa HTTPS sin seguir redirecciones, un máximo conservador de 24 MiB por grabación, respuesta limitada a 256 KiB, timeout total de 60 s y ningún reintento automático de red. Claves rechazadas, cuotas, respuestas vacías y errores de conexión conservan el audio para reintentar. Cerrar el panel cancela la petición y descarta datos no enviados; pasar a segundo plano durante la interpretación la cancela y conserva el audio. Las respuestas tardías no ejecutan órdenes.

### Gemini Decisions opcional (1.6.0)

En **IA**, la sección **Gemini Decisions · híbrido** permite guardar una clave
personal de Google AI Studio. Guardarla activa el híbrido; el interruptor permite
desactivarlo y también se puede borrar la clave. La clave se cifra por separado
con Android Keystore en `noBackupFilesDir`; no se incluye en APK, Git o backups.
Sin clave o con el híbrido desactivado se conserva el comportamiento anterior.

Los comandos locales completos, las aclaraciones, las confirmaciones y las
opciones de contactos/apps siguen resolviéndose localmente. Las consultas
explícitas reconocidas para Instinct tampoco necesitan Gemini. El resultado
desconocido del parser y ciertos rechazos por redacción no comprendida consultan
`gemini-decision-1`, usando el texto escrito o la transcripción original. Nunca
se envían a Gemini el audio, los adjuntos ni la libreta de contactos; el texto
puede contener el nombre o número que el usuario haya dicho.

Decisions elige una capacidad y evalúa si el pedido es único, incondicional y
sin negaciones. Se exige probabilidad y confianza de al menos 0,95 en ambas
evaluaciones. Es un umbral inicial conservador, sin calibración con uso real.
Solo se ejecutan candidatos tipados completos: linterna, pasos de volumen,
apertura de cámara y operaciones/listas del Reloj. Se conservan los permisos,
comprobaciones de handlers y confirmaciones de los ejecutores existentes.
Las horas, porcentajes, títulos y destinos no reconocidos requieren aclaración;
Decisions no genera parámetros, URIs ni intents. Los rechazos explícitos del
parser por detalles no admitidos no se reinterpretan para eludir restricciones.

Una consulta identificada va a Instinct por el envío existente, con el M4A
original si se habló. Baja confianza o un pedido no admitido conservan la
solicitud y muestran el problema. HTTP inválido, JSON/esquema inválido, clave
rechazada, cuota o timeout conservan texto/audio para reintentar. Reintentar
audio reutiliza la transcripción y el archivo original. Pausar cancela la
clasificación y conserva el pedido; cerrar descarta los datos no enviados.
Editar durante una interpretación cancela ese trabajo y comienza el nuevo
borrador. Ninguna respuesta de una sesión obsoleta ejecuta ni envía.

La petición REST a `https://generativelanguage.googleapis.com/v1beta/decisions`
tiene timeout total de 10 s, respuesta máxima de 64 KiB, no sigue redirecciones
y no reintenta automáticamente. El nivel gratuito está sujeto a cuotas del
proyecto y a términos sobre uso de contenido para mejorar productos de Google:
[precios y condiciones](https://aistudio.google.com/docs/pricing#gemini-decision).
La rapidez de las rutas locales se conserva; el fallback agrega una llamada de
red. No se ha medido precisión ni latencia con el servicio real sin una clave.

### Pedidos disponibles

- `Poné un timer de diez minutos para fideos` o `temporizador de una hora y treinta minutos`.
- `Despertame a las siete y media de la mañana`, `alarma a las 07:00`, `alarma a las 07:00 todos los lunes y jueves`. Las horas ambiguas requieren aclaración y las que ya pasaron requieren confirmar la próxima ocurrencia. Las fechas o condiciones que no pueden expresarse fielmente con los intents públicos del Reloj se rechazan con explicación; no se ignoran detalles.
- `Abrí las alarmas` / `mostrá los temporizadores`. Crear se delega al Reloj; se confirma solo la entrega de la solicitud, no la creación verificada. Pausar o cancelar un timer específico requiere usar el Reloj.
- `Iniciá el cronómetro`, `pausá el cronómetro`, `continuá el cronómetro`, `reiniciá el cronómetro`, `mostrá el cronómetro`, `abrí el cronómetro`. Se delegan al Reloj de Google instalado mediante sus intents específicos de DeskClock. Cada operación se comprueba por separado: el handler debe estar exportado, habilitado y accesible con los permisos de Insto. Reiniciar requiere confirmación porque Insto no puede leer el tiempo nativo. El floating ya no mantiene un contador propio ni controles paralelos. Los datos del cronómetro anterior se conservan sin usarse; no se migran al Reloj.
- `Abrí Spotify` (o el nombre de otra app lanzable y visible). Si hay varias coincidencias, ofrece opciones.
- `Subí el volumen`, `bajá el volumen`, `poné el volumen al cincuenta por ciento`: volumen multimedia.
- `Prendé la linterna` / `apagá la linterna`: pide permiso de cámara cuando corresponda.
- `Activá el wifi`, `abrí Bluetooth`, `abrí conectividad`: abre los ajustes que Android permite gestionar al usuario; no afirma haber cambiado un interruptor que no controla.

La interpretación inicial usa una gramática explícita en español; el híbrido opcional consulta Gemini Decisions en los casos no resueltos. No es control arbitrario de cualquier aplicación. Consultas como `explicame cómo funciona un cronómetro` van a Instinct. Los pedidos locales incompletos piden aclaración dentro del panel; se responden por el mismo micrófono o por texto. Los rechazos de Android conservan el pedido para un reintento explícito, sin repetir un despacho incierto. Las acciones locales funcionan sin contacto, WhatsApp ni Accesibilidad. Un teléfono bloqueado requiere desbloqueo.

The normal assistant entrypoint always uses this panel, independent of legacy
activation-mode preferences. Existing explicit Open Chat shortcuts and optional
volume-key and floating-pill activations now open the same interpreting panel. Legacy home-screen recording controls remain available.

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

Audio accepts the configured saved contact name only after this request’s successful targeted share launch. It verifies the unique recording filename when exposed. When WhatsApp hides it, only a fresh `.AudioPreviewActivity` window whose ID was absent before launch and whose event timestamp is strictly after successful launch completion can authorize the generated `instinct_*.m4a`, with a matching recipient and no caption/draft; old or other windows cannot authorize it. Send uses the same one-touch gesture as text and checks return to the empty chat composer. An unavailable/disconnected Accessibility service retains the floating recording instead of opening a manual share. A
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

## Corrección 1.4.1: burbuja y envío de audio

El usuario confirmó que grababa desde la burbuja. Se encontró que ese disparador conservaba una captura/envío directo a WhatsApp, fuera del flujo de interpretación. Ahora tocar o mantener la burbuja, y mantener la tecla de volumen configurada, abren `AssistActivity`; ese servicio ya no captura ni despacha archivos por su cuenta. La burbuja también puede aparecer sin contacto configurado para acciones locales.

El audio tenía otra diferencia con el texto: no registraba un lanzamiento dirigido exitoso, rechazaba el nombre guardado del destinatario y dependía de ACTION_CLICK. Ahora registra el lanzamiento de la solicitud actual, verifica destinatario/archivo o nueva ventana AudioPreview de esa misma solicitud, sondea la ventana correcta y utiliza un único gesto real. No reintenta un toque incierto. Se agregaron variantes naturales de linterna (`enciende`, `encendeme`, `podés encender`) y se evita mandar a Instinct pedidos locales incompletamente entendidos.

Versión visible 1.4.1, versionCode 5. APK y fuentes de pruebas Android compiladas; 34 pruebas JVM aprobadas. Las pruebas Android de entrada por burbuja, servicio desconectado y lanzamiento de audio están escritas pero su ejecución sigue bloqueada por dependencias de Robolectric. El comportamiento en WhatsApp real no se afirma como validado sin el dispositivo.

La revisión de 1.4.1 exigió guardar las ventanas anteriores al lanzamiento y su hora de finalización: eventos encolados anteriores o ventanas preexistentes no autorizan la alternativa sin nombre de archivo. Si no hay evidencia suficiente se conserva la verificación exacta del archivo.

## Corrección 1.4.2: confirmación de WhatsApp dentro del mismo envío

El paso positivo de audio admite OK/Aceptar/Listo además de Enviar, con evidencia de la solicitud propia, destinatario y propósito de envío. El destinatario se reconoce también en el texto de confirmación o reproductor, sin depender únicamente de los IDs de la cabecera o del nombre AudioPreviewActivity. No autoriza diálogos ajenos, errores, listas de contactos ni otro destinatario.

La operación conserva su estado después del primer toque: contempla OK → Enviar, Enviar → OK y envío directo. Cada paso se puede tocar como máximo una vez; se declara terminado solo al observar el chat del destinatario con compositor vacío, el micrófono de notas de voz del chat visible y sin botón de envío habilitado, estable durante 500 ms. Una vista previa cuyo botón se deshabilita no cuenta como chat final. Una transición puede reutilizar la misma ventana si cambia el control; el control anterior no se vuelve a tocar. Los flujos de texto no se modifican.

Pruebas de la lógica de confirmación cubren esos órdenes, ventanas antiguas, borradores, destinatarios distintos, diálogos de permisos/error y selección de contactos. La ejecución con WhatsApp real sigue pendiente porque no hay dispositivo conectado. La suite Android sigue bloqueada por las dependencias externas documentadas. Esta corrección no se presenta como una prueba de ejecución real en el teléfono.

Validación de destinatario de 1.4.2: una lista «Enviar a» no queda autorizada por un paso anterior; el nombre del archivo de audio tampoco puede servir como nombre del contacto. Los controles de confirmación se buscan por sus IDs positivos de Android/WhatsApp, sin pulsar botones genéricos dentro de mensajes del chat.

Resultado 1.4.2: assembleDebug y fuentes de pruebas compilados; 48 pruebas JVM aprobadas, incluidas 14 de la secuencia de confirmación. Firma APK y alineación de 16 KiB verificadas. Suite completa bloqueada antes de ejecución por nativeruntime-dist-compat (redirección HTTP 403). Sigue pendiente la verificación real en WhatsApp.

## Corrección 1.4.3: interpretar el pedido completo antes de ejecutarlo

Se reprodujo la frase real «quiero que enciendas la linterna»: el reconocedor la transcribía correctamente, pero el intérprete anterior la clasificaba Unsupported. Se normalizan ahora únicamente el encabezado del pedido («quiero que», «necesito que», «me gustaría que», «podrías», «me puedes») y su primer verbo (subjuntivo/infinitivo), conservando parámetros y condiciones. Esto aplica a las capacidades existentes, sin un segundo LLM y sin cambios de WhatsApp o Groq. Negaciones y explicaciones continúan sin ejecutar acciones.

Los mensajes de resultado, permiso pendiente o error aparecen arriba del campo de entrada y la transcripción. En la linterna, el pedido reconocido llega a CameraManager tras el permiso de cámara de Android; rechazar el permiso deja el audio conservado. La prueba de integración de esa frase hasta el permiso está escrita y compila, pero la ejecución Android continúa bloqueada por Robolectric.

Resultado 1.4.3: APK y fuentes de pruebas compilados; 52 pruebas JVM aprobadas. La revisión detectó y corrigió la preservación de «encendé el wifi» al normalizarlo. Firma y alineación verificadas. Suite completa continúa bloqueada antes de ejecutar por Robolectric (403); falta prueba física de la linterna.

Compatibilidad revisada al preparar el PR: el PR #5 seguía abierto fuera de main. Se conserva también su selector `com.whatsapp:id/button1` y su comparación exacta del destinatario en «Share with Instinct?» / «¿Compartir con Instinct?». Los PR #5 y #6 se documentan como solapamientos de integración; no se fusionan ni cierran automáticamente.

## Acciones nativas (1.4.4)

El Motorola del usuario usa Reloj de Google (`com.google.android.deskclock`). Los intents de cronómetro específicos de DeskClock no son parte del contrato público universal de Android. START y RESUME solicitan `com.android.deskclock.action.START_STOPWATCH`; PAUSE solicita `PAUSE_STOPWATCH`; RESET solicita `RESET_STOPWATCH`; STATUS solicita `SHOW_STOPWATCH`. Solo se despachan si el paquete instalado expone un handler accesible. El lanzamiento confirma entrega de la solicitud, no el estado final del cronómetro.

Si la versión instalada no admite una operación, Insto conserva el texto o el audio y ofrece abrir Reloj de Google para completarla allí. No crea un cronómetro alternativo ni envía ese pedido a WhatsApp. Esta alternativa abre la app; no se presenta como una pausa, inicio o reinicio realizado. Tampoco se adivinan componentes internos ni se inyectan toques.

Alarmas y temporizadores siguen usando los intents públicos de Android con `EXTRA_SKIP_UI`; linterna y volumen usan las APIs nativas. Se declara `MODIFY_AUDIO_SETTINGS` para ajustar volumen. Las acciones completadas y las solicitudes entregadas cierran el floating con un aviso breve del sistema. Aclaraciones, permisos, confirmaciones y errores lo mantienen abierto.

### Comprobación en el Motorola

1. Con depuración USB y el teléfono conectado, ejecutar `scripts/check-google-clock.sh` (o pasar el serial como argumento). Solo consulta versión y handlers; no modifica ningún cronómetro. La identidad ADB no equivale a la de Insto: el ejecutor también verifica permisos en tiempo de ejecución.
2. Probar inicio, pausa, continuación, consulta y reset confirmado desde voz y texto; comprobar el resultado en el Reloj de Google. Cancelar un reset no debe afectar al contador.
3. Si faltan handlers, conservar la salida del diagnóstico para identificar la integración posible con esa versión. El script no instala servicios ni requiere Shizuku.
4. Comprobar cierre del floating tras ajustar volumen/linterna y tras entregar solicitudes al Reloj. Denegar cámara debe conservar el pedido.
5. Comprobar que las consultas generales siguen enviando el audio M4A original a Instinct y que una transcripción tardía tras cerrar el panel no ejecuta acciones.

No hay dispositivo conectado en el entorno de implementación; la compatibilidad y ejecución reales en la versión del Reloj instalada quedan pendientes de esa comprobación.

### Resultado de verificación 1.4.4

- `assembleDebug`: correcto, repetido tras la corrección de ciclo de vida; firma v2 y alineación de 16 KiB verificadas. Se generó una clave de depuración local excluida de Git: este APK de prueba no garantiza actualización sobre la firma de una instalación anterior.
- 50 pruebas JVM aprobadas: interpretación en español, rutas de comandos, política de voz, guardas de envío, confirmaciones de WhatsApp y protocolo Groq. Se usó un init script externo que excluye únicamente `nativeruntime-dist-compat`; las dependencias del proyecto no cambiaron.
- Todos los fuentes de pruebas Android compilan, incluidos handlers exportados/protegidos, reset confirmado, retención, cancelación de transcripción y cierre de la actividad detenida. La ejecución de estas pruebas no está validada: el runner no puede descargar `android-all-instrumented:9-robolectric-4913185-2-i7` (HTTP 403).
- Se ejecutó también `testDebugUnitTest` sin exclusiones después de los cambios: bloqueado por `nativeruntime-dist-compat:1.0.18` (HTTP 403 del mirror de Maven), antes de ejecutar las pruebas.
- Revisión independiente: detectó la posibilidad de dejar el panel detrás del Reloj al detenerse Compose. La finalización ahora se observa desde `Activity.lifecycleScope` hasta destrucción, se consume una sola vez y no depende de un frame. Se agregó una prueba de regresión de ciclo de vida; su ejecución Android sigue bloqueada como se indica arriba.
- Diagnóstico ADB: sintaxis de script verificada. No hay dispositivos conectados; no se afirma validación física ni compatibilidad comprobada de una versión específica del Reloj de Google.

## Pedidos cotidianos nativos (1.5.0)

La ampliación usa apps instaladas y conserva el flujo de audio original hacia Instinct para consultas generales. Se puede consultar «Ejemplos de pedidos» desde el floating. No requiere ADB ni Shizuku.

| Ejemplo | Qué entrega Insto |
|---|---|
| `Llevame a Plaza de Mayo` | Ruta en Maps, por defecto en auto. |
| `Llevame a pie a Retiro` / `Llevame a Retiro en bicicleta` | Ruta con el modo indicado. También admite `en transporte público` / `en colectivo` como modo general de transporte público. |
| `Buscá farmacias cerca` / `Buscá en Maps café de especialidad` | Búsqueda en Maps. Puede abrir el navegador si Maps no está instalado. |
| `Guardá mi casa en Av. Córdoba 1234, Buenos Aires` | Guarda esa dirección en preferencias privadas de Insto. `Guardá mi trabajo en …` hace lo mismo para trabajo; repetirlo reemplaza la dirección guardada. |
| `Llevame a casa` / `Llevame al trabajo` | Usa la dirección guardada explícitamente. No consulta ni supone las direcciones de la cuenta Google. |
| `Llamá a Ana María` / `Llamá al +54 11 5555 0100` | Abre el marcador con el número; hay que tocar Llamar allí. |
| `Agendá Reunión con María mañana a las 17:30 durante cuarenta y cinco minutos` | Borrador en Calendario con título, inicio y fin; hay que revisarlo y guardarlo allí. |
| `Agendá clase el 20/11/2026 a las siete de la tarde` | Fecha explícita. También acepta hoy, mañana, pasado mañana y dd/MM del año actual. |
| `Abrí la cámara` / `Abrí la cámara para grabar` | Abre modo foto/video. No toma la foto ni empieza a grabar automáticamente. |
| `Buscá jazz en YouTube` | Abre una búsqueda, no afirma reproducción. |
| `Poné música de Soda Stereo` / `Reproducí la canción De Música Ligera` | Entrega una búsqueda de música a una app que implemente el intent público de reproducción desde búsqueda. Su disponibilidad y reproducción dependen de esa app. |

Los nombres, títulos, direcciones y búsquedas conservan acentos, mayúsculas y signos. Los parámetros se codifican para no convertirse en otros parámetros del enlace. Las rutas no admiten restricciones de peajes, paradas ni subtipos de transporte: se conserva el pedido y se explica qué debe completarse en Maps. Las órdenes múltiples se rechazan; deben pedirse por separado.

Para nombres de contactos se solicita `READ_CONTACTS` en tiempo de ejecución, solo al necesitarlo. Un número explícito no requiere ese permiso. La búsqueda es local; no se envían contactos a Groq ni a Instinct. Cuando hay varios números exactos o coincidencias aproximadas, el usuario debe elegir la opción y número visibles. Una búsqueda truncada no permite una selección implícita. Se puede responder con el índice de la opción o tocarla; cancelar cierra esa selección. Denegar permiso conserva el pedido y permite indicar un número.

Los eventos usan la zona horaria del teléfono y validan fechas y horas estrictamente, incluyendo horas inexistentes o repetidas por cambios de horario. Sin duración explícita, el borrador dura una hora, revisable en Calendario. Una hora como «cinco» sin período exige repetir el evento con la hora completa; las solicitudes pasadas, fechas inválidas, repeticiones o detalles no admitidos no se convierten en otro evento. No se piden permisos de lectura/escritura de calendario y no se afirma que el evento quedó guardado.

Si falta una app receptora, el permiso o un dato, se conserva el pedido/audio y el floating permanece abierto. Una entrega exitosa cierra el floating mediante el observador de ciclo de vida existente. Se conservan los comandos previos, el envío a Instinct, el descarte de transcripciones tardías y una sola captura de micrófono.

### Comprobación manual en el Motorola

- Probar rutas con los cuatro modos y direcciones guardadas/no guardadas. Comprobar direcciones con acentos, símbolos y signos.
- Llamar por número sin permiso de contactos; luego denegar/conceder el permiso para nombres. Usar dos contactos homónimos o varios números, y comprobar elección, cancelación y retorno.
- Preparar eventos por voz/texto en hoy/mañana/fecha explícita. Verificar título, zona, inicio, fin y duración; cancelar en Calendario no debe presentarse como evento guardado.
- Abrir los dos modos de cámara y las búsquedas de Maps, YouTube y música; comprobar el comportamiento cuando falta una app compatible.
- Pedir una explicación general y verificar envío del M4A original a Instinct; las negaciones, condiciones y órdenes múltiples no deben activar parcialmente acciones nativas.
- Mantener comprobaciones de linterna, volumen, Reloj, cierre/rotación del panel y adjuntos de versiones anteriores.

### Resultado de verificación 1.5.0

- `assembleDebug`: correcto con versión 1.5.0 / versionCode 9; firma v2 y alineación de 16 KiB verificadas. El certificado coincide con el APK 1.4.4 entregado en esta conversación, sin regenerar ni publicar claves.
- 76 pruebas JVM aprobadas (0 fallas), incluyendo interpretación nueva/anterior, contratos de intents, preservación de acentos Unicode, fechas y zona horaria, selección de contactos, negaciones, condiciones y órdenes encadenadas. El init script externo excluye únicamente el artefacto nativo inaccesible de Robolectric; las dependencias del proyecto no cambiaron.
- Todos los fuentes de pruebas Android compilan. Se intentaron las nuevas pruebas de integración de permisos, número/contacto, ausencia de app y navegación por voz; el runner quedó bloqueado por `android-all-instrumented:9-robolectric-4913185-2-i7` (HTTP 403), antes de ejecutar sus assertions.
- Suite completa `testDebugUnitTest`, sin exclusiones, intentada después de los cambios: bloqueada por `nativeruntime-dist-compat:1.0.18` (HTTP 403 del mirror de Maven), antes de ejecutar las pruebas.
- Revisión independiente realizada. Se observaron fallar y luego pasar las regresiones de órdenes encadenadas, modo/restricción de ruta, deduplicación de nombres/números, duración de eventos con cláusulas no soportadas y respuestas negativas/condicionales a aclaraciones. Las búsquedas de contactos truncadas no permiten elegir implícitamente un número.
- No hay dispositivo Android conectado: la ejecución y disponibilidad de apps nativas, diálogos de permiso y regreso real al floating requieren comprobación en el Motorola. No se afirma que la suite Android ni la validación física hayan pasado.


### Verificación de la entrega 1.6.0

- `assembleDebug`: correcto; versión 1.6.0, versionCode 10. Firma v2 y alineación
  de 16 KiB verificadas. APK de prueba firmado con una clave local nueva, excluida
  de Git. Para actualizar una instalación con otra firma hay que volver a firmar
  con su clave original; no se verificó compatibilidad con el certificado previo.
- Todos los fuentes de pruebas Android y JVM compilan, incluidas seis pruebas
  nuevas de sesiones híbridas y la edición del floating durante una decisión.
- 87 pruebas JVM en 14 clases: cero fallos. Se ejecutaron en Gradle con un init
  script externo que configura proxy/CA y excluye únicamente
  `org.robolectric:nativeruntime-dist-compat:1.0.18`, inaccesible. No se cambiaron
  las dependencias del proyecto. Incluye 11 pruebas nuevas de política/HTTP,
  cancelación del cliente y regresiones existentes de acciones, voz y WhatsApp.
- Suite completa intentada sin exclusiones: bloqueada al descargar ese artefacto
  nativo por HTTP 403. `FloatingHybridRoutingTest` (SDK 28) y `FloatingOverlayTest`
  (SDK 36) también se intentaron: sus runtimes `android-all-instrumented` dieron
  HTTP 403 durante inicialización. Sus assertions **no se ejecutaron**.
- Revisión independiente de código y del ajuste de edición: sin hallazgos
  materiales. No hay teléfono conectado ni clave Gemini en este entorno:
  comportamiento/latencia del proveedor y flujos físicos siguen pendientes.
