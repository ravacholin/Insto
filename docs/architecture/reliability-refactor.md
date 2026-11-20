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
  recuperación). Si no, Groq se consulta cuando hace falta.
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
con su mensaje. `Rejected` no lleva un motivo tipado (ver riesgos).

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

- `Rejected` no distingue con un tipo «no comprendido», «no disponible» y «condición»; solo el mensaje los diferencia.
- El ledger vive en memoria del ViewModel: al cerrar el asistente se pierde. El reintento automático tampoco
  sobrevive al cierre, así que no hay una ruta de repetición afectada, pero un pedido nuevo idéntico se ejecuta como
  pedido nuevo.
- `REJECTED` del cronómetro por accesibilidad («no pude comprobar el cambio») puede ocurrir después de tocar
  controles; se registra como fallido, aunque el estado real es incierto. Conviene marcarlo `UNCERTAIN` en el futuro.
- `WHEN_DEGRADED` usa solo las fallas de Decisions; una extracción de Gemini que falla seguido no reactiva la
  especulación.
- Las consultas del SMN y del calendario no pasan por `EXECUTING`: son lecturas sin efectos externos y conservan sus
  propias comprobaciones de identidad.
