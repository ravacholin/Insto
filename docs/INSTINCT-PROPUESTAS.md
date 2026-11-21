# Órdenes de Instinct («Instinct con manos», 1.13.0 a 1.16.0)

Instinct puede pedirle acciones al teléfono **por su cuenta**: cuando lo considera útil, escribe en su chat de WhatsApp una o más líneas `#insto <orden>`. Insto las detecta en la notificación de ese contacto, las interpreta con la gramática local, las valida igual que cualquier pedido y **las ejecuta sin preguntar**. Después avisa con una notificación «Instinct hizo: …». No hace falta que vos le hayas escrito antes a Instinct.

Desde 1.14.0, además: Insto le puede **responder** a Instinct con el resultado de cada orden, Instinct puede pedir **rutinas** que vos le habilitaste, el bloque de instrucciones para Instinct se **genera desde el código**, las órdenes pueden quedar **programadas** para más tarde (con una única condición de clima) y lo que se puede revertir trae **Deshacer**.

Desde 1.15.0, los acuses pueden ir a una **bitácora** en Google Sheets en lugar del chat, para no llenarlo de mensajes ([Bitácora](#bitácora-1150)). Las órdenes siguen llegando por WhatsApp exactamente igual.

Desde 1.16.0, qué hace Instinct solo se elige **por tipo de acción** ([Autonomía por acción](#autonomía-por-acción-1160)), con horas de silencio y un ajuste automático cuando deshacés mucho. Con la bitácora configurada, Insto además publica el **estado del teléfono** para que Instinct decida con contexto ([Estado del teléfono](#estado-del-teléfono-1160)).

La función está **apagada por defecto**: **Ajustes → Propuestas de Instinct → «Recibir órdenes de Instinct»**. En **Autonomía** elegís, por categoría, si Instinct lo hace solo, espera tu confirmación o nunca. Todos los interruptores y estados de esta función viven en `noBackupFilesDir`: una restauración de copia de seguridad nunca los enciende ni los trae.

## Protocolo

- Una línea de orden empieza con `#insto` y un espacio, y sigue con una orden en español canónico que `PhoneCommandParser` ya entiende:
  - `#insto poner una alarma a las 07:00`
  - `#insto poner un temporizador de 25 minutos`
  - `#insto poner el volumen al 30 por ciento`
- La línea tiene que *empezar* con `#insto`, en minúsculas (se ignoran los espacios iniciales). Un `#insto` en medio de una oración es texto común.
- Se leen como máximo **3 líneas por mensaje**; las demás se ignoran. El resto del mensaje no se interpreta.
- No hay JSON ni parámetros libres: la línea pasa por el mismo parser que un pedido escrito. No usa Gemini, Groq, frases aprendidas, estructuras aprendidas ni el contexto operativo. **Solo la gramática explícita.**
- Hay tres formas extra, todas fijas: el envoltorio de una **orden diferida** (`a las HH:MM: …`, `en N minutos: …`, ver [Órdenes diferidas](#órdenes-diferidas-1140)), su única **condición de clima** y las **consultas** `ver catálogo`, `ver programadas` y `cancelar programadas`.
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
| Consultas (1.14.0) | `#insto ver catálogo` · `#insto ver programadas` · `#insto cancelar programadas` | No: no hacen nada en el teléfono |

Los ejemplos salen de una sola fuente en el código (`InstinctProposalValidator.examples`, junto a la lista blanca) y un test comprueba que cada uno es una orden válida.

**Prohibidas siempre:** llamadas (`Dial`, `CALL_DIRECT`), cualquier envío de mensajes o archivos, compartir texto, `SavePlace`, navegación, rutinas que no habilitaste (o que cambiaron desde que las habilitaste), workflows y planes de varios pasos armados a partir del texto, y volumen multimedia temporal.

## Cómo llega y se ejecuta

`NotificationAccessService.onNotificationPosted` → `InstinctProposalIntake` → `InstinctProposalFilter` → `InstinctProposalValidator` → `InstinctProposalCenter`.

1. **Interruptor.** Con «Recibir órdenes de Instinct» apagado, `onNotificationPosted` retorna antes de tocar la notificación.
2. **Filtros**, en orden, cortando en el primero que falle:
   1. Paquete de la app de WhatsApp configurada.
   2. No es un grupo (`EXTRA_IS_GROUP_CONVERSATION`).
   3. El remitente del mensaje más nuevo coincide **exactamente** con el nombre del contacto configurado (sin `MessagingStyle`, el título). Una respuesta tuya en el mismo chat no cuenta, y tampoco un acuse de Insto.
   4. Mensaje nuevo: dedupe por `key` de la notificación más un SHA-256 del texto, en memoria.
   5. Tiene líneas `#insto`.
3. **Validación de cada línea:** primero las consultas y el envoltorio de las diferidas; después, para la orden (o la orden interior), `RequestSafetyPolicy` (también descarta preguntas), `PhoneCommandParser`, `SemanticCommandGuard.review` con la línea como texto del pedido y la lista blanca (más las rutinas habilitadas).
4. **Ejecución:**
   - **Flotante cerrado:** `PhoneActionExecutor` en el proceso de Insto, sin `confirmed`. Si sale bien, llega «Instinct hizo: …» en el canal «Insto · acciones de Instinct» (con **Deshacer** si se puede revertir). Si falla, «Instinct pidió: … No se pudo: …». Si el resultado es incierto, se avisa y **no se repite**.
   - **Flotante abierto y libre:** se ejecuta en la sesión del panel, como una orden local. **Flotante ocupado** (texto sin enviar, grabación, interpretación, confirmación, plan o elección): la orden espera y se ejecuta sola cuando el panel se libera. Un evento de calendario pasa por la vista previa de siempre.
   - **Diferida:** se guarda y se ejecuta por este mismo camino cuando llega su hora.
5. **Cuándo espera tu toque en lugar de ejecutarse** (notificación «Instinct propone: …» con **Confirmar**/**Descartar**, en el canal «Insto · propuestas de Instinct»):
   - la [autonomía](#autonomía-por-acción-1160) de esa categoría es **Confirmar**, o es la hora de silencio (desde 1.16.0; reemplaza a «Pedirme confirmación antes de ejecutar»);
   - el teléfono está bloqueado;
   - la orden (o algún paso de la rutina) abre otra app, el panel está cerrado y el servicio de Accesibilidad de Insto no está conectado (ver Límites);
   - Instinct ya pidió 6 acciones en los últimos 10 minutos (freno anti-bucle; una rutina cuenta como una);
   - una orden diferida se disparó con más de 15 minutos de atraso;
   - la acción necesita algo tuyo: confirmar «esa hora ya pasó», confirmar un modo temporal, un permiso o elegir entre varias apps.

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

**Migración.** Si en 1.15.0 tenías encendido «Pedirme confirmación antes de ejecutar» (`instinct-proposals-ask-first`), todas las categorías arrancan en **Confirmar** y ese archivo se borra. La política vive en `noBackupFilesDir` (`instinct-autonomy.json`, escritura atómica); nunca entra en copias de seguridad ni se restaura. Si el archivo no se puede leer, todo queda en Confirmar hasta que vuelvas a elegir.

**La política solo puede endurecer.** Los bloqueos que ya existían (teléfono bloqueado, abrir otra app sin Accesibilidad, freno anti-bucle, diferida atrasada más de 15 minutos, la acción necesita algo tuyo) siguen mandando sobre **Solo**. Es estructural: `InstinctProposalCenter.gate` decide primero esos bloqueos y después la política (`InstinctAutonomyGate.combine`); el freno se consume último, solo si la orden de verdad se ejecuta. **Nunca** gana sobre todo.

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

El bloque de instrucciones para el prompt de Instinct ya no se escribe a mano: lo genera `InstinctProtocolCatalog` (lógica pura) a partir del código. Incluye las reglas del protocolo, una forma de ejemplo por cada tipo permitido, las rutinas que habilitaste (**solo los nombres**, nunca los pasos), las consultas de solo lectura, los prefijos de los acuses (o, con la bitácora, cómo leerla) y la versión del protocolo («Protocolo Insto v3» desde 1.15.0).

- **Ajustes → Propuestas de Instinct → «Copiar instrucciones para Instinct»** lo copia al portapapeles. Si las respuestas están activas y hay una acción «Responder» vigente (de un mensaje de Instinct de los últimos 10 minutos), aparece **«Enviarlas a Instinct»**: lo manda por ese chat.
- La copia que viaja por WhatsApp marca cada ejemplo con «› » delante y lo explica, porque Insto lee ese chat y nunca debe tomar su propio mensaje por órdenes. Igual que un acuse, se comprueba que no tenga ninguna línea que empiece con «#insto ».
- `#insto ver catálogo` es una orden de **solo lectura**: no ejecuta nada en el teléfono, no avisa y no consume el freno. Si las respuestas están activas (en cualquiera de los dos modos), contesta `#insto-ok ver catálogo → Protocolo Insto v3 · rutinas habilitadas: Estudio.` (no el catálogo entero).
- Copias de referencia, generadas sin rutinas: [`instinct-catalog.txt`](instinct-catalog.txt) (canal WhatsApp) y [`instinct-catalog-bitacora.txt`](instinct-catalog-bitacora.txt) (con bitácora, la copia que viaja por WhatsApp, sin URL ni token). Un test compara las dos con lo que genera el código; para regenerarla, corré `INSTO_WRITE_CATALOG=1 python3 scripts/test-phone-core.py com.example.phone.InstinctRoutinesTest`.

## Órdenes diferidas (1.14.0)

El envoltorio solo dice **cuándo**; la orden interior pasa por el validador intacta, al recibirla y otra vez al dispararse.

```text
#insto a las HH:MM: <orden>       24 horas; si esa hora ya pasó hoy (o es justo ahora), es mañana
#insto en N minutos: <orden>      también «en N horas»; de 1 minuto a 24 horas
```

- **Los dos puntos después del tiempo son obligatorios.** Sin ellos la línea es una orden inmediata común, así que `#insto poner una alarma a las 07:00` conserva su significado.
- **Al recibirla** se valida la orden interior con `InstinctProposalValidator.check` en ese momento. Si es inválida, se descarta y se responde `#insto-error`. Si es válida, se guarda y se responde `#insto-programada <orden> → a las 22:00`. También te llega un aviso «Instinct programó: …». No se anidan envoltorios ni consultas.
- **Almacenamiento:** `instinct-scheduled.json` en `noBackupFilesDir`, con escritura atómica (como `ConfiguredRoutineStore`): id, orden completa e interior (texto), hora de disparo en tiempo de pared, huella de la configuración, condición opcional y un token aleatorio. Como máximo **10 programadas** y **24 horas** a futuro. Una restauración nunca las trae.
- **Disparo:** `AlarmManager.setAndAllowWhileIdle` (`setExactAndAllowWhileIdle` solo si `canScheduleExactAlarms`) hacia `InstinctScheduleReceiver`, que no está exportado; la alarma lleva el token de la orden. Se reprograma todo en `BOOT_COMPLETED` y cuando cambia la hora (`TIME_SET`) o la zona horaria (`TIMEZONE_CHANGED`). La hora guardada es un instante: si cambiás de zona, se dispara en el mismo instante, no a la misma hora local.
- **Al dispararse:**
  1. Se marca **consumida en disco antes de cualquier otra cosa**: cada programada se ejecuta como máximo una vez. Si el proceso muere en el medio, al reiniciar se avisa «Pudo haberse ejecutado… no se repite» y nunca se vuelve a ejecutar (es un resultado incierto).
  2. Se vuelve a validar desde cero: interruptor encendido, huella vigente y orden válida con el parser de ese momento. Si algo falla, no se ejecuta y se avisa por qué.
  3. Si tiene condición, se evalúa ([Condiciones por clima](#condiciones-por-clima-1140)).
  4. Si llegó con **más de 15 minutos de atraso** (Doze, teléfono apagado), no se ejecuta sola: queda para Confirmar con el motivo «Llegó tarde».
  5. Si no, pasa por `autoRunBlocker` y `runInBackground` como cualquier orden (o al panel, si está abierto).
- **Gestión:** `#insto ver programadas` (solo lectura, responde por el acuse con la hora y la orden de cada una) y `#insto cancelar programadas` (cancela todas las que vinieron de Instinct). En **Ajustes → Propuestas de Instinct → Órdenes programadas por Instinct** ves la lista con hora y resumen y podés cancelar cada una. Apagar «Recibir órdenes de Instinct» las cancela todas.
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
 "detail": "Volumen multimedia ajustado.", "message": "3fa94c0b12de", "protocol": "Protocolo Insto v3"}
```

- `id`: UUID aleatorio generado al crear el evento. El script descarta los ids repetidos y los confirma igual: un reintento nunca duplica una fila.
- `at`: el instante, ISO-8601 con zona.
- `kind`: el prefijo del acuse sin `#insto-`: `ok`, `espera`, `error`, `incierto`, `programada`, `descartada`, `vencida`, `deshecho` (y `prueba`, solo desde el botón).
- `order`: la orden original recortada a 80 caracteres, como en el chat.
- `detail`: el mismo texto de resultado del acuse en el chat (`InstinctAck`), con los mismos recortes y el mismo aplanado de saltos de línea y caracteres de control.
- `message`: los primeros 12 dígitos hexadecimales del SHA-256 que ya usa el dedupe (clave de la notificación más texto). Identifica el mensaje de Instinct que originó la orden; **nunca es el texto**.
- `protocol`: «Protocolo Insto v3».

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

Con Bitácora o Ambos, el catálogo («Protocolo Insto v3») reemplaza la explicación de los prefijos por cómo leer la bitácora:

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

Con la bitácora, el catálogo («Protocolo Insto v4») suma la sección «Estado del teléfono»: qué campos hay, cómo leerlos (`action=estado`) y la regla **«antes de una orden que dependa del estado (silencio, No molestar, alarmas, calendario), mirá el último estado; si tiene más de 30 minutos, pedí la consulta «ver estado» y esperá»**. Con el canal en WhatsApp esa sección no aparece, porque Instinct no tiene cómo leer la bitácora.

## Idempotencia y vencimiento

- Las órdenes inmediatas viven solo en memoria (`ProposalBook`). Cada una se ejecuta como máximo una vez gracias a un `claim` atómico. Si WhatsApp vuelve a publicar la misma notificación, no pasa nada.
- Las que esperan confirmación vencen a los **10 minutos** (unos 2 segundos antes si hay un acuse pendiente, para poder avisar el vencimiento). Una vencida, descartada o ya ejecutada no se puede volver a ejecutar.
- Las diferidas se guardan en disco y se marcan consumidas **antes** de ejecutarse: nunca se repiten.
- Cada orden guarda la huella de la configuración con la que llegó (contacto y app de WhatsApp). Si eso cambió o se apagó el interruptor, no se ejecuta, tampoco una diferida.
- Confirmar desde la notificación exige, además del id, un **token aleatorio** que solo viaja en el `PendingIntent` de Insto, porque `AssistActivity` está exportada. Lo mismo vale para Deshacer y para la alarma de una programada.
- Un resultado incierto nunca se repite: ni una orden suelta, ni un paso de rutina, ni una programada interrumpida.

## Privacidad

- La lectura es **pasiva**: con el interruptor encendido, Insto mira cada notificación de WhatsApp y lee solo el último mensaje del chat individual con el contacto configurado. De ese mensaje usa únicamente las líneas `#insto`. Las notificaciones de otras apps solo se miran por su paquete. Las de grupos y otros contactos se descartan antes de leer el texto.
- El texto del mensaje no se guarda, no se registra (Logcat muestra solo contadores, en debug), no sale del teléfono y no entra en copias de seguridad. La única excepción es la orden de una diferida, que se guarda en `noBackupFilesDir` hasta que se dispara o la cancelás.
- Los interruptores, el modo de respuesta, los permisos de rutinas y las programadas viven en `noBackupFilesDir`: una restauración nunca enciende ni trae nada de esto.
- El diagnóstico de Ajustes muestra estados y el motivo del último resultado. Nunca muestra el contenido del mensaje.
- Los acuses a Instinct (apagados por defecto) solo repiten la orden que Instinct mandó y el texto de resultado que Insto ya te muestra. La acción «Responder» que los envía vive en memoria como máximo 10 minutos y nunca se guarda. A Instinct le llegan los nombres de las rutinas habilitadas, nunca sus pasos.
- **Bitácora (1.15.0, apagada por defecto):** con «Bitácora» o «Ambos», ahora sí sale algo del teléfono, solo hacia el Apps Script que vos configuraste: la orden recortada a 80 caracteres, el texto de resultado que Insto ya te muestra, el tipo de acuse, la hora y un hash corto del mensaje (12 dígitos hexadecimales). **Nunca** el texto del mensaje de Instinct ni datos del teléfono (contactos, número, ubicación, notificaciones). La URL, el token y la bandeja de salida viven en `noBackupFilesDir`: nunca entran en copias de seguridad. La bitácora queda en tu planilla de Google, con los permisos que le des.
- Fuera de la bitácora, la única red en uso es la del clima que ya existía (SMN y Open-Meteo), y solo cuando se dispara una orden con condición.

## Lo que no hacen las órdenes

- No alimentan el aprendizaje: no crean frases (`LearnedPhraseStore`) ni estructuras (`PhrasePatterns`).
- No cuentan como turnos de `OperationalContext` ni lo modifican, tampoco los pasos de una rutina pedida por Instinct. Eso sí: al ejecutar un control multimedia sin app indicada, `PhoneActionExecutor` sigue eligiendo la sesión con el contexto vigente, como con cualquier pedido.
- No se juntan en planes: cada línea es un comando independiente. Una rutina es un plan que **vos** configuraste y habilitaste; Instinct solo la nombra.
- **Condiciones:** la única forma admitida es la del clima, con hora y una sola por línea ([Condiciones por clima](#condiciones-por-clima-1140)). Cualquier otra condición («si estoy en casa», «si llueve y hace frío») se descarta.
- No agregan dependencias ni servidores propios. Los acuses usan la acción «Responder» de WhatsApp, que ya existe en la notificación, o tu propio Apps Script (con OkHttp y `JobScheduler`, que ya estaban).

## Límites conocidos

- **El nombre no prueba el número.** Android no expone el número del remitente de WhatsApp. Si otro contacto tuyo se llama exactamente igual que Instinct, sus `#insto` también se ejecutarían. Por eso el contacto de Instinct necesita un nombre único. Lo acotan la lista blanca, el freno y el aviso posterior.
- **Abrir otra app desde segundo plano.** Desde Android 10, una app no puede abrir pantallas si no está en primer plano. La excepción es tener un servicio de Accesibilidad conectado. Sin la Accesibilidad de Insto, alarma, temporizador, abrir apps, búsquedas y eventos esperan tu toque. Volumen, linterna, silencio y música siempre se ejecutan solos.
- La comparación usa el nombre que **muestra WhatsApp** (el de tu agenda). Si en Insto guardaste a Instinct con otro nombre, no se lee nada.
- Si Android acaba de iniciar el proceso de Insto y todavía no cargó los ajustes, esa notificación se descarta.
- Si tenés abierto el chat de Instinct en WhatsApp, WhatsApp no genera notificación y no llega nada.
- **Acuses:** dependen de que WhatsApp ofrezca «Responder» en la notificación. Si no lo hace, no se responde (lo dice el diagnóstico). Un acuse de una diferida que se dispara después de 10 minutos no sale.
- **Bitácora:** depende de la red y de que Apps Script responda. Sin red, los eventos esperan en la bandeja (hasta 200) y se reintentan solos; si Google cambia la redirección de Apps Script, el diagnóstico dirá «respuesta inválida». Las cuotas diarias de Apps Script (ejecuciones y URL Fetch) alcanzan de sobra para uso personal: cada lote de hasta 50 eventos es una sola ejecución. Las líneas en memoria se pierden si el proceso muere, salvo las de las diferidas: un seguimiento de una orden inmediata que estaba en espera durante un reinicio no se registra.
- **Diferidas:** sin permiso de alarmas exactas (Insto no lo pide), Android puede demorar el disparo; si la demora pasa de 15 minutos, la orden espera tu confirmación.
- Requiere Android 9 o posterior.

## Cómo probarlo en el teléfono

1. Configurá el contacto de Instinct con el nombre exacto que muestra WhatsApp.
2. Andá a **Ajustes → Propuestas de Instinct**, activá «Recibir órdenes de Instinct» y habilitá «Insto · notificaciones» y las notificaciones.
3. Sin escribirle nada antes, que Instinct mande `#insto poner el volumen al 30 por ciento`: el volumen cambia y llega «Instinct hizo: …» con **Deshacer**.
4. Con la Accesibilidad de Insto activa, `#insto poner un temporizador de 1 minuto` se crea solo. Sin ella, llega «Instinct propone: …» con Confirmar.
5. Elegí «Responderle a Instinct: Siempre» y repetí el paso 3: en el chat aparece `#insto-ok poner el volumen al 30 por ciento → Volumen multimedia ajustado.`
6. Si algo no pasa, mirá **Diagnóstico** en la misma pantalla y tocá «Actualizar diagnóstico».
7. Bitácora: configurá el Apps Script ([Bitácora](#bitácora-1150)), elegí «Canal de estados: Bitácora», guardá la URL y el token y tocá «Probar bitácora»: aparece una fila `prueba` en la planilla. Repetí el paso 3: el chat no recibe nada y la planilla suma una fila `ok`.

## Instrucciones para pegar en el prompt de Instinct

El bloque se genera desde el código y se obtiene en **Ajustes → Propuestas de Instinct → «Copiar instrucciones para Instinct»** (o «Enviarlas a Instinct»). Así nunca se desincroniza de la gramática ni de tus rutinas habilitadas. Copialo de nuevo cada vez que habilites o deshabilites una rutina, o cuando Insto se actualice.

La copia de referencia, sin rutinas, está en [`instinct-catalog.txt`](instinct-catalog.txt). Con la bitácora, la copia de referencia es [`instinct-catalog-bitacora.txt`](instinct-catalog-bitacora.txt). Incluye las formas diferidas y condicionales, los prefijos de los acuses (`#insto-ok`, `#insto-espera`, `#insto-error`, `#insto-incierto`, `#insto-programada`, y `#insto-descartada`, `#insto-vencida` y `#insto-deshecho` como seguimiento) y la regla para Instinct: **esas líneas son informativas; no se responden con nuevas órdenes, salvo una sola orden corregida después de un `#insto-error`, y nunca se repite una orden después de `#insto-incierto` o `#insto-espera`.**
