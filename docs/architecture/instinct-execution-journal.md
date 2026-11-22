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

Los detalles de verificación, recuperación, autonomía con pantalla bloqueada y backup están en las secciones
siguientes, que se completan en cada fase.
