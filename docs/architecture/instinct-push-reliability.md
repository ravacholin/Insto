# Transporte push de Instinct a Insto: auditoría y diseño (1.19.0)

Este documento registra la auditoría del transporte de órdenes de Instinct a Insto tal como quedó en **Insto 1.18.0
(Protocolo Insto v6)** y el diseño con el que 1.19.0 lo corrige. Distingue **defectos comprobados** (se ven en el código
y, cuando se pudo, en una prueba), **riesgos teóricos** (posibles, pero no demostrados) y **limitaciones de Android** que
ningún código de Insto puede eliminar.

Fuentes leídas antes de tocar código: `docs/INSTINCT-PROPUESTAS.md`, `docs/LOCAL-INTENTS.md`, `docs/instinct-bitacora.gs`,
las notas de 1.17.0 y 1.18.0, `InstinctDoorbell.kt`, `InstinctDoorbellService.kt`, `InstinctMailbox.kt`,
`InstinctMailboxService.kt`, `InstinctProposalCenter.kt`, `InstinctCatalogService.kt`, `InstinctLogService.kt` y las
pruebas `InstinctDoorbellTest`, `InstinctDoorbellFlowTest`, `InstinctMailboxTest` e `InstinctMailboxFlowTest`.
Especificaciones consultadas: [UnifiedPush AND_3](https://codeberg.org/UnifiedPush/specifications/src/branch/main/specifications/android.md)
(revisión de noviembre de 2026), [RFC 8291](https://www.rfc-editor.org/rfc/rfc8291) (cifrado de Web Push),
[RFC 8030](https://www.rfc-editor.org/rfc/rfc8030) (TTL, Urgency) y el código fuente de
[`org.unifiedpush.android:connector` 3.3.6](https://codeberg.org/UnifiedPush/android-connector) (Apache 2.0).

## 1. Recorrido completo en 1.18.0

```text
Instinct ──POST action:"orden"──▶ Apps Script (doPost → postOrder_)
                                    │ 1. LockService + appendRow en la hoja «Buzón» (si el id es nuevo)
                                    │ 2. ring_(): UrlFetchApp.fetch(endpoint, "insto-timbre" | JSON firmado EN CLARO)
                                    │    como mucho uno cada 5 s (las demás órdenes nuevas NO tocan el timbre)
                                    ▼
                          servidor ntfy (ntfy.sh o propio) ──WebSocket/JSON──▶ app ntfy (distribuidor)
                                    ▼
             broadcast explícito org.unifiedpush.android.connector.MESSAGE (token, bytesMessage, id)
                                    ▼
InstinctDoorbellReceiver.onReceive  ── HILO PRINCIPAL ──
  InstinctDoorbell.onReceive: lee estado (disco), InstinctDoorbellProtocol.handle (token, payload)
  ack(): MESSAGE_ACK al distribuidor (ANTES de procesar)
  ring(): escribe estado (disco), InstinctMailbox.rearm (JobScheduler)
     ├─ con orden en el payload → insideBroadcast { direct() }
     └─ sin orden → gate → insideBroadcast { pollForRing() }  (GET ordenes + POST tomadas, hasta 15 s + 15 s)
  insideBroadcast(): goAsync(); FutureTask en un CachedThreadPool; task.get(20 s) EN EL HILO PRINCIPAL; finish()
                                    ▼
direct(): verify (HMAC, ventana, visto) → InstinctMailboxSeen.claim → InstinctProposalIntake.onMailbox
          → schedule(follow-up, 60 s)  (sondeo completo GET + POST para marcar «tomada»)
                                    ▼
onMailbox → validador → deliver → main.post { runInBackground }  → autonomía, freno, ejecución, acuses
                                    ▼
InstinctLog.enqueue (bandeja en disco) → InstinctLogJobService → POST a la bitácora
```

### Qué corre dónde

| Paso | Hilo | Red | Disco | Sobrevive a la muerte del proceso |
| --- | --- | --- | --- | --- |
| `onReceive` → `InstinctDoorbell.onReceive` | principal | no | lee y escribe `instinct-doorbell-state.json` | no |
| `ack()` (`MESSAGE_ACK`) | principal | no | no | — (ya confirmado al distribuidor) |
| `ring()` → `rearm` | principal | no | estado; `JobScheduler` (binder) | el job sí |
| `insideBroadcast` → `task.get(20 s)` | **principal, bloqueado** | espera la red de otro hilo | — | no |
| `pollForRing` → `InstinctMailbox.poll` | `insto-timbre` (pool sin límite) | GET + POST, hasta ~30 s | `instinct-mailbox-seen.json` | no; el sondeo de respaldo sí |
| `direct()` | `insto-timbre` | no | `seen` | no |
| `onMailbox` → `deliver` | `insto-timbre`, luego `main.post` | no | acuses en la bandeja | la bandeja sí; la ejecución no |
| Job de seguimiento (60 s) | `Thread` nuevo por job | GET + POST | `seen` | sí (JobScheduler) |
| `NEW_ENDPOINT` | principal | no (programa un job) | endpoint y estado, escritura atómica | el aviso al script sí (job con backoff) |
| `register()` desde Ajustes | principal | no | estado | — |

## 2. Defectos comprobados

1. **El hilo principal se bloquea hasta 20 segundos.** `insideBroadcast()` llama a `task.get(pollTimeoutMillis)` dentro
   de `onReceive()`, que Android ejecuta en el hilo principal. Si el timbre llega con Insto en pantalla, la interfaz se
   congela; con la red lenta, el sondeo del timbre (GET + POST, cada uno con 15 s de límite) supera los 5 s del ANR de
   entrada y los 10 s del ANR de un broadcast en primer plano. `goAsync()` no sirve de nada si el mismo hilo espera.
2. **`MESSAGE_ACK` se manda antes de procesar.** Si el proceso muere entre el acuse y la verificación, el distribuidor
   no reintenta (ya está confirmado) y la orden queda solo en la planilla: la recupera el sondeo de respaldo, hasta
   15 minutos después. No hay duplicación, pero sí pérdida de latencia sin rastro en el diagnóstico.
3. **Hilos sin control.** `Executors.newCachedThreadPool` crea un hilo por timbre sin límite y cada job de
   `InstinctDoorbellJobService` arranca un `Thread` nuevo. Una ráfaga de timbres abre tantos hilos como timbres.
4. **Nada queda pendiente en disco.** Una señal que llega y no se procesa (proceso muerto, `RejectedExecution`, red
   caída en medio de `direct`) depende de un job programado más tarde o del sondeo de respaldo; la señal misma no se
   guarda.
5. **El cifrado de UnifiedPush no existe.** La especificación AND_3 dice que el mensaje «MUST be an encrypted content
   that follows RFC 8291». El Apps Script manda `insto-timbre` o el JSON firmado **en claro**; Insto no genera claves ni
   las publica. Esto no es compatible con UnifiedPush aunque ntfy lo tolere. La firma HMAC autoriza la orden, pero no la
   oculta: ntfy (y cualquiera que lea su tráfico del lado servidor) ve las líneas.
6. **El sondeo marca «tomadas» antes de ejecutar.** En `pollOnce()` el `POST tomadas` (hasta 15 s) va **antes** de
   `onMailbox`: una orden ya verificada y reclamada espera un viaje de ida y vuelta a Apps Script para ejecutarse.
7. **Una orden directa siempre provoca un sondeo completo.** A los 60 s se hace GET + POST solo para marcarla tomada,
   aunque alcance con el POST.
8. **Ráfagas sin timbre.** El script toca el timbre como mucho una vez cada 5 s también con la orden adentro: en una
   ráfaga, solo la primera orden viaja; las demás esperan el sondeo de seguimiento (60 s) o el de respaldo.
9. **Un fallo de disco se informa como «sin red».** Si `claimAll` no puede escribir `seen`, `pollForRing` lo atrapa
   como `NETWORK` y programa un job de red que no arregla nada.
10. **Trabajo de disco en el hilo principal.** `NEW_ENDPOINT`, `UNREGISTERED` y cada `MESSAGE` leen y escriben archivos
    en el hilo principal. Son escrituras chicas, pero cuentan para el ANR y para *StrictMode*.

## 3. Riesgos teóricos (no demostrados)

- **Carrera `contains()` / `claim()`.** `verify()` consulta `seen::contains` y después se llama a `claim()`. Entre las
  dos, otro transporte puede tomar el id. **No es un defecto:** `claim()`/`claimAll()` comprueban y marcan en la misma
  sección crítica (un candado por archivo, compartido por todas las instancias), así que el perdedor recibe `false` y no
  ejecuta. `contains()` solo sirve para responder antes «repetida». Se mantiene y se prueba con hilos reales.
- **Doble lectura de `seen`.** Cada `contains()` y cada `claim()` leen el archivo entero (hasta 2000 ids). Es O(n) por
  orden y no un problema con 20 órdenes, pero no conviene llamarlo en un bucle caliente.
- **Muerte del proceso entre `claim` y `onMailbox`.** El id queda reclamado y la orden no se ejecuta ni se informa. En
  1.18.0 se pierde en silencio; en 1.19.0 se informa como `incierta` (ver 5.4).
- **Timbre perdido por Doze.** ntfy puede estar dormido; el mensaje llega tarde o nunca. Cubierto por el sondeo de
  respaldo.

## 4. Limitaciones de Android (no se pueden eliminar)

- **Ventana de un broadcast:** un `BroadcastReceiver` tiene 10 s (primer plano) o 60 s (segundo plano) para terminar,
  aun con `goAsync()`. Pasado `finish()`, el proceso puede morir en cualquier momento si no hay otro componente activo.
- **El distribuidor eleva a Insto 5 segundos** (`RAISE_TO_FOREGROUND`); después Insto vuelve a ser un proceso en caché.
- **Los jobs no son inmediatos:** un job *expedited* tiene cuota; uno común puede esperar minutos bajo Doze.
- **Abrir actividades en segundo plano está prohibido** desde Android 10. Insto ya lo respeta (lo que abre otra app
  espera un toque o usa Accesibilidad); 1.19.0 no cambia esa lógica.
- **Pantalla bloqueada:** las acciones que no necesitan UI (volumen, linterna, No molestar, multimedia) se ejecutan
  igual; las que sí, quedan esperando confirmación como antes.
- **ntfy puede morir o ser restringido** por el ahorro de batería del fabricante (Motorola incluido). Ningún cambio en
  Insto evita eso; por eso el sondeo de respaldo es obligatorio.

## 5. Diseño de 1.19.0

### 5.1 Recepción, cola durable y despacho separados

```text
broadcast ─▶ InstinctPushReceiver.onReceive (principal): goAsync() y encolar; vuelve en microsegundos
                │ cola acotada (16) del hilo «insto-push-rx»; si está llena: finish() y se cuenta
                ▼
            MessagingReceiver de la biblioteca oficial: token conocido, descifrado RFC 8291
                ▼
            onMessage: clasificar (timbre / orden / inválido) → PushInbox.add (disco, atómico)
                │ + job de rescate (30 s) por si el proceso muere
                ▼ (la biblioteca manda MESSAGE_ACK recién acá: lo confirmado ya está en disco)
            InstinctPushDispatcher.kick → hilo único «insto-push-dispatch»
                │ el hilo receptor espera como mucho 8 s (nunca el principal) y llama finish() una sola vez
                ▼
            ORDEN: verify (HMAC, 15/2 min, visto) → claimAll en `seen` → marcar «reclamada» → onMailbox → quitar
            TIMBRE: gate → sondeo en «insto-buzon» (el de siempre) → quitar al terminar
```

- **`PushInbox`** (`instinct-push-inbox.json`, `noBackupFilesDir`): como mucho 32 entradas; los timbres sin orden se
  funden en uno; una orden repetida en la cola no se agrega dos veces; una entrada vence a los 15 minutos (la misma
  ventana que la firma) y una orden que no se pudo reclamar por error de disco se reintenta 3 veces.
- **`finish()` exactamente una vez**: un envoltorio con `AtomicBoolean`, probado.
- **Concurrencia acotada:** un hilo de recepción, un hilo de despacho, el hilo del buzón de siempre y un ejecutor
  único para los jobs. Ningún hilo nuevo por notificación.
- **Recuperación:** el job de rescate, el reinicio del teléfono y la apertura de Insto vacían la cola. Una orden en
  cola se **revalida** (firma, vigencia, visto, autonomía y configuración) antes de ejecutarse. Una orden reclamada por
  un proceso que murió se informa como `incierta` en la bitácora y **no se repite**.

### 5.2 UnifiedPush con la biblioteca oficial

- Se adopta `org.unifiedpush.android:connector:3.3.6` (Apache 2.0, minSdk 16, Kotlin 2.2). Gestiona el token, el
  registro con la identidad compartida (Android 14+) o el `pi` (anteriores), el `MESSAGE_ACK`, `UNREGISTERED`, los
  distribuidores de respaldo y las **claves de Web Push**: un par P-256 y un secreto `auth` de 16 bytes por instancia,
  con la clave privada cifrada por una clave del Android Keystore (Android 6+). Descifra con
  `WebPushHybridDecrypt` (Tink, RFC 8291 `aes128gcm`).
- La biblioteca entrega `PushMessage(content, decrypted)`: si el descifrado falla, entrega el contenido **en claro** con
  `decrypted=false`. Insto no confía en eso: un mensaje sin cifrar solo puede pedir un sondeo, salvo en el modo heredado
  (5.3).
- Insto **no** usa `PushService` ni `MessagingReceiverImpl`: declara su propio `MessagingReceiver` (prioridad 0, así el
  de la biblioteca no corre) para que el acuse salga **después** de guardar el mensaje en disco y para controlar
  `goAsync()`.
- **Migración desde 1.18.0:** si queda un token del conector manual, Insto le manda `UNREGISTER` al distribuidor, lo
  olvida y se registra de nuevo con la biblioteca. El endpoint cambia y se le avisa al script; la configuración de 1.18.0
  (interruptores, clave, bitácora) se conserva.

### 5.3 Dos modalidades cifradas y el modo heredado

**Apps Script no tiene ECDH P-256 ni AES-GCM** (solo `computeDigest`, `computeHmac*` y Base64), así que no puede cifrar
RFC 8291 sin criptografía escrita a mano. 1.19.0 lo resuelve sin criptografía casera:

- **A. Timbre cifrado simple (sin infraestructura nueva).** Insto cifra **para sí mismo**, con Tink
  (`WebPushHybridEncrypt`, la contraparte oficial), el texto fijo `insto-timbre` y le pasa al script el resultado
  (`timbreCifrado`, Base64). El script lo manda tal cual al endpoint con `Content-Encoding: aes128gcm`, `TTL` y
  `Urgency: high`. Es un mensaje RFC 8291 válido; repetirlo no revela nada (es solo «sonó») y lo peor que puede hacer
  alguien que lo copie es pedir un sondeo, igual que antes. Se regenera cuando cambian el endpoint o las claves.
- **B. Timbre cifrado con orden (opcional, apagado).** Necesita cifrar cada orden: lo hace un **relé mínimo en
  Cloudflare Workers** (`relay/insto-webpush-relay`, plan gratuito, Web Crypto) que recibe del script
  `{endpoint, p256dh, auth, payload}` con un secreto propio, cifra y publica. El script lo usa solo si se configuran
  `RELAY_URL` y `RELAY_SECRET`; si el relé falla, cae al timbre cifrado simple. La orden sigue firmada con HMAC: **el
  cifrado protege el transporte; la firma autoriza la orden**.
- **Modo heredado:** mientras el script no confirme el cifrado (un script de 1.18.0 o anterior), Insto acepta el timbre
  en claro como en 1.18.0 y el diagnóstico dice «sin cifrar (no conforme con UnifiedPush)». Cuando el script confirma
  el cifrado, un mensaje en claro ya **no** puede ejecutar una orden: solo pide un sondeo (protección contra degradación).

### 5.4 Latencia, deduplicación y reconciliación

- Una orden completa y autenticada se ejecuta **sin leer la planilla**: verificar, reclamar, `onMailbox`.
- La reconciliación es un `POST tomadas` del id (job con red y reintentos), no un sondeo completo. Un sondeo de
  seguimiento solo se hace si el script no confirmó que toca el timbre por cada orden (scripts anteriores a 1.19.0).
- El script de 1.19.0 manda cada orden nueva cifrada sin la espera de 5 s (con un tope de 30 por minuto); el timbre
  simple conserva la espera.
- En el sondeo, `onMailbox` va **antes** del `POST tomadas`: la orden se ejecuta apenas se reclama.
- Un único registro de ids (`instinct-mailbox-seen.json`, 48 h, 2000 ids) para push directo, sondeo periódico, de
  respaldo, por WhatsApp y de seguimiento. `claimAll` es la única puerta.

### 5.5 Diagnóstico

Ajustes → Propuestas de Instinct → Buzón → Timbre muestra: distribuidor, registro, cifrado, endpoint (enmascarado),
última recepción, última orden directa validada, último sondeo de respaldo, duplicados descartados, fallos de
descifrado y de verificación, cola de recepción y último error; y explica cuando Insto funciona solo por sondeo.
«Probar timbre de punta a punta» separa registro, entrega, descifrado, verificación, despacho y resultado con una orden
de solo lectura (`#insto probar ver estado`), sin tocar el volumen, crear alarmas ni ejecutar nada.

## 6. Qué no cambia

La interpretación de órdenes, `SemanticCommandGuard`, la autonomía (Nunca / Confirmar / Solo), el freno anti-bucle,
las rutinas, «Deshacer», las órdenes programadas, los disparadores, las restricciones para abrir actividades en segundo
plano, la vía WhatsApp y el formato firmado `insto-v4`.
