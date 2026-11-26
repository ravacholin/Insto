# Órdenes de Instinct («Instinct con manos», 1.13.0 a 1.23.0)

Instinct puede pedirle acciones al teléfono **por su cuenta**: cuando lo considera útil, escribe en su chat de WhatsApp una o más líneas `#insto <orden>`. Insto las detecta en la notificación de ese contacto, las interpreta con la gramática local, las valida igual que cualquier pedido y **las ejecuta sin preguntar**. Después avisa con una notificación «Instinct hizo: …». No hace falta que vos le hayas escrito antes a Instinct.

Desde 1.14.0, además: Insto le puede **responder** a Instinct con el resultado de cada orden, Instinct puede pedir **rutinas** que vos le habilitaste, el bloque de instrucciones para Instinct se **genera desde el código**, las órdenes pueden quedar **programadas** para más tarde (con una única condición de clima) y lo que se puede revertir trae **Deshacer**.

Desde 1.15.0, los acuses pueden ir a una **bitácora** en Google Sheets en lugar del chat, para no llenarlo de mensajes ([Bitácora](#bitácora-1150)). Las órdenes siguen llegando por WhatsApp exactamente igual.

Desde 1.16.0, qué hace Instinct solo se elige **por tipo de acción** ([Autonomía por acción](#autonomía-por-acción-1160)), con horas de silencio y un ajuste automático cuando deshacés mucho. Con la bitácora configurada, Insto además publica el **estado del teléfono** para que Instinct decida con contexto ([Estado del teléfono](#estado-del-teléfono-1160)), y las órdenes pueden llegar también por un **buzón firmado** en la misma planilla, sin escribir en el chat ([Buzón firmado](#buzón-firmado-1160)).

Desde 1.17.0, un **timbre** push con ntfy hace que una orden del buzón se tome en segundos ([Timbre](#timbre-1170)), y las órdenes pueden quedar **armadas** para que el teléfono las dispare solo: al terminar o empezar un evento, al conectar o desconectar auriculares, al desbloquear ([Disparadores por estado](#disparadores-por-estado-1170)) o todos los días, los días hábiles o los fines de semana hasta una fecha ([Órdenes recurrentes](#órdenes-recurrentes-1170)).

Desde 1.18.0, la orden firmada puede viajar **dentro del timbre** y ejecutarse apenas llega, sin leer la planilla ([Orden directa en el timbre](#orden-directa-en-el-timbre-1180)); Insto publica en la planilla un **catálogo vivo** con las reglas vigentes, para que Instinct no dependa de una copia pegada ([Catálogo vivo](#catálogo-vivo-1180)), e Instinct puede **probar** una orden antes de mandarla ([Probar una orden](#probar-una-orden-1180)). Protocolo Insto v6.

Desde 1.19.0, el timbre usa la biblioteca oficial de UnifiedPush y viaja **cifrado según RFC 8291**; la recepción ya no bloquea Android, lo recibido queda en una cola en disco que sobrevive a la muerte del proceso y una orden completa y autenticada se ejecuta sin leer la planilla ([Timbre cifrado y transporte confiable](#timbre-cifrado-y-transporte-confiable-1190)). Para Instinct no cambia nada: Protocolo Insto v6.

La función está **apagada por defecto**: **Ajustes → Propuestas de Instinct → «Recibir órdenes de Instinct»**. En **Autonomía** elegís, por categoría, si Instinct lo hace solo, espera tu confirmación o nunca. Todos los interruptores y estados de esta función viven en `noBackupFilesDir`: una restauración de copia de seguridad nunca los enciende ni los trae.

Desde 1.21.0, Instinct puede hacerte [preguntas con opciones](#preguntas-con-opciones-1210). El Protocolo Insto v7 mantiene la gramática de las órdenes y agrega `preguntar`.

## Protocolo

- Una línea de orden empieza con `#insto` y un espacio, y sigue con una orden en español canónico que `PhoneCommandParser` ya entiende:
  - `#insto poner una alarma a las 07:00`
  - `#insto poner un temporizador de 25 minutos`
  - `#insto poner el volumen al 30 por ciento`
- La línea tiene que *empezar* con `#insto`, en minúsculas (se ignoran los espacios iniciales). Un `#insto` en medio de una oración es texto común.
- Se leen como máximo **3 líneas por mensaje**; las demás se ignoran. El resto del mensaje no se interpreta.
- No hay JSON ni parámetros libres: la línea pasa por el mismo parser que un pedido escrito. No usa Gemini, Groq, frases aprendidas, estructuras aprendidas ni el contexto operativo. **Solo la gramática explícita.**
- Hay formas extra, todas fijas: el envoltorio de una **orden diferida** (`a las HH:MM: …`, `en N minutos: …`, ver [Órdenes diferidas](#órdenes-diferidas-1140)), su única **condición de clima**, los **disparadores** y las **recurrentes** (1.17.0) y las **consultas** `ver catálogo`, `ver programadas`, `cancelar programadas` y `cancelar programada <id>`.
- Si una línea no se resuelve como un único `PhoneRoute.Local` permitido (o una de esas formas), se descarta y aparece un aviso discreto: «Instinct propuso algo que no entiendo». Eso incluye las aclaraciones (por ejemplo, «a las 7» sin mañana ni tarde), varios pasos, condiciones fuera del envoltorio, negaciones y preguntas.

## Acciones permitidas (lista blanca)

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
| `ROUTINE_RUN` de una rutina **habilitada para Instinct** (1.14.0) | `#insto ejecutar la rutina Estudio` | Según sus pasos |
| Cualquiera de las anteriores, **diferida** (1.14.0) | `#insto a las 22:00: poner el volumen al 10 por ciento` · `#insto a las 06:30 si llueve: poner una alarma a las 06:45` | Según la orden |
| Cualquiera de las anteriores, con un **disparador** (1.17.0) | `#insto al conectar auriculares hasta las 20:00: abrir spotify` · `#insto al terminar el evento: desactivar no molestar` | Según la orden |
| Cualquiera de las anteriores, **recurrente** (1.17.0) | `#insto los días hábiles a las 08:00 hasta el 15/12: activar no molestar` | Según la orden |
| Consultas (1.14.0) | `#insto ver catálogo` · `#insto ver programadas` · `#insto cancelar programadas` · `#insto cancelar programada <id>` (1.17.0) | No: no hacen nada en el teléfono |
| `ver estado` (1.16.0) | `#insto ver estado` | No: publica el [estado](#estado-del-teléfono-1160) en la bitácora y no consume el freno |
| `avisarme` (1.16.0), **solo por el [buzón](#buzón-firmado-1160)** | `#insto avisarme Te bajé el volumen para la clase.` | No: es una notificación de Insto para vos |

Los ejemplos salen de una sola fuente en el código (`InstinctProposalValidator.examples`, junto a la lista blanca) y un test comprueba que cada uno es una orden válida.

**Prohibidas siempre:** llamadas (`Dial`, `CALL_DIRECT`), cualquier envío de mensajes o archivos, compartir texto, `SavePlace`, navegación, rutinas que no habilitaste (o que cambiaron desde que las habilitaste), workflows y planes armados a partir de texto libre (solo se aceptan los [planes](#planes-de-varios-pasos-1230) con su gramática fija), y volumen multimedia temporal. `avisarme` **no** es un envío de mensajes: es una notificación local de Insto para vos, que no sale del teléfono ni le llega a nadie más.

## Cómo llega y se ejecuta

`NotificationAccessService.onNotificationPosted` → `InstinctProposalIntake` → `InstinctProposalFilter` → `InstinctProposalValidator` → `InstinctProposalCenter`. Desde 1.16.0 hay una segunda entrada, el [buzón firmado](#buzón-firmado-1160) (`InstinctMailbox` → `InstinctProposalIntake.onMailbox`), que se une en el mismo validador con `ProposalSource.BUZON`.

1. **Interruptor.** Con «Recibir órdenes de Instinct» apagado, `onNotificationPosted` retorna antes de tocar la notificación.
2. **Filtros**, en orden, cortando en el primero que falle:
   1. Paquete de la app de WhatsApp configurada.
   2. No es un grupo (`EXTRA_IS_GROUP_CONVERSATION`).
   3. El remitente del mensaje más nuevo coincide **exactamente** con el nombre del contacto configurado (sin `MessagingStyle`, el título). Una respuesta tuya en el mismo chat no cuenta, y tampoco un acuse de Insto.
   4. Mensaje nuevo: dedupe por `key` de la notificación más un SHA-256 del texto, en memoria.
   5. Tiene líneas `#insto`.
3. **Validación de cada línea:** primero las consultas y el envoltorio de las diferidas; después, para la orden (o la orden interior), `RequestSafetyPolicy` (también descarta preguntas), `PhoneCommandParser`, `SemanticCommandGuard.review` con la línea como texto del pedido y la lista blanca (más las rutinas habilitadas).
4. **Ejecución:**
   - **Flotante cerrado:** `PhoneActionExecutor` en el proceso de Insto; desde 1.20.0 una orden autorizada (Solo) no vuelve a pedir el «¿Confirmás?» del modo temporal, y el resultado se [verifica](#diario-de-ejecución-verificación-y-pantalla-bloqueada-1200). Si sale bien, llega «Instinct hizo: …» en el canal «Insto · acciones de Instinct» (con **Deshacer** si se puede revertir). Si falla, «Instinct pidió: … No se pudo: …». Si el resultado es incierto, se avisa y **no se repite**.
   - **Flotante abierto y libre:** se ejecuta en la sesión del panel, como una orden local. **Flotante ocupado** (texto sin enviar, grabación, interpretación, confirmación, plan o elección): la orden espera y se ejecuta sola cuando el panel se libera. Un evento de calendario pasa por la vista previa de siempre.
   - **Diferida:** se guarda y se ejecuta por este mismo camino cuando llega su hora.
5. **Cuándo espera tu toque en lugar de ejecutarse** (notificación «Instinct propone: …» con **Confirmar**/**Descartar**, en el canal «Insto · propuestas de Instinct»):
   - la [autonomía](#autonomía-por-acción-1160) de esa categoría es **Confirmar**, o es la hora de silencio (desde 1.16.0; reemplaza a «Pedirme confirmación antes de ejecutar»);
   - el teléfono está bloqueado **y la orden abre una pantalla** (desde 1.20.0, volumen, linterna, silencio, No molestar, música y modos temporales se ejecutan con el teléfono bloqueado);
   - la orden (o algún paso de la rutina) abre otra app, el panel está cerrado y el servicio de Accesibilidad de Insto no está conectado (ver Límites);
   - Instinct ya pidió 6 acciones en los últimos 10 minutos (freno anti-bucle; una rutina cuenta como una);
   - una orden diferida se disparó con más de 15 minutos de atraso;
   - la acción necesita algo tuyo: confirmar «esa hora ya pasó», un permiso que falta, aceptar una restauración aproximada (sin alarmas exactas) o elegir entre varias apps.

## Autonomía por acción (1.16.0)

Hasta 1.15.0 había un solo interruptor, «Pedirme confirmación antes de ejecutar». Ahora hay una **política por categoría**, en **Ajustes → Propuestas de Instinct → Autonomía**.

| Categoría | Qué incluye |
| --- | --- |
| Volumen | `Volume` |
| Linterna | `Torch` |
| Silencio y No molestar | silencio, vibración, No molestar y el `TemporaryMode` de silencio o No molestar |
| Música | `Media` |
| Alarmas y temporizadores | `Alarm`, `Timer`, `ClockList` |
| Abrir apps y búsquedas | `OpenApp`, `MusicSearch`, `SpotifySearch`, `YouTubeSearch` |
| Calendario | `CalendarEvent` (igual pasa siempre por la vista previa) |
| Rutinas | `ROUTINE_RUN` |
| Avisos para vos | `#insto avisarme …` (solo por el [buzón](#buzón-firmado-1160)) |

Cada categoría tiene uno de tres niveles:

- **Solo** (valor por defecto, el comportamiento de 1.15.0): lo ejecuta y avisa, con Deshacer si corresponde.
- **Confirmar**: queda como «Instinct propone: …». El acuse dice el motivo concreto: `#insto-espera <orden> → la persona pide confirmación para alarmas y temporizadores`.
- **Nunca**: se rechaza enseguida, también si es una diferida: `#insto-error <orden> → la persona no permite esta acción`.

Una diferida usa la categoría de su orden interior. Una rutina usa **Rutinas** y además la más estricta de las categorías de sus pasos.

**Migración.** Si en 1.15.0 tenías encendido «Pedirme confirmación antes de ejecutar» (`instinct-proposals-ask-first`), todas las categorías arrancan en **Confirmar** y ese archivo se borra. La política vive en `noBackupFilesDir` (`instinct-autonomy.json`, escritura atómica); desde 1.20.0 viaja en la copia `.insto` (categoría Instinct) y se restaura tal cual. Si el archivo no se puede leer, todo queda en Confirmar hasta que vuelvas a elegir.

**La política solo puede endurecer.** Los bloqueos que ya existían (teléfono bloqueado con una acción que abre una pantalla, abrir otra app sin Accesibilidad, freno anti-bucle, diferida atrasada más de 15 minutos, la acción necesita algo tuyo) siguen mandando sobre **Solo**. Es estructural: `InstinctProposalCenter.gate` decide primero esos bloqueos y después la política (`InstinctAutonomyGate.combine`); el freno se consume último, solo si la orden de verdad se ejecuta. **Nunca** gana sobre todo.

### Horas de silencio

Un rango opcional (por ejemplo, de 00:00 a 07:00; apagado por defecto). Dentro del rango, cada **Solo** pasa a **Confirmar** con el motivo «horas de silencio». El interruptor **«Permitir que silencie el teléfono durante las horas de silencio»** (encendido por defecto) exceptúa las órdenes que **bajan** el ruido: silenciar, poner en vibración y activar No molestar (también como modo temporal). Se evalúa en el momento de ejecutar: una diferida, cuando se dispara. Los rangos que cruzan la medianoche (23:00 a 07:00) funcionan.

### Ajuste adaptativo

`InstinctTrustLedger` (lógica pura) lleva, por categoría y por día, los contadores `ejecutada`, `deshecha`, `confirmada`, `descartada` y `vencida`. Guarda 30 días como máximo en `instinct-trust.json` (`noBackupFilesDir`). **Solo contadores:** ni órdenes ni textos.

- **Baja sola:** si una categoría está en **Solo** y en los últimos 7 días tiene 3 o más deshechas, o al menos 30 % de deshechas con 5 o más ejecutadas, pasa a **Confirmar**. Te llega «Instinct va a pedirte confirmación para <categoría>: deshiciste N de M» con **«Volver a automático»**. En Ajustes la categoría dice «Ajustada automáticamente el DD/MM». Si la volvés a **Solo** (desde ahí o desde Ajustes), sus contadores se reinician.
- **Sugerir subir, nunca subir sola:** si una categoría está en **Confirmar** y en los últimos 14 días tiene 8 o más confirmadas y ninguna descartada, llega una sola sugerencia: «¿Dejar que Instinct haga <categoría> solo?», con **Sí** y **No**. Un «No» la silencia 30 días; sin respuesta, no se repite durante una semana.
- **Nunca** jamás se toca solo.
- Las acciones viajan en `PendingIntent` a `InstinctProposalReceiver` (no exportado) con un token aleatorio de un solo uso, guardado en el mismo archivo para que sobreviva a un reinicio del proceso (vence a los 7 días).

Qué cuenta como qué: **ejecutada** es una orden que corrió sola (en segundo plano o en el panel abierto); **confirmada**, una que confirmaste vos; **descartada**, una que descartaste; **vencida**, una que pasó sus 10 minutos esperando; **deshecha**, un Deshacer que revirtió algo.

### Para Instinct

El catálogo suma la sección **«Qué hace Insto solo y qué espera confirmación»**, generada desde la política vigente: qué categorías hace solo, cuáles esperan, cuáles rechaza, las horas de silencio y los bloqueos que siempre esperan. Así Instinct sabe de antemano qué va a quedar esperando. Copiá de nuevo las instrucciones cuando cambies la autonomía.

## Respuestas a Instinct (1.14.0)

Hasta 1.13.0, Instinct nunca sabía qué había pasado con sus órdenes. Ahora Insto puede contestarle en el mismo chat.

**Ajuste:** **Ajustes → Propuestas de Instinct → «Responderle a Instinct»**: **Nunca** (valor por defecto), **Solo errores y esperas** o **Siempre**. Vive en `noBackupFilesDir` (`instinct-proposals-reply-mode`), como los otros interruptores: una restauración nunca lo enciende ni lo trae. Apagar «Recibir órdenes de Instinct» también corta los acuses pendientes.

**Mecanismo.** Insto responde con la acción **«Responder»** de la misma notificación de WhatsApp que trajo las órdenes (`Notification.Action` con un `RemoteInput` de texto libre, de WhatsApp o WhatsApp Business): `RemoteInput.addResultsToIntent` y `PendingIntent.send` desde el proceso de Insto. **No abre WhatsApp** ni usa el envío automático por Accesibilidad: no interrumpe la pantalla y funciona igual con el teléfono bloqueado.

- `NotificationAccessService` guarda **solo** esa acción y la clave de su `RemoteInput` (`InstinctReplyHandle`), en memoria, asociada al mensaje aceptado y durante **10 minutos como máximo** (igual que `ProposalBook.TTL_MILLIS`). Nunca se persiste. Se lee solo de un mensaje que pasó todos los filtros y solo con las respuestas activas.
- Si WhatsApp no ofrece «Responder», o el envío tira `CanceledException`, el acuse se descarta en silencio y el diagnóstico lo registra («No se pudo responder: WhatsApp no ofreció Responder»). **Nunca se reintenta** ni se abre WhatsApp en su lugar.
- Después de responder, WhatsApp vuelve a publicar la notificación con nuestro mensaje. El filtro de contacto la descarta (el remitente del último mensaje sos vos) y, aunque no lo hiciera, el acuse no tiene ninguna línea que empiece con «#insto ».

**Formato.** Un solo mensaje por mensaje de Instinct procesado, con una línea por orden, en el orden en que llegaron:

```text
#insto-ok <orden original> → <mensaje de resultado de Insto>
#insto-espera <orden original> → <motivo por el que quedó para Confirmar>
#insto-error <orden original> → <motivo>
#insto-incierto <orden original> → <mensaje> · no se repite
```

Hay dos prefijos más: `#insto-programada <orden> → a las 22:00` (una orden diferida quedó guardada) y `#insto-deshecho <orden> → <mensaje>` (deshiciste lo que hizo).

- El mensaje es el texto de resultado que Insto ya produce para vos (`PhoneActionResult.message`) o un motivo fijo. No se agregan datos del teléfono.
- Los motivos de `#insto-error` para una línea inválida: vacía («la línea no tenía ninguna orden»), insegura o pregunta, no entendida («no la entendí: usá una de las formas del catálogo»), incoherente con el control semántico y no permitida («esa acción no está permitida para Instinct»).
- **Guardia anti-bucle:** ninguna línea puede empezar con «#insto » (con espacio). El formateador aplana saltos de línea y caracteres de control, y además pasa el acuse por el mismo lector de órdenes: si detectara una, no se envía.
- Como máximo 3 líneas (igual que el máximo de órdenes) y 600 caracteres. La orden original se recorta a 80.
- **Solo errores y esperas** omite las líneas `#insto-ok`; si todo salió bien, no se envía nada. Las respuestas a consultas (`ver catálogo`, `ver programadas`, `cancelar programadas`) salen en los dos modos activos.

Desde 1.15.0, el **«Canal de estados»** decide si estos acuses van al chat (como acá), a la [bitácora](#bitácora-1150) o a los dos. El modo de arriba sigue decidiendo qué se informa.

**Seguimiento.** Si una orden quedó en espera (o programada) y después la confirmás, la descartás, vence o se dispara, Insto manda un segundo acuse breve con la misma acción guardada, **solo si sigue vigente**: `#insto-ok …` (o `#insto-error`/`#insto-incierto` si al confirmarla falló), `#insto-descartada …` o `#insto-vencida …`. Este seguimiento sale también en «Solo errores y esperas», porque cierra una espera que Instinct ya vio. Para que el aviso de vencimiento llegue antes de que caduque la acción, una orden en espera vence unos 2 segundos antes de sus 10 minutos.

**Diagnóstico.** La pantalla de diagnóstico suma una línea propia, sin contenido: «Última respuesta a Instinct: Se respondió a Instinct» o «No se pudo responder» con el motivo. No reemplaza el estado del último mensaje.

## Rutinas para Instinct (1.14.0)

Instinct puede pedir `#insto ejecutar la rutina <nombre>` para correr una rutina que **vos** configuraste (`ConfiguredRoutineStore`), sin habilitar planes libres.

- **Permiso por rutina.** En **Ajustes → Propuestas de Instinct → Rutinas para Instinct** cada rutina tiene un interruptor «Instinct puede ejecutar «…»», apagado por defecto. Se guarda en `noBackupFilesDir` (`instinct-routines.json`, escritura atómica) el nombre normalizado y un **hash de los pasos** al momento de habilitarla. Si la rutina cambia (por ejemplo, la reimportás), el hash no coincide y queda deshabilitada hasta que la vuelvas a encender. Una restauración nunca trae estos permisos.
- **Qué rutinas se pueden habilitar.** Solo las que tienen **como máximo 6 pasos** y en las que **todos** los pasos pasan la lista blanca de órdenes sueltas (`InstinctProposalValidator.allowed`). Si no, el interruptor está deshabilitado y dice qué paso lo impide («El paso 2 («Llamar…») no está permitido para Instinct»).
- **Validación.** `ROUTINE_RUN` pasa la lista blanca solo si el nombre coincide **exactamente** (normalizado como lo hace `routine_run`) con una rutina habilitada y con hash vigente. «Estudios» o «Estudio intensivo» no valen por «Estudio». Se vuelve a comprobar al ejecutar: si la deshabilitaste o cambió en el medio, no corre.
- **Antes del primer paso.** Cada paso pasa por los mismos controles que una orden (confirmación pedida, teléfono bloqueado, apertura de otra app sin Accesibilidad). Si alguno no puede correr solo, **la rutina entera** queda para Confirmar antes de empezar. La rutina cuenta como **una sola** ejecución en el freno anti-bucle.
- **En segundo plano** se ejecuta con `WorkflowRunner` y su escritura previa al despacho, en un archivo propio (`instinct-routine-workflow.json`), separado del flujo del panel. Se detiene en el primer paso que falla, necesita algo tuyo, queda bloqueado o tiene resultado incierto. Los pasos restantes quedan `CANCELLED` y **nunca** se reintentan solos. Un paso entregado a otra app cuenta como hecho. Si el proceso muere en el medio, el paso en curso queda incierto y la rutina no se retoma.
- **Con el panel abierto** se ejecuta por el flujo de rutinas que ya tiene el asistente (la lista de pasos visible), arrancando sola porque la orden ya estaba confirmada o permitida.
- **Aviso:** «Instinct ejecutó la rutina Estudio: 3 de 3 pasos» o «…: se detuvo en el paso 2: <motivo>». **Acuse** (si está activo): `#insto-ok` con ese resumen, `#insto-error` si se detuvo, y `#insto-incierto … · no se repite` si se detuvo por un resultado incierto (para que Instinct no la vuelva a pedir).

## Catálogo para Instinct (1.14.0)

El bloque de instrucciones para el prompt de Instinct ya no se escribe a mano: lo genera `InstinctProtocolCatalog` (lógica pura) a partir del código. Incluye las reglas del protocolo, una forma de ejemplo por cada tipo permitido, las rutinas que habilitaste (**solo los nombres**, nunca los pasos), las consultas de solo lectura, los prefijos de los acuses (o, con la bitácora, cómo leerla) y la versión del protocolo («Protocolo Insto v8» desde 1.22.0, v7 en 1.21.0, v6 desde 1.18.0, v5 en 1.17.0, v4 en 1.16.0).

- **Ajustes → Propuestas de Instinct → «Copiar instrucciones para Instinct»** lo copia al portapapeles. Si las respuestas están activas y hay una acción «Responder» vigente (de un mensaje de Instinct de los últimos 10 minutos), aparece **«Enviarlas a Instinct»**: lo manda por ese chat.
- La copia que viaja por WhatsApp marca cada ejemplo con «› » delante y lo explica, porque Insto lee ese chat y nunca debe tomar su propio mensaje por órdenes. Igual que un acuse, se comprueba que no tenga ninguna línea que empiece con «#insto ».
- `#insto ver catálogo` es una orden de **solo lectura**: no ejecuta nada en el teléfono, no avisa y no consume el freno. Si las respuestas están activas (en cualquiera de los dos modos), contesta `#insto-ok ver catálogo → Protocolo Insto v8 · rutinas habilitadas: Estudio.` (no el catálogo entero). Desde 1.18.0, si hay un [catálogo vivo](#catálogo-vivo-1180) publicado, agrega `Catálogo publicado: hash <hash>.`
- Copias de referencia, generadas sin rutinas: [`instinct-catalog.txt`](instinct-catalog.txt) (canal WhatsApp) y [`instinct-catalog-bitacora.txt`](instinct-catalog-bitacora.txt) (con bitácora, la copia que viaja por WhatsApp, sin URL ni token). Un test compara las dos con lo que genera el código; para regenerarla, corré `INSTO_WRITE_CATALOG=1 python3 scripts/test-phone-core.py com.example.phone.InstinctRoutinesTest`.

## Órdenes diferidas (1.14.0)

El envoltorio solo dice **cuándo**; la orden interior pasa por el validador intacta, al recibirla y otra vez al dispararse.

```text
#insto a las HH:MM: <orden>       24 horas; si esa hora ya pasó hoy (o es justo ahora), es mañana
#insto en N minutos: <orden>      también «en N horas»; de 1 minuto a 24 horas
```

- **Los dos puntos después del tiempo son obligatorios.** Sin ellos la línea es una orden inmediata común, así que `#insto poner una alarma a las 07:00` conserva su significado.
- **Al recibirla** se valida la orden interior con `InstinctProposalValidator.check` en ese momento. Si es inválida, se descarta y se responde `#insto-error`. Si es válida, se guarda y se responde `#insto-programada <orden> → a las 22:00`. También te llega un aviso «Instinct programó: …». No se anidan envoltorios ni consultas.
- **Almacenamiento:** `instinct-scheduled.json` en `noBackupFilesDir` (con versión de formato desde 1.17.0, ver [Disparadores](#disparadores-por-estado-1170)), con escritura atómica (como `ConfiguredRoutineStore`): id, orden completa e interior (texto), hora de disparo en tiempo de pared, huella de la configuración, condición opcional y un token aleatorio. Como máximo **10 programadas** y **24 horas** a futuro. Una restauración nunca las trae.
- **Disparo:** `AlarmManager.setAndAllowWhileIdle` (`setExactAndAllowWhileIdle` solo si `canScheduleExactAlarms`) hacia `InstinctScheduleReceiver`, que no está exportado; la alarma lleva el token de la orden. Se reprograma todo en `BOOT_COMPLETED` y cuando cambia la hora (`TIME_SET`) o la zona horaria (`TIMEZONE_CHANGED`). La hora guardada es un instante: si cambiás de zona, se dispara en el mismo instante, no a la misma hora local.
- **Al dispararse:**
  1. Se marca **consumida en disco antes de cualquier otra cosa**: cada programada se ejecuta como máximo una vez. Si el proceso muere en el medio, al reiniciar se avisa «Pudo haberse ejecutado… no se repite» y nunca se vuelve a ejecutar (es un resultado incierto).
  2. Se vuelve a validar desde cero: interruptor encendido, huella vigente y orden válida con el parser de ese momento. Si algo falla, no se ejecuta y se avisa por qué.
  3. Si tiene condición, se evalúa ([Condiciones por clima](#condiciones-por-clima-1140)).
  4. Si llegó con **más de 15 minutos de atraso** (Doze, teléfono apagado), no se ejecuta sola: queda para Confirmar con el motivo «Llegó tarde».
  5. Si no, pasa por `autoRunBlocker` y `runInBackground` como cualquier orden (o al panel, si está abierto).
- **Gestión:** `#insto ver programadas` (solo lectura, responde por el acuse con un id corto, el disparador y la orden de cada una), `#insto cancelar programada <id>` (1.17.0) y `#insto cancelar programadas` (cancela todas las que vinieron de Instinct, recurrentes incluidas). En **Ajustes → Propuestas de Instinct → Órdenes programadas por Instinct** ves la lista con hora y resumen y podés cancelar cada una. Apagar «Recibir órdenes de Instinct» las cancela todas.
- **Acuse al dispararse:** por el chat, solo si la acción «Responder» del mensaje original sigue vigente (10 minutos), es decir, en la práctica para demoras cortas. Con la [bitácora](#bitácora-1150) se registra siempre, aunque hayan pasado horas o se haya reiniciado el teléfono (la programada guarda el identificador corto del mensaje).

## Condiciones por clima (1.14.0)

Solo con una hora de disparo y **una sola condición por línea**, sin «y», «o» ni anidamiento:

```text
#insto a las 06:30 si llueve: <orden>
#insto a las 06:30 si no llueve: <orden>
#insto a las 06:30 si hace menos de N grados: <orden>
#insto a las 06:30 si hace más de N grados: <orden>
```

- **«Llueve»** = probabilidad de lluvia del pronóstico para las **próximas 6 horas ≥ 50 %** (`WeatherConditions.RAIN_THRESHOLD_PERCENT`). Se toma el bloque más alto de la ventana. El SMN publica rangos: si el rango completo está en 50 % o más, llueve; si está todo por debajo, no llueve; si cruza el umbral (por ejemplo, 40–70 %), es **ambiguo** y cuenta como sin datos. Open-Meteo, el respaldo, publica valores exactos por hora.
- **Temperatura** = la **observación actual** (no la sensación térmica ni el pronóstico), con la misma regla de frescura que la consulta común (más de 3 horas es vieja). «Menos de» y «más de» son estrictos: con 10 grados, «si hace menos de 10 grados» no se cumple.
- La condición se evalúa **en el momento del disparo**, con el SMN y el respaldo que ya existen. `SmnWeatherService` expone valores estructurados (`WeatherReadings.currentTemperature()` y `rainChance()`, números), no el texto de `SmnWeatherReply`; no hay clientes nuevos.
- **Fail-safe:** si el clima no está disponible, tarda más de 20 segundos o el dato es ambiguo, **no se ejecuta**. Te avisa y responde `#insto-error … → sin datos del clima`. Nunca se toma un valor por defecto. Si la condición no se cumple, tampoco se ejecuta: te avisa y responde `#insto-descartada … → no se cumplió «si llueve»`.

## Deshacer (1.14.0)

La notificación «Instinct hizo: …» trae la acción **«Deshacer»** solo para comandos con estado reversible:

| Comando | Qué restaura |
| --- | --- |
| `Volume` | El volumen multimedia anterior. |
| `Torch` | El estado inverso (Android no deja leer el estado de la linterna). |
| Silencio, vibración, No molestar | El modo de timbre o el filtro de interrupciones anterior. |
| `TemporaryMode` | El estado anterior, terminando su propio *lease* con `TemporaryModeManager`. |

- **Nunca pisa un cambio manual:** si después de la orden tocaste ese estado (volumen, modo de timbre, No molestar o el modo temporal), Deshacer no hace nada y lo dice («No toqué lo que cambiaste después: …»). Para el modo temporal usa `TemporaryModeManager.current` y la misma regla del *lease* (`TemporaryModePolicy.canRestore`).
- Alarmas, temporizadores, eventos, apps y búsquedas **no tienen Deshacer**.
- El estado anterior vive **en memoria**, con id y **token aleatorio** como en `ProposalBook`; la acción viaja en un `PendingIntent` a `InstinctProposalReceiver`, que no está exportado. **Vence a los 10 minutos y se usa una sola vez.**
- **Rutinas:** Deshacer revierte los pasos reversibles en **orden inverso** y avisa cuáles no se podían revertir («No se pueden deshacer: Temporizador…»).
- Si los acuses están activos y la acción «Responder» sigue vigente, se responde `#insto-deshecho <orden> → <qué se deshizo>`.
- Un modo temporal pedido por Instinct siempre espera tu confirmación (como cualquier modo temporal), así que en la práctica no se ejecuta en segundo plano ni trae este botón; el soporte queda listo para cuando se ejecute por ese camino.

## Bitácora (1.15.0)

Con los acuses en el chat, cada orden suma un mensaje a la conversación con Instinct. La bitácora los anota en una planilla de Google, a través de un Apps Script tuyo, y el chat queda solo para la conversación. **El camino de entrada no cambia:** las órdenes `#insto` siguen llegando por WhatsApp y pasan por los mismos filtros, la misma lista blanca y la misma ejecución.

**Ajuste:** **Ajustes → Propuestas de Instinct → «Canal de estados»**: **WhatsApp** (valor por defecto, el comportamiento de 1.14.0), **Bitácora** o **Ambos**. Vive en `noBackupFilesDir` (`instinct-proposals-ack-channel`): una restauración nunca lo trae. «Responderle a Instinct» (Nunca / Solo errores y esperas / Siempre) sigue decidiendo **qué** se informa; el canal decide **por dónde**. Con «Nunca» no se anota nada.

- Con **Bitácora**, Insto **nunca** lee ni usa la acción «Responder» de WhatsApp: ni siquiera la guarda en memoria. «Enviarlas a Instinct» no aparece.
- Con **Ambos**, el chat recibe lo mismo que en 1.14.0 (mientras la acción «Responder» siga vigente) y la bitácora recibe todo.

### Configuración

1. Creá una planilla de Google y abrí **Extensiones → Apps Script**. Pegá [`instinct-bitacora.gs`](instinct-bitacora.gs) y cambiá `TOKEN` por un texto largo y aleatorio.
2. **Implementar → Nueva implementación → Aplicación web**, «Ejecutar como: yo», «Quién tiene acceso: cualquier usuario». Copiá la URL.
3. En Insto, **Ajustes → Propuestas de Instinct → Bitácora**: pegá la URL (tiene que empezar con `https://script.google.com/macros/s/` y terminar en `/exec`; si no, no se guarda) y el token (de 8 a 200 caracteres, sin espacios). **«Guardar bitácora»**.
4. **«Probar bitácora»** manda un evento `kind=prueba` y muestra el resultado.

La URL y el token viven en `noBackupFilesDir` (`instinct-log-url`, `instinct-log-token`) y nunca entran en copias de seguridad. El token nunca se muestra entero (solo los últimos 4 caracteres: «••••abcd»), el campo para escribirlo arranca vacío (dejarlo vacío conserva el guardado) y nunca se registra en Logcat ni en el diagnóstico.

### Evento

Cada acuse es un evento JSON:

```json
{"id": "6f1c…", "at": "2026-11-21T09:30:12-03:00", "kind": "ok", "order": "poner el volumen al 30 por ciento",
 "detail": "Volumen multimedia ajustado.", "message": "3fa94c0b12de", "protocol": "Protocolo Insto v6"}
```

- `id`: UUID aleatorio generado al crear el evento. El script descarta los ids repetidos y los confirma igual: un reintento nunca duplica una fila.
- `at`: el instante, ISO-8601 con zona.
- `kind`: el prefijo del acuse sin `#insto-`: `ok`, `espera`, `error`, `incierto`, `programada`, `descartada`, `vencida`, `deshecho` (y `prueba`, solo desde el botón).
- `order`: la orden original recortada a 80 caracteres, como en el chat.
- `detail`: el mismo texto de resultado del acuse en el chat (`InstinctAck`), con los mismos recortes y el mismo aplanado de saltos de línea y caracteres de control.
- `message`: los primeros 12 dígitos hexadecimales del SHA-256 que ya usa el dedupe (clave de la notificación más texto). Identifica el mensaje de Instinct que originó la orden; **nunca es el texto**.
- `protocol`: «Protocolo Insto v6» (v5 en 1.17.0, v4 en 1.16.0, v3 hasta 1.15.0).

**Guardia anti-bucle:** ningún campo puede empezar con «#insto » ni tener una línea que el lector de órdenes tome por una orden. Un evento que no pase la guardia no se crea y la bandeja tampoco lo acepta. Las respuestas a consultas (`ver catálogo`, `ver programadas`, `cancelar programadas`) también son eventos (`kind=ok`).

### Envío

- **Bandeja de salida persistente:** `instinct-outbox.json` en `noBackupFilesDir`, con escritura atómica como `ConfiguredRoutineStore`. Como máximo **200 eventos**; si se llena, se descarta el más viejo y el diagnóstico lo dice («se descartaron N por bandeja llena»).
- **Un POST por lote** de hasta 50 eventos, con OkHttp, cuerpo `{token, events}`. Apps Script responde con un **302** a `script.googleusercontent.com`: Insto lo sigue con un **GET** (solo a los hosts de Google y por HTTPS, como máximo 2 saltos) y lee el JSON final `{ok, accepted:[ids]}`. **Solo los ids aceptados** salen de la bandeja.
- **Reintentos** con `JobScheduler` del sistema (sin dependencias nuevas): restricción de red y *backoff* exponencial desde 30 segundos. Un token rechazado o una configuración faltante no se reintenta solo: espera a que la corrijas (guardarla vuelve a programar el envío). En `BOOT_COMPLETED` se reprograma si quedan pendientes.
- **Timeout:** 15 segundos para todo el intercambio.
- **Un fallo nunca bloquea** la ejecución de una orden: el evento solo se agrega a la bandeja. Tampoco dispara un envío por WhatsApp como reemplazo.

### Seguimientos sin límite de tiempo

Con la acción «Responder», los seguimientos solo salen durante 10 minutos. Con la bitácora ese límite no existe: confirmada, descartada, vencida, el disparo de una diferida y Deshacer se registran siempre que el modo los incluya, aunque haya pasado más tiempo. Las líneas de cada mensaje se recuerdan en memoria hasta 25 horas (una diferida se dispara como mucho 24 horas después); la acción «Responder», en cambio, se olvida a los 10 minutos como siempre. Una diferida guarda el identificador corto de su mensaje, así que su resultado se registra aunque el proceso se haya reiniciado. Una orden en espera se registra como `vencida` al cumplir sus 10 minutos.

### Para Instinct

Con Bitácora o Ambos, el catálogo («Protocolo Insto v6») reemplaza la explicación de los prefijos por cómo leer la bitácora:

```text
GET <URL>?token=<token>&since=<ISO>&limit=50
→ {ok, events:[{recibido, evento_id, ocurrido, tipo, orden, detalle, mensaje_id, protocolo}]}
```

- **«Copiar instrucciones para Instinct»** incluye la URL y el token reales, porque Instinct los necesita para leerla.
- **«Enviarlas a Instinct»** por WhatsApp **no** los incluye: en su lugar dice «(URL y token: pedíselos a Pablo)». Antes de enviarla, Insto comprueba además que el texto no contenga ni la URL ni el token.
- Se mantienen las reglas: los eventos son informativos, una sola orden corregida después de un `error`, nunca repetir después de `incierto` o `espera`. Se agrega: **antes de repetir o corregir una orden, consultá la bitácora.**

### Diagnóstico

Una línea más, sin contenido: «Bitácora: última escritura HH:MM · pendientes N» o «Bitácora: no se pudo escribir (motivo: sin red / token rechazado / respuesta inválida / HTTP NNN / falta la URL o el token) · pendientes N». Aparece con Bitácora o Ambos, o mientras queden eventos en la bandeja.

## Estado del teléfono (1.16.0)

Hasta 1.15.0 Instinct actuaba a ciegas. Ahora Insto publica **siempre** un resumen del teléfono en la bitácora: si la bitácora está configurada y «Recibir órdenes de Instinct» está encendido, el estado se publica. No hay interruptores por campo: los campos son fijos y de solo lectura.

| Campo | Contenido | Fuente |
| --- | --- | --- |
| `sonido` | modo de timbre: normal, vibración o silencio | `AudioManager` |
| `dnd` | si No molestar está activo | `NotificationManager` |
| `alarma` | hora de la próxima alarma (solo la hora, nunca la etiqueta) o «ninguna» | `AlarmManager.getNextAlarmClock()` |
| `evento` | título, inicio y fin del evento en curso y del próximo en las 12 horas siguientes, o «libre» | `CalendarContract.Instances` (sin los eventos de todo el día) |
| `audio` | si hay auriculares o audio Bluetooth (sí/no y tipo, nunca el nombre del dispositivo) | `AudioManager.getDevices` |
| `bloqueado` | si la pantalla está bloqueada | `KeyguardManager` |
| `modo_temporal` | si hay un modo temporal activo y hasta qué hora | `TemporaryModeManager` |
| `programadas` | cuántas órdenes de Instinct hay programadas y la próxima hora | `InstinctScheduler` |

- **Nunca** se incluyen volumen, batería, red ni wifi, ni ubicación, contactos, contenido de notificaciones, uso de apps, SSID, nombres de dispositivos ni ningún identificador.
- `evento` necesita `READ_CALENDAR`. Se pide una sola vez, desde **Ajustes → Propuestas de Instinct → Estado para Instinct → «Permitir leer el calendario»**, con una línea que explica para qué es. Sin permiso el campo sale `sin permiso` y Insto no lo vuelve a pedir solo.
- Si un campo falla al leerse, sale `sin datos` y el snapshot igual se publica.
- La lógica pura (`InstinctStateSnapshot`, `InstinctState`, `InstinctStateDebouncer`) está separada del lector de Android (`InstinctStateReader`).

### Publicación

Va como un evento de bitácora `kind=estado`, por la misma bandeja, el mismo envío y los mismos reintentos. `order` y `message` quedan vacíos; `detail` lleva una línea compacta, recortada a 600 caracteres:

```text
sonido=vibración; dnd=no; alarma=07:00; evento=ahora Clase 17:00–18:00; audio=no; bloqueado=sí; modo_temporal=no; programadas=ninguna
```

Pasa por la misma guardia anti-bucle que los acuses: cada valor se aplana (saltos de línea y caracteres de control a espacios), se le quitan `#`, `;` y `=` (así un título de evento no puede escribir «#insto …» ni inventar campos) y el evento entero pasa por `InstinctLogEvents.safe`.

**Cuándo se publica:**

1. **Cuando cambia** algún campo: `RINGER_MODE_CHANGED`, `ACTION_INTERRUPTION_FILTER_CHANGED`, `NEXT_ALARM_CLOCK_CHANGED`, la conexión y desconexión de audio (`AudioDeviceCallback` y `ACTION_HEADSET_PLUG`), `USER_PRESENT`, `SCREEN_OFF` y los cambios del calendario (un `ContentObserver` sobre el proveedor del calendario, que incluye `Instances`, solo con el permiso). Se registran en tiempo de ejecución desde «Insto · notificaciones», que ya está vivo. Los cambios se agrupan 30 segundos y solo se publica si algo difiere del último estado enviado.
2. **Cada 30 minutos** como latido, aunque no cambie nada, con un job periódico de `JobScheduler` con restricción de red. Si otro motivo lo publicó hace menos de 25 minutos, el latido se saltea.
3. **Después de cada orden de Instinct** que se ejecutó (o con resultado incierto), un segundo y medio después, para que vea el efecto.
4. Con **`#insto ver estado`**: consulta de solo lectura, no consume el freno y publica aunque no haya cambios. El acuse es `#insto-ok ver estado → estado publicado en la bitácora; leelo con action=estado.`

**Sale solo por la bitácora**, nunca por WhatsApp, sea cual sea el «Canal de estados». Con el canal en WhatsApp, `ver estado` responde `#insto-error ver estado → el estado solo se publica en la bitácora` (y, si la bitácora está configurada, igual lo publica ahí). Sin bitácora no se publica nada y el diagnóstico lo dice.

El último estado publicado se guarda, con su hora, en `instinct-state-last.json` (`noBackupFilesDir`), para comparar. En Ajustes hay una sola línea informativa («Estado para Instinct: último envío HH:MM») y el botón **«Publicar estado ahora»**. El latido se cancela al apagar «Recibir órdenes de Instinct» o borrar la bitácora, y se reprograma en `BOOT_COMPLETED`.

### Apps Script

`GET ?token=…&action=estado` devuelve `{ok, estado}`, con el último evento `estado` (o `null`). Un `GET` sin `action` se comporta exactamente como en 1.15.0.

### Para Instinct

Con la bitácora, el catálogo («Protocolo Insto v6») suma la sección «Estado del teléfono»: qué campos hay, cómo leerlos (`action=estado`) y la regla **«antes de una orden que dependa del estado (silencio, No molestar, alarmas, calendario), mirá el último estado; si tiene más de 30 minutos, pedí la consulta «ver estado» y esperá»**. Con el canal en WhatsApp esa sección no aparece, porque Instinct no tiene cómo leer la bitácora.

## Buzón firmado (1.16.0)

Hasta 1.15.0 una orden solo entraba si WhatsApp publicaba una notificación: no llegaba si tenías el chat abierto, costaba un mensaje en la conversación y el nombre del contacto no prueba quién la manda. El buzón agrega una entrada por la misma planilla, **con firma**.

### Modelo de seguridad

- **Clave de órdenes:** un secreto HMAC-SHA256 que conocen solo Insto e Instinct. **No vive en el Apps Script ni en la planilla.** Aunque alguien consiga el token de la bitácora o acceso a la planilla, no puede inyectar órdenes: sin la clave no puede firmarlas.
- Insto la guarda en `noBackupFilesDir` (`instinct-mailbox-key`). Nunca se muestra entera («••••abcd»), nunca va a Logcat, al diagnóstico ni a WhatsApp, y el campo para escribirla arranca vacío. **«Generar clave»** crea 32 bytes aleatorios de `SecureRandom` en base64url (43 caracteres) y los copia; **«Copiar clave»** la copia al portapapeles. También podés pegar una propia (de 32 a 200 caracteres, sin espacios) y tocar «Guardar clave».
- **Cadena canónica firmada**, con `\n` entre partes y sin uno al final:

  ```text
  insto-v4
  <id>          un UUID nuevo por orden
  <at>          ISO-8601 con zona, por ejemplo 2026-11-21T09:30:00-03:00
  <línea 1>     las líneas «#insto …» tal cual
  <línea 2>…
  ```

  La firma es el HMAC-SHA256 de esa cadena (UTF-8) con la clave (UTF-8), en hexadecimal en minúsculas. Insto la compara en tiempo constante (`MessageDigest.isEqual`).
- **Se rechaza** una orden con firma inválida (o mal formada: id que no es UUID, `at` sin zona), con `at` de hace más de 15 minutos o más de 2 minutos en el futuro, o con un `id` ya visto. Los ids vistos se guardan **48 horas en disco** (`instinct-mailbox-seen.json`): la deduplicación sobrevive a un reinicio. Una orden con firma inválida **no se ejecuta ni se responde a nadie**: solo suma al contador del diagnóstico.
- Se leen como máximo **3 líneas por orden** (como un mensaje de WhatsApp: las demás se ignoran) y **10 órdenes por sondeo**.

### Apps Script

`instinct-bitacora.gs` suma la hoja **`Buzón`** (`recibido, orden_id, at, lineas, firma, tomada`, en texto plano y con el mismo `cell_` contra fórmulas). Todo con `LockService`, como el `doPost` de siempre.

| Pedido | Qué hace |
| --- | --- |
| `POST {token, action:"orden", id, at, lines:[…], sig}` | Anota la orden, deduplicando por `id`. **No verifica la firma** (no tiene la clave): solo guarda. |
| `GET ?token=…&action=ordenes&limit=10` | Devuelve `{ok, ordenes:[{recibido, orden_id, at, lineas, firma}]}`: las no tomadas, en orden. |
| `POST {token, action:"tomadas", ids:[…]}` | Las marca como tomadas. |

Un `POST` sin `action` (los eventos de Insto) y un `GET` sin `action` funcionan exactamente igual que en 1.15.0: podés actualizar el script antes o después de actualizar Insto.

### Sondeo

- `InstinctMailboxJobService`, con `JobScheduler`: un job de una sola vez, con restricción de red, que se vuelve a programar al terminar. Intervalo de **2, 5 (por defecto) o 15 minutos**. Android puede demorarlo con el teléfono en reposo (Doze).
- Además se sondea enseguida al abrir Insto o el panel flotante, y cuando llega **cualquier** notificación de WhatsApp del contacto de Instinct (aunque no traiga `#insto`). Dos sondeos inmediatos quedan separados por al menos 20 segundos, para no gastar cuota.
- Se reprograma en `BOOT_COMPLETED` (en el mismo receiver de arranque de la bitácora). Apagar «Recibir órdenes de Instinct» o el buzón cancela el job.
- **Ciclo:** `GET ordenes` → verificar cada una → marcar los ids válidos como vistos **en disco antes de despachar** → `POST tomadas` con **todas** las leídas, también las rechazadas, para que no vuelvan → despachar las válidas. Si el `POST tomadas` falla, no pasa nada: los ids ya están vistos y no se repiten.
- Usa el transporte de `InstinctLogClient`: la redirección 302 a `script.googleusercontent.com`, solo hosts de Google por HTTPS, como máximo 2 saltos y 15 segundos.

### Mismo camino de ejecución

Una orden del buzón entra a `InstinctProposalCenter` por el mismo punto que una de WhatsApp, con `ProposalSource.BUZON`, y pasa por el mismo validador, la misma lista blanca, el mismo freno anti-bucle (**compartido** entre las dos fuentes), la [autonomía](#autonomía-por-acción-1160), la misma ejecución, Deshacer y diferidas (una diferida guarda su fuente).

- El `message` de sus eventos es `InstinctLogEvents.messageId` del SHA-256 del `id` de la orden.
- **Sus acuses van siempre a la bitácora**, sea cual sea el «Canal de estados»: no hay notificación de WhatsApp a la que responder. El modo («Nunca / Solo errores y esperas / Siempre») sigue decidiendo qué se informa. Con «Nunca», Ajustes te sugiere subirlo, porque sin acuses Instinct no se entera de nada.

### `#insto avisarme <texto>`

Una notificación local de Insto para vos, en el canal nuevo **«Insto · mensajes de Instinct»**. Así Instinct te puede hablar sin escribir en WhatsApp.

- Texto de 1 a 200 caracteres, aplanado a una línea, en texto plano: nada se puede tocar salvo la notificación.
- **Solo desde el buzón.** Desde WhatsApp: `#insto-error avisarme … → avisarme solo por el buzón`. No se puede diferir.
- Su propio freno: **3 por hora** (el cuarto responde `#insto-error … → ya hubo 3 avisos en la última hora; no se mostró.`).
- Sin Deshacer. Categoría **Avisos para vos**: con «Nunca» se rechaza; dentro de las horas de silencio (o con «Confirmar», porque no hay nada que confirmar) se publica **sin sonido**, en «Insto · mensajes de Instinct (horas de silencio)».

### Ajustes del buzón

**Ajustes → Propuestas de Instinct → Buzón**:

- **«Recibir órdenes por el buzón»** (apagado; requiere la bitácora configurada y la clave guardada).
- **«Aceptar órdenes de»: WhatsApp y buzón** (por defecto) o **Solo buzón**. Con «Solo buzón» las líneas `#insto` de WhatsApp se ignoran y el diagnóstico lo dice. Es la forma de cerrar el riesgo de que alguien use el nombre del contacto.
- **«Las órdenes por WhatsApp (sin firma) como máximo en»: Solo** (por defecto) o **Confirmar**. Es un tope que se combina con la autonomía: con «Confirmar», una orden de WhatsApp que haría sola espera con el motivo «las órdenes por WhatsApp, sin firma, esperan confirmación». Las del buzón no tienen ese tope.
- El intervalo de sondeo, «Generar clave», «Copiar clave» y **«Probar buzón»**, que hace un `GET ordenes` sin tomar ni ejecutar nada y muestra cuántas hay pendientes.

### Catálogo y diagnóstico

- Con el buzón encendido, el catálogo explica cómo firmar y publicar una orden, con un ejemplo de cadena canónica y un pseudocódigo HMAC. **Nunca incluye la clave**, tampoco «Copiar instrucciones»: la clave se copia aparte con su botón. La copia que viaja por WhatsApp sigue sin URL, sin token y sin clave, y antes de enviarla Insto comprueba que no contenga ninguna de las tres. Copia de referencia: [`instinct-catalog-buzon.txt`](instinct-catalog-buzon.txt).
- Diagnóstico, sin contenido: «Buzón: último sondeo HH:MM · órdenes N · firmas inválidas N · vencidas N», o el motivo del fallo (sin red, token rechazado, respuesta inválida, HTTP NNN, falta la URL o el token, falta la clave).

### Prueba manual del Apps Script con `curl`

Con la URL y el token de tu implementación (`-L` sigue la redirección de Google; en un `POST`, `curl` la sigue con `GET`, como Insto):

```sh
URL='https://script.google.com/macros/s/<id>/exec'
TOKEN='<token>'
# 1.15.0 sigue igual: eventos desde una fecha.
curl -sL "$URL?token=$TOKEN&since=2026-01-01T00:00:00Z&limit=5"
# El último estado.
curl -sL "$URL?token=$TOKEN&action=estado"
# Una orden (el script no verifica la firma: para probarlo alcanza cualquier hex de 64).
curl -sL -H 'Content-Type: application/json' \
  -d '{"token":"'"$TOKEN"'","action":"orden","id":"3b241101-e2bb-4255-8caf-4136c566a962","at":"2026-11-21T09:30:00-03:00","lines":["#insto ver estado"],"sig":"'"$(printf '0%.0s' $(seq 64))"'"}' "$URL"
# Las pendientes: tiene que aparecer la de arriba.
curl -sL "$URL?token=$TOKEN&action=ordenes&limit=10"
# Marcarla como tomada; después «ordenes» ya no la devuelve.
curl -sL -H 'Content-Type: application/json' -d '{"token":"'"$TOKEN"'","action":"tomadas","ids":["3b241101-e2bb-4255-8caf-4136c566a962"]}' "$URL"
# Un token incorrecto: {"ok":false,"error":"token"}.
curl -sL "$URL?token=malo&action=ordenes"
```

Si Insto sondea mientras tanto, va a tomar esa orden de prueba y contarla como «firma inválida» (es lo esperado: no se ejecuta ni se responde).

## Timbre (1.17.0)

Con el buzón, una orden tarda lo que tarde el próximo sondeo: de 5 a 15 minutos, o más con Doze. El **timbre** es un push que solo dice «hay algo en el buzón». **No lleva órdenes** (salvo que actives la [orden directa](#orden-directa-en-el-timbre-1180), 1.18.0): cuando suena, Insto lee la planilla y verifica la firma exactamente como en cualquier sondeo, así que el modelo de seguridad del buzón no cambia. Quien toca el timbre es tu Apps Script, al anotar una orden nueva; Instinct no tiene que hacer nada distinto.

### Transporte: UnifiedPush con ntfy

- **Desde 1.19.0**, el lado *connector* de [UnifiedPush](https://unifiedpush.org/) es la biblioteca oficial `org.unifiedpush.android:connector` (ver [1.19.0](#timbre-cifrado-y-transporte-confiable-1190)). Hasta 1.18.0 Insto lo implementaba a mano (`InstinctDoorbellReceiver`); lo que sigue describe ese diseño, que 1.19.0 reemplaza en registro, tokens, acuses y cifrado. Sin Firebase ni Google Play Services.
- El distribuidor es la app **ntfy**. Insto busca los distribuidores instalados (las apps que reciben `org.unifiedpush.android.distributor.REGISTER`). Si hay varios, se elige uno en Ajustes; si no hay ninguno, dice «Instalá ntfy para usar el timbre».
- **Registro:** `REGISTER` con un token aleatorio (UUID) por registro. En Android 14 o posterior, el broadcast comparte la identidad de Insto (`setShareIdentityEnabled`); antes, va el `PendingIntent` `pi` inmutable que pide la especificación. Se agrega `application` (el nombre del paquete) para distribuidores anteriores a AND_3. Al reiniciar el teléfono se vuelve a registrar con el mismo token.
- **El receptor está exportado**, porque el distribuidor es otra app. Todo pedido con un token que Insto no registró se ignora. Un `MESSAGE` **ignora el contenido**: lo mande quien lo mande y diga lo que diga, lo único que hace es sondear el buzón. Si trae `id`, se confirma con `MESSAGE_ACK`.
- **El endpoint** (`NEW_ENDPOINT`) se guarda en `noBackupFilesDir` (`instinct-doorbell-endpoint`, escritura atómica). Nunca va a Logcat, al diagnóstico, al catálogo ni a WhatsApp; en Ajustes aparece enmascarado («••••» y los últimos 6 caracteres). El resto del estado (token, contadores, último aviso al script) vive en `instinct-doorbell-state.json`, sin el endpoint.
- **`UNREGISTERED`, `REGISTRATION_FAILED` y un endpoint nuevo:** se borra o se reemplaza el endpoint guardado y se le vuelve a informar al script (vacío, para que lo borre). Después de un `REGISTRATION_FAILED`, el próximo registro usa un token nuevo, como pide la especificación. Al apagar el timbre se manda `UNREGISTER` y el token se olvida enseguida.

### Apps Script

| Pedido | Qué hace |
| --- | --- |
| `POST {token, action:"timbre", endpoint}` | Guarda el endpoint en `PropertiesService.getScriptProperties()`, **no en la planilla**. Tiene que empezar con `https://` y tener como máximo 500 caracteres; si no, `{ok:false, error:"endpoint"}`. Con `endpoint` vacío lo borra. Responde `{ok, timbre:"guardado"}` o `{ok, timbre:"borrado"}`. |
| `POST {token, action:"probar-timbre"}` | Toca el timbre ahora y responde `{ok, timbre:true|false, codigo}` (con `codigo:-1` y `detalle`, el error de Google, si el pedido ni salió), o `{ok:false, error:"sin-endpoint"}`. |
| `POST {token, action:"orden", …}` | Igual que en 1.16.0. Además, después de anotar una orden **nueva** (no una repetida), hace `UrlFetchApp.fetch(endpoint, {method:"post", payload:"insto-timbre", muteHttpExceptions:true})`, como mucho una vez cada 5 segundos (la última hora queda en las propiedades del script). Si el fetch falla, la orden queda anotada igual y la respuesta sigue siendo `{ok, id}`. |

Un `POST` o un `GET` sin estas acciones se comporta exactamente igual que en 1.16.0. Con un script viejo, `action:"timbre"` cae en la rama de los eventos y responde `{ok:true, accepted:[]}`: Insto lo toma como «el script no confirmó el endpoint» y lo dice en el diagnóstico, sin reintentar. La prueba del script sin Google está en `scripts/test-bitacora-gs.js` (`node scripts/test-bitacora-gs.js`; `scripts/test-phone-core.py` la corre si hay Node).

**Cuándo Insto le avisa al script:** cuando recibe el endpoint, cuando encendés el timbre, cuando el endpoint cambia o se borra y cuando guardás la bitácora. Usa el transporte de `InstinctLogClient` (la misma política de redirección, solo hosts de Google por HTTPS, 15 segundos). Si falla por red o HTTP, reintenta con `JobScheduler`, como la bandeja; un token rechazado o un script viejo esperan a que lo corrijas.

### Qué hace Insto cuando suena

- Con `goAsync()`, sondea el buzón enseguida, fuera del hilo principal. (Hasta 1.18.0 el hilo principal esperaba hasta 20 segundos; desde 1.19.0 no espera nada: ver [1.19.0](#timbre-cifrado-y-transporte-confiable-1190).)
- **Ningún timbre se pierde por el espaciado.** Para el timbre, la separación mínima entre sondeos es de **5 segundos** (no los 20 de los sondeos inmediatos al abrir Insto o al llegar un mensaje de WhatsApp). Si suena dentro de esa ventana, se programa un sondeo para cuando termine. Si suena mientras hay un sondeo en curso, ese sondeo vuelve a leer el buzón al terminar (pudo haber leído la planilla antes de que se anotara la orden). Sin red, queda un job con restricción de red: *expedited* desde Android 12 (`setExpedited`), común en versiones anteriores.
- **Intervalo de respaldo:** mientras el timbre está activo y sonó en las últimas 24 horas, el sondeo periódico pasa a ser cada **15 minutos**, aunque hayas elegido 2 o 5. Así se ahorra cuota de Apps Script. Si en 24 horas no suena ninguna vez, vuelve al intervalo que elegiste.

### Ajustes, diagnóstico y catálogo

- **Ajustes → Propuestas de Instinct → Buzón → «Timbre instantáneo (ntfy)»**: apagado por defecto; requiere el buzón encendido y un distribuidor. Con varios distribuidores, se elige uno. Una línea de estado: «Endpoint ••••abc123 · avisado al script HH:MM» (o «todavía no se avisó al script»). **«Probar timbre»** le pide al script que lo toque (`probar-timbre`) y muestra si llegó en menos de 30 segundos.
- **Diagnóstico**, sin contenido: «Timbre: último HH:MM · timbres N · sondeos por timbre N», o el motivo del fallo (sin distribuidor, registro fallido, el script no confirmó el endpoint, el distribuidor dio de baja el registro).
- **Catálogo:** con el timbre activo, la sección del buzón suma «Después de publicar una orden en el buzón, Insto la toma en segundos; no hace falta avisar por WhatsApp». Nunca incluye el endpoint.

### Configuración paso a paso

1. Instalá **ntfy** (F-Droid o Play Store). No hace falta crear una cuenta; podés usar `ntfy.sh` o tu propio servidor. En Android, sacale a ntfy la optimización de batería y, si lo pide, dejale activada la «conexión instantánea».
2. Actualizá el Apps Script con [`instinct-bitacora.gs`](instinct-bitacora.gs) de esta versión (conservá tu línea `const TOKEN`). **Autorizá el timbre una vez:** en el editor, elegí la función `autorizarTimbre` en la lista de arriba, tocá **Ejecutar** y aceptá el permiso «Conectarse a un servicio externo». Sin ese permiso, `UrlFetchApp` falla y «Probar timbre» dice «el script no pudo conectarse a ntfy». Después volvé a implementarlo (**Implementar → Gestionar implementaciones → Editar → Nueva versión**: así se conserva la URL).
3. En Insto, **Ajustes → Propuestas de Instinct → Buzón**, con el buzón ya funcionando, encendé **«Timbre instantáneo (ntfy)»**. ntfy registra a Insto y le da el endpoint; la línea de estado pasa a «Endpoint ••••… · avisado al script HH:MM».
4. Tocá **«Probar timbre»**: tiene que decir «El timbre llegó en N s».

### Prueba con `curl`

```sh
URL='https://script.google.com/macros/s/<id>/exec'
TOKEN='<token>'
# Que el script toque el timbre ahora (Insto sondea el buzón al recibirlo).
curl -sL -H 'Content-Type: application/json' -d '{"token":"'"$TOKEN"'","action":"probar-timbre"}' "$URL"
# Una orden de prueba: queda anotada y, como es nueva, el script toca el timbre (repetirla no lo toca).
curl -sL -H 'Content-Type: application/json' \
  -d '{"token":"'"$TOKEN"'","action":"orden","id":"3b241101-e2bb-4255-8caf-4136c566a962","at":"2026-11-21T09:30:00-03:00","lines":["#insto ver estado"],"sig":"'"$(printf '0%.0s' $(seq 64))"'"}' "$URL"
# Con el endpoint que ves en ntfy (Ajustes de ntfy → la suscripción de Insto), tocar el timbre a mano:
curl -s -d 'insto-timbre' '<endpoint>'
```

Al tocar el timbre a mano, el diagnóstico suma un timbre y un sondeo; la orden de prueba se descarta como «firma inválida», como en la prueba del buzón.

### Límites

- **Depende de que ntfy esté vivo.** Si el sistema mata a ntfy o le restringe la batería, el timbre no llega y queda el sondeo de respaldo. Si ntfy deja de suscribirse, el intervalo vuelve solo al que elegiste a las 24 horas sin timbres.
- El timbre solo acelera: nunca saltea la firma, la lista blanca, la autonomía ni el freno. Una orden que llega por el timbre es idéntica a una del sondeo.
- El script toca el timbre como mucho una vez cada 5 segundos; varias órdenes seguidas igual se leen todas en el mismo sondeo.
- ntfy reenvía el texto `insto-timbre` sin cifrar; como no lleva nada, no importa qué diga. Con la [orden directa](#orden-directa-en-el-timbre-1180) encendida, ntfy ve las líneas de la orden (no puede cambiarlas: la firma lo impide).

## Timbre cifrado y transporte confiable (1.19.0)

Diseño completo, auditoría y riesgos: [`architecture/instinct-push-reliability.md`](architecture/instinct-push-reliability.md).

### Recepción sin bloqueos

- `onReceive()` solo toma `goAsync()` y encola el trabajo en un hilo propio (`insto-push-rx`, cola de 16). Vuelve enseguida: nunca espera disco, red ni una ejecución. `PendingResult.finish()` se llama **una sola vez**, también si el trabajo falla o la cola está llena.
- El hilo receptor hace lo liviano: la biblioteca verifica el token y descifra; Insto clasifica y guarda el mensaje en la **cola durable** `instinct-push-inbox.json` (`noBackupFilesDir`, escritura atómica). Recién después la biblioteca manda `MESSAGE_ACK`: lo confirmado al distribuidor ya está en disco.
- Un despachador de un solo hilo (`insto-push-dispatch`) atiende la cola; el hilo receptor lo espera como mucho **8 segundos** y suelta el broadcast. Un job de rescate (30 s, sin red) vacía lo que quede si el proceso muere; también al reiniciar el teléfono y al abrir Insto.
- Cola acotada: 32 entradas, los timbres sin orden se funden en uno, una orden repetida no entra dos veces y una orden vence con la ventana de su firma (15 minutos). Los jobs del timbre corren en un solo ejecutor: ningún hilo por notificación.

### Una sola ejecución

- Una orden de la cola pasa por `InstinctMailboxSignature.verify` (firma, 15/2 minutos, id visto) y por `InstinctMailboxSeen.claimAll` sobre el registro que comparten el push, el sondeo periódico, el de respaldo, el que dispara WhatsApp y el de seguimiento. Solo el que gana la ejecuta, con la misma entrada que el sondeo (`InstinctProposalIntake.onMailbox`): mismo validador, autonomía, freno, «Deshacer», programadas y disparadores.
- Una orden recuperada después de un cierre se **revalida** (firma, vigencia, visto, buzón encendido y clave) antes de seguir. Si un proceso la reclamó y murió antes de entregarla, no se sabe si corrió: se informa `incierta` en la bitácora y **no se repite**.
- Si el registro de ids no se puede escribir, no se ejecuta nada: la orden se reintenta 3 veces y después queda para el sondeo.

### UnifiedPush con la biblioteca oficial

- `org.unifiedpush.android:connector` **3.3.6** (Apache 2.0; trae Tink 1.24.0). Maneja el token, el registro (identidad compartida en Android 14+, `pi` antes), `MESSAGE_ACK`, `UNREGISTERED`, los distribuidores de respaldo y las **claves de Web Push**: P-256 y un secreto `auth` por registro, con la privada cifrada por el Android Keystore. Descifra RFC 8291 (`aes128gcm`) con Tink.
- Insto declara su propio receptor (`InstinctPushReceiver`, prioridad 0): el de la biblioteca no corre y el acuse sale después de guardar.
- **Migración desde 1.18.0:** si queda el token del conector manual, se le manda `UNREGISTER` al distribuidor y se registra de nuevo con la biblioteca; el endpoint cambia y se le avisa al script. Interruptores, clave y bitácora se conservan.

### Dos modalidades cifradas

Apps Script no tiene ECDH ni AES-GCM, así que no puede cifrar RFC 8291 sin criptografía escrita a mano. Por eso:

| Modalidad | Qué viaja | Qué hace falta |
| --- | --- | --- |
| **A. Timbre cifrado simple** (por defecto) | `insto-timbre` cifrado por Insto para sus propias claves (Tink). El script lo publica tal cual con `Content-Encoding: aes128gcm`, `TTL: 300` y `Urgency: high`. Insto lee la orden de la planilla. | El `instinct-bitacora.gs` de 1.19.0. |
| **B. Timbre cifrado con orden** (opcional, apagada) | La orden firmada, cifrada por un relé propio en Cloudflare Workers (`relay/insto-webpush-relay`). Insto la ejecuta sin leer la planilla. | «Incluir la orden en el timbre» encendido y `RELAY_URL`/`RELAY_SECRET` en el script. Si el relé falla, suena la modalidad A. |

El cifrado protege el transporte; **la firma HMAC sigue autorizando la orden**. Ninguno reemplaza al otro.

- Cuando el script confirma el cifrado (`cifrado:true`), un mensaje que llega **en claro** ya no puede ejecutar una orden: solo hace revisar el buzón, y se cuenta como «sin cifrar».
- Con un script anterior a 1.19.0 el timbre sigue en claro, como en 1.18.0 (**modo heredado, no conforme con UnifiedPush**), y Ajustes lo dice.

### Apps Script (1.19.0)

| Pedido | Qué hace |
| --- | --- |
| `POST {token, action:"timbre", endpoint, conOrden, cifrado:{timbre[, p256dh, auth]} \| null}` | Además del endpoint, guarda el timbre cifrado (y, solo con la orden en el timbre, las claves públicas para el relé) en las propiedades del script. Responde `{ok, timbre, conOrden, cifrado, rele, cadaOrden:true}`. Sin `cifrado` (Insto 1.18.0) responde exactamente lo de antes. |
| `POST {token, action:"orden", …}` | Con el timbre cifrado guardado, **cada orden nueva toca el timbre**, sin la espera de 5 segundos y con un tope de 30 por minuto. Sin él, como en 1.18.0. |
| `POST {token, action:"probar-timbre"}` | Usa el timbre cifrado y agrega `cifrado:true`. |

### Latencia y reconciliación

- Orden directa: verificar, reclamar, ejecutar. Sin pedido a la planilla.
- Después, la planilla solo recibe `POST tomadas` con ese id (job con red y backoff). El sondeo de seguimiento a los 60 segundos queda solo para scripts anteriores a 1.19.0, que se saltean timbres dentro de los 5 segundos.
- En cualquier sondeo, la orden se ejecuta **antes** de marcar «tomadas».
- El sondeo periódico de respaldo sigue siempre: Insto nunca depende solo del push.

### Ajustes y diagnóstico

- **Ajustes → Propuestas de Instinct → Buzón → Timbre:** una línea de cifrado y **«Detalle del timbre»**: distribuidor, registro, cifrado, endpoint enmascarado, última recepción push, última orden directa validada, último sondeo del buzón, duplicados descartados, fallos de descifrado y de verificación, cola de recepción y último error, más una explicación cuando Insto funciona solo por sondeo.
- **«Probar de punta a punta»** publica en el buzón, firmada con tu clave, la orden de solo lectura `#insto probar ver estado` (no cambia el volumen, no crea alarmas, no ejecuta nada) y muestra por separado: registro, entrega del push, descifrado, verificación, despacho (por el timbre o por sondeo) y resultado.

### Para actualizar

1. Pegá el `instinct-bitacora.gs` de 1.19.0 (conservá `const TOKEN`) e implementá una versión nueva. Si el permiso de `UrlFetchApp` ya estaba dado, no hace falta repetir `autorizarTimbre`.
2. Actualizá Insto. Con el timbre encendido, se registra de nuevo con la biblioteca y le pasa al script el endpoint nuevo y el timbre cifrado.
3. Tocá «Probar de punta a punta». La línea de cifrado tiene que decir «Cifrado RFC 8291: activo».
4. (Opcional) Para la orden cifrada dentro del timbre, desplegá el relé siguiendo [`relay/insto-webpush-relay/README.md`](../relay/insto-webpush-relay/README.md).

## Disparadores por estado (1.17.0)

Hasta 1.16.0, para «cuando termine la clase, sacá el No molestar», Instinct tenía que vigilar el estado y mandar la orden en el momento justo. Ahora deja la orden **armada** y el teléfono la dispara solo. El envoltorio solo dice **cuándo**; la orden interior pasa por el validador intacta, al recibirla y otra vez al dispararse, como una diferida.

```text
#insto al terminar el evento: <orden>
#insto al empezar el próximo evento: <orden>
#insto al conectar auriculares: <orden>
#insto al desconectar auriculares: <orden>
#insto al desbloquear: <orden>
```

- **Los dos puntos son obligatorios** y cada línea admite **una sola condición**: sin «y», «o», sin clima y sin anidar otra diferida. `al conectar auriculares si llueve: …` o `al conectar auriculares y al desbloquear: …` se rechazan, nunca se toman por órdenes inmediatas.
- **Vencimiento:** todos aceptan `hasta las HH:MM` antes de los dos puntos (`#insto al conectar auriculares hasta las 20:00: abrir spotify`); si esa hora ya pasó hoy, es mañana. Sin tope, vencen a las **12 horas**; el máximo es de **24 horas**. Una orden que vence sin dispararse se borra, te avisa y se registra como `vencida` (`venció sin dispararse; no se ejecutó.`).
- **Cada disparador se usa una sola vez.** Se marca consumido en disco antes de ejecutarse, como cualquier programada.
- **Eventos.** `al terminar el evento` toma el evento **en curso**; `al empezar el próximo evento`, el próximo que empiece dentro de las **12 horas**. Los dos leen el calendario al recibir la orden (`CalendarContract.Instances`, sin los eventos de todo el día y sin leer títulos), guardan el id de la instancia, el del evento y la hora, y se programan con `AlarmManager`, como una diferida. Al dispararse vuelven a leer esa instancia: **si se movió**, se reprograma a la hora nueva (y se avisa); **si se borró**, se descarta y se responde `descartada … → el evento se borró; no se ejecutó.`. Sin permiso de calendario, o sin un evento en curso o próximo dentro del tope, la orden se rechaza al llegar con el motivo («no hay ningún evento en curso.», «Insto no tiene permiso para leer el calendario…»).
- **Auriculares y desbloqueo** se apoyan en los callbacks que ya registra `InstinctStateWatcher` para el [estado](#estado-del-teléfono-1160): `AudioDeviceCallback` y `ACTION_HEADSET_PLUG` (auriculares con cable, USB o Bluetooth) y `USER_PRESENT`. No hay receptores nuevos. Lo que ya estaba conectado cuando arranca el servicio no cuenta como «conectar» (ni el broadcast fijo inicial de `HEADSET_PLUG`). **Solo funcionan mientras «Insto · notificaciones» esté vivo**; su alarma es la del vencimiento, que se rearma en `BOOT_COMPLETED`.
- **Acuses:** `#insto-programada <orden> → al conectar auriculares, hasta las 20:00` al recibirla; al dispararse, el acuse normal (`ok`, `error`, `descartada`, `incierto`) con el mismo `message` de la orden original.

### Modelo y almacenamiento

`Deferred` y `ScheduledOrder` llevan un disparador explícito (`Trigger`: `At`, `EventEnd`, `EventStart`, `HeadphonesOn`, `HeadphonesOff`, `Unlock`, `Recurring`), el vencimiento y la fuente. `instinct-scheduled.json` pasó a `{"version": 2, "orders": […]}`; un archivo de 1.16.0 (un arreglo sin versión) se lee igual y cada orden sin disparador es `At`: no se pierde nada, y se reescribe en el formato nuevo la próxima vez que cambia. La lógica de decidir (`ScheduleFirePolicy`, `InstinctTriggers`: próxima ocurrencia, vencimiento, si un evento coincide) es pura y está separada de `AlarmManager` y del calendario.

## Órdenes recurrentes (1.17.0)

```text
#insto todos los días a las HH:MM hasta el DD/MM: <orden>
#insto los días hábiles a las HH:MM hasta el DD/MM: <orden>
#insto los fines de semana a las HH:MM hasta el DD/MM: <orden>
```

- **`hasta el DD/MM` es obligatorio:** no hay recurrentes sin fin. Es inclusive (la última ocurrencia puede ser ese día) y como mucho **30 días** después de hoy. Si la fecha ya pasó este año, es la del año que viene, y entonces queda fuera de rango.
- Se combinan con la condición de clima que ya existe, antes de `hasta el`: `#insto todos los días a las 06:30 si llueve hasta el 15/12: poner una alarma a las 06:45`.
- **Días hábiles** son de lunes a viernes (Insto no conoce los feriados); **fines de semana**, sábado y domingo.
- Como máximo **5 recurrentes**, aparte del límite de 10 programadas.
- **Cada ocurrencia se comporta como una diferida:** se marca consumida en disco antes de ejecutarse, se revalida desde cero y, si llega con más de 15 minutos de atraso, pasa a Confirmar. Al consumirse una ocurrencia, en la misma escritura queda programada la siguiente, con su propio id y su propio token. Si una ocurrencia queda incierta (el proceso murió en el medio), esa no se repite, pero la recurrencia sigue.
- La hora es **local**: después de un cambio de zona horaria (`TIMEZONE_CHANGED`), la próxima ocurrencia pasa a ser la misma hora en la zona nueva. Una diferida común, en cambio, sigue siendo un instante.
- Si la autonomía de su categoría pasa a **Nunca**, la recurrente se cancela entera, te avisa y se registra (`error … → la persona no permite esta acción; se canceló la recurrente entera.`). Lo mismo si llega a dispararse con «Nunca».
- **Acuses:** `#insto-programada <orden> → días hábiles 08:00 hasta el 15/12, próxima mañana`; cada ocurrencia, el suyo.

### Consultas y gestión

- `#insto ver programadas` muestra, por cada una, un **id corto** de 6 caracteres, el disparador en palabras y la orden: `a1b2c3 · al conectar auriculares, hasta las 20:00: abrir spotify; d4e5f6 · días hábiles 08:00 hasta el 15/12, próxima mañana: activar no molestar.` El id es el mismo para todas las ocurrencias de una recurrente.
- `#insto cancelar programada <id>` cancela esa orden o la recurrente entera. Es una consulta: no consume el freno. Responde `Se canceló a1b2c3: …` o `No hay ninguna orden programada con el id a1b2c3.`.
- `#insto cancelar programadas` sigue cancelando todas, recurrentes incluidas.
- En **Ajustes → Propuestas de Instinct → Órdenes programadas por Instinct** cada una muestra su id, el disparador o la recurrencia y un botón para cancelarla («Cancelar la recurrente» cancela todas sus ocurrencias).

### Para Instinct

El catálogo (desde «Protocolo Insto v5») suma las formas nuevas, con un ejemplo de cada una (validados por un test), los límites y la regla **«Para algo que depende de un momento del día o del teléfono, preferí dejar un disparador antes que vigilar el estado y mandar la orden después»**. La firma del buzón no cambia: la cadena canónica sigue empezando con `insto-v4`.

## Orden directa en el timbre (1.18.0)

Con el timbre, una orden del buzón se toma en segundos, pero Insto igual tiene que leer la planilla (un pedido a Apps Script, que a veces tarda varios segundos). Con **«Incluir la orden en el timbre»**, el push lleva la orden firmada y Insto la ejecuta apenas llega. **El modelo de seguridad no cambia:** la firma HMAC sigue siendo lo único que autoriza una orden, y la orden del timbre pasa exactamente por los mismos controles que una sondeada.

### Apps Script

- Propiedad del script `insto_timbre_con_orden` (`"1"`/`"0"`), que Insto cambia con `POST {token, action:"timbre", endpoint, conOrden:true|false}`. Si `conOrden` falta (Insto 1.17.0), se conserva el valor guardado. Cuando viene, el script la repite en la respuesta (`{ok, timbre:"guardado", conOrden:true}`): así Insto sabe que el script la entendió.
- Al anotar una orden **nueva** con la opción encendida, el cuerpo del timbre es la orden en JSON compacto, tal cual llegó:

  ```json
  {"v":1,"id":"3b241101-e2bb-4255-8caf-4136c566a962","at":"2026-11-21T09:30:00-03:00","lines":["#insto poner el volumen al 30 por ciento"],"sig":"0118c3…"}
  ```

  Si ocupa más de **3500 bytes** en UTF-8 (ntfy convierte los mensajes grandes en adjuntos), tiene más de 3 líneas o una línea de más de 300 caracteres, o con la opción apagada, el cuerpo sigue siendo `insto-timbre`. Se mantienen el espaciado de 5 segundos y `muteHttpExceptions`. Una orden repetida no toca el timbre. Si el timbre se saltea por el espaciado, no pasa nada: queda el sondeo.
- El timbre nunca lleva el token ni la clave.

### En Insto

- `InstinctDoorbellPayload.parse` (lógica pura) lee el `bytesMessage` de UnifiedPush con un parser **estricto**: JSON con exactamente las claves `v`, `id`, `at`, `lines` y `sig`; `v == 1`; `id` UUID; `at` ISO-8601 con zona; de 1 a 3 líneas de como mucho 300 caracteres; `sig` de 64 dígitos hexadecimales en minúsculas; nada después del objeto. Cualquier otra cosa (incluido `insto-timbre`) no es una orden y el timbre sondea como en 1.17.0. **El payload nunca se loguea** ni se guarda.
- La orden se verifica con **la misma ruta que el sondeo**: `InstinctMailboxSignature.verify` (firma, 15 minutos hacia atrás y 2 hacia adelante, id visto). Para que una orden que el sondeo lee al mismo tiempo no se ejecute dos veces, `InstinctMailboxSeen.claim(id)` comprueba y marca en disco en la misma sección sincronizada; hay una sola instancia compartida (y un lock por archivo) y el sondeo también toma sus órdenes con `claimAll`. Gana uno solo.
- Si es válida y el `claim` gana, entra por `InstinctProposalIntake.onMailbox`, exactamente como una orden sondeada: fuente BUZON, freno, autonomía, acuses a la bitácora.
- Después, un sondeo a los **~60 segundos** (un job propio, que respeta `InstinctMailboxGate`) la marca como `tomada` en la planilla y levanta otras pendientes.
- Una firma inválida o una orden fuera de tiempo **no se ejecuta ni se responde**: suma al diagnóstico y se sondea enseguida, como un timbre común.

### Ajustes, diagnóstico y catálogo

- **Ajustes → Propuestas de Instinct → Buzón → Timbre → «Incluir la orden en el timbre»**, apagado por defecto: «Más rápido. ntfy puede ver las líneas de la orden, pero no cambiarlas: la firma lo impide. Con tu propio servidor ntfy no las ve nadie más». Al cambiarlo se le avisa al script con `conOrden`, por el mismo camino y con los mismos reintentos que el endpoint. Si el script es anterior a 1.18.0, Ajustes lo dice y el timbre sigue sin la orden.
- **Diagnóstico:** «Timbre: … · órdenes directas N · payload inválido N». `insto-timbre` no cuenta como inválido.
- **Catálogo:** con la opción encendida, aclara que para Instinct no cambia nada: publica la orden en el buzón igual que siempre.

## Catálogo vivo (1.18.0)

Hasta 1.17.0, Instinct conocía las reglas por el texto que Pablo copiaba y pegaba en su prompt; si cambiaba la autonomía o una rutina, había que volver a copiarlo. Ahora Insto **publica las reglas vigentes en la planilla** y Instinct las lee de ahí.

### El catálogo en JSON

`InstinctProtocolCatalog.json(...)` (lógica pura) arma el catálogo a partir de **las mismas fuentes** que el texto: la lista blanca y sus ejemplos (`InstinctProposalValidator`, agrupados en acciones, diferidas, clima, disparadores y recurrentes), las consultas, los límites, las rutinas habilitadas (solo los nombres), la política de autonomía por categoría, las horas de silencio, los bloqueos fijos, lo prohibido, los canales activos (WhatsApp, bitácora, buzón, timbre, orden en el timbre, acuses) y las versiones de Insto y del protocolo. Los ejemplos van **sin** el prefijo `#insto ` (campo `orden`).

- Lleva su `hash`: el SHA-256 de la **forma canónica** (claves ordenadas, sin espacios, sin `hash` ni `publicado`).
- **Nunca** incluye la URL, el token, la clave ni el endpoint: la función ni los recibe, y un test lo comprueba. Otro test valida cada ejemplo con el parser.
- Copia de referencia, con la política por defecto, sin rutinas y con los canales apagados: [`instinct-catalog.json`](instinct-catalog.json).

### Apps Script

| Pedido | Qué hace |
| --- | --- |
| `POST {token, action:"catalogo", hash, json}` | Reemplaza la única fila de la hoja **`Catálogo`** (`publicado, hash, json`, en texto plano), con `LockService`. Si el hash es el mismo, responde `{ok, catalogo:"igual"}` sin escribir; si no, `{ok, catalogo:"guardado"}`. Un `json` de más de 45 000 caracteres (o un hash que no es SHA-256) → `{ok:false, error:"catalogo"}`. |
| `GET ?token=…&action=catalogo` | `{ok, catalogo:{publicado, hash, json}}`, o `{ok, catalogo:null}` si todavía no hay. |

### Publicación

- Con la bitácora configurada y «Recibir órdenes de Instinct» encendido, se publica **cuando cambia el hash** respecto del último publicado (`instinct-catalog-last.json` en `noBackupFilesDir`): al cambiar la autonomía, las horas de silencio, un permiso de rutina, el buzón, el timbre, la orden en el timbre, los acuses o la bitácora; en el primer arranque después de una actualización (`MY_PACKAGE_REPLACED` y al iniciar el proceso: el hash lleva la versión de Insto) y en `BOOT_COMPLETED`.
- Va por el transporte de `InstinctLogClient`, con un job propio de `JobScheduler` (restricción de red y *backoff* exponencial, como la bandeja). Sin red o con un error HTTP, reintenta; un token rechazado o un script viejo esperan a que lo corrijas. **Un fallo nunca bloquea nada.**
- **Ajustes:** «Catálogo para Instinct: publicado HH:MM · ••••<6 del hash>» y el botón **«Publicar catálogo ahora»**.

### Para Instinct

El catálogo de texto (v6), con la bitácora, suma: **«Las reglas vigentes están en GET <URL>?token=<token>&action=catalogo. Leelo al empezar y cada vez que una orden dé un error inesperado; si el hash cambió, las reglas de ahí mandan sobre este texto»**. La copia que viaja por WhatsApp no lleva la URL ni el token. `#insto ver catálogo` responde además con el hash publicado.

## Probar una orden (1.18.0)

`#insto probar <orden>` es una consulta de **solo lectura**, por WhatsApp y por el buzón: valida `<orden>` (también con envoltorio diferido, disparador, recurrente o clima) con **el mismo validador** y dice qué pasaría **ahora**, sin ejecutar, sin guardar, sin programar, sin avisarle a Pablo y sin consumir el freno.

- La decisión de `InstinctProposalCenter.gate` salió a una función pura sin efectos, `InstinctGatePreview`, que recibe una foto del contexto (`GateSnapshot`: bloqueado, Accesibilidad, panel abierto, lugar en el freno, política, horas de silencio, hora actual, fuente y tope de WhatsApp). **El camino real la usa** con `tryAcquire` del freno y la prueba con un vistazo que no consume nada, así que la prueba y la ejecución nunca difieren. La política sigue pudiendo solo endurecer.
- Si la acción necesitaría algo de Pablo y eso se sabe sin ejecutarla, lo dice: un modo temporal siempre espera confirmación; una alarma para una hora que ya pasó hoy pide confirmar que es para mañana. Elegir entre varias apps o un permiso solo aparecen al ejecutar.
- **Acuse** `kind=ok`, `orden = "probar <orden>"`, con `detail` de forma fija:

  ```text
  válida · <categoría> · la haría sola
  válida · <categoría> · esperaría confirmación: <motivo>
  válida · programada: <cuándo, en palabras>
  no válida: <el mismo motivo que daría #insto-error>
  rechazada: la persona no permite esta acción
  ```

  Pasa por la guardia anti-bucle de siempre.
- **Freno propio:** 20 pruebas cada 10 minutos; la que sobra responde `#insto-error probar … → demasiadas pruebas seguidas`. No cuenta en el ajuste adaptativo de la autonomía.
- **Catálogo** (texto y JSON): la consulta y la regla **«Si no estás seguro de una forma o de si va a quedar esperando, probala antes con "probar <orden>"»**.

## Diario de ejecución, verificación y pantalla bloqueada (1.20.0)

Diseño, auditoría, matrices y política de recuperación: [`architecture/instinct-execution-journal.md`](architecture/instinct-execution-journal.md).

- **Diario de ejecución** (`instinct-executions.db`, SQLite en `noBackupFilesDir`): una fila por orden aceptada, con id
  propio, transporte (timbre, buzón, WhatsApp, programada, disparador, recurrente, restauración), estado, resultado e
  intentos. El timbre de 1.19.0 sigue igual: su cola de recepción se vacía recién cuando el diario tiene la orden.
- **Verificación**: Insto lee el teléfono antes y después. Volumen, silencio, vibración, No molestar, linterna (si la
  cámara lo informa), música (si la sesión lo informa) y modos temporales se comprueban. Alarmas, temporizadores,
  calendario, búsquedas y apps solo se **entregan**: el acuse sigue siendo `ok`, pero dice «Entregado; Insto no puede
  comprobar el resultado final.». Un estado que no se alcanzó es `error` («No se logró: …»).
- **Recuperación**: después de una muerte del proceso, un reinicio o una actualización, lo que no tocó el teléfono se
  continúa si sigue vigente; una confirmación pendiente se vuelve a ofrecer (siempre esperándote); lo que se estaba
  despachando no se repite: se relee si es un ajuste, y si no, queda `incierta`. Cada resultado recuperado llega una
  sola vez a la bitácora, con «(recuperada tras reiniciarse Insto)».
- **Pantalla bloqueada o apagada**: volumen, linterna, silencio, vibración, No molestar, música, modos temporales y
  consultas se ejecutan solos; lo que abre una pantalla espera el desbloqueo. «Silenciá durante una hora» en Solo ya
  no pide confirmación.
- **Preparación**: Ajustes → Propuestas de Instinct → «Funcionar con el teléfono bloqueado» lista los permisos que
  Android exige conceder a mano (notificaciones, No molestar, alarmas exactas, batería, acceso a notificaciones,
  cámara). Insto nunca los pide en segundo plano.
- **Consultas nuevas** (solo lectura): `#insto ver ejecuciones` y `#insto estado de ejecución <id>`.
- **Backup**: la copia `.insto` incluye todo Instinct (con token y clave en la copia completa); al restaurar queda
  apagado hasta «Reactivar Instinct en este teléfono», que registra UnifiedPush de nuevo con las claves y el endpoint
  del teléfono nuevo.

## Preguntas con opciones (1.21.0)

```text
#insto preguntar ¿Preferís café o té? | Café | Té hasta las 18:30
#insto probar preguntar ¿Preferís café o té? | Café | Té
```

Mandá una pregunta corta por línea cuando la decisión sea dudosa o personal. Si ya tenés permiso para una acción clara, ejecutala. `preguntar` acepta hasta 140 caracteres de texto y dos o tres opciones de hasta 24 caracteres, sin respuesta libre. Al final podés agregar `hasta las HH:MM` o `en N horas`. La hora indicada es la próxima ocurrencia en el horario local. El plazo por defecto es de 2 horas, con un máximo de 24. No programes ni envuelvas la pregunta en un disparador. Si vence o la persona la descarta, no la repitas ni insistas. `probar preguntar …` valida y dice qué haría Insto sin mostrar la pregunta ni ocupar un cupo.

La pregunta puede llegar por cualquiera de los caminos de las órdenes de Instinct y se registra con el id de ejecución del diario. Se admiten hasta tres pendientes y cinco por hora. Sin bitácora configurada queda rechazada con «necesita bitácora». Los acuses de preguntas y sus pruebas usan la bitácora. Si todavía no hay bitácora, el motivo queda en el diario local.

La notificación «Insto te pregunta» tiene un botón por opción y se puede responder desde la pantalla bloqueada si Android muestra las notificaciones de ese canal. Elegir una opción cierra la notificación. Deslizarla la descarta. Un toque después del plazo avisa que ya venció. Durante las horas de silencio, Insto espera para mostrar las preguntas nuevas. Si el plazo termina antes, registra «vencida». Después de un reinicio, una pregunta vigente pendiente recupera su misma notificación si hace falta y las respuestas ya registradas se siguen enviando desde la bandeja de la bitácora.

En Autonomía → Preguntas, Solo muestra la notificación directamente. Confirmar abre una vista previa en Insto antes de mostrar los botones. Nunca rechaza la pregunta. Descartar preguntas o dejarlas vencer repetidamente puede ajustar Solo a Confirmar.

Leé las respuestas con el GET habitual de la bitácora (`since` y `limit`). En las filas de tipo `respuesta`, `evento_id` es el id de ejecución de la pregunta, `orden` contiene su texto completo, `detalle` es la opción exacta o «vencida» / «descartada», y `ocurrido` es la hora UTC. `mensaje_id` y `protocolo` quedan vacíos. El id permite deduplicar los reintentos. El texto de la respuesta también aparece en Ejecuciones recientes en Insto y en las consultas `ver ejecuciones` y `estado de ejecución <id>` por bitácora. Cuando la consulta responde por WhatsApp, muestra el estado y remite el detalle a la bitácora.

## Qué tiene que hacer Instinct

1. **Al empezar** (y cada vez que una orden dé un error inesperado), leer las reglas vigentes: `GET <URL>?token=<token>&action=catalogo` (1.18.0). Si el `hash` cambió, mandan sobre el texto pegado en el prompt.
2. **Antes de una orden que dependa del estado** (silencio, No molestar, alarmas, calendario): leer `GET <URL>?token=<token>&action=estado`. Si el estado tiene más de 30 minutos, pedir `#insto ver estado` y esperar el evento nuevo.
3. **Si no está seguro de una forma** o de si va a quedar esperando, probarla antes: `#insto probar <orden>` (1.18.0).
4. **Mandar la orden** por WhatsApp (`#insto …`) o, mejor, por el buzón firmado, que no ensucia el chat y prueba quién la manda.
5. **Mirar el resultado** en la bitácora (`GET <URL>?token=<token>&since=<ISO>&limit=50`): `ok`, `espera`, `error`, `incierto`, `programada`… Nunca repetir después de `incierto` ni de `espera`; una sola orden corregida después de un `error`.
6. **Respetar la autonomía**: lo que el catálogo dice que espera confirmación va a esperar. No insistir.
7. Para hablarle a la persona sin escribir en WhatsApp: `#insto avisarme <texto>`, solo por el buzón, como máximo 3 por hora.

Ejemplo de orden firmada, en Python (solo biblioteca estándar; `urllib` sigue la redirección 302 de Google con `GET`):

```python
import datetime, hashlib, hmac, json, urllib.request, uuid

URL = "https://script.google.com/macros/s/<id>/exec"
TOKEN = "<token de la bitácora>"
CLAVE = "<clave del buzón>"  # la que te pasó la persona; nunca la pongas en un mensaje

def mandar(lineas):
    id_ = str(uuid.uuid4())
    at = datetime.datetime.now().astimezone().isoformat(timespec="seconds")  # p. ej. 2026-11-21T09:30:00-03:00
    cadena = "\n".join(["insto-v4", id_, at] + lineas)
    sig = hmac.new(CLAVE.encode("utf-8"), cadena.encode("utf-8"), hashlib.sha256).hexdigest()
    cuerpo = json.dumps({"token": TOKEN, "action": "orden", "id": id_, "at": at, "lines": lineas, "sig": sig}).encode("utf-8")
    pedido = urllib.request.Request(URL, data=cuerpo, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(pedido, timeout=15) as respuesta:
        return json.load(respuesta)  # {"ok": true, "id": "..."}

mandar(["#insto poner el volumen al 30 por ciento", "#insto avisarme Te bajé el volumen para la clase."])
```

Con la clave `clave-de-prueba-0123456789-abcdefghijkl`, el id `3b241101-e2bb-4255-8caf-4136c566a962`, `at` `2026-11-21T09:30:00-03:00` y esas dos líneas, la firma tiene que dar `0118c3671f915c0c55cc4364838ce32afcc3ce33e984617ea69b60f2377859df` (el mismo vector que usa `InstinctMailboxTest`).

## Idempotencia y vencimiento

- Desde 1.20.0 cada orden aceptada tiene una fila en el [diario de ejecución](#diario-de-ejecución-verificación-y-pantalla-bloqueada-1200) (SQLite): reclamar el id y registrar la orden son una sola transacción, y «despachando» se escribe antes de tocar el teléfono. La propuesta en memoria (`ProposalBook`) sigue ejecutándose como máximo una vez gracias a un `claim` atómico, y ahora se identifica por su ejecución: dos órdenes iguales de mensajes distintos ya no se confunden. Si WhatsApp vuelve a publicar la misma notificación, no pasa nada.
- Las que esperan confirmación vencen a los **10 minutos** (unos 2 segundos antes si hay un acuse pendiente, para poder avisar el vencimiento). Una vencida, descartada o ya ejecutada no se puede volver a ejecutar.
- Las diferidas se guardan en disco y se marcan consumidas **antes** de ejecutarse: nunca se repiten.
- Cada orden guarda la huella de la configuración con la que llegó (contacto y app de WhatsApp). Si eso cambió o se apagó el interruptor, no se ejecuta, tampoco una diferida.
- Confirmar desde la notificación exige, además del id, un **token aleatorio** que solo viaja en el `PendingIntent` de Insto, porque `AssistActivity` está exportada. Lo mismo vale para Deshacer y para la alarma de una programada.
- Un resultado incierto nunca se repite: ni una orden suelta, ni un paso de rutina, ni una programada interrumpida.

## Privacidad

- La lectura es **pasiva**: con el interruptor encendido, Insto mira cada notificación de WhatsApp y lee solo el último mensaje del chat individual con el contacto configurado. De ese mensaje usa únicamente las líneas `#insto`. Las notificaciones de otras apps solo se miran por su paquete. Las de grupos y otros contactos se descartan antes de leer el texto.
- El texto del mensaje no se guarda, no se registra (Logcat muestra solo contadores, en debug), no sale del teléfono y no entra en copias de seguridad. La única excepción es la orden de una diferida, que se guarda en `noBackupFilesDir` hasta que se dispara o la cancelás.
- Los interruptores, el modo de respuesta, los permisos de rutinas y las programadas viven en `noBackupFilesDir`. Desde 1.20.0 la copia `.insto` los trae, pero una restauración **nunca enciende** nada: hay que tocar «Reactivar Instinct en este teléfono». Las órdenes únicas programadas no viajan; las recurrentes, como definición.
- El diagnóstico de Ajustes muestra estados y el motivo del último resultado. Nunca muestra el contenido del mensaje.
- Los acuses a Instinct (apagados por defecto) solo repiten la orden que Instinct mandó y el texto de resultado que Insto ya te muestra. La acción «Responder» que los envía vive en memoria como máximo 10 minutos y nunca se guarda. A Instinct le llegan los nombres de las rutinas habilitadas, nunca sus pasos.
- **Bitácora (1.15.0, apagada por defecto):** con «Bitácora» o «Ambos», ahora sí sale algo del teléfono, solo hacia el Apps Script que vos configuraste: la orden recortada a 80 caracteres, el texto de resultado que Insto ya te muestra, el tipo de acuse, la hora y un hash corto del mensaje (12 dígitos hexadecimales). **Nunca** el texto del mensaje de Instinct ni datos del teléfono (contactos, número, ubicación, notificaciones). La URL, el token y la bandeja de salida viven en `noBackupFilesDir`. Desde 1.20.0 la URL y el token viajan en la copia `.insto` completa (cifrada); la «copia sin secretos» no lleva el token. La bandeja nunca. La bitácora queda en tu planilla de Google, con los permisos que le des.
- **Estado del teléfono (1.16.0):** con la bitácora configurada, sale hacia tu Apps Script un resumen con campos fijos (modo de sonido, No molestar, hora de la próxima alarma, título y horario del evento en curso y del próximo, si hay auriculares o Bluetooth, si está bloqueado, el modo temporal y las programadas). Nunca volumen, batería, red, ubicación, contactos, notificaciones, uso de apps ni identificadores.
- **Buzón (1.16.0, apagado por defecto):** Insto lee de tu Apps Script las órdenes firmadas y las marca como tomadas. La clave vive en `noBackupFilesDir`, en Instinct y, desde 1.20.0, dentro de la copia `.insto` completa (cifrada con tu contraseña); nunca en la planilla, en el diagnóstico, en WhatsApp ni en la copia sin secretos. Los contadores de autonomía (`instinct-trust.json`) y los ids vistos del buzón son solo contadores e identificadores, sin texto.
- **Timbre cifrado (1.19.0):** con el script de 1.19.0, ntfy solo ve un mensaje cifrado (RFC 8291). Con la orden en el timbre y el relé configurado, la orden la ve en claro solo tu relé mientras la cifra; sin relé, la orden no viaja en el timbre. Las claves públicas (`p256dh`, `auth`) van solo a tu Apps Script y, desde ahí, a tu relé; la privada nunca sale del Android Keystore.
- **Orden directa en el timbre (1.18.0, apagada por defecto):** con un script anterior a 1.19.0 y la opción encendida, el push que reenvía ntfy lleva el id, la hora, las líneas `#insto` y la firma de cada orden nueva. ntfy (el servidor público `ntfy.sh` o el tuyo) **puede ver esas líneas, pero no cambiarlas ni inventar otras**: sin la clave no hay firma válida. Nunca viajan el token, la clave ni otra cosa del teléfono. Con tu propio servidor ntfy no las ve nadie más. El payload nunca se loguea ni se guarda.
- **Catálogo vivo (1.18.0):** sale hacia tu Apps Script lo mismo que ya decía el catálogo de texto: las formas, los límites, los nombres de las rutinas habilitadas, la autonomía, las horas de silencio y qué canales están encendidos (sí/no). Nunca URL, token, clave ni endpoint.
- `#insto probar` (1.18.0) no hace nada en el teléfono ni guarda nada: solo responde por el acuse.
- Fuera de la bitácora, la única red en uso es la del clima que ya existía (SMN y Open-Meteo), y solo cuando se dispara una orden con condición.

## Lo que no hacen las órdenes

- No alimentan el aprendizaje: no crean frases (`LearnedPhraseStore`) ni estructuras (`PhrasePatterns`).
- No cuentan como turnos de `OperationalContext` ni lo modifican, tampoco los pasos de una rutina pedida por Instinct. Eso sí: al ejecutar un control multimedia sin app indicada, `PhoneActionExecutor` sigue eligiendo la sesión con el contexto vigente, como con cualquier pedido.
- Fuera de `plan` (1.23.0) no se juntan: cada línea es un comando independiente. Una rutina es un plan que **vos** configuraste y habilitaste; Instinct solo la nombra. Un plan de Instinct usa una gramática fija, acciones sin interfaz y la misma validación, autonomía y diario que una orden suelta ([Planes de varios pasos](#planes-de-varios-pasos-1230)).
- **Condiciones:** las únicas formas admitidas son la del clima, con hora o en una recurrente ([Condiciones por clima](#condiciones-por-clima-1140)), y los cinco [disparadores](#disparadores-por-estado-1170). Una sola por línea: cualquier otra condición o combinación («si estoy en casa», «si llueve y hace frío», «al conectar auriculares y al desbloquear») se descarta.
- **No vigilan nada por su cuenta** fuera de lo que ya vigilaba Insto: los disparadores usan los callbacks del estado y el calendario, nunca la ubicación, la red ni otras apps. No hay recurrentes sin fecha de fin.
- No agregan dependencias ni servidores propios. Los acuses usan la acción «Responder» de WhatsApp, que ya existe en la notificación, o tu propio Apps Script (con OkHttp y `JobScheduler`, que ya estaban).

## Límites conocidos

- **El nombre no prueba el número.** Android no expone el número del remitente de WhatsApp. Si otro contacto tuyo se llama exactamente igual que Instinct, sus `#insto` también se ejecutarían. Por eso el contacto de Instinct necesita un nombre único. Lo acotan la lista blanca, el freno y el aviso posterior.
- **Abrir otra app desde segundo plano.** Desde Android 10, una app no puede abrir pantallas si no está en primer plano. La excepción es tener un servicio de Accesibilidad conectado. Sin la Accesibilidad de Insto, alarma, temporizador, abrir apps, búsquedas y eventos esperan tu toque. Volumen, linterna, silencio y música siempre se ejecutan solos.
- La comparación usa el nombre que **muestra WhatsApp** (el de tu agenda). Si en Insto guardaste a Instinct con otro nombre, no se lee nada.
- Si Android acaba de iniciar el proceso de Insto y todavía no cargó los ajustes, una notificación de WhatsApp se descarta (lo dice el diagnóstico). Una orden del buzón o del timbre ya no: queda recibida en el diario y se reanuda (1.20.0).
- Si tenés abierto el chat de Instinct en WhatsApp, WhatsApp no genera notificación y no llega nada.
- **Acuses:** dependen de que WhatsApp ofrezca «Responder» en la notificación. Si no lo hace, no se responde (lo dice el diagnóstico). Un acuse de una diferida que se dispara después de 10 minutos no sale.
- **Bitácora:** depende de la red y de que Apps Script responda. Sin red, los eventos esperan en la bandeja (hasta 200) y se reintentan solos; si Google cambia la redirección de Apps Script, el diagnóstico dirá «respuesta inválida». Las cuotas diarias de Apps Script (ejecuciones y URL Fetch) alcanzan de sobra para uso personal: cada lote de hasta 50 eventos es una sola ejecución. Las líneas en memoria se pierden si el proceso muere, salvo las de las diferidas: un seguimiento de una orden inmediata que estaba en espera durante un reinicio no se registra.
- **Diferidas:** sin permiso de alarmas exactas (Insto no lo pide), Android puede demorar el disparo; si la demora pasa de 15 minutos, la orden espera tu confirmación. Vale igual para cada ocurrencia de una recurrente y para los disparadores de calendario.
- **Disparadores por estado:** auriculares y desbloqueo solo se detectan mientras «Insto · notificaciones» esté conectado; si Android lo desconecta, no se disparan y vencen. Un evento que se mueve después de la alarma se ve recién cuando la alarma suena; uno que se adelanta a una hora que ya pasó se dispara en ese momento. Bluetooth cuenta como auriculares, igual que en el estado.
- **Recurrentes:** como mucho 30 días y 5 a la vez. Una ocurrencia atrasada que espera confirmación bloquea, hasta que la confirmes, descartes o venza, otra ocurrencia de la misma orden (el libro no duplica una orden igual en espera). Los feriados cuentan como días hábiles.
- **Timbre:** ver [Timbre](#timbre-1170), «Límites».
- **Buzón y Doze:** `JobScheduler` no garantiza el intervalo. Con el teléfono en reposo, Android agrupa los jobs en ventanas de mantenimiento: en la práctica el intervalo mínimo real puede ser de 15 minutos o más, aunque elijas 2. Al abrir Insto, el panel o al llegar un mensaje de Instinct se sondea enseguida. Una orden que llega con más de 15 minutos de antigüedad se descarta como vencida: Instinct tiene que mandarla de nuevo con un id y un `at` nuevos.
- **Cuotas de Apps Script:** cada sondeo es una ejecución (un `GET`, más un `POST tomadas` si había algo) y cada latido del estado suma un evento a la bandeja. Con el intervalo de 2 minutos son unas 720 ejecuciones por día solo del buzón, más 48 latidos; con 5 minutos, unas 290. Alcanza para uso personal en una cuenta común de Google, pero si compartís la cuota con otros scripts, preferí 5 o 15 minutos.
- **El estado es una foto:** se publica con hasta 30 segundos de demora después de un cambio (por el agrupamiento), y además tarda lo que tarde la bandeja en vaciarse. Un evento sin permiso de calendario dice `sin permiso`; uno de todo el día no aparece.
- **La autonomía no ve el contenido:** el ajuste adaptativo solo cuenta Deshacer, confirmaciones y descartes. Si deshacés por otro motivo, también cuenta.
- Requiere Android 9 o posterior.

## Cómo probarlo en el teléfono

1. Configurá el contacto de Instinct con el nombre exacto que muestra WhatsApp.
2. Andá a **Ajustes → Propuestas de Instinct**, activá «Recibir órdenes de Instinct» y habilitá «Insto · notificaciones» y las notificaciones.
3. Sin escribirle nada antes, que Instinct mande `#insto poner el volumen al 30 por ciento`: el volumen cambia y llega «Instinct hizo: …» con **Deshacer**.
4. Con la Accesibilidad de Insto activa, `#insto poner un temporizador de 1 minuto` se crea solo. Sin ella, llega «Instinct propone: …» con Confirmar.
5. Elegí «Responderle a Instinct: Siempre» y repetí el paso 3: en el chat aparece `#insto-ok poner el volumen al 30 por ciento → Volumen multimedia ajustado.`
6. Si algo no pasa, mirá **Diagnóstico** en la misma pantalla y tocá «Actualizar diagnóstico».
7. Bitácora: configurá el Apps Script ([Bitácora](#bitácora-1150)), elegí «Canal de estados: Bitácora», guardá la URL y el token y tocá «Probar bitácora»: aparece una fila `prueba` en la planilla. Repetí el paso 3: el chat no recibe nada y la planilla suma una fila `ok`.
8. Autonomía (1.16.0): en **Autonomía** poné «Alarmas y temporizadores» en **Confirmar** y que Instinct mande `#insto poner un temporizador de 1 minuto`: llega «Instinct propone: …» y el acuse dice «la persona pide confirmación para alarmas y temporizadores». Poné «Linterna» en **Nunca** y probá `#insto prender la linterna`: no se prende y el acuse es `#insto-error … → la persona no permite esta acción`. Activá horas de silencio que incluyan la hora actual: `#insto poner el volumen al 30 por ciento` espera («horas de silencio») y `#insto poner en vibración` se hace solo. Mandá tres veces un cambio de volumen y tocá Deshacer las tres: llega «Instinct va a pedirte confirmación para volumen: deshiciste 3 de 3» con «Volver a automático».
9. Estado (1.16.0): con la bitácora, tocá **«Publicar estado ahora»**: aparece una fila `estado` con `sonido=…; dnd=…`. Poné el teléfono en vibración y esperá unos 30 segundos: aparece otra. Probá `curl -sL "$URL?token=$TOKEN&action=estado"`. Con el canal en WhatsApp, `#insto ver estado` responde `#insto-error ver estado → el estado solo se publica en la bitácora`.
10. Buzón (1.16.0): actualizá el Apps Script, tocá **«Generar clave»**, pasale la clave a Instinct por un canal seguro y encendé **«Recibir órdenes por el buzón»**. Publicá una orden firmada con el ejemplo de Python de [Qué tiene que hacer Instinct](#qué-tiene-que-hacer-instinct) y abrí Insto: se ejecuta y la bitácora suma su `ok`. Repetí el mismo `POST` (mismo id): no pasa nada. Cambiá una letra de la firma: no se ejecuta, no hay acuse y el diagnóstico suma «firmas inválidas 1». Por el buzón, `#insto avisarme Hola` muestra «Instinct te avisa». Elegí «Solo buzón» y mandá `#insto poner el volumen al 30 por ciento` por WhatsApp: se ignora y el diagnóstico lo dice.
11. Timbre (1.17.0): instalá ntfy, actualizá el Apps Script, encendé **«Timbre instantáneo (ntfy)»** y tocá **«Probar timbre»**: dice «El timbre llegó en N s». Publicá una orden firmada con el ejemplo de Python: se ejecuta en segundos, sin abrir Insto. El diagnóstico suma «Timbre: último HH:MM · timbres 1 · sondeos por timbre 1».
12. Disparadores (1.17.0): que Instinct mande `#insto al conectar auriculares: poner el volumen al 30 por ciento` y conectá unos auriculares: el volumen cambia una sola vez (desconectá y volvé a conectar: no pasa nada). Con un evento en curso en el calendario, `#insto al terminar el evento: desactivar no molestar`; cambiale la hora de fin en el calendario y mirá que, al llegar la hora vieja, se reprograma. Probá `#insto al desbloquear hasta las HH:MM: mostrar las alarmas` con una hora cercana y no desbloquees: llega «Venció sin dispararse».
13. Recurrentes (1.17.0): `#insto todos los días a las HH:MM hasta el DD/MM: poner el volumen al 30 por ciento` con la hora de dentro de unos minutos; `#insto ver programadas` muestra su id y «próxima hoy». Se ejecuta y queda programada la del día siguiente. `#insto cancelar programada <id>` la cancela entera. Sin «hasta el», se rechaza.
14. Orden directa (1.18.0): actualizá el Apps Script, encendé **«Incluir la orden en el timbre»** (la línea dice «El script lo confirmó») y publicá una orden firmada: se ejecuta sin que el diagnóstico sume un sondeo inmediato; suma «órdenes directas 1» y, unos 60 segundos después, la fila del buzón queda `tomada`. Cambiá una letra de la firma: no pasa nada, no hay acuse y suma «payload inválido 1».
15. Catálogo vivo (1.18.0): con la bitácora, tocá **«Publicar catálogo ahora»**: la hoja `Catálogo` tiene una fila y `curl -sL "$URL?token=$TOKEN&action=catalogo"` la devuelve. Cambiá la autonomía de una categoría: al rato la línea muestra otro hash. `#insto ver catálogo` responde con ese hash.
16. Probar (1.18.0): `#insto probar poner el volumen al 30 por ciento` responde `válida · volumen · la haría sola` sin cambiar el volumen. Con «Volumen» en Confirmar, `… esperaría confirmación: la persona pide confirmación para volumen`; en Nunca, `rechazada: …`. `#insto probar a las 22:00: apagar la linterna` responde `válida · programada: a las 22:00` y `ver programadas` sigue vacío.

## Instrucciones para pegar en el prompt de Instinct

El bloque se genera desde el código y se obtiene en **Ajustes → Propuestas de Instinct → «Copiar instrucciones para Instinct»** (o «Enviarlas a Instinct»). Así nunca se desincroniza de la gramática ni de tus rutinas habilitadas. Copialo de nuevo cada vez que habilites o deshabilites una rutina, o cuando Insto se actualice.

La copia de referencia, sin rutinas, está en [`instinct-catalog.txt`](instinct-catalog.txt). Con la bitácora, la copia de referencia es [`instinct-catalog-bitacora.txt`](instinct-catalog-bitacora.txt), y con la bitácora y el buzón, [`instinct-catalog-buzon.txt`](instinct-catalog-buzon.txt) (las tres sin URL, token ni clave, y con la autonomía por defecto). Desde 1.18.0, con la bitácora, Instinct además lee el [catálogo vivo](#catálogo-vivo-1180), que se actualiza solo; su copia de referencia en JSON es [`instinct-catalog.json`](instinct-catalog.json). Incluye las formas diferidas y condicionales, los prefijos de los acuses (`#insto-ok`, `#insto-espera`, `#insto-error`, `#insto-incierto`, `#insto-programada`, y `#insto-descartada`, `#insto-vencida` y `#insto-deshecho` como seguimiento) y la regla para Instinct: **esas líneas son informativas; no se responden con nuevas órdenes, salvo una sola orden corregida después de un `#insto-error`, y nunca se repite una orden después de `#insto-incierto` o `#insto-espera`.**

## Decidir y reglas contextuales (1.22.0)

### Elegir y ejecutar

```text
#insto decidir ¿Qué preferís? | Silencio => silenciar el teléfono | No molestar => activar no molestar
#insto probar decidir ¿Qué preferís? | Silencio => silenciar el teléfono | No molestar => activar no molestar
```

Cada opción vincula una acción de la lista blanca que se puede ejecutar sin interfaz, como volumen, linterna, silencio, No molestar o controles multimedia. Se aplican los mismos plazos, cupos y ajustes de Preguntas que para `preguntar`. Si la categoría de la acción está en Confirmar, el toque de la opción ya es esa confirmación. Si el permiso falta o la categoría pasó a Nunca, Insto registra el resultado y no vuelve a pedir confirmación. La respuesta y el resultado van a la bitácora como filas con IDs de ejecución distintos. El resultado incluye el ID de la pregunta para vincularlos.

Por defecto, en la pantalla bloqueada se ve un aviso genérico sin el texto ni los botones. «Mostrar preguntas en la pantalla bloqueada» permite verlos si la configuración de notificaciones de Android lo permite.

### Automatizar por condiciones del teléfono

```text
#insto cuando batería menor de 15 por ciento y no cargando: avisarme Batería baja
#insto cuando horario 22:00 a 07:00 y wifi conectado por 7 días: activar no molestar
#insto cuando al desbloquear y evento en curso: silenciar el teléfono
#insto ver reglas
#insto diagnóstico de regla abc123
#insto desactivar regla abc123
#insto activar regla abc123
#insto eliminar regla abc123
```

Enviá las órdenes de reglas por el buzón firmado. `cuando` admite hasta tres condiciones distintas unidas por `y`, seguidas de una sola acción de la lista blanca que pueda ejecutarse sin interfaz o de `avisarme`. Si omitís el plazo, la regla vence a los 30 días. Para acortarlo, agregá `por N días` antes de los dos puntos. Hay un cupo de 12 reglas vigentes. Para controlar las reglas manualmente, abrí Ajustes → Propuestas de Instinct → Reglas contextuales.

| Señal | Condiciones |
| --- | --- |
| Batería | `batería menor de N por ciento`, `batería mayor de N por ciento` |
| Carga | `cargando`, `no cargando` |
| Red activa | `wifi conectado`, `sin wifi`, `con internet`, `sin internet` |
| Multimedia | `reproduciendo multimedia`, `sin reproducción multimedia` |
| Horario local | `horario HH:MM a HH:MM`, incluso si cruza medianoche |
| Calendario | `evento en curso`, `sin evento en curso` |
| Auriculares | `auriculares conectados`, `sin auriculares` |
| Desbloqueo | `al desbloquear`, como evento instantáneo |

Wi-Fi se refiere a la red activa del sistema. Internet requiere conectividad validada por Android. El calendario requiere permiso de lectura y excluye eventos de todo el día. Las condiciones desconocidas no disparan acciones. Las condiciones de estado deben sostenerse cinco segundos. Una vez disparada, la regla debe volver a estado falso antes de dispararse otra vez. Hay un minuto mínimo entre disparos por regla y un máximo persistente de seis disparos contextuales cada diez minutos. Bajo Confirmar, las acciones esperan la confirmación normal. Los avisos mantienen la semántica anterior y son silenciosos en Confirmar o en horas de silencio.

Las reglas no dependen de una conexión permanente con Instinct. El servicio «Insto · notificaciones» entrega los eventos dinámicos disponibles, y Android entrega una alarma compartida para horarios, límites de calendario, vencimiento y antirrebote. Doze, la falta de permisos y las restricciones del fabricante pueden demorar o impedir algunos eventos. Tras reiniciar, Android debe permitir el acceso al almacenamiento de Insto. Una ocurrencia que ya empezó a ejecutarse se verifica si es posible y no se repite.

El respaldo `.insto` conserva las definiciones, el vencimiento original, la activación y la preferencia de privacidad. Las definiciones nuevas se incorporan al tocar «Reactivar Instinct en este teléfono». El historial y las ocurrencias pendientes quedan en el teléfono de origen.

## Planes de varios pasos (1.23.0)

Instinct puede pedir un objetivo compuesto en **una sola línea**, por WhatsApp o por el buzón firmado:

```text
#insto plan: activar no molestar; poner el volumen al 20 por ciento; pausar la música
#insto plan: esperar al empezar el próximo evento; guardar no molestar; activar no molestar; esperar al terminar el evento; restaurar no molestar
#insto plan: decidir ¿Preferís silencio o música? | Silencio => activar no molestar + pausar la música | Música => desactivar no molestar + poner el volumen al 40 por ciento
#insto plan hasta las 22:00: bajar el volumen si falla silenciar el teléfono; si batería menor de 20 por ciento entonces activar no molestar si no subir el volumen
#insto probar plan: …
#insto ver planes
#insto estado de plan p1a2b3
#insto cancelar plan p1a2b3
#insto reanudar plan p1a2b3
```

### Gramática

`plan[ hasta las HH:MM]: <paso>; <paso>; …`. Los pasos se separan con `;` y cada uno es exactamente una de estas formas:

| Paso | Forma |
| --- | --- |
| Acción | una orden de la lista blanca que se ejecuta **sin abrir una pantalla** (volumen, linterna, silencio, vibración, No molestar, modos temporales, música), con alternativa opcional `<acción> si falla <acción>` |
| Espera | `esperar N minutos` / `esperar N horas` (1 minuto a 12 horas), `esperar hasta las HH:MM` |
| Disparador | `esperar al empezar el próximo evento`, `esperar al terminar el evento`, `esperar al conectar auriculares`, `esperar al desconectar auriculares`, `esperar al desbloquear` |
| Condición | `si <condición> [y <condición>] entonces <acción> [+ <acción>] [si no <acción> [+ <acción>]]`, con las condiciones de `cuando` salvo `al desbloquear` |
| Decisión | `decidir <texto> \| <opción> => <acción> [+ <acción>] \| …` (2 o 3 opciones, mismos límites que `decidir`) |
| Guardar | `guardar volumen`, `guardar sonido`, `guardar no molestar` o `guardar estado` (los tres) |
| Restaurar | `restaurar`, `restaurar estado` o `restaurar <ajuste>` |

Límites: 6 acciones (se cuentan las alternativas y todas las opciones), 10 pasos, una decisión, 500 caracteres y 5 planes activos. Vence a las 12 horas o en el tope `hasta las HH:MM` (máximo 24). Todo lo que se guarda tiene que restaurarse con un paso posterior. Cualquier otra forma rechaza el plan entero con su motivo: nunca se ejecuta texto libre, no se anidan planes, diferidas, reglas ni rutinas, y no hay acciones que abran apps, el Reloj o el calendario. Cada acción pasa por `InstinctProposalValidator.backgroundAction` al llegar y de nuevo justo antes de ejecutarse.

### Ejecución

- La orden recibe un acuse normal, `Plan p1a2b3 aceptado: N pasos, M acciones.`, y su fila del diario queda verificada. El plan vive en su propia fila (`plans`, en `instinct-executions.db`) y cada paso en `plan_steps`; cada acción y cada pregunta es una fila del diario con transporte `plan`.
- Cada acción escribe «despachando» antes de tocar el teléfono, se ejecuta con `PhoneActionExecutor` (con la autorización de 1.20.0: no vuelve a pedir el «¿Confirmás?» del modo temporal) y se verifica con el mismo verificador. El paso siguiente empieza **solo después** de esa comprobación.
- **Falla** (Android la rechazó, no se logró, falta un permiso): corre la alternativa `si falla` si existe; si no, el plan se detiene como `fallido`. Un permiso que falta se anota para Ajustes; nunca se pide en segundo plano.
- **Incierta** (pudo haberse hecho): no se repite. El plan queda `suspendido` después de ese paso; `reanudar plan <id>` sigue con el siguiente y `cancelar plan <id>` lo termina.
- **Autonomía**: el plan cuenta como una sola orden para el freno anti-bucle. Si alguna categoría está en Nunca, se rechaza al llegar. Si alguna está en Confirmar, hay horas de silencio, el freno está lleno o las órdenes sin firma de WhatsApp esperan, la persona lo aprueba **una sola vez** con una notificación de preguntas (Ejecutar / Cancelar, 10 minutos). Después no se vuelve a preguntar. Si un plan aprobado por Solo llega a una acción cuya categoría pasó a Confirmar, pregunta en ese momento; si pasó a Nunca, se detiene.
- **Pantalla bloqueada**: como las acciones de un plan no abren pantallas, se ejecutan con el teléfono bloqueado o la pantalla apagada, igual que una orden suelta de esas categorías.
- **Decidir**: el plan queda `decidiendo` y se muestra la notificación de preguntas de siempre, con sus botones, su privacidad en la pantalla bloqueada y su espera en horas de silencio. El toque elige la secuencia y el plan sigue. Descartarla cancela el plan; si vence, el plan vence. La categoría Preguntas en Confirmar pide la vista previa; en Nunca, el plan se rechaza.
- **Esperas**: una alarma compartida (`AlarmManager`, exacta cuando Android lo permite) despierta el plan. Los eventos se leen con el mismo lector del calendario de los disparadores; `esperar al terminar el evento` después de `esperar al empezar el próximo evento` usa el mismo evento. Si el evento se mueve, el plan espera la hora nueva; si se borra, se detiene. Auriculares y desbloqueo usan los callbacks de «Insto · notificaciones». Una espera que Android entrega con más de 15 minutos de atraso pide aprobación, como una diferida atrasada.
- **Restaurar**: `guardar` lee el valor con las mismas lecturas de Deshacer. `restaurar` lo vuelve a poner con `InstinctUndoActions` solo si el ajuste sigue como lo dejó el plan; si la persona lo cambió después, no se toca y se informa. Si el plan se cancela, falla o vence antes de restaurar, se restaura igual al cerrarse. Si se apaga «Recibir órdenes», el plan se cancela sin tocar nada más.

### Progreso, consultas y recuperación

- El progreso y el resultado final van **solo a la bitácora**, como eventos de tipo `plan`: `orden` es `plan <id> · <paso> · <texto>` y `detalle`, el estado y el resultado. Cada evento tiene un id estable, así que un reintento no duplica filas. No se escribe nada en el chat de WhatsApp. En el teléfono hay una sola notificación por plan, al terminar o al suspenderse.
- `ver planes`, `estado de plan <id>`, `cancelar plan <id>` y `reanudar plan <id>` funcionan por cualquier canal; las dos últimas son cambios de programación. En **Ajustes → Propuestas de Instinct → Planes de Instinct** cada plan muestra su estado con Cancelar y, si está suspendido, Reanudar.
- **Reinicios**. Insto relee el estado solicitado antes de decidir cómo seguir. Si ya se cumple, avanza. En Android 13 o superior, puede reintentar una vez un volumen absoluto si el diario guardó el parlante integrado, el nivel y la escala y la lectura actual sigue coincidiendo. Si sigue sin poder confirmar el efecto, pide revisión. Al reanudar manualmente se omite el paso incierto y se omiten los pasos JSON que lo declaran como dependencia. Las preguntas pendientes conservan su estado y una entrega duplicada identifica el mismo plan.
- **Backup `.insto`**: viajan los planes que todavía no ejecutaron nada (su línea y su vencimiento). Al restaurar quedan esperando «Reactivar Instinct en este teléfono», que los valida de nuevo y los empieza solo si no vencieron. Los planes ya empezados, sus pasos y lo guardado quedan en el teléfono de origen.

## Objetivos y planes estructurados

Desde 1.24.0, las instrucciones para Instinct incluyen un ejemplo de cómo pasar de un objetivo a un plan JSON v1. Instinct lee las capacidades disponibles y el estado fechado, genera el plan y lo manda por el buzón firmado. El ejecutor local usa el diario, la autonomía, las condiciones y las preguntas existentes. Para activar líneas de más de 300 caracteres en la planilla hace falta desplegar el Apps Script actualizado.

El contrato y el ejemplo de la reunión están en `plan_json` del catálogo vivo. Insto publica la disponibilidad que encuentra en Android y los destinos autorizados que declara cada adaptador. Los controles `omniremote_power`, `omniremote_volume` y `omniremote_mute` figuran como no disponibles hasta contar con un contrato local verificable del proveedor. [Capacidades y adaptadores](architecture/instinct-device-capabilities.md)

### Entrega de órdenes grandes

Con el timbre cifrado, el relé y «Incluir la orden en el timbre», un aviso de una orden que excede los 3500 bytes del push contiene solo el ID y una huella SHA-256. Insto pide esa fila por `GET action=orden&id=<id>`, verifica huella, HMAC y vigencia, y la reclama en el mismo diario que usa el sondeo. Las órdenes que caben van completas y se ejecutan sin un GET. El sondeo sigue disponible si el timbre no llega o la recuperación dirigida falla.

Para activar esta ruta, actualizá la app y desplegá `docs/instinct-bitacora.gs` en la planilla conservando la configuración existente. Insto informa el soporte al registrar o actualizar el timbre. Podés guardar de nuevo la configuración de la bitácora para reenviarla. El relé existente acepta ambos cuerpos cifrados. El diario muestra el tiempo entre que Insto recibe la señal (o recibe la respuesta del sondeo) y que empieza el primer despacho. Los pasos del plan incluyen las esperas y confirmaciones transcurridas desde la recepción original. [Detalles del transporte](architecture/instinct-push-reliability.md)

 Los accesos del dispositivo, los resultados verificables y el procedimiento de prueba física están en la guía de la versión. [Insto 1.24.0](releases/Insto-1.24.0.md)

## Accesos directos de aplicaciones

Los planes del buzón firmado pueden pedir **execute_app_shortcut** como último paso para un destino autorizado. El lanzamiento requiere que la persona revise el destino y confirme en primer plano. Consultá la [guía de accesos directos](APP-SHORTCUTS.md).
