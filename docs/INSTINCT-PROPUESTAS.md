# Propuestas de Instinct («Instinct con manos», 1.13.0)

Hasta 1.12.0, Insto solo le enviaba cosas a Instinct. Desde 1.13.0, Instinct puede responder en WhatsApp con **líneas de propuesta** en un formato fijo. Insto las detecta en la notificación de WhatsApp de **ese** contacto, las interpreta con la gramática local, las valida igual que cualquier pedido y te las ofrece para confirmar.

**Ninguna propuesta se ejecuta sin un toque en «Confirmar» o un «dale» tuyo.**

La función está **apagada por defecto** y tiene un interruptor propio: **Ajustes → Propuestas de Instinct → «Recibir propuestas de Instinct»**.

## Protocolo

- Una línea de propuesta empieza con `#insto` y un espacio, y sigue con una orden en español canónico que `PhoneCommandParser` ya entiende:
  - `#insto poner una alarma a las 07:00`
  - `#insto poner un temporizador de 25 minutos`
  - `#insto poner el volumen al 30 por ciento`
- La línea tiene que *empezar* con `#insto`, en minúsculas (se ignoran los espacios iniciales). Un `#insto` en medio de una oración es texto común.
- Se leen como máximo **3 líneas de propuesta por mensaje**; las demás se ignoran. El resto del mensaje no se interpreta.
- No hay JSON ni parámetros libres: la línea pasa por el mismo parser que un pedido escrito, así que no existe una segunda vía de interpretación. No pasa por Gemini, Groq, frases aprendidas, estructuras aprendidas ni el contexto operativo. **Solo la gramática explícita.**
- Si una línea no se resuelve como un único `PhoneRoute.Local` permitido, se descarta con un aviso discreto: «Instinct propuso algo que no entiendo». Eso incluye una aclaración (por ejemplo, «a las 7» sin indicar mañana o tarde), varios pasos, condiciones, negaciones y preguntas.

## Acciones permitidas (lista blanca v1)

| Tipo | Ejemplo de línea |
| --- | --- |
| `Alarm` | `#insto poner una alarma a las 07:00` |
| `Timer` | `#insto poner un temporizador de 25 minutos` |
| `Volume` | `#insto poner el volumen al 30 por ciento` · `#insto subir el volumen` |
| `Torch` | `#insto prender la linterna` |
| Silencio, vibrar y No molestar (`TemporaryMode` de silencio o No molestar, y las acciones `SILENT_ON/OFF`, `VIBRATE_ON`, `DND_ON/OFF` con las que la gramática expresa esos modos sin duración) | `#insto silenciar el teléfono durante una hora` · `#insto activar no molestar` |
| `Media` | `#insto pausar la música` · `#insto siguiente canción` |
| `OpenApp` | `#insto abrir spotify` |
| `ClockList` | `#insto mostrar las alarmas` |
| `MusicSearch`, `SpotifySearch`, `YouTubeSearch` | `#insto poner música de Soda Stereo` · `#insto buscar jazz en YouTube` |
| `CalendarEvent` (con la vista previa y la confirmación de siempre) | `#insto agendar reunión mañana a las 17:00 durante una hora` |

**Prohibidas:** `Dial` y cualquier llamada, cualquier envío de mensajes o archivos, compartir texto, `SavePlace`, navegación, rutinas y workflows, planes de varios pasos y un volumen multimedia temporal (`TemporaryMode` de volumen). Todo lo que no figura en la tabla queda prohibido.

## Cómo llega una propuesta

`NotificationAccessService.onNotificationPosted` → `InstinctProposalIntake` → `InstinctProposalFilter` → `InstinctProposalValidator` → `InstinctProposalCenter`.

1. **Interruptor.** Con «Recibir propuestas de Instinct» apagado, `onNotificationPosted` retorna antes de tocar la notificación: no lee ni el paquete.
2. **Filtros**, en orden, cortando en el primero que falle:
   1. El paquete es el de la app de WhatsApp configurada (la misma que Insto usa para enviar).
   2. La conversación no es grupal (`EXTRA_IS_GROUP_CONVERSATION`).
   3. El remitente del mensaje más nuevo de `MessagingStyle` coincide **exactamente** con el nombre del contacto configurado. Sin `MessagingStyle`, tiene que coincidir el título. Una respuesta tuya en el mismo chat no cuenta.
   4. Hubo un **envío confirmado** de Insto a ese contacto en los últimos 15 minutos. La ventana se configura de 5 a 60 minutos.
   5. El mensaje es nuevo: dedupe por `key` de la notificación más un hash SHA-256 del texto, solo en memoria. Además, una propuesta igual a otra que sigue pendiente no se ofrece dos veces.
3. **Validación de cada línea**: `RequestSafetyPolicy` (y se descartan preguntas), `PhoneCommandParser`, `SemanticCommandGuard.review` con la línea como texto del pedido y, por último, la lista blanca.
4. **Presentación** (ver abajo).

Lo que no pasa los filtros se descarta sin guardarlo ni registrarlo. Logcat (solo en debug) registra **contadores por etapa**, nunca un texto, un nombre o un número.

## Presentación y confirmación

- **Con el flotante abierto:** la propuesta aparece como confirmación dentro de la sesión actual («Instinct propone: …»). Se confirma con **Confirmar** o diciendo «dale»; «no», «cancelar» o **Cancelar** la descartan. Si hay algo en curso (un texto escrito sin enviar, una grabación, una interpretación, una confirmación, una elección, un plan o un borrador), la propuesta **queda en espera** y el panel lo indica. Aparece cuando eso termina. Si el panel se cierra con propuestas sin resolver, vuelven a notificación mientras sigan vigentes.
- **Con el flotante cerrado:** una notificación heads-up en el canal propio **«Insto · propuestas de Instinct»**: «Instinct propone: …», con **Confirmar** y **Descartar**. En la pantalla bloqueada solo se ve «Instinct propone una acción». **Confirmar** abre el panel de Insto (es una acción tuya, así que abrir la Activity está permitido) y ejecuta el comando validado, sin abrir el micrófono. El aviso «Instinct propuso algo que no entiendo» va en un canal de baja importancia, «Insto · avisos de propuestas», que no suena ni aparece arriba.
- **Ejecución:** siempre con `PhoneActionExecutor`, con los permisos, las confirmaciones propias de cada acción (por ejemplo, «esa hora ya pasó») y las reglas de no repetir lo incierto. Un evento de calendario muestra la vista previa de siempre y necesita su confirmación.

## Vencimiento e idempotencia

- Las propuestas viven **solo en memoria** (`ProposalBook`) y vencen a los **10 minutos** (reloj monotónico). Si el proceso se reinicia, se pierden: su notificación, si sigue visible, solo informa que ya no está disponible.
- Una propuesta vencida, descartada o ya ejecutada no se puede volver a ejecutar. Un segundo Confirmar o un «dale» repetido recibe «Esa propuesta ya se ejecutó o se descartó. No se repite.».
- Cada propuesta guarda la huella de la configuración con la que se validó: contacto (nombre y número) y paquete de WhatsApp. Si al confirmar cambió algo de eso o se apagó el interruptor, no se ejecuta y queda descartada.
- Confirmar desde la notificación exige, además del id, un **token aleatorio** que solo viaja en el `PendingIntent` de Insto. Como `AssistActivity` está exportada, otra app no puede confirmar una propuesta adivinando su id.
- Apagar el interruptor descarta las propuestas pendientes, cierra sus notificaciones y borra la ventana de correlación.

## Privacidad: qué cambia

Hasta 1.12.0, `NotificationAccessService` leía contenido solo ante un pedido explícito («mostrá mis notificaciones»). Esta función necesita leer de forma **pasiva**, por eso:

- Va detrás de un interruptor **separado y apagado por defecto**. Al activarlo se muestra exactamente qué se lee: el último mensaje de la conversación individual con el contacto configurado, en la app de WhatsApp configurada, solo durante la ventana posterior a un envío confirmado, y de ese mensaje solo las líneas `#insto`.
- Si falta el acceso a «Insto · notificaciones», se pide con el flujo de Ajustes de Android que ya existía. En Android 13 o posterior también se pide el permiso de notificaciones.
- El texto de la notificación no se guarda, no se registra, no sale del teléfono y no entra en copias de seguridad. Lo único persistente es `noBackupFilesDir/instinct-last-send.json` (hora monotónica, número de arranque y hash del número del destinatario) y los archivos del interruptor y de la ventana, también en `noBackupFilesDir`. Nada de eso forma parte de la copia `.insto` ni de la copia de Android: una restauración nunca enciende la lectura pasiva.
- La ventana solo se abre con un envío **confirmado** del auto-envío (`AutoSendMetrics.Outcome.CONFIRMED`) al número del contacto configurado, y solo se registra con el interruptor encendido.

## Lo que no hacen las propuestas

- No alimentan el aprendizaje: no crean frases (`LearnedPhraseStore`) ni estructuras (`PhrasePatterns`), ni registran usos.
- No cuentan como turnos de `OperationalContext` ni establecen una referencia para la próxima orden.
- No se juntan en planes: cada línea es un comando independiente.
- No saltean confirmaciones de Android ni de Insto.
- No agregan dependencias, servidores ni llamadas de red.

## Límites conocidos

- **El nombre no prueba el número.** Android no expone el número del remitente en la notificación de WhatsApp. Un contacto que se llame exactamente igual que Instinct en otro chat individual podría pasar el filtro de nombre. Lo acotan la ventana de correlación, la lista blanca (nada de llamadas ni mensajes) y la confirmación obligatoria.
- La comparación usa el nombre que **muestra WhatsApp** en la notificación (el de tu agenda), no el número. Si en Insto guardaste a Instinct con otro nombre, no se lee nada: usá exactamente el mismo.
- Si Android acaba de iniciar el proceso de Insto y los ajustes todavía no se cargaron, esa notificación se descarta (se prefiere perder una propuesta a leer sin saber el contacto).
- Solo los envíos **confirmados** por el auto-envío abren la ventana. Si enviás a mano (sin Accesibilidad, con el envío automático en revisión o con la nota de voz nativa de WhatsApp), Insto no puede saber que el envío ocurrió y no lee propuestas.
- Requiere Android 9 o posterior: antes no existe `EXTRA_IS_GROUP_CONVERSATION` y no habría forma confiable de excluir los grupos.
- Si WhatsApp agrupa varios mensajes y el título cambia (por ejemplo «Instinct (2 mensajes)») sin `MessagingStyle`, el nombre no coincide exactamente y no se lee nada.
- Las propuestas que llegan mientras la app de WhatsApp está abierta en ese chat no generan notificación y, por lo tanto, no se ven.
- Las propuestas en memoria no sobreviven a que Android cierre el proceso de Insto.
- El contexto operativo no interviene en la interpretación de una propuesta, pero al ejecutar un control multimedia sin app indicada («pausar la música»), `PhoneActionExecutor` sigue eligiendo la sesión con el contexto vigente, como con cualquier pedido. La propuesta no modifica ese contexto.

## Cómo probarlo en el teléfono

1. Instalá el APK debug de 1.13.0 y configurá el contacto de Instinct.
2. En **Ajustes → Propuestas de Instinct**, activá «Recibir propuestas de Instinct», leé la explicación y tocá **Activar**. Habilitá «Insto · notificaciones» si Android lo pide y permití las notificaciones.
3. Verificá que el auto-envío esté «listo» y mandale algo a Instinct desde el flotante.
4. Pedile a Instinct (o a alguien desde el chat de Instinct, para probar) que responda: `#insto poner un temporizador de 1 minuto`. Con el flotante cerrado tiene que aparecer «Instinct propone: Temporizador…». Probá **Descartar** y, con otra propuesta, **Confirmar**.
5. Abrí el flotante, escribí algo sin enviarlo y hacé que llegue otra propuesta: tiene que quedar en espera. Borrá el texto: aparece para confirmar; decí «dale».
6. Casos negativos: un grupo, otro contacto, un mensaje después de la ventana, `#insto llamar a Ana` (aviso discreto, sin acción), la misma propuesta dos veces, Confirmar después de 10 minutos y Confirmar después de apagar el interruptor.
7. Con `adb logcat -s InstoProposals` en un build debug, verificá que solo aparezcan contadores.

## Instrucciones para pegar en el prompt de Instinct

Copiá este bloque tal cual en las instrucciones de Instinct:

```text
Propuestas para Insto (el asistente del teléfono de quien te escribe)

Cuando sea útil que el teléfono haga algo concreto, podés proponerlo agregando, en tu respuesta de WhatsApp, líneas con este formato exacto:

#insto <orden>

Reglas:
- Cada propuesta va sola en su propia línea y la línea empieza con "#insto " (en minúsculas, seguido de un espacio). Nunca pongas "#insto" en medio de una oración.
- Como máximo 3 líneas de propuesta por mensaje. Insto ignora las demás.
- Una sola acción por línea, en español, con verbo en infinitivo y todos los datos explícitos. Sin condiciones ("si…"), sin negaciones, sin preguntas, sin "y" para encadenar acciones.
- Horas en formato de 24 horas (07:00, 17:30). Duraciones con número y unidad (25 minutos, una hora). Volumen en porcentaje (30 por ciento).
- Insto nunca ejecuta nada solo: le muestra la propuesta a la persona, que la confirma o la descarta. Explicá en el resto del mensaje por qué la proponés.
- Insto solo lee la línea: el resto de tu mensaje no se interpreta. Si una línea no se entiende o no está permitida, se descarta.

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

Nunca propongas llamadas ni mensajes (de WhatsApp, SMS ni de ningún otro tipo), ni enviar o compartir archivos, ni guardar lugares, ni rutinas, ni navegación, ni varias acciones juntas. Insto los rechaza siempre.
```
