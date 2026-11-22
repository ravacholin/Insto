# Ejecución de órdenes de Instinct: auditoría de 1.19.0 y diario transaccional (1.20.0)

Este documento sigue al de transporte ([`instinct-push-reliability.md`](instinct-push-reliability.md)). Aquel resolvió
**cómo llega** una orden (timbre cifrado, cola de recepción, deduplicación). Este trata **qué pasa después**: cuándo una
orden queda aceptada para ejecutarse, cuándo se ejecuta, cómo se comprueba el resultado y cómo se recupera Insto si
el proceso muere a mitad de camino.

Igual que en el documento anterior se separan **defectos comprobados** (se ven en el código de 1.19.0 y, cuando se
pudo, en una prueba), **riesgos teóricos** (posibles, no demostrados) y **limitaciones de Android**.

Leído antes de tocar código: `docs/INSTINCT-PROPUESTAS.md`, `docs/LOCAL-INTENTS.md`,
`docs/architecture/instinct-push-reliability.md`, `docs/releases/Insto-1.19.0.md`, `docs/releases/Insto-1.12.0-backup.md`,
`docs/instinct-bitacora.gs`, `relay/insto-webpush-relay/README.md`, `InstinctPushInbox.kt`, `InstinctPushService.kt`,
`InstinctPushConnector.kt`, `InstinctDoorbell*.kt`, `InstinctMailbox*.kt`, `InstinctProposalCenter.kt`,
`InstinctProposals.kt`, `InstinctAutonomy*.kt`, `InstinctReplies.kt`, `InstinctLog*.kt`, `InstinctSchedule*.kt`,
`InstinctScheduler.kt`, `InstinctRoutines.kt`, `InstinctUndo*.kt`, `PhoneActionExecutor.kt`,
`PlatformActionExecutor.kt`, `TemporaryMode*.kt`, `WorkflowRunner.kt`, `WorkflowStore.kt`, `backup/*.kt`,
`BackupRestoreSettings.kt`, `InstinctProposalSettings.kt`, `AndroidManifest.xml`, `strings.xml`, `app/build.gradle.kts`
y sus pruebas.

## 1. Cuatro etapas que 1.19.0 no distinguía

| Etapa | Qué prueba | Dónde quedaba en 1.19.0 |
| --- | --- | --- |
| **Transporte** | Llegó algo, y viene de quien dice | `InstinctPushInbox` (disco), firma HMAC, `instinct-mailbox-seen.json` |
| **Aceptación** | Esta orden se va a ejecutar como mucho una vez | `InstinctMailboxSeen.claimAll` (disco) |
| **Ejecución** | Se intentó, con tal resultado | `ProposalBook` (memoria) + `PhoneActionExecutor` (sin registro) |
| **Comprobación** | El teléfono quedó como se pidió | parcial: solo silencio y No molestar releían el estado |

La aceptación era durable, pero **no decía qué había que ejecutar**: `seen` guarda solamente ids. Lo que había que
ejecutar viajaba en memoria desde que se reclamaba el id hasta que el hilo principal corría la propuesta.

## 2. Recorrido real de una orden en 1.19.0

```text
push ─▶ PushInbox.add (disco) ─▶ dispatcher.order():
          verify ─▶ seen.claimAll (DISCO: id reclamado) ─▶ inbox.markClaimed (disco)
          ─▶ InstinctProposalIntake.onMailbox()                         ┐
               fingerprint(app) == null ? return  ◀── en silencio        │ todo en memoria
               validar ─▶ ProposalBook.offer (memoria)                    │
               ─▶ main.post { runInBackground }  ◀── todavía no ejecutó   │
          ─▶ inbox.remove (DISCO: ya no queda rastro de la orden)        ┘
                         … más tarde, en el hilo principal …
          runInBackground: gate ─▶ book.claim ─▶ PhoneActionExecutor.execute ─▶ acuse (bandeja en disco)

sondeo ─▶ InstinctMailboxPoll.process: verify ─▶ seen.claimAll (DISCO) ─▶ onMailbox (como arriba) ─▶ POST tomadas
```

## 3. Defectos comprobados

1. **Ventana de pérdida silenciosa entre la cola push y la ejecución.** `InstinctPushDispatcher.order()` quita la entrada
   de la cola (`inbox.remove`) en cuanto `onMailbox()` vuelve, y `onMailbox()` vuelve **antes** de ejecutar: la
   ejecución queda en un `Runnable` publicado al hilo principal (`InstinctProposalCenter.deliver`, `main.post`). Si el
   proceso muere entre `inbox.remove` y el momento en que el hilo principal corre `runInBackground`, la orden no está
   en la cola, su id está reclamado (no se reintenta) y no queda ningún acuse. La ventana suele ser de milisegundos,
   pero existe por construcción y crece con el hilo principal ocupado.
2. **El sondeo no tiene ninguna red de seguridad.** `InstinctMailboxPoll.process()` reclama los ids en disco y recién
   después `pollOnce()` llama a `onMailbox()`. Una muerte del proceso entre ambos, o una excepción dentro de
   `onMailbox()` (que `pollOnce` traga con `runCatching`), deja la orden reclamada, sin ejecutar y sin informar. Para el
   push, 1.19.0 al menos informa `incierta`; para el sondeo, nada.
3. **Arranque en frío: una orden reclamada se descarta sin aviso.** `onMailbox()` termina con
   `fingerprint(app) ?: return`, y `fingerprint()` devuelve `null` mientras `AssistantPreferencesCache` no cargó
   DataStore. Es justo el caso en que el distribuidor o el job de rescate despiertan un proceso nuevo: `onCreate` de la
   aplicación empieza a cargar las preferencias y, en paralelo, el hilo `insto-push-dispatch` ya reclamó el id. Si llega
   antes que DataStore, la orden se pierde sin acuse. (WhatsApp tiene el mismo `return`, pero allí el diagnóstico
   `PREFS` al menos lo registra.)
4. **La propuesta no tiene identidad propia.** `ProposalBook.offer()` rechaza una orden si **otra con el mismo comando**
   está pendiente. Entre `offer` y `main.post { runInBackground }` todas están pendientes, así que dos mensajes distintos
   y legítimos de Instinct con la misma línea (por ejemplo, dos «volumen al 30 %» de ids distintos que llegan por push y
   por sondeo casi a la vez) terminan en «ya había una orden igual esperando confirmación» para el segundo.
5. **No hay constancia de «comenzó el despacho».** Entre `book.claim` y el acuse final no se escribe nada. Si el
   proceso muere durante `PhoneActionExecutor.execute`, no hay forma de saber después si la acción empezó; y como la
   entrada push ya se había quitado, ni siquiera se informa `incierta`.
6. **Bloqueo global por pantalla bloqueada, dos veces.** `InstinctGatePreview.blocker()` devuelve `LOCKED` para
   **cualquier** acción con `!foreground && locked`, y además `PhoneActionExecutor.execute()` rechaza **todo** con
   «Desbloqueá el teléfono» si `KeyguardManager.isKeyguardLocked`. Volumen, linterna, No molestar o multimedia, que
   Android permite cambiar con el teléfono bloqueado, quedaban esperando confirmación.
7. **Un modo temporal siempre pide confirmación**, aun con la categoría en Solo: `TemporaryModeManager.execute()` exige
   `confirmed = true` y `runInBackground` llama `execute(command)` con `confirmed = false`; el resultado
   `NEEDS_INPUT` vuelve como «Confirmar». `InstinctGatePreview.needs()` lo documentaba («un modo temporal siempre espera
   la confirmación de la persona»). «Silenciar durante una hora» nunca corría solo.
8. **El modo temporal no puede arrancar su servicio desde segundo plano.** `TemporaryModeManager.execute()` llama
   `startForegroundService` y, si falla (Android 12+ lo prohíbe desde segundo plano salvo exenciones), deshace el
   cambio y relanza la excepción: con Insto en segundo plano y sin exención de batería, el modo temporal fallaba.
9. **La restauración de un modo temporal casi nunca ocurría tras una muerte del proceso.** `InstoApplication.onCreate`
   llama `TemporaryModeManager.loseOwnership()` en cada arranque de proceso, y `end()` solo restaura si
   `lease.owned`. Un proceso que muere y renace en la hora del modo (lo habitual en segundo plano) hace que al vencer
   se conserve el silencio «por si fue un cambio manual», aunque el valor siga siendo exactamente el que puso Insto.
10. **Un traspaso se informa como hecho.** `InstinctAck.kindOf()` devuelve `OK` para `PhoneActionOutcome.HANDOFF`: una
    alarma que el Reloj solo aceptó abrir, una búsqueda o una app abierta llegaban a Instinct como `#insto-ok`, igual
    que un volumen comprobado.
11. **Volumen y linterna no se comprueban.** `PhoneActionExecutor` devuelve «Volumen multimedia ajustado.» y «Linterna
    encendida.» sin releer nada. (Silencio, vibración y No molestar **sí** releen el estado en
    `PlatformActionExecutor.mode()`; el modo temporal también. Eso se conserva.)
12. **El backup no incluye nada de Instinct.** Por diseño de 1.12.0–1.19.0, interruptores, autonomía, bitácora (URL y
    token), clave del buzón y timbre viven en `noBackupFilesDir` y no se exportan: cambiar de teléfono obliga a volver a
    escribir URL, token y clave.
13. **Nombres viejos visibles.** El servicio de Accesibilidad se llama «PingAssist Auto-Send & Triggers», el mosaico de
    Ajustes rápidos y el widget dicen «PingAssist», el atajo «PingAssist Voice» / «Voice Ping», la pantalla principal
    muestra «PingAssist» y varios mensajes de error lo nombran.

## 4. Riesgos teóricos (no demostrados)

- **Muerte entre `seen.claimAll` y `inbox.markClaimed`.** El id queda reclamado y la entrada sin marcar; el siguiente
  `drain()` la verifica, recibe `REPEATED` y la quita como duplicado. La orden se pierde sin acuse. Ventana de una
  escritura de archivo.
- **Escrituras atómicas pero no transaccionales entre archivos.** `seen`, la cola push y la bandeja de la bitácora se
  escriben por separado; no hay forma de que dos de ellos cambien juntos. Cada ventana es pequeña; el diseño nuevo las
  elimina juntando reclamación y registro en una transacción SQLite.
- **`book` y `undos` en memoria**: un proceso nuevo no sabe qué confirmaciones estaban pendientes. La notificación vieja
  responde «ya no está disponible». Es pérdida de una confirmación, no una ejecución doble.
- **Carreras push / sondeo.** No es un defecto: `claimAll` comprueba y marca en la misma sección crítica (confirmado en
  `InstinctDoorbellFlowTest`). Se conserva la propiedad con el almacenamiento nuevo.

## 5. Limitaciones de Android

- **Force-stop.** Una app detenida a la fuerza (por la persona o por el sistema) no recibe broadcasts, alarmas ni jobs
  hasta que se la vuelve a abrir. Es distinto de la muerte normal del proceso, que Android hace todo el tiempo y que
  no impide recibir el siguiente push o job. Ningún diseño puede ejecutar nada en una app detenida a la fuerza.
- **Doze y buckets de espera** posponen jobs y alarmas no exactas; un push de prioridad alta del distribuidor eleva a
  Insto unos segundos. Insto no puede prometer inmediatez bajo Doze.
- **Actividades desde segundo plano** siguen prohibidas: lo que abre otra app (alarmas con UI, búsquedas, apps) necesita
  el teléfono desbloqueado o un toque.
- **Servicios en primer plano desde segundo plano** están prohibidos desde Android 12 salvo exenciones (entre ellas, que
  la persona haya quitado la optimización de batería para Insto).
- **Permisos especiales** (acceso a No molestar, alarmas exactas, notificaciones, optimización de batería) se conceden a
  mano en Ajustes; ninguna app puede concedérselos sola, ni por Accesibilidad.

## 6. Diseño de 1.20.0

### 6.1 Responsabilidades

```text
InstinctPushInbox        recepción confiable del transporte push (sin cambios: 1.19.0)
InstinctExecutionJournal ciclo de vida y resultado de cada orden aceptada (nuevo, SQLite)
                         + registro único de ids reclamados (migrado desde instinct-mailbox-seen.json)
ActionOutcomeVerifier    qué se puede observar de cada acción y qué se observó (nuevo)
InstinctExecutionRecoveryManager  qué hacer con lo que quedó a medias (nuevo; lo llaman los jobs existentes)
```

### 6.2 Reclamar es registrar

La reclamación de un id y la creación de una fila por cada línea de la orden ocurren en **la misma transacción
SQLite** (`InstinctExecutionJournal.admit`). Si la transacción falla no se ejecuta nada (igual que el fallo de disco de
1.19.0). Si el proceso muere después, la orden está en el diario como `RECEIVED` con su línea: la recuperación la
revalida y la continúa si sigue vigente, o la vence. La entrada push se quita solo cuando el diario ya tiene la orden.

### 6.3 Estados

`RECEIVED → VALIDATED → (WAITING | SCHEDULED | READY) → DISPATCHING → VERIFYING → (VERIFIED | HANDED_OFF | FAILED | UNCERTAIN)`;
además `CANCELLED` y `EXPIRED`. `DISPATCHING` se escribe **antes** de tocar el teléfono y, si no se puede escribir, no
se ejecuta. Los estados terminales nunca se reejecutan.

### 6.4 Migración

- `instinct-mailbox-seen.json` se importa al diario en una transacción (ids y fechas). El archivo **no se borra ni se
  vacía**. Mientras dure la retención de 48 h, un id visto en 1.19.0 sigue contando como visto.
- Una entrada `claimed` de `instinct-push-inbox.json` sin fila en el diario (heredada de 1.19.0) se registra como
  `UNCERTAIN` y se informa `incierta` una sola vez; nunca se ejecuta.

## 7. Verificación de resultados (fase 2)

`ActionOutcomeVerifier` lee el teléfono antes y después de cada acción y `ActionOutcomeRules` decide:

| Veredicto | Significa | Estado del diario | Acuse |
| --- | --- | --- | --- |
| `VERIFIED` | se observó el estado pedido | `VERIFIED` | `ok`, «Verificado: …» |
| `NOT_ACHIEVED` | evidencia de que no se alcanzó | `FAILED` | `error`, «No se logró: …» |
| `NOT_OBSERVABLE` | no hay forma confiable de comprobarlo | `HANDED_OFF` | `ok`, «Entregado; Insto no puede comprobar el resultado final.» |
| `INDETERMINATE` | evidencia incompleta o contradictoria | `UNCERTAIN` | `incierto` |

`AckKind` no cambia: un traspaso sigue siendo `ok`, como en 1.19.0, y el texto lo distingue.

Atribución, para no adjudicarle a Insto lo que pudo hacer la persona u otra app:

- `CAUSED`: se leyó antes y después, y cambió por esta ejecución.
- `ALREADY`: ya estaba en el estado pedido antes de ejecutarla.
- `SATISFIED`: el estado se cumple ahora, pero no hay lectura previa (panel, recuperación): no se puede probar que la
  produjo esta ejecución.

### Matriz de acciones verificables

| Acción | Qué se lee | Verificación |
| --- | --- | --- |
| Volumen a un porcentaje | `AudioManager` `STREAM_MUSIC`, índice discreto | exacto: `max × % / 100` (la misma cuenta que el ejecutor); un tope de Android (límite de audición) es `NOT_ACHIEVED` |
| Subir / bajar volumen | índice antes y después | cambió en el sentido pedido; en el tope, `ALREADY` |
| Silencio, vibración, sonido normal | `ringerMode` efectivo | igualdad |
| No molestar | `currentInterruptionFilter` efectivo | activo = cualquier filtro salvo «todas» |
| Linterna | `CameraManager.TorchCallback` | solo si la cámara ya lo informó; si no, `NOT_OBSERVABLE` |
| Reproducir / pausar | `MediaSession` activa (requiere acceso a notificaciones) | si la sesión ya informa el estado; si no, entregada |
| Siguiente / anterior | — | `NOT_OBSERVABLE` |
| Modo temporal | el lease de Insto y el valor actual | aplicado = valor actual igual al aplicado |
| Restauración del modo temporal | valor actual frente al original | registrada como ejecución propia (`RESTORE`) |
| Alarma, temporizador, calendario, búsquedas, abrir apps | — | `NOT_OBSERVABLE`: otra app aceptó el pedido, nada más |
| Consultas (batería, «ver …») | — | la respuesta es lo pedido |

En el hilo principal nunca se espera: lo que Android todavía no informó cuenta como no observable.

## 8. Recuperación (fase 3)

`InstinctExecutionRecoveryManager` corre dentro de lo que ya existía: el job de vaciado del timbre (`KIND_DRAIN`), el
receptor de arranque y actualización y la apertura de Insto (`InstinctMailbox.pollSoon`). No hay otro servicio, ni
sondeo, ni wake lock.

Una fila no terminal de **otro proceso** (el campo `owner` guarda un id aleatorio por proceso) quedó de un proceso que
murió. La recuperación la adopta con una escritura condicional (dos recuperaciones nunca actúan sobre la misma fila) y
decide con `ExecutionRecoveryPolicy`:

| Estado encontrado | Clase de acción | Qué hace |
| --- | --- | --- |
| `RECEIVED`, `VALIDATED`, `READY` | cualquiera | dentro de la ventana (15 min y la vigencia de la firma): revalida con la configuración actual y continúa; fuera: `EXPIRED` |
| `WAITING` | cualquiera | dentro de los 10 min originales: vuelve a ofrecerla **esperando confirmación** (nunca corre sola); fuera: `EXPIRED` |
| `SCHEDULED` | cualquiera | si el programador ya no tiene su serie: termina como su disparo único o `CANCELLED` (sin otro mensaje) |
| `DISPATCHING` | `IDEMPOTENT_SET` | relee: si el estado se cumple, `VERIFIED` con atribución `SATISFIED`; si no, `UNCERTAIN` |
| `DISPATCHING` | `NON_IDEMPOTENT`, `EXTERNAL_HANDOFF` | `UNCERTAIN`, nunca se repite |
| `DISPATCHING` | `READ_ONLY` | `FAILED` («pedila de nuevo») |
| `VERIFYING` | cualquiera | solo repite la lectura |
| terminal | — | nada |

Clases: `READ_ONLY` (consultas), `IDEMPOTENT_SET` (volumen a un porcentaje, linterna, silencio, vibración, No
molestar), `NON_IDEMPOTENT` (subir/bajar, modo temporal, rutinas), `EXTERNAL_HANDOFF` (todo lo que entrega a otra app).

Cada fila que la recuperación termina se informa **una vez** a la bitácora con «(recuperada tras reiniciarse Insto)»,
y solo si no había salido ya un acuse para ella (`reported`). El transporte no informa nada de una orden que tiene
filas: si la cola push encuentra una entrada reclamada por un proceso muerto, consulta el diario; solo una entrada
heredada de 1.19.0 sin filas se informa `incierta` (y queda en el diario como `UNCERTAIN`, transporte `LEGACY`).

### Reinicios y restricciones reales

| Situación | Qué pasa |
| --- | --- |
| Muerte normal del proceso | el siguiente push, job o alarma arranca un proceso nuevo; la recuperación corre en el vaciado |
| Reinicio del teléfono | `BOOT_COMPLETED`: el receptor de la bitácora programa el vaciado; el programador rearma sus alarmas |
| Actualización de Insto | `MY_PACKAGE_REPLACED`: igual que el arranque |
| Error de almacenamiento | sin diario no se reclama ni se ejecuta; la orden queda en la planilla o en la cola push para el próximo intento |
| Pérdida de red | lo recibido ya está en disco; los acuses esperan en la bandeja de la bitácora (job con red) |
| Fallo de la bitácora | no detiene ninguna orden; la bandeja reintenta |
| Reinicio del distribuidor push | la biblioteca registra de nuevo; el sondeo de respaldo cubre el hueco |
| Force-stop (persona o sistema) | Android no entrega nada hasta abrir Insto; al abrirlo, la recuperación corre |
| Arranque en frío sin preferencias | la orden queda `RECEIVED` y el vaciado la reanuda (antes se perdía) |

### Modo temporal

`InstoApplication` ya no quita la propiedad del modo temporal en cada proceso nuevo: solo la quita si el valor actual
difiere del aplicado (un cambio manual evidente). Al vencer, restaurar sigue exigiendo que el valor sea exactamente el
aplicado. El reinicio del teléfono sigue siendo conservador (pierde la propiedad), como en 1.19.0.

## 9. Autonomía con el teléfono bloqueado (fase 4)

El bloqueo global `!snapshot.foreground && snapshot.locked -> LOCKED` de `InstinctGatePreview.blocker()` y el rechazo
de todo con «Desbloqueá el teléfono» de `PhoneActionExecutor.execute()` se reemplazaron por `LockScreenPolicy`: una
acción espera el desbloqueo solo si abre una pantalla. Una prueba (`InstinctLockScreenTest`) impide volver al bloqueo
global.

### Matriz de acciones con el teléfono bloqueado o la pantalla apagada

| Acción | Bloqueado, Insto en segundo plano | Qué hace falta |
| --- | --- | --- |
| Volumen (porcentaje, subir, bajar) | se ejecuta solo | nada |
| Linterna | se ejecuta solo | permiso de cámara |
| Silencio, vibración, sonido normal | se ejecuta solo | acceso a No molestar (vibración no lo necesita) |
| No molestar | se ejecuta solo | acceso a No molestar |
| Reproducir, pausar, siguiente, anterior | se ejecuta solo | acceso a notificaciones (sesiones multimedia) |
| Modo temporal (volumen, silencio, No molestar por un tiempo) | se ejecuta solo y se restaura a su hora | notificaciones; acceso a No molestar; alarmas exactas para la restauración exacta |
| Restauración de un modo temporal | se ejecuta sola (alarma exacta permitida en Doze) | lo mismo |
| Consultas (batería, «ver …», «probar …») | se responden | nada |
| Rutina con pasos de la lista anterior | se ejecuta sola | lo de cada paso |
| Órdenes programadas, disparadores, recurrencias | se disparan y siguen la misma regla | alarmas exactas |
| Alarma, temporizador, calendario, búsquedas, abrir apps | espera confirmación | abren una pantalla: desbloqueo |

Insto no enciende la pantalla ni abre actividades para nada de la lista. Si el servicio visible del modo temporal no
puede arrancar desde segundo plano (Android 12+ sin exención de batería), el modo se aplica igual y se restaura por su
alarma; solo falta el aviso con «Terminar» hasta abrir Insto.

### Confirmaciones internas eliminadas

| Antes (1.19.0) | Ahora (1.20.0) |
| --- | --- |
| Cualquier orden con el teléfono bloqueado esperaba «Confirmar» | solo las que abren una pantalla |
| `PhoneActionExecutor` rechazaba todo con el teléfono bloqueado (también desde el panel) | solo lo que abre una pantalla |
| Un modo temporal en Solo («silenciá durante una hora») siempre pedía «¿Confirmás?» | en Solo se ejecuta: esa autorización es la de la categoría (`InstinctConfirmations`) |
| El paso «modo temporal» de una rutina autorizada pedía confirmación y detenía la rutina | se ejecuta |
| `probar` decía «un modo temporal siempre espera la confirmación» | ya no |

Se conservan: la categoría en **Confirmar**; **Nunca** (rechaza siempre, también bloqueado); las horas de silencio;
el freno de 6 órdenes en 10 minutos; WhatsApp sin firma con tope en Confirmar; una alarma para una hora ya pasada hoy;
elegir app o contacto; un permiso que falta; y la pregunta de restauración aproximada cuando Android no permite
alarmas exactas (es una degradación real, no una formalidad).

### Permisos: preparar una sola vez

Ajustes → Propuestas de Instinct → «Funcionar con el teléfono bloqueado» lista solo lo que falta, primero lo que una
orden ya necesitó, con un botón por permiso que abre su página de Ajustes **cuando la persona lo toca**. En segundo
plano Insto no muestra diálogos, no reabre Ajustes y no usa Accesibilidad para conceder nada: anota la capacidad
(`instinct-readiness-needed.json`) y la orden queda esperando o falla con el motivo. Lo concedido no se vuelve a pedir.

| Capacidad | Para qué | Cómo se concede |
| --- | --- | --- |
| Notificaciones (`POST_NOTIFICATIONS`, Android 13+) | avisos, confirmaciones, modo temporal | diálogo del sistema desde Ajustes de Insto |
| Acceso a No molestar | silencio, No molestar | Ajustes → Acceso a No molestar |
| Alarmas y recordatorios (`SCHEDULE_EXACT_ALARM`, Android 12+) | restauración y programadas a la hora exacta | Ajustes → Alarmas y recordatorios |
| Sin optimización de batería | menos demoras en Doze; servicio visible del modo temporal | Ajustes → Optimización de batería |
| Acceso a notificaciones | WhatsApp y sesiones multimedia | Ajustes → Acceso a notificaciones |
| Cámara | linterna | diálogo del sistema |
| Accesibilidad de Insto (opcional) | abrir otras apps sin tocar | Ajustes → Accesibilidad |

### Doze y pantalla apagada

No hay servicio permanente ni wake locks nuevos. Lo que llega por push (prioridad alta del distribuidor) se procesa en
la ventana que Android le da a Insto; lo que llega por el sondeo de respaldo, cuando JobScheduler lo deja correr. Las
restauraciones y las programadas usan `setExactAndAllowWhileIdle`. Lo que Android pospone queda en el diario
(`RECEIVED`) y se continúa si sigue vigente; si venció, se informa `vencida`.

## 10. Backup completo (fase 6)

El `.insto` de 1.12.0 se extiende sin cambiar su cifrado (AES-256-GCM, PBKDF2-HMAC-SHA256, cabecera autenticada) ni
su esquema (sigue en 1): se agrega la categoría versionada `instinct`. Una copia de 1.12.0–1.19.0 no la tiene y se
restaura igual; un Insto anterior que abre una copia de 1.20.0 la informa como categoría desconocida y restaura el
resto.

- **Copia completa**: incluye las claves de Groq y Gemini, el token de la bitácora y la clave del buzón.
- **Copia sin secretos**: ninguna clave de API, token ni clave de autenticación (la URL del Apps Script sí: es
  configuración).
- Importar escribe archivos de configuración directamente: no arma jobs, no registra el timbre, no avisa al script, no
  publica el catálogo y no ejecuta nada. «Recibir órdenes», el buzón y el timbre **se restauran apagados** y lo que
  estaba encendido queda como pedido de reactivación (`instinct-reactivation.json`, sin secretos).
- **Reactivar Instinct en este teléfono** (Ajustes → Propuestas de Instinct) es la autorización conjunta: revisa
  permisos, enciende, prueba bitácora y buzón, registra UnifiedPush de nuevo (claves Web Push y endpoint propios de
  este teléfono, timbre cifrado regenerado; el script recibe el endpoint nuevo), vuelve a programar las recurrentes
  (revalidadas; nunca una orden única consumida o vencida), rearma los jobs y publica el catálogo. Lo que no se pudo
  hacer queda pendiente; lo hecho no se repite.
- Nunca se exporta: el endpoint, el timbre cifrado, el estado del timbre, la base de la biblioteca UnifiedPush, las
  claves del Keystore, el diario de ejecución ni las colas.

El inventario completo está en `backup/BackupInventory.kt` y en las notas de 1.20.0; `BackupInventoryTest` lee el
código y falla si aparece un archivo, una clave de preferencias, un DataStore o unas SharedPreferences sin clasificar.

## 11. Observabilidad (fase 7)

- `#insto ver ejecuciones`: las últimas 6 filas del diario, cada una con su id corto (`e` y 8 hexadecimales), estado,
  hora, transporte, orden y resultado. `#insto estado de ejecución <id>`: una fila. Las dos son de solo lectura y
  responden por el canal de acuses de siempre (la bitácora para el buzón); figuran en el catálogo vivo.
- Ajustes → Propuestas de Instinct → «Ejecuciones recientes» muestra lo mismo.
- Los acuses no se multiplican: un acuse por orden, como antes. El texto distingue «Verificado: …» (estado alcanzado y
  verificado), «Entregado; Insto no puede comprobar el resultado final.» (entregada a otra app), `espera`
  (pendiente), `error` con «No se logró: …» (fallida), `incierto` e «(recuperada tras reiniciarse Insto)».

## 12. Riesgos residuales

- **Robolectric no es un teléfono.** SQLite, `AudioManager`, `NotificationManager`, `KeyguardManager` y
  `AlarmManager` son simulados. El callback de la linterna, las sesiones multimedia reales, Doze y el límite de volumen
  por audición solo se ven en el dispositivo.
- **Ventana de `READY` tras la muerte del proceso**: una orden ofrecida y no despachada se revalida y se ofrece de nuevo
  si sigue vigente. Si el proceso murió justo después de escribir `DISPATCHING` pero antes de que Android aplicara el
  cambio, la relectura puede encontrar el estado anterior y la marca queda `incierta` (correcto: nunca se repite).
- **Atribución**: con el panel o tras una recuperación no hay lectura previa; Insto dice «el estado se cumple», nunca
  que lo produjo.
- **Modo temporal**: un proceso nuevo conserva la propiedad si el valor no cambió. Si la persona cambió el valor y lo
  volvió a dejar exactamente como lo puso Insto mientras nadie observaba, al vencer se restaura. El reinicio del
  teléfono sigue perdiendo la propiedad (conservador).
- **Servicio visible del modo temporal desde segundo plano**: sin exención de batería Android 12+ puede rechazarlo;
  el modo se aplica y se restaura igual, pero sin el aviso «Terminar» hasta abrir Insto.
- **Reactivación sin distribuidor**: si ntfy no está instalado en el teléfono nuevo, el timbre queda pendiente y el
  buzón funciona por sondeo.
- **Integraciones reales sin probar**: como en 1.19.0, no se probaron ntfy, Apps Script ni Cloudflare reales.

## 13. Prueba física en un Motorola con Android 16

Preparación: `adb` conectado, Insto 1.19.0 funcionando con bitácora, buzón y timbre; ntfy instalado; una hoja de
cálculo para anotar, en cada paso, la hora de envío, la de «Última recepción push» del detalle del timbre, la del acuse
en la bitácora y la fila de `#insto ver ejecuciones`. **No inventar métricas**: anotar lo que se ve, o «no medido».

1. **Actualizar sobre 1.19.0.** Instalar el APK 1.20.0 (`adb install -r`). Abrir Insto: los interruptores siguen
   encendidos. `#insto ver ejecuciones` responde (vacío o con filas nuevas).
2. **ntfy y distribuidor.** Detalle del timbre: «Registro: registrado», distribuidor ntfy.
3. **Apps Script 1.19.0 o posterior.** «Probar de punta a punta» con las seis etapas en ✓.
4. **Timbre cifrado simple.** Detalle: «Cifrado RFC 8291: activo». Mandar una orden: llega por el timbre y el sondeo.
5. **Orden cifrada directa (si hay relé).** «Despacho: por el timbre, sin leer la planilla».
6. **Diario.** Tras una orden, `#insto ver ejecuciones` muestra su id y estado `verificada` o `entregada`.
7. **Pantalla bloqueada.** Bloquear, apagar la pantalla, esperar 1 minuto y mandar «poner el volumen al 30 por
   ciento». El volumen cambia sin encender la pantalla; la bitácora dice «Verificado: quedó en N de M».
   Repetir con «activar no molestar» y «silenciá el teléfono durante una hora» (debe correr sin pedir confirmación en
   Solo y restaurarse a la hora). Repetir con «poner un temporizador de 5 minutos»: debe quedar en espera.
8. **Verificación.** Con auriculares, «volumen al 100 por ciento»: si Android lo limita, el acuse dice «No se logró».
9. **Bitácora.** Cada acuse aparece una sola vez.
10. **Muerte normal del proceso.** `adb shell am kill com.aistudio.pingassist.qvrtkm` (no `force-stop`) y mandar una
    orden: llega con el siguiente push o job. `ver ejecuciones` no la muestra repetida.
11. **Reinicio.** Mandar una orden, reiniciar a mitad (o enseguida) y no abrir Insto: tras el arranque, la recuperación
    la termina o la informa `vencida`/`incierta`, una sola vez.
12. **Sondeo de respaldo sin ntfy.** `adb shell am force-stop io.heckel.ntfy`, mandar una orden: llega por el sondeo de
    respaldo (hasta 15 minutos).
13. **Doze.** `adb shell dumpsys deviceidle force-idle`, mandar una orden, esperar, `adb shell dumpsys deviceidle unforce`.
    Anotar la demora real.
14. **Backup completo.** Ajustes → Copia de seguridad → «Crear copia completa». Luego «Copia sin secretos»: abrirla en
    otro teléfono (o tras reinstalar) y comprobar que pide token y clave.
15. **Restauración y nuevo registro.** Desinstalar Insto (o usar otro teléfono), instalar 1.20.0, restaurar la copia
    completa: los interruptores quedan apagados y aparece «Reactivar Instinct en este teléfono». Tocar «Reactivar»:
    todos los pasos en ✓, en ntfy aparece una suscripción nueva y el detalle del timbre muestra un endpoint distinto
    del anterior. `#insto ver estado` responde.
16. **Nombre.** Revisar Launcher, Ajustes → Apps, asistente digital, Accesibilidad («Insto · accesibilidad»),
    acceso a notificaciones, canales de notificación, mosaico de Ajustes rápidos, widget, atajos y botón flotante: en
    ningún lado debe decir PingAssist.

