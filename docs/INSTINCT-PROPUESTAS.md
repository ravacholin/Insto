# Órdenes de Instinct («Instinct con manos», 1.13.0 y 1.14.0)

Instinct puede pedirle acciones al teléfono **por su cuenta**: cuando lo considera útil, escribe en su chat de WhatsApp una o más líneas `#insto <orden>`. Insto las detecta en la notificación de ese contacto, las interpreta con la gramática local, las valida igual que cualquier pedido y **las ejecuta sin preguntar**. Después avisa con una notificación «Instinct hizo: …». No hace falta que vos le hayas escrito antes a Instinct.

Desde 1.14.0, además: Insto le puede **responder** a Instinct con el resultado de cada orden, Instinct puede pedir **rutinas** que vos le habilitaste, el bloque de instrucciones para Instinct se **genera desde el código**, las órdenes pueden quedar **programadas** para más tarde (con una única condición de clima) y lo que se puede revertir trae **Deshacer**.

La función está **apagada por defecto**: **Ajustes → Propuestas de Instinct → «Recibir órdenes de Instinct»**. Con **«Pedirme confirmación antes de ejecutar»** encendido, cada orden espera tu toque o un «dale». Todos los interruptores y estados de esta función viven en `noBackupFilesDir`: una restauración de copia de seguridad nunca los enciende ni los trae.

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
   - encendiste «Pedirme confirmación antes de ejecutar»;
   - el teléfono está bloqueado;
   - la orden (o algún paso de la rutina) abre otra app, el panel está cerrado y el servicio de Accesibilidad de Insto no está conectado (ver Límites);
   - Instinct ya pidió 6 acciones en los últimos 10 minutos (freno anti-bucle; una rutina cuenta como una);
   - una orden diferida se disparó con más de 15 minutos de atraso;
   - la acción necesita algo tuyo: confirmar «esa hora ya pasó», confirmar un modo temporal, un permiso o elegir entre varias apps.

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

El bloque de instrucciones para el prompt de Instinct ya no se escribe a mano: lo genera `InstinctProtocolCatalog` (lógica pura) a partir del código. Incluye las reglas del protocolo, una forma de ejemplo por cada tipo permitido, las rutinas que habilitaste (**solo los nombres**, nunca los pasos), las consultas de solo lectura, los prefijos de los acuses y la versión del protocolo («Protocolo Insto v2»).

- **Ajustes → Propuestas de Instinct → «Copiar instrucciones para Instinct»** lo copia al portapapeles. Si las respuestas están activas y hay una acción «Responder» vigente (de un mensaje de Instinct de los últimos 10 minutos), aparece **«Enviarlas a Instinct»**: lo manda por ese chat.
- La copia que viaja por WhatsApp marca cada ejemplo con «› » delante y lo explica, porque Insto lee ese chat y nunca debe tomar su propio mensaje por órdenes. Igual que un acuse, se comprueba que no tenga ninguna línea que empiece con «#insto ».
- `#insto ver catálogo` es una orden de **solo lectura**: no ejecuta nada en el teléfono, no avisa y no consume el freno. Si las respuestas están activas (en cualquiera de los dos modos), contesta `#insto-ok ver catálogo → Protocolo Insto v2 · rutinas habilitadas: Estudio.` (no el catálogo entero).
- Copia de referencia, generada sin rutinas: [`instinct-catalog.txt`](instinct-catalog.txt). Un test compara esa copia con lo que genera el código; para regenerarla, corré `INSTO_WRITE_CATALOG=1 python3 scripts/test-phone-core.py com.example.phone.InstinctRoutinesTest`.

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
- **Acuse al dispararse:** solo si la acción «Responder» del mensaje original sigue vigente (10 minutos), es decir, en la práctica para demoras cortas. Si no, el resultado solo te llega a vos.

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
- La única red nueva en uso es la del clima que ya existía (SMN y Open-Meteo), y solo cuando se dispara una orden con condición.

## Lo que no hacen las órdenes

- No alimentan el aprendizaje: no crean frases (`LearnedPhraseStore`) ni estructuras (`PhrasePatterns`).
- No cuentan como turnos de `OperationalContext` ni lo modifican, tampoco los pasos de una rutina pedida por Instinct. Eso sí: al ejecutar un control multimedia sin app indicada, `PhoneActionExecutor` sigue eligiendo la sesión con el contexto vigente, como con cualquier pedido.
- No se juntan en planes: cada línea es un comando independiente. Una rutina es un plan que **vos** configuraste y habilitaste; Instinct solo la nombra.
- **Condiciones:** la única forma admitida es la del clima, con hora y una sola por línea ([Condiciones por clima](#condiciones-por-clima-1140)). Cualquier otra condición («si estoy en casa», «si llueve y hace frío») se descarta.
- No agregan dependencias ni servidores. Los acuses usan la acción «Responder» de WhatsApp, que ya existe en la notificación.

## Límites conocidos

- **El nombre no prueba el número.** Android no expone el número del remitente de WhatsApp. Si otro contacto tuyo se llama exactamente igual que Instinct, sus `#insto` también se ejecutarían. Por eso el contacto de Instinct necesita un nombre único. Lo acotan la lista blanca, el freno y el aviso posterior.
- **Abrir otra app desde segundo plano.** Desde Android 10, una app no puede abrir pantallas si no está en primer plano. La excepción es tener un servicio de Accesibilidad conectado. Sin la Accesibilidad de Insto, alarma, temporizador, abrir apps, búsquedas y eventos esperan tu toque. Volumen, linterna, silencio y música siempre se ejecutan solos.
- La comparación usa el nombre que **muestra WhatsApp** (el de tu agenda). Si en Insto guardaste a Instinct con otro nombre, no se lee nada.
- Si Android acaba de iniciar el proceso de Insto y todavía no cargó los ajustes, esa notificación se descarta.
- Si tenés abierto el chat de Instinct en WhatsApp, WhatsApp no genera notificación y no llega nada.
- **Acuses:** dependen de que WhatsApp ofrezca «Responder» en la notificación. Si no lo hace, no se responde (lo dice el diagnóstico). Un acuse de una diferida que se dispara después de 10 minutos no sale.
- **Diferidas:** sin permiso de alarmas exactas (Insto no lo pide), Android puede demorar el disparo; si la demora pasa de 15 minutos, la orden espera tu confirmación.
- Requiere Android 9 o posterior.

## Cómo probarlo en el teléfono

1. Configurá el contacto de Instinct con el nombre exacto que muestra WhatsApp.
2. Andá a **Ajustes → Propuestas de Instinct**, activá «Recibir órdenes de Instinct» y habilitá «Insto · notificaciones» y las notificaciones.
3. Sin escribirle nada antes, que Instinct mande `#insto poner el volumen al 30 por ciento`: el volumen cambia y llega «Instinct hizo: …» con **Deshacer**.
4. Con la Accesibilidad de Insto activa, `#insto poner un temporizador de 1 minuto` se crea solo. Sin ella, llega «Instinct propone: …» con Confirmar.
5. Elegí «Responderle a Instinct: Siempre» y repetí el paso 3: en el chat aparece `#insto-ok poner el volumen al 30 por ciento → Volumen multimedia ajustado.`
6. Si algo no pasa, mirá **Diagnóstico** en la misma pantalla y tocá «Actualizar diagnóstico».

## Instrucciones para pegar en el prompt de Instinct

El bloque se genera desde el código y se obtiene en **Ajustes → Propuestas de Instinct → «Copiar instrucciones para Instinct»** (o «Enviarlas a Instinct»). Así nunca se desincroniza de la gramática ni de tus rutinas habilitadas. Copialo de nuevo cada vez que habilites o deshabilites una rutina, o cuando Insto se actualice.

La copia de referencia, sin rutinas, está en [`instinct-catalog.txt`](instinct-catalog.txt). Incluye las formas diferidas y condicionales, los prefijos de los acuses (`#insto-ok`, `#insto-espera`, `#insto-error`, `#insto-incierto`, `#insto-programada`, y `#insto-descartada`, `#insto-vencida` y `#insto-deshecho` como seguimiento) y la regla para Instinct: **esas líneas son informativas; no se responden con nuevas órdenes, salvo una sola orden corregida después de un `#insto-error`, y nunca se repite una orden después de `#insto-incierto` o `#insto-espera`.**
