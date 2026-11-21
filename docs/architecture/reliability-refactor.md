# Refactorización de confiabilidad: red, interpretación y sesiones

Este documento describe el estado real después de los tres cambios (A, B y C), qué se decidió y por qué, y qué
queda fuera. Las cifras están en [`docs/metrics/reliability-before-after.md`](../metrics/reliability-before-after.md).

## Estado inicial auditado

- **Red.** `ProviderHttp` ya usaba OkHttp 4.10.0 con un pool único, sin redirecciones ni reintentos, pero:
  - conservaba una ruta paralela con `HttpURLConnection` que solo usaban las pruebas (`connectionFactory`);
  - tenía `writeTimeout(0)`: una subida de audio podía quedar bloqueada sin límite por escritura;
  - no fijaba un plazo total por llamada en los POST (dependía solo del `withTimeout` de la corrutina);
  - ejecutaba las llamadas bloqueantes en un `CoroutineScope` global propio;
  - armaba a mano el multipart de Groq;
  - clasificaba los errores HTTP en `GeminiTransport`, `GroqTranscriber` y `SmnHttp` por separado.
- **Interpretación.** `GeminiCoordinator` ya era el único punto de entrada y aplicaba `SemanticCommandGuard` a todo
  resultado. Pero consultaba a Groq de forma especulativa en paralelo con **cada** llamada a Decisions, y las
  métricas existentes no contaban esas consultas cuando se descartaban.
- **Sesiones.** `MainViewModel` (≈2.000 líneas) protegía los resultados tardíos con un contador
  `floatingGeneration` y cancelaba el `floatingJob`, pero no había estados explícitos ni registro de resultados por
  acción. El auto-envío de WhatsApp ya tenía garantías de un solo toque por pedido (`TextSendAttempt`,
  `ShareSendAttempt`, `AutoSendCoordinator.Report`), que se conservaron sin cambios.

## A. Transporte HTTP único

`ProviderHttp` es la única ruta HTTP de la app (Gemini Decisions, extracción estructurada, Groq chat y
transcripción, SMN y Open-Meteo).

| Política | Antes | Después |
| --- | --- | --- |
| Rutas | OkHttp + `HttpURLConnection` (pruebas) | Solo OkHttp; las pruebas usan un `Interceptor` (`FakeHttp`) |
| Escritura | `writeTimeout(0)` (sin límite) | Finito por operación de E/S |
| Plazo total | Solo el de la corrutina | `callTimeout` además del de la corrutina |
| Ejecución | `CoroutineScope` global + `execute()` | `enqueue()` en el dispatcher de OkHttp |
| Multipart | Manual | `MultipartBody` de OkHttp, archivo leído en streaming |
| Clasificación | Tres copias | `ProviderHttp.classifyStatus` / `classify` |

### Límites

`ProviderHttp.Limits(connect, write, read, call)`. Conexión, escritura y lectura limitan cada operación de E/S, así
que una subida que avanza no se corta; `call` limita el intercambio completo, DNS incluido.

- **JSON a proveedores** (`Limits.json(presupuesto)`): conexión y escritura `min(5 s, presupuesto)`, lectura
  `min(8 s, presupuesto)`, llamada `presupuesto + 1 s`. El plazo de la corrutina decide primero; el segundo extra del
  `callTimeout` es un respaldo para que ningún hilo de OkHttp quede vivo sin límite. Presupuestos sin cambios:
  Decisions 4 s, extracción 4–8 s, Groq 6 s y verificación 1,5 s.
- **Subida de audio** (`Limits.UPLOAD`): conexión 15 s, escritura y lectura 30 s por operación, 60 s en total (igual que
  el `withTimeout` anterior).
- **SMN / Open-Meteo**: conexión 4 s, lectura 8 s, total 12 s (sin cambios); la escritura deja de ser ilimitada.
- **Valores por defecto del cliente**: 10 s por operación y 30 s por llamada, para cualquier pedido que no declare
  los suyos.

### Errores

`classifyStatus`: 401/403 → AUTH, 429 → QUOTA, 408 → TIMEOUT, 404/405/501 → UNAVAILABLE, 5xx → SERVER, otro 4xx →
INVALID_REQUEST, 1xx/3xx → INVALID_RESPONSE. `classify`: `InterruptedIOException` (incluido el `callTimeout`) →
TIMEOUT, `ResponseTooLarge` → TOO_LARGE, otra `IOException` → NETWORK, `CancellationException` → CANCELLED.
Los mensajes que ve el usuario no cambiaron. Una cancelación siempre se relanza: nunca se convierte en un error del
proveedor ni dispara a otro proveedor.

### Sin duplicaciones

Sin redirecciones, `retryOnConnectionFailure(false)` y cuerpos de un solo envío. El multipart de OkHttp 4 no se
declara de un solo envío, así que `GroqTranscriber` lo envuelve. El único segundo pedido automático que existe es el
cambio documentado de modelo de extracción cuando el primero no existe (404 o error de modelo), que va a otra URL y no
tiene efectos externos.

### Dependencia

OkHttp 4.10.0 → **4.12.0** (última 4.x estable; misma API, Okio 3.6.0). No se pasó a 5.x: cambia la organización de
artefactos y no aporta nada medible a este uso. No se agregaron dependencias.

## B. Interpretación

### Contrato

Se conservó `InterpretationResult` porque ya representa todos los casos pedidos sin conversiones:

| Caso | Tipo |
| --- | --- |
| Acción local válida | `Direct` |
| Plan de varias acciones | `Plan` |
| Pedido incompleto | `Incomplete` (borrador tipado) |
| Necesidad de aclaración | `Clarification` |
| Consulta general | `Query` |
| Pedido no admitido / condición que impide ejecutar | `Rejected` |
| Error técnico recuperable | `Failure(kind)` |
| Cancelación | `CancellationException` (cancelación estructurada, nunca un resultado) |

`GeminiCoordinator.interpret` es el único que decide qué interpretación nueva sigue hacia ejecución, y todo lo que
devuelve pasa una vez por `SemanticCommandGuard.review`. Las continuaciones de borrador y de aclaración no vuelven al
coordinador: las valida el borrador o la gramática de la respuesta, como antes.

### Orden de precedencia (real)

En `MainViewModel.routeFloatingRequest` y luego en `GeminiCoordinator`:

1. Respuesta a una consulta pendiente para Instinct.
2. Cancelaciones («cancelar», «no lo hagas») y rectificaciones («No, prendé la linterna», que se vuelve a enrutar
   desde el principio).
3. Frases de enseñanza y respuestas a confirmaciones, opciones o pasos de una secuencia.
4. Protecciones contra negaciones, condiciones e hipótesis (`RequestSafetyPolicy.rejection`).
5. Continuación del borrador activo y de la aclaración pendiente.
6. Pedido nuevo en el coordinador: seguridad otra vez, plan local de varios pasos, parser local, consulta explícita.
7. Frase aprendida exacta y estructura aprendida.
8. Contexto operativo breve.
9. Gemini Decisions (si el circuito lo permite).
10. Extracción estructurada de Gemini.
11. Groq como respaldo o verificación (ver abajo).
12. `SemanticCommandGuard` sobre el resultado final.
13. Ejecución, confirmación o aclaración.

Coincide con el orden pedido salvo en un punto deliberado: las negaciones se evalúan **antes** que la continuación
de borradores, para que «no, no le mandes nada» nunca complete un borrador.

### Groq: respaldo por necesidad

`FallbackSpeculation` define cuándo se le pregunta a Groq en paralelo con Decisions:

- `ALWAYS`: el comportamiento original, en cada llamada a Decisions.
- `WHEN_DEGRADED` (**por defecto**): solo si Decisions falló hace poco (circuito con fallas o en prueba de
  recuperación) o, desde la Fase 5, si la extracción estructurada de Gemini falló de forma repetida dentro de la
  ventana del circuito. Si no, Groq se consulta cuando hace falta.
- `NEVER`: nunca en paralelo.

La reproducción con los mismos casos y proveedores simulados mostró que con proveedores sanos la especulación gastaba
una consulta a Groq por cada llamada a Decisions sin mejorar la exactitud. El costo de no especular se limita a los
casos en que Decisions responde «unsupported» para una orden válida o en que falla la extracción de Gemini: ahí Groq
empieza después (+0,7–0,8 s simulados). Con Decisions caído el circuito se abre y Groq responde solo, igual que antes.

### Divergencias entre proveedores

La propuesta de Groq pasa por las mismas comprobaciones que la de Gemini (`validateProposal` y
`SemanticCommandGuard`). La reproducción encontró un caso real: con Decisions caído, «Suponete que apagás el wifi»
terminaba ejecutando la linterna que proponía Groq. Se corrigió con dos reglas mínimas, sin tocar umbrales ni prompts:

- `RequestSafetyPolicy`: «suponete», «imaginate», «supongamos» e «imaginemos» son hipótesis, como «suponé».
- `SemanticCommandGuard`: una propuesta de linterna o de volumen no puede salir de un paso que nombra otro dispositivo
  y no el suyo, y «dejá de iluminar» no puede convertirse en prender la linterna.

### Errores y confianza

Los umbrales de `DecisionsPolicy` no cambiaron. Un fallo técnico (`Failure`) solo lleva a Groq para interpretar **el
mismo** pedido, o a Instinct si el pedido ya era una consulta general; nunca se usa para elegir otra intención. Las
categorías quedan separadas así: no comprendido → `Rejected`; comprendido pero incompleto → `Incomplete` /
`Clarification`; error del proveedor → `Failure`; acción no disponible o condición que impide ejecutar → `Rejected`
con su mensaje. Desde la Fase 5, `Rejected` lleva además un motivo tipado (`RejectionReason`).

## C. Sesiones y ejecución

### Piezas nuevas (`com.example.session`)

- **`SessionLifecycle`**: identidad y máquina de estados de cada pedido del asistente flotante: `IDLE`, `CAPTURING`,
  `TRANSCRIBING`, `INTERPRETING`, `AWAITING_CLARIFICATION`, `AWAITING_CONFIRMATION`, `EXECUTING`, `COMPLETED`,
  `FAILED` y `CANCELLED`. Reemplaza al contador `floatingGeneration`, que ahora es su identidad actual. Las
  transiciones inválidas o de un pedido viejo se rechazan y se cuentan. Los estados finales solo cambian con un pedido
  nuevo. `canExecute(id)` impide ejecutar para un pedido cancelado, terminado o reemplazado.
- **`ActionExecutionLedger`**: cada despacho recibe un ticket y su resultado se registra **una sola vez** (un callback
  de accesibilidad duplicado se ignora). Distingue `NOT_DISPATCHED`, `DISPATCHED_UNCERTAIN`, `CONFIRMED` y `FAILED`.
  Un `HANDOFF` (app abierta, gesto enviado) cuenta como incierto, no como confirmado.

### Integración en `MainViewModel`

Cambios acotados, sin reescritura:

- Cada inicio, pausa, cancelación o cierre abre un pedido nuevo en `SessionLifecycle`, en los mismos lugares en que
  antes se incrementaba el contador.
- Se registran las transiciones principales (transcripción, interpretación, aclaración, confirmación, ejecución y
  resultado).
- `runPhoneCommand` exige `canExecute` y la transición a `EXECUTING` antes de llamar al ejecutor.
- El resultado del cronómetro por accesibilidad se aplica solo si el ledger lo registra por primera vez y el pedido
  sigue vigente.
- **Reintento tras resultado incierto**: antes, reenviar el mismo texto después de un `UNCERTAIN` volvía a ejecutar
  el comando automáticamente, conservando incluso la confirmación anterior. Ahora pide una confirmación nueva y
  explícita que avisa que el intento anterior pudo haberse realizado.

Se evaluó extraer `VoiceSessionController`, `InterpretationSessionCoordinator` y `ActionExecutionCoordinator`, pero no
se hizo: el estado del panel (`floatingState`, borradores, confirmaciones, opciones) está entrelazado en el ViewModel y
moverlo exigía reescribir la mayor parte de sus ≈2.000 líneas sin una mejora verificable. Se extrajeron las dos
responsabilidades con reglas comprobables (identidad/estado y ejecución única) y el resto queda como trabajo futuro.

### Capas de protección contra resultados tardíos

1. Cancelación estructurada: iniciar, editar, pausar, cancelar o cerrar cancela el `floatingJob`, y el coordinador
   llama a `ensureActive` después de cada respuesta de un proveedor.
2. Identidad del pedido: todo resultado comprueba que su pedido siga vigente antes de tocar el estado.
3. Compuerta de ejecución: un pedido cancelado o terminado nunca ejecuta.

Una prueba de mutación mostró que, desactivando solo la identidad, 6 de los 7 escenarios siguen protegidos por la
cancelación estructurada; la identidad fue decisiva en el séptimo (el `catch` del pedido cancelado pisaba el estado
nuevo). Las capas se mantienen como defensa en profundidad.

## Pruebas nuevas

| Prueba | Qué cubre |
| --- | --- |
| `ProviderHttpTest` | límites finitos; clasificación central; timeout de lectura, de escritura y de llamada completa contra un servidor local real; conexión cortada sin reenvío; cancelación durante una subida de 64 MB; reintento explícito del llamador; tamaño declarado excesivo |
| `GeminiTransportTest`, `GeminiDecisionsClientTest` | migradas a `FakeHttp`; 401/403/408/422/429/5xx/3xx sin reintentos; JSON inválido y demasiado grande; cancelación sin fallback a Groq |
| `GroqTranscriberTest` | multipart estándar (campos, nombre `recording.m4a`, `audio/mp4`, contenido binario exacto), límites de subida, cancelación durante la subida, errores sin reintento, audio conservado |
| `InterpretationRoutingMetricsTest` | 7 escenarios de fallas × 3 políticas de especulación, en tiempo virtual: exactitud, aceptaciones incorrectas, rechazos válidos, parámetros perdidos, derivaciones a Instinct, llamadas por proveedor, p50/p95 |
| `ProviderDivergenceTest` | inversión, acción agregada, parámetro cambiado, otro dispositivo, hipótesis, negación, consulta convertida en operación, paso perdido |
| `GeminiCoordinatorTest` (nuevas) | Groq no se consulta con Decisions sano; vuelve a superponerse después de una falla |
| `SessionLifecycleTest` | transiciones válidas e inválidas, estados finales, pedidos viejos, cierre y reapertura |
| `ActionExecutionLedgerTest` | callback duplicado, resultado incierto, cancelación después de despachar, WhatsApp sin confirmación (un toque, un reporte) |
| `FloatingSessionIsolationTest` (Robolectric) | cancelar mientras responde Gemini; Groq después de cerrar; dos pedidos casi simultáneos; edición durante la interpretación; resultado viejo con borrador nuevo; recreación de la actividad; confirmar un plan cancelado |

## Pruebas que requieren dispositivo físico (no ejecutadas)

- Latencia y timeouts reales de Gemini, Groq, SMN y Open-Meteo en redes móviles (las cifras de B son simulaciones).
- Subida real de audios grandes a Groq con red lenta (se probó contra un servidor local).
- Auto-envío de WhatsApp y cronómetro de Reloj de Google con el servicio de accesibilidad real.
- Recreación real de `AssistActivity` (rotación, cambio de tema) durante una interpretación.
- El caso `UNCERTAIN` del ejecutor de plataforma (Android acepta el cambio pero no lo refleja) no se puede forzar en
  Robolectric; la regla de reintento se cubre con `ActionExecutionLedgerTest` y su cableado directo en el ViewModel.

## Riesgos residuales

- El ledger vive en memoria del ViewModel: al cerrar el asistente se pierde. El reintento automático tampoco
  sobrevive al cierre, así que no hay una ruta de repetición afectada, pero un pedido nuevo idéntico se ejecuta como
  pedido nuevo.
- Las consultas del SMN y del calendario no pasan por `EXECUTING`: son lecturas sin efectos externos y conservan sus
  propias comprobaciones de identidad.
- Resueltos en la Fase 5 (ver abajo): motivo tipado en `Rejected`, `REJECTED` del cronómetro como incierto y
  especulación ante fallas de la extracción de Gemini.

## Seguimiento: lecturas no bloqueantes, dependencias y riesgos residuales

### Fase 1 — Sin `runBlocking` en el hilo principal

Antes había 10 lecturas de DataStore bloqueantes en código de producción: 5 en `MainViewModel` (`runBlockingPrefs`),
`AssistActivity.onCreate`, `PingQuickSettingsTileService.onStartListening`, dos en `PingAssistWidgetProvider` y una
en `PlatformActionExecutor` (envío confirmado de WhatsApp). Las tres últimas no estaban en el análisis original, pero
el criterio era que no quedara ninguna.

- **`AssistantPreferencesCache`** (`com.example.data`): la crea `InstoApplication.onCreate` y expone
  `StateFlow<AssistantPreferences?>` (`stateIn(Eagerly, null)` sobre el flujo del repositorio), `current` (nunca
  bloquea) y `awaitCurrent()` (suspende solo hasta el primer valor). Un `IOException` de DataStore se trata como
  preferencias vacías, como recomienda DataStore, en lugar de dejar la caché en `null` para siempre.
- **`MainViewModel`**: `prepareFloatingAssistant` aplica el valor cacheado; en arranque en frío lanza la carga en
  `viewModelScope` y el panel muestra «Instinct» hasta que llega. Las rutas que leen `floatingPrefs` justo después
  (`dispatchFloatingAudio`, `dispatchFloatingTextQuery`, `sendFloatingAttachment`) son `suspend` y esperan dentro de
  la corrutina del pedido, así que cancelar el pedido cancela la espera; después comprueban la identidad del pedido.
  `openFloatingInstinct` y `openFloatingInstinctManually` corren en una corrutina `UNDISPATCHED`: con la caché cargada
  se ejecutan de forma síncrona, igual que antes. En las sesiones de grabación el micrófono arranca antes de leer
  preferencias, para no perder audio.
- **`AssistActivity`**: `ACTION_OPEN_CHAT` decide en `lifecycleScope` y no dibuja nada mientras tanto.
- **Tile**: pinta con el valor cacheado (o un estado neutro, sin «Set Contact») y se actualiza con cada cambio
  mientras el sistema lo escucha (`onStartListening` → `onStopListening`).
- **Widget**: usa el valor cacheado; en frío, `goAsync()` y carga fuera del hilo principal. `requestUpdate` recibe el
  nombre recién guardado, para no mostrar el contacto anterior.
- **`PlatformActionExecutor`**: el envío confirmado usa el valor cacheado; si todavía no cargó (no ocurre después de
  una confirmación), no envía y pide reintentar, en lugar de elegir otra variante de WhatsApp.

Pruebas: `NoBlockingCallsInProductionTest` recorre `app/src/main` y falla ante cualquier `runBlocking` (allowlist
vacía); `AssistantPreferencesCacheTest` (null hasta el primer valor, espera sin bloquear, sigue cada cambio, archivo
ilegible) y `AssistantPreferencesCacheRepositoryTest` (escrituras reales del repositorio llegan a la caché). Las
pruebas Robolectric usan `TestInstoApplication`, que arranca con la caché cargada, y `awaitCachedPreferences` después
de escribir preferencias, porque la caché las recibe de forma asíncrona.

### Fase 3 — Dependencias y plugins

- **Plugins quitados**: KSP, google-services y secrets (con sus bloques `secrets {}` y `googleServices {}`, la
  importación de `MissingGoogleServicesStrategy`, `googleServices.missing.passthrough` y `.env.example`). Nada leía
  `BuildConfig.GEMINI_API_KEY`: la clave de Gemini vive en el Keystore. `.gitignore` conserva `.env` como protección.
- **Librerías quitadas**: las 25 líneas comentadas y su catálogo (Room, Moshi, Retrofit, Coil, CameraX, Firebase,
  Credentials, Navigation, Accompanist, play-services-location, logging-interceptor), más `ui-tooling-preview` (no hay
  `@Preview`), `espresso-core` y el Compose BOM / `ui-test-junit4` de `androidTest` (las pruebas instrumentadas no
  los usan). `ui-tooling` queda en `debugImplementation` como herramienta de depuración.
- **Catálogo**: plugins 5 → 2, versiones 41 → 15, librerías 51 → 25; dependencias declaradas en `app` 31 → 27 y 0
  comentadas. Artefactos resueltos en release 106 → 131: Compose 1.11 se reparte en más módulos.
- **Versiones**: Compose BOM 2024.09.00 → 2026.06.01 (Compose UI 1.11.4, Material 3 1.4.0), lifecycle 2.8.7 →
  2.10.0, activity-compose 1.10.1 → 1.13.0, coroutines 1.10.2 → 1.11.0, Robolectric 4.16.1 → 4.18.1, androidx.test
  core/runner 1.6.x → 1.7.0, org.json 20240303 → 20260814 (ahora en el catálogo). Compose BOM ≥ 2026.08 (Compose 1.12,
  Material 3 1.5) y lifecycle 2.11 exigen `compileSdk 37`; no se subió `compileSdk` en este cambio. OkHttp sigue en
  4.12.0.
- **Lint**: el baseline perdió solo sus 13 entradas obsoletas (OkHttp 4.10.0, logging-interceptor, Retrofit, Moshi,
  play-services-location, las versiones ya subidas y `UseTomlInstead` del `org.json` suelto); no se agregó ninguna.
- **CI**: `.github/dependabot.yml` (Gradle y GitHub Actions, semanal, grupos `androidx` y `kotlin`, sin OkHttp 5) y
  un paso que compara el SHA-256 de `app/libs/android-vad-webrtc-2.0.10.aar` con `app/libs/README.md`.

### Fase 5 — Riesgos residuales

- **Motivo tipado.** `InterpretationResult.Rejected(message, reason = NOT_UNDERSTOOD)` con `RejectionReason`:
  `NOT_UNDERSTOOD`, `UNAVAILABLE`, `BLOCKING_CONDITION`, `UNSAFE_OR_HYPOTHETICAL` y `OUT_OF_SCOPE`. Los mensajes no
  cambiaron. `RequestSafetyPolicy.refusal` separa hipótesis o cita y negación (`UNSAFE_OR_HYPOTHETICAL`), condición
  (`BLOCKING_CONDITION`, con el mismo mensaje que la negación) y restricción de ruta (`UNAVAILABLE`);
  `rejection(text)` sigue devolviendo el texto para sus otros llamadores. `PhoneRoute.Unsupported` y
  `ConversationResult.Prompt` también llevan motivo (por defecto `UNAVAILABLE` y `NOT_UNDERSTOOD`), así que el que
  viene del registro o del parser llega al coordinador. Casos asignados a mano: pedido pendiente →
  `BLOCKING_CONDITION`; «Pedido cancelado.» → `UNSAFE_OR_HYPOTHETICAL`; acción del teléfono como tema de una búsqueda
  o un mensaje y clima fuera del alcance del SMN → `OUT_OF_SCOPE`; las demás objeciones del `SemanticCommandGuard` y
  de la validación de propuestas → `NOT_UNDERSTOOD`.
- **Cronómetro por accesibilidad.** «No pude comprobar el cambio del cronómetro» se sigue mostrando igual
  (`REJECTED`), pero lleva `effectUnverified = true` y el ledger lo registra como `DISPATCHED_UNCERTAIN`. Reenviar el
  mismo pedido pide una confirmación nueva («El intento anterior pudo haberse realizado…») y no vuelve a abrir el
  Reloj. «Activá Accesibilidad…» (nada se tocó) sigue siendo `FAILED` y se puede reintentar. Se marca incierto todo
  `fail()` de la automatización, también cuando todavía no había tocado ningún control: es el lado seguro.
- **Especulación ante fallas de extracción.** El coordinador observa la extracción de Gemini con una segunda
  instancia de `DecisionsCircuitBreaker` (`record`, sin bloquear llamadas ni hacer pruebas de recuperación). Con dos
  fallas seguidas (o una de cuota o «no disponible») se abre por 30 s, y mientras `openRecently()` sea verdadero
  `WHEN_DEGRADED` vuelve a consultar a Groq en paralelo. Una extracción correcta lo cierra. Umbrales de
  `DecisionsPolicy` sin cambios. Cifras en `docs/metrics/reliability-before-after.md`.
- **`GroqKeyStore`.** `read()` devuelve `StoredKey`: `Missing`, `Present(value)` o `Unreadable` (clave del Keystore
  invalidada o reemplazada, archivo dañado). `readValue()` / `GeminiSettings.readKey()` solo devuelven una clave
  válida, así que una ilegible nunca habilita Decisions ni Groq. La pantalla «Voz e IA» muestra «Clave ilegible» y
  «La clave guardada no se puede leer; volvé a cargarla.» para cada proveedor afectado, y permite borrar la de Groq.
  `toString()` no incluye el valor y no se registra nada. La escritura sigue siendo atómica (archivo temporal y
  `renameTo`). La clave AES es inyectable para pruebas.

Pruebas nuevas: `RejectionReasonTest`; `ActionExecutionLedgerTest.anAutomationThatCouldNotVerifyItsEffectIsUncertainNotFailed`;
`StopwatchAccessibilityRoutingTest.retryAfterAnUnverifiedStopwatchChangeAsksForANewConfirmation` (falla si se quita
`effectUnverified`); `DecisionsCircuitBreakerTest.observedExtractionOpensOnlyAfterRepeatedRecentFailuresAndNeverBlocks`;
escenario `extraction_5xx_sparse` y aserciones de reactivación en `InterpretationRoutingMetricsTest`;
`GroqKeyStoreTest` (Keystore simulado: invalidación, clave reemplazada, archivo dañado, recarga, valor oculto);
`KeySettingsUnreadableTest` (Robolectric, pantalla de ajustes). `WeatherIntentTest` ahora espera `OUT_OF_SCOPE` en
el pedido de lluvia fuera de alcance (antes comparaba con el motivo por defecto).

### Pendiente de prueba en un teléfono real

- Arranque en frío real: abrir el asistente, el tile y el widget recién iniciado el proceso, y confirmar que no hay
  bloqueo ni pantalla con preferencias por defecto (Robolectric solo cubre el camino con la caché cargada y la caché
  aislada).
- Tile de ajustes rápidos: actualización mientras está visible al cambiar el contacto.
- Widget en arranque en frío (`goAsync`) y después de guardar un contacto.
- APK debug con las dependencias nuevas (Compose 1.11, lifecycle 2.10, activity 1.13, coroutines 1.11): que abra y se
  comporte igual.
- Cronómetro de Reloj de Google con el servicio de accesibilidad real cuando la verificación falla después de tocar
  un control.
- Una clave invalidada de verdad por Android Keystore (por ejemplo al quitar el bloqueo de pantalla) y su recarga.

