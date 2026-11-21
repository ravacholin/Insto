# Órdenes de Instinct («Instinct con manos», 1.13.0)

Instinct puede pedirle acciones al teléfono **por su cuenta**: cuando lo considera útil, escribe en su chat de WhatsApp una o más líneas `#insto <orden>`. Insto las detecta en la notificación de ese contacto, las interpreta con la gramática local, las valida igual que cualquier pedido y **las ejecuta sin preguntar**. Después avisa con una notificación «Instinct hizo: …». No hace falta que vos le hayas escrito antes a Instinct.

La función está **apagada por defecto**: **Ajustes → Propuestas de Instinct → «Recibir órdenes de Instinct»**. Con **«Pedirme confirmación antes de ejecutar»** encendido, cada orden espera tu toque o un «dale».

## Protocolo

- Una línea de orden empieza con `#insto` y un espacio, y sigue con una orden en español canónico que `PhoneCommandParser` ya entiende:
  - `#insto poner una alarma a las 07:00`
  - `#insto poner un temporizador de 25 minutos`
  - `#insto poner el volumen al 30 por ciento`
- La línea tiene que *empezar* con `#insto`, en minúsculas (se ignoran los espacios iniciales). Un `#insto` en medio de una oración es texto común.
- Se leen como máximo **3 líneas por mensaje**; las demás se ignoran. El resto del mensaje no se interpreta.
- No hay JSON ni parámetros libres: la línea pasa por el mismo parser que un pedido escrito. No usa Gemini, Groq, frases aprendidas, estructuras aprendidas ni el contexto operativo. **Solo la gramática explícita.**
- Si una línea no se resuelve como un único `PhoneRoute.Local` permitido, se descarta y aparece un aviso discreto: «Instinct propuso algo que no entiendo». Eso incluye las aclaraciones (por ejemplo, «a las 7» sin mañana ni tarde), varios pasos, condiciones, negaciones y preguntas.

## Acciones permitidas (lista blanca v1)

| Tipo | Ejemplo | ¿Abre otra app? |
| --- | --- | --- |
| `Volume` | `#insto poner el volumen al 30 por ciento` · `#insto subir el volumen` | No |
| `Torch` | `#insto prender la linterna` | No |
| Silencio, vibrar, No molestar (`TemporaryMode` de silencio o No molestar, y `SILENT_ON/OFF`, `VIBRATE_ON`, `DND_ON/OFF`) | `#insto silenciar el teléfono durante una hora` · `#insto activar no molestar` | No |
| `Media` | `#insto pausar la música` · `#insto siguiente canción` | No |
| `Alarm` | `#insto poner una alarma a las 07:00` | Sí (Reloj) |
| `Timer` | `#insto poner un temporizador de 25 minutos` | Sí (Reloj) |
| `ClockList` | `#insto mostrar las alarmas` | Sí |
| `OpenApp` | `#insto abrir spotify` | Sí |
| `MusicSearch`, `SpotifySearch`, `YouTubeSearch` | `#insto buscar jazz en YouTube` | Sí |
| `CalendarEvent` | `#insto agendar reunión mañana a las 17:00 durante una hora` | Sí (el calendario muestra el evento para guardarlo) |

**Prohibidas siempre:** llamadas (`Dial`, `CALL_DIRECT`), cualquier envío de mensajes o archivos, compartir texto, `SavePlace`, navegación, rutinas y workflows, planes de varios pasos y volumen multimedia temporal.

## Cómo llega y se ejecuta

`NotificationAccessService.onNotificationPosted` → `InstinctProposalIntake` → `InstinctProposalFilter` → `InstinctProposalValidator` → `InstinctProposalCenter`.

1. **Interruptor.** Con «Recibir órdenes de Instinct» apagado, `onNotificationPosted` retorna antes de tocar la notificación.
2. **Filtros**, en orden, cortando en el primero que falle:
   1. Paquete de la app de WhatsApp configurada.
   2. No es un grupo (`EXTRA_IS_GROUP_CONVERSATION`).
   3. El remitente del mensaje más nuevo coincide **exactamente** con el nombre del contacto configurado (sin `MessagingStyle`, el título). Una respuesta tuya en el mismo chat no cuenta.
   4. Mensaje nuevo: dedupe por `key` de la notificación más un SHA-256 del texto, en memoria.
   5. Tiene líneas `#insto`.
3. **Validación de cada línea:** `RequestSafetyPolicy` (también descarta preguntas), `PhoneCommandParser`, `SemanticCommandGuard.review` con la línea como texto del pedido y la lista blanca.
4. **Ejecución:**
   - **Flotante cerrado:** `PhoneActionExecutor` en el proceso de Insto, sin `confirmed`. Si sale bien, llega «Instinct hizo: …» en el canal «Insto · acciones de Instinct». Si falla, «Instinct pidió: … No se pudo: …». Si el resultado es incierto, se avisa y **no se repite**.
   - **Flotante abierto y libre:** se ejecuta en la sesión del panel, como una orden local. **Flotante ocupado** (texto sin enviar, grabación, interpretación, confirmación, plan o elección): la orden espera y se ejecuta sola cuando el panel se libera. Un evento de calendario pasa por la vista previa de siempre.
5. **Cuándo espera tu toque en lugar de ejecutarse** (notificación «Instinct propone: …» con **Confirmar**/**Descartar**, en el canal «Insto · propuestas de Instinct»):
   - encendiste «Pedirme confirmación antes de ejecutar»;
   - el teléfono está bloqueado;
   - la orden abre otra app, el panel está cerrado y el servicio de Accesibilidad de Insto no está conectado (ver Límites);
   - Instinct ya pidió 6 acciones en los últimos 10 minutos (freno anti-bucle);
   - la acción necesita algo tuyo: confirmar «esa hora ya pasó», un permiso o elegir entre varias apps.

## Idempotencia y vencimiento

- Las órdenes viven solo en memoria (`ProposalBook`). Cada una se ejecuta como máximo una vez gracias a un `claim` atómico. Si WhatsApp vuelve a publicar la misma notificación, no pasa nada.
- Las que esperan confirmación vencen a los **10 minutos**. Una vencida, descartada o ya ejecutada no se puede volver a ejecutar.
- Cada orden guarda la huella de la configuración con la que llegó (contacto y app de WhatsApp). Si eso cambió o se apagó el interruptor, no se ejecuta.
- Confirmar desde la notificación exige, además del id, un **token aleatorio** que solo viaja en el `PendingIntent` de Insto, porque `AssistActivity` está exportada.

## Privacidad

- La lectura es **pasiva**: con el interruptor encendido, Insto mira cada notificación de WhatsApp y lee solo el último mensaje del chat individual con el contacto configurado. De ese mensaje usa únicamente las líneas `#insto`. Las notificaciones de otras apps solo se miran por su paquete. Las de grupos y otros contactos se descartan antes de leer el texto.
- El texto no se guarda, no se registra (Logcat muestra solo contadores, en debug), no sale del teléfono y no entra en copias de seguridad.
- Los interruptores viven en `noBackupFilesDir`: una restauración nunca enciende ni la lectura ni la ejecución automática.
- El diagnóstico de Ajustes muestra estados y el motivo del último resultado. Nunca muestra el contenido del mensaje.

## Lo que no hacen las órdenes

- No alimentan el aprendizaje: no crean frases (`LearnedPhraseStore`) ni estructuras (`PhrasePatterns`).
- No cuentan como turnos de `OperationalContext` ni lo modifican. Eso sí: al ejecutar un control multimedia sin app indicada, `PhoneActionExecutor` sigue eligiendo la sesión con el contexto vigente, como con cualquier pedido.
- No se juntan en planes: cada línea es un comando independiente.
- No agregan dependencias, servidores ni llamadas de red.

## Límites conocidos

- **El nombre no prueba el número.** Android no expone el número del remitente de WhatsApp. Si otro contacto tuyo se llama exactamente igual que Instinct, sus `#insto` también se ejecutarían. Por eso el contacto de Instinct necesita un nombre único. Lo acotan la lista blanca, el freno y el aviso posterior.
- **Abrir otra app desde segundo plano.** Desde Android 10, una app no puede abrir pantallas si no está en primer plano. La excepción es tener un servicio de Accesibilidad conectado. Sin la Accesibilidad de Insto, alarma, temporizador, abrir apps, búsquedas y eventos esperan tu toque. Volumen, linterna, silencio y música siempre se ejecutan solos.
- La comparación usa el nombre que **muestra WhatsApp** (el de tu agenda). Si en Insto guardaste a Instinct con otro nombre, no se lee nada.
- Si Android acaba de iniciar el proceso de Insto y todavía no cargó los ajustes, esa notificación se descarta.
- Si tenés abierto el chat de Instinct en WhatsApp, WhatsApp no genera notificación y no llega nada.
- Requiere Android 9 o posterior.

## Cómo probarlo en el teléfono

1. Configurá el contacto de Instinct con el nombre exacto que muestra WhatsApp.
2. Andá a **Ajustes → Propuestas de Instinct**, activá «Recibir órdenes de Instinct» y habilitá «Insto · notificaciones» y las notificaciones.
3. Sin escribirle nada antes, que Instinct mande `#insto poner el volumen al 30 por ciento`: el volumen cambia y llega «Instinct hizo: …».
4. Con la Accesibilidad de Insto activa, `#insto poner un temporizador de 1 minuto` se crea solo. Sin ella, llega «Instinct propone: …» con Confirmar.
5. Si algo no pasa, mirá **Diagnóstico** en la misma pantalla y tocá «Actualizar diagnóstico».

## Instrucciones para pegar en el prompt de Instinct

```text
Órdenes para Insto (el asistente del teléfono de quien te escribe)

Podés hacer que el teléfono haga algo concreto, por tu cuenta, cuando lo consideres claramente útil. Para eso agregá en tu mensaje de WhatsApp líneas con este formato exacto:

#insto <orden>

Insto ejecuta cada línea apenas llega, sin preguntarle a la persona, y después le avisa. Usalo con criterio: solo cuando la acción sea útil ahora, y explicá en el mismo mensaje qué hiciste y por qué.

Reglas:
- Cada orden va sola en su propia línea y la línea empieza con "#insto " (en minúsculas, seguido de un espacio). Nunca pongas "#insto" en medio de una oración ni lo uses como ejemplo en el texto.
- Como máximo 3 líneas de orden por mensaje. Insto ignora las demás. Más de 6 órdenes en 10 minutos quedan esperando confirmación.
- Una sola acción por línea, en español, con verbo en infinitivo y todos los datos explícitos. Sin condiciones ("si…"), sin negaciones, sin preguntas, sin "y" para encadenar acciones.
- Horas en formato de 24 horas (07:00, 17:30). Duraciones con número y unidad (25 minutos, una hora). Volumen en porcentaje (30 por ciento).
- Insto solo lee esas líneas: el resto de tu mensaje no se interpreta. Si una línea no se entiende o no está permitida, se descarta.

Acciones permitidas (usá estas formas):
#insto poner una alarma a las 07:00
#insto poner una alarma a las 07:00 todos los días
#insto poner un temporizador de 25 minutos
#insto poner el volumen al 30 por ciento
#insto subir el volumen
#insto bajar el volumen
#insto prender la linterna
#insto apagar la linterna
#insto silenciar el teléfono
#insto silenciar el teléfono durante una hora
#insto activar no molestar
#insto poner en vibración
#insto pausar la música
#insto siguiente canción
#insto abrir spotify
#insto mostrar las alarmas
#insto mostrar los temporizadores
#insto poner música de Soda Stereo
#insto buscar jazz en YouTube
#insto buscar Cerati en Spotify
#insto agendar reunión mañana a las 17:00 durante una hora

Nunca pidas llamadas ni mensajes (de WhatsApp, SMS ni de ningún otro tipo), ni enviar o compartir archivos, ni guardar lugares, ni rutinas, ni navegación, ni varias acciones juntas. Insto los rechaza siempre.
```
