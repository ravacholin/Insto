# Insto 1.20.0 (código 29): ejecución autónoma, verificable y recuperable

Insto recibe las órdenes de Instinct por el mismo transporte de 1.19.0 (timbre UnifiedPush cifrado, cola en disco,
sondeo de respaldo), pero ahora **cada orden queda registrada en un diario durable** desde que se reclama hasta su
resultado, **se comprueba en el teléfono** cuando Android lo permite, **se recupera** después de una muerte del
proceso o un reinicio sin repetir nada incierto y **se ejecuta con el teléfono bloqueado** cuando no abre una pantalla.
La copia `.insto` trae ahora todo Instinct, y la app se llama Insto en todas partes. Protocolo Insto v6, con dos
consultas nuevas de solo lectura.

Diseño, auditoría, matrices y prueba física: [`architecture/instinct-execution-journal.md`](../architecture/instinct-execution-journal.md).

## 1. Arquitectura

```text
push ─▶ InstinctPushReceiver ─▶ InstinctPushInbox (1.19.0, sin cambios: recepción)
sondeo ─┐                              │
WhatsApp┼──────────────▶ InstinctExecutionJournal.admit  ── una transacción SQLite: reclamar id + una fila por orden
programadas (disparo) ─┘                │
                                        ▼  (recién ahora se quita la entrada de la cola push)
              validar ─▶ VALIDATED ─▶ WAITING | SCHEDULED | READY ─▶ DISPATCHING (escrito antes de tocar el teléfono)
                                        ─▶ VERIFYING ─▶ ActionOutcomeVerifier ─▶ VERIFIED | HANDED_OFF | FAILED | UNCERTAIN
InstinctExecutionRecoveryManager: dentro del job de vaciado del timbre, el arranque y la apertura de Insto
```

## 2. Archivos

Nuevos: `InstinctExecution.kt` (modelo puro, transiciones, política de recuperación), `InstinctExecutionJournal.kt`,
`InstinctExecutions.kt`, `InstinctExecutionRecoveryManager.kt`, `InstinctExecutionQueries.kt`, `ActionOutcome.kt`,
`AndroidActionOutcomeVerifier.kt`, `InstinctReadiness.kt`, `TemporaryModeRestorations.kt`, `InstinctBackupStore.kt`
(incluye `InstinctReactivation`), `backup/InstinctBackupSection.kt`, `backup/BackupInventory.kt`.

Modificados: `InstinctPushService.kt`, `InstinctMailbox.kt`, `InstinctMailboxService.kt`, `InstinctProposalCenter.kt`,
`InstinctProposals.kt`, `InstinctScheduler.kt`, `InstinctAutonomy.kt`, `InstinctProtocolCatalog.kt`,
`InstinctLogService.kt`, `PhoneActionExecutor.kt`, `TemporaryModeManager.kt`, `TemporaryModeService.kt`,
`InstoApplication.kt`, `backup/*`, pantallas de Ajustes y backup, recursos y textos con el nombre viejo,
`app/build.gradle.kts` (1.20.0, código 29).

Pruebas nuevas: `InstinctExecutionTest`, `InstinctExecutionFlowTest` (39), `ActionOutcomeTest`,
`InstinctLockScreenTest`, `InstinctBackupSectionTest`, `InstinctBackupFlowTest`, `BackupInventoryTest`,
`InstoNameTest`.

## 3. Integración con el PR #70 (1.19.0)

Se conservan sin cambios el conector oficial `org.unifiedpush.android:connector:3.3.6`, el cifrado RFC 8291, el
timbre cifrado simple, el relé opcional, `InstinctPushReceiver`, `InstinctPushIntake`, la cola
`instinct-push-inbox.json`, la recepción asíncrona, `finish()` una sola vez, el acuse después de guardar, el despacho
directo sin leer la planilla, `POST tomadas`, la recuperación de la cola, el diagnóstico y la prueba de punta a punta.
No hay otro receptor, registro, cola ni deduplicación: el registro único de ids **se mudó** de
`instinct-mailbox-seen.json` al diario (importado una vez; el archivo se conserva intacto) y sigue siendo el único para
timbre, sondeo, respaldo, seguimiento y WhatsApp. `InstinctPushDispatcher.order()` reclama y registra en la misma
transacción y quita la entrada de la cola cuando el diario ya tiene la orden. Una entrada reclamada por un proceso
muerto se resuelve con el diario; solo una heredada de 1.19.0 sin filas se informa `incierta` (y queda en el diario).

## 4. Diario y recuperación

Estados, transiciones, clases de acción y la tabla de recuperación: secciones 6.3 y 8 del documento de arquitectura.
Lo esencial: lo que no tocó el teléfono se continúa si sigue vigente; una confirmación pendiente se vuelve a ofrecer
esperando a la persona; un ajuste interrumpido se relee («el estado se cumple», nunca «lo hizo esta ejecución»); todo
lo demás que pudo haberse hecho queda `incierta` y **nunca se repite**. Cada resultado recuperado se informa una vez.

## 5. Acciones verificables

| Acción | Verificación |
| --- | --- |
| Volumen a un porcentaje | índice exacto; un tope de Android es «No se logró» |
| Subir / bajar | cambió en el sentido pedido (o ya estaba en el extremo) |
| Silencio, vibración, sonido normal | `ringerMode` efectivo |
| No molestar | filtro de interrupciones efectivo |
| Linterna | callback de la cámara; si no informó, entregada |
| Reproducir / pausar | estado de la `MediaSession`; si no informó, entregada |
| Modo temporal y su restauración | valor aplicado / valor original |
| Alarma, temporizador, calendario, búsquedas, apps, siguiente/anterior | entregadas: nunca verificadas |

## 6. Con el teléfono bloqueado o la pantalla apagada

Se ejecutan solos: volumen, linterna, silencio, vibración, No molestar, música, modos temporales (y su restauración),
consultas, rutinas con esos pasos, y las programadas, disparadores y recurrentes de esas acciones. Esperan el
desbloqueo: alarma, temporizador, calendario, búsquedas y abrir apps (abren una pantalla). Insto no enciende la
pantalla ni abre actividades, no agrega servicios permanentes ni wake locks.

## 7. Confirmaciones internas eliminadas

- El bloqueo global por teléfono bloqueado en `InstinctGatePreview.blocker()` y en `PhoneActionExecutor.execute()`.
- El «¿Confirmás?» del modo temporal con la categoría en Solo (también como paso de una rutina autorizada).
- Se conservan Confirmar, Nunca, horas de silencio, freno, tope de WhatsApp, «esa hora ya pasó», elegir app o contacto,
  permisos y la restauración aproximada sin alarmas exactas. Nunca se convierte un Nunca en Solo.

## 8. Permisos de Android

Ajustes → Propuestas de Instinct → «Funcionar con el teléfono bloqueado»: notificaciones, acceso a No molestar,
alarmas y recordatorios, sin optimización de batería, acceso a notificaciones, cámara y (opcional) Accesibilidad. Solo
lista lo que falta, con un botón por permiso; en segundo plano solo anota lo que una orden necesitó. Nada se pide dos
veces ni se concede por Accesibilidad.

## 9. Inventario de backup

| Dato | Clase | Categoría |
| --- | --- | --- |
| Claves de Groq y Gemini (`*-key.enc`, cifradas con el Keystore) | secreto | Claves de API |
| Gemini Decisions, «seguir en local» | configuración | Claves de API |
| Preferencias del asistente (`ping_assist_prefs`: contactos, WhatsApp, voz, botón flotante, teclas de volumen, horario nocturno, vocabulario) | configuración | Asistente |
| `mic_prompted_once` | transitorio | — |
| `recent_logs_json` | historial | — |
| Frases y estructuras (`learned_phrases`) | configuración | Frases |
| Rutinas (`configured-routines.json`) | configuración | Rutinas |
| Lugares (`saved_places`) | configuración | Lugares |
| Recibir órdenes, buzón, timbre (interruptores) | configuración: se restauran **apagados** y se pide reactivar | Instinct |
| Respuestas, canal de acuses, autonomía, horas de silencio, rutinas autorizadas | configuración | Instinct |
| Aceptación, tope de WhatsApp, intervalo del buzón | configuración | Instinct |
| URL del Apps Script | configuración | Instinct |
| Token de la bitácora, clave HMAC del buzón | secreto | Instinct |
| Distribuidor y modalidad del timbre | configuración | Instinct |
| Órdenes recurrentes | configuración (definición; se reprograma al reactivar) | Instinct |
| Endpoint, timbre cifrado, estado del registro, base UnifiedPush, claves Web Push del Keystore | vinculado al teléfono: se regenera | — |
| Diario de ejecución, contadores de autonomía, métricas del envío automático | historial | — |
| Colas (push, bitácora), ids vistos, estados de publicación, rutina o secuencia en curso, modo temporal activo, permisos anotados, pedido de reactivación, marca de restauración | transitorio | — |
| Claves del Android Keystore, capturas | no exportable | — |

**Copia completa**: todo lo exportable. **Copia sin secretos**: sin claves de API, token ni clave del buzón. Ningún
secreto aparece en vistas previas, informes ni registros. Al restaurar: «Reactivar Instinct en este teléfono» revisa
permisos, enciende, prueba bitácora y buzón, registra UnifiedPush de nuevo (claves y endpoint propios), reprograma las
recurrentes sin duplicarlas, rearma los jobs y publica el catálogo. `BackupInventoryTest` falla ante un dato nuevo sin
clasificar.

## 10. Nombres corregidos

| Antes | Ahora |
| --- | --- |
| «PingAssist Auto-Send & Triggers» (Accesibilidad) | «Insto · accesibilidad» |
| «PingAssist» (mosaico, widget, pantalla principal) | «Insto» |
| «Ping: <contacto>» (mosaico) | «Insto: <contacto>» |
| «PingAssist Voice», «Voice Ping» (atajos) | «Insto · voz» |
| «Modo temporal» (canal) | «Insto · modo temporal» |
| «PingAssist Floating Trigger» (TalkBack) | «Insto · botón flotante» |
| Mensajes de Accesibilidad, Gemini y contacto con «PingAssist» | «Insto» / «Accesibilidad de Insto» |

Se conservan, por compatibilidad, el `applicationId` `com.aistudio.pingassist.qvrtkm`, la firma, los nombres de clases
(`PingAutoSendAccessibilityService`, `PingAssistWidgetProvider`…), el estilo `Theme.PingAssist.Translucent` y el
DataStore `ping_assist_prefs`: no son visibles y cambiarlos rompería actualizaciones, el permiso de Accesibilidad, los
widgets o los datos. Android actualiza el nombre de los canales ya creados al volver a crearlos.

## 11. Pruebas ejecutadas

Ver la sección «Verificación» al final.

## 12. Riesgos residuales

Sección 12 del documento de arquitectura. Lo principal: nada de esto se probó todavía en un teléfono real ni con ntfy,
Apps Script o Cloudflare reales.

## 13. Configuración y prueba física

Para actualizar: instalar 1.20.0 sobre 1.19.0 (no hace falta tocar el Apps Script de 1.19.0). Abrir Ajustes →
Propuestas de Instinct → «Funcionar con el teléfono bloqueado» y conceder lo que falte. El procedimiento de 16 pasos
para el Motorola con Android 16 está en la sección 13 del documento de arquitectura.

## Verificación

Ejecutadas en este entorno (Linux, JDK 21, Android SDK 36.1, Robolectric 4.18.1), **no en un teléfono**:

- `./gradlew lintDebug testDebugUnitTest assembleDebug`: **1225 pruebas, 0 fallos**, 1 omitida (preexistente:
  `GeminiCorpusTest`, requiere `INSTO_GEMINI_LIVE=1`); lint sin errores nuevos; APK debug generado.
- `:app:minifyReleaseWithR8` con una clave efímera (el APK no es distribuible): sin clases faltantes.
- `python3 scripts/test-phone-core.py`: **739 pruebas**, 0 fallos (incluye las puras nuevas del diario, la verificación
  y el backup).
- `node scripts/test-bitacora-gs.js`: **32/32** (el Apps Script no cambia en 1.20.0).
- `npm test` del relé: **9/9** (sin cambios; vectores de RFC 8291 intactos).
- Regresiones de 1.19.0 que siguen pasando: `InstinctPushInboxTest`, `InstinctDoorbellFlowTest`,
  `InstinctMailboxFlowTest`, `InstinctPushCryptoTest`, `InstinctDoorbellTest`, `InstinctMailboxTest`. Cuatro pruebas
  cambiaron a propósito porque fijaban el bloqueo global por pantalla bloqueada (ahora un volumen corre bloqueado y un
  temporizador espera), una porque el acuse ahora dice «Verificado: …» y una porque un modo temporal autorizado ya no
  pide confirmación.
- Interrupciones simuladas (Robolectric, `InstinctExecutionFlowTest`): antes de persistir (la cola push la retiene),
  después de recibir en la cola, antes y después de reclamar, antes de la propuesta, después de la propuesta y antes
  del despacho, durante el despacho, después del efecto y antes del resultado (relectura), durante la verificación, y
  una confirmación pendiente; carrera push/sondeo con 16 hilos; migración de `instinct-mailbox-seen.json` (legible,
  ilegible y bloqueada) y de entradas `claimed` de `instinct-push-inbox.json`. La falla de la bitácora no detiene
  nada (la bandeja reintenta; probado desde 1.15.0).

**No probado de verdad:** el teléfono (Motorola, Android 16), ntfy, Apps Script y Cloudflare reales, Doze real, el
callback real de la linterna, sesiones multimedia reales, el Android Keystore real con la biblioteca UnifiedPush y la
restauración entre dos teléfonos. Esas integraciones **no se declaran verificadas**: el procedimiento está en la
sección 13 del documento de arquitectura.
