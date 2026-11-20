# Clima del SMN: temperatura y probabilidad de lluvia

Insto responde dos consultas meteorológicas **en el teléfono**, con datos exclusivamente del
Servicio Meteorológico Nacional (SMN):

| Intent | Acción | Fuente |
| --- | --- | --- |
| `weather_temperature` | `PlatformAction.WEATHER_TEMPERATURE` (con `argument = "feels_like"` si se pregunta por la sensación térmica) | Datos abiertos «Estado del tiempo presente» (`tiepre`) |
| `weather_rain` | `PlatformAction.WEATHER_RAIN` | API `ws1` del SMN, pronóstico por localidad |

No intervienen Gemini Decisions, Groq ni Instinct. No se usa OpenWeather, Open-Meteo, Google
Weather ni ningún otro tercero, servidor propio, clave de API, cuenta, GPS ni permiso de
ubicación. La respuesta es texto en la superficie del asistente flotante: no se abre el
navegador, no se envía nada a WhatsApp y no hay lectura en voz alta (Insto no tiene TTS).

## Estado de verificación (20/11/2026)

| Punto | Estado |
| --- | --- |
| `tiepre`: GET simple, HTTP 200, ZIP de 3 KB, sin token ni bloqueo | **Verificado en vivo** |
| `tiepre`: Windows-1252, CRLF, sin encabezado, `;`, 10 columnas, ` / ` final | **Verificado.** Además: la primera línea no empieza con espacio y hay temperaturas enteras (`24`) |
| `tiepre`: horas distintas por fila (09:00, 11:00, 12:00 y filas del día anterior a las 21:00) | **Verificado** |
| Estación «Buenos Aires» = Observatorio Central | **No verificado por clave.** El listado oficial de estaciones (`?dato=estaciones`) tiene «BUENOS AIRES OBSERVATORIO» (Capital Federal, OACI SABA) y «AEROPARQUE AERO», pero no comparte una clave con `tiepre`. Por eso la respuesta usa el nombre tal cual aparece en el archivo: «Buenos Aires». |
| Portada `www.smn.gob.ar` con un User-Agent honesto | **HTTP 403 «Attention Required! \| Cloudflare»** desde el entorno de desarrollo (datacenter). No apareció el patrón del token. No se insistió. |
| Token anónimo, `ws1/forecast`, estructura del JSON, ID 4864 = «Capital Federal» | **No verificado.** El parser sigue la estructura que describen clientes independientes y el fixture `app/src/test/resources/smn/forecast-4864.unverified.json` está marcado como no verificado. |
| Comportamiento desde un celular con IP argentina | **No verificado.** Si Cloudflare también bloquea ahí, la consulta de lluvia responde «El SMN no permitió la consulta automática del pronóstico en este momento.» y se detiene. |

La temperatura funciona sin depender de la portada ni del token.

## Frases

Se reconocen oraciones completas, con tuteo o voseo, acentos, signos de pregunta, «por
favor» y fórmulas como «¿podés decirme…?», «quiero saber…», «decime si…».

- **Temperatura:** «temperatura», «¿qué temperatura hace?», «¿cuál es la temperatura?»,
  «¿cuántos grados hace?», «¿cómo está la temperatura?», «decime la temperatura»,
  «¿qué temperatura hay en Buenos Aires?».
- **Sensación térmica:** «sensación térmica», «¿cuál es la sensación térmica?».
- **Lluvia:** «lluvia», «¿va a llover?», «¿va a llover hoy?», «¿qué probabilidad de lluvia
  hay?», «probabilidad de lluvia», «¿hay pronóstico de lluvia?», «¿va a llover en las
  próximas horas?», «¿qué chances de lluvia hay?», «decime si va a llover», «¿cómo viene la
  lluvia para las próximas horas?».

**No activan** la consulta: «lluvia de ideas», «explicame qué es la sensación térmica» (va
como consulta general), «escribí un cuento sobre la lluvia», «no consultes la temperatura»,
«quiero cambiar el formato de temperatura», «si llueve…» (es una condición), «qué calor
hace» (es una exclamación).

**Fuera de alcance, con mensaje local y sin consultar:** otra localidad («¿qué temperatura
hace en Córdoba?»), otro día o el pasado («¿qué temperatura hubo ayer?», «¿va a llover el
sábado?», «¿va a llover mañana?»), partes del día que la ventana no garantiza («esta
noche») y máximas o mínimas del pronóstico. Respuestas:

- Temperatura: «Por ahora solo consulto la medición actual del SMN en la estación Buenos Aires.»
- Lluvia: «Por ahora solo consulto el SMN para Capital Federal y las próximas horas.»

`«Decime si va a llover»` es una pregunta indirecta: `RequestSafetyPolicy.rejection` no la
trata como condición. Cualquier otra frase con «si» sigue el control habitual.

## Respuestas

Coma decimal, nombre de estación o localidad tal cual lo publica el SMN, hora de la
medición o del período y atribución «Según el SMN» o «SMN».

**Temperatura** (`tiepre`):

- «Según el SMN, en Buenos Aires hay 27,6 grados (medición de las 11:00). La sensación térmica es de 28,2.»
- Si el archivo dice `No se calcula`: «Según el SMN, en Buenos Aires hay 25,7 grados (medición de las 11:00).»
  Si la pregunta era por la sensación térmica: «El SMN no calcula la sensación térmica para Buenos Aires en la medición de las 11:00.»
- Si el campo falta o es inválido: «El SMN no informa la sensación térmica para Buenos Aires en este momento.»
- Si la fila tiene más de tres horas: «La última medición del SMN para Buenos Aires es de hace más de tres horas; no la informo como temperatura actual.»
- Se conserva la precisión publicada: `24` se dice «24», `27.6` se dice «27,6».

Nunca se calcula una sensación térmica propia ni se usan temperaturas del pronóstico
(mínima, máxima o por período) como temperatura actual.

**Lluvia** (`ws1`): la ventana es de **6 horas desde la consulta**. Es una decisión de
experiencia de usuario, no una resolución del SMN. Se informan los bloques publicados que se
superponen con la ventana, como máximo dos, con sus límites originales:

- «Según el SMN, en Capital Federal, para esta tarde (12 a 18) hay entre un 40 y un 70 por ciento de probabilidad de precipitaciones. Para la noche (18 a 24), entre un 10 y un 40 por ciento.»
- Valor publicado cero: «En Capital Federal, para esta mañana (6 a 12), el SMN indica un cero por ciento de probabilidad de precipitaciones.»
- Cruce de medianoche: «… Para mañana a la madrugada (0 a 6), entre un cero y un 10 por ciento.»
- Sin porcentaje verificable: «El SMN no informa una probabilidad de precipitaciones para Capital Federal en las próximas horas.»
- Bloqueo o límite: «El SMN no permitió la consulta automática del pronóstico en este momento.»
- Otro fallo: «No pude consultar el pronóstico actualizado del SMN.»

Prohibido y cubierto por tests: promediar rangos o usar su punto medio; deducir la
probabilidad de `rain06h` (milímetros), de la descripción, la humedad o la nubosidad; tratar
un dato ausente como cero; decir «no va a llover» por falta de datos. `Exact` solo se usa si
el rango publicado tiene `min == max`. El arreglo original se conserva en `originalRange`.

Si `location` no trae nombre, la respuesta dice «la localidad 4864 del SMN»; nunca lo
reemplaza por «Buenos Aires».

## Red y seguridad

Código en `app/src/main/java/com/example/phone/weather/`.

- **Transporte:** `SmnTransport` es una interfaz inyectable. En producción,
  `OkHttpSmnTransport` usa `ProviderHttp.get`: GET acotado sobre el mismo pool y dispatcher
  de OkHttp, sin seguir redirecciones, con 4 s para conectar, 8 s para leer y cancelación
  cooperativa. Usa un cliente derivado **sin** el listener de tiempos, y ese listener ya no
  etiqueta hosts ajenos como Gemini o Groq: el SMN no altera `GeminiDiagnostics`.
- **User-Agent honesto:** `Insto/1.12 (Android)`. Sin cookies, sin WebView, sin cloudscraper,
  sin resolver desafíos, sin rotar IP y sin reintentos masivos.
- **Hosts admitidos:** solo `ssl.smn.gob.ar`, `www.smn.gob.ar` y `ws1.smn.gob.ar`, por HTTPS,
  sin usuario ni puerto (`SmnHosts`). `ws2` no se usa.
- **`tiepre`:** máximo 64 KB comprimido; se rechaza HTML o lo que no empiece con la firma ZIP;
  un único `.txt` sin rutas; máximo 256 KB descomprimido contando los bytes realmente
  inflados (protección contra zip bombs); decodificación `windows-1252`; filas ilegibles se
  descartan, no se adivinan. La fecha usa el mes en castellano y se interpreta en
  `America/Argentina/Buenos_Aires`.
- **Token:** se extrae de la portada (máximo 1 MB) con un patrón acotado, comillas simples o
  dobles. Debe tener tres segmentos base64url y un encabezado JSON con `alg`. Si el payload
  trae `exp`, se renueva con 2 minutos de margen; si no, dura 10 minutos. Un token ya vencido
  es un error. Vive **solo en memoria** (`SmnTokenProvider`, protegido con `Mutex`): nunca en
  preferencias, DataStore, archivos, copias de seguridad ni logs; `toString()` no lo muestra.
  Se envía únicamente a `ws1.smn.gob.ar` como `Authorization: JWT …`.
- **401:** se invalida el token, se renueva **una vez** y se reintenta una vez. Un segundo 401
  es `UNAUTHORIZED`.
- **403, 429, 503 o desafío de Cloudflare** (`cf-mitigated: challenge`, HTML con «Attention
  Required», `cf-chl` o `challenge-platform`): error `BLOCKED` o `RATE_LIMITED`, sin
  reintento, y **enfriamiento en memoria de 30 minutos**. Un bloqueo en la portada o en `ws1`
  enfría ambos hosts: durante ese lapso no se vuelve a pedir la portada.
- **Errores tipados:** `NETWORK`, `TIMEOUT`, `BLOCKED`, `RATE_LIMITED`, `UNAUTHORIZED`,
  `NOT_JSON`, `MALFORMED`, `TOO_LARGE`. Los mensajes no incluyen tokens ni cuerpos.
- **Pronóstico:** `Content-Type` JSON obligatorio y máximo 256 KB.

## Frescura y caché

- Observación de más de 3 horas: no se presenta como actual. Una hora posterior a la actual
  (más de 15 minutos) tampoco se informa.
- Pronóstico cuyo `updated` tiene más de 24 horas: no se informa.
- Se descartan los períodos ya terminados.
- Caché en memoria, compartida por el proceso (`SmnWeatherService.shared`): 5 minutos para el
  `tiepre`, **pero nunca más allá de la próxima hora en punto**, porque el archivo se publica
  por hora; 15 minutos para el pronóstico. Pedidos concurrentes iguales comparten un único
  `Deferred`. Los errores no se guardan en caché (salvo el enfriamiento).
- Sin caché en disco, sin polling, sin workers y sin llamadas al arrancar.

## Asistente flotante

`MainViewModel` intercepta ambas acciones junto a `CALENDAR_QUERY` y llama a
`queryFloatingWeather`, con el mismo patrón que `queryFloatingCalendar`: captura
`floatingGeneration`, pone `FloatingMode.SENDING` con «Consultando el SMN…», lanza
`viewModelScope.launch`, vuelve a lanzar `CancellationException`, descarta la respuesta si
cambió la generación y muestra el texto en `status`. Los fallos van a
`retainDecisionRequest(...)`, que conserva el pedido para reintentar. La red y el parseo corren
en `Dispatchers.IO` **dentro** del servicio, para que el view model se pruebe con un
`WeatherQueries` falso. `PhoneActionExecutor.execute()` sigue siendo síncrono: si una acción
del SMN llegara ahí, se rechaza con un mensaje y no se hace HTTP.

## Rutinas, secuencias, frases y Decisions

- **Rutinas y secuencias: excluidas.** Son respuestas asíncronas sin resultado Android que
  confirmar. `WorkflowRunner.prepare` y `ConfiguredRoutineStore.decode` las rechazan, y el
  panel responde «Las consultas al SMN se hacen de a una, fuera de secuencias. Pedila sola.».
  Un plan de un solo paso con una consulta del SMN se responde directamente.
- **Frases aprendidas:** se pueden asignar manualmente en **IA → Frases aprendidas**, grupo
  «Clima (SMN)». Una frase aprendida llega como `InterpretationResult.Direct` y pasa por la
  misma consulta asíncrona.
- **Gemini Decisions: excluidas.** Los intents tienen `decisions = false`: no aparecen en el
  catálogo de Decisions ni en la extracción de Groq. Solo los activa la gramática local.

## Localidad y copia de seguridad

Temperatura: estación «Buenos Aires» del `tiepre`. Lluvia: localidad `4864`
(`smn_id_interno` de «Capital Federal»; no confundir con el campo `smn_id`, donde 4864 es
San Antonio de Paclín). La preferencia para elegir otra localidad o estación **no se
implementó**, porque dependía de verificar `ws1` (incluida la búsqueda
`georef/location/search`, que devuelve filas posicionales). Por eso no hay nada nuevo en la
copia de seguridad cifrada. El token nunca se guarda.

## Pruebas

JVM, sin internet (`python3 scripts/test-phone-core.py`):

- `phone/weather/SmnTokenTest`: JWT con comillas simples y dobles, página sin token, JWT
  malformado, `exp` vencido, margen de renovación, reutilización, una sola obtención
  concurrente, enfriamiento de la portada, hosts admitidos y base64url.
- `phone/weather/SmnForecastClientTest`: token solo hacia `ws1`, 401 resuelto con una
  renovación, 401 repetido sin bucle, 403 y desafíos con enfriamiento, portada bloqueada,
  429 y 503, `NOT_JSON`, `MALFORMED`, `TOO_LARGE`, `TIMEOUT`, `NETWORK` y mensajes sin token.
- `phone/weather/SmnOpenDataTest`: el ZIP real, Windows-1252, «No se calcula» frente a
  ausente, precisión publicada, horas por fila, filas ilegibles, HTML, no ZIP, varias
  entradas, rutas, ZIP truncado y zip bomb.
- `phone/weather/SmnForecastParserTest`: estructura (no verificada), períodos `null`,
  `Range`/`Exact`/`Missing`, `rain06h` ignorado, rangos inválidos y fechas.
- `phone/weather/SmnWeatherServiceTest`: textos de Fase 4, observación vieja, caché de
  5 minutos que no cruza la hora, deduplicación concurrente, ventana de 6 horas, cero,
  medianoche, ausencia de datos, pronóstico viejo, bloqueos y caché de 15 minutos.
- `phone/weather/SmnTransportTest`: GET acotado contra un servidor local, sin redirecciones,
  sin tocar `GeminiDiagnostics`, plazo de lectura y errores tipados.
- `phone/WeatherIntentTest`: frases positivas, negativas y fuera de alcance; política de
  seguridad; coordinador sin Gemini; registro, codec y catálogo; exclusión de secuencias y
  rutinas.

Robolectric (`FloatingWeatherTest`, corre con Gradle en CI): consulta y respuesta en la
superficie, fallo que conserva el pedido, respuesta tardía descartada por generación y
pedido fuera de alcance sin consulta. **No se ejecutó en este entorno** (no hay Android SDK).

Falta probarlo en un teléfono real, sobre todo si Cloudflare permite la portada desde una IP
móvil argentina.
