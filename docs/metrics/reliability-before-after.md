# Confiabilidad: métricas antes y después

Mismo entorno para ambas columnas: contenedor Linux, JDK 21, Android SDK 36.1, Gradle 9.3.1, Robolectric. «Antes» es
el commit `edcafd2`; «después», el final de esta rama. Ninguna cifra viene de un dispositivo ni de llamadas reales
a Gemini o Groq.

## Suites

| Suite | Antes | Después |
| --- | --- | --- |
| `./gradlew testDebugUnitTest` | 684 pruebas, 0 fallas, 1 omitida | 723 pruebas, 0 fallas, 1 omitida |
| `assembleDebug` | correcto | correcto |
| `lintDebug` (con `lint-baseline.xml`) | correcto, 25 avisos en el baseline | correcto; mismos avisos de código. Los 2 avisos «OkHttp 5.5.1 disponible» dejan de coincidir con el baseline (que los registraba para 4.10.0) y aparecen como informativos |
| `scripts/test-phone-core.py` (JVM sin Android) | 434 pruebas OK | 459 pruebas OK |
| Release (`assembleRelease`, R8 + lint vital) | no verificado | correcto, firmado con una clave efímera de prueba vía `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD` (sin tocar la configuración de firma; el APK no es distribuible) |

La prueba omitida es `GeminiCorpusTest.liveProviderEvaluationIsExplicitAndDoesNotExecuteCommands`, que exige
`INSTO_GEMINI_LIVE=1` y claves reales.

## B. Enrutamiento entre proveedores (`InterpretationRoutingMetricsTest`)

**Método.** El corpus `gemini-es-corpus.json` sin los casos de contexto (110) más 17 órdenes rioplatenses nuevas
(127 en total; 91 positivas). Proveedores simulados en tiempo virtual (`kotlinx-coroutines-test`) que cuentan cada
llamada **al empezar**, incluidas las especulativas que después se cancelan. Latencias declaradas: Decisions 700 ms,
extracción de Gemini 900 ms, Groq 800 ms, verificación de Groq 400 ms, error 250 ms. Son entradas de la simulación,
no mediciones de red: muestran el efecto relativo del enrutamiento. Un único circuito por corrida con reloj virtual y
5 s entre pedidos.

«Antes» = código de `edcafd2` (especulación `ALWAYS`, guard y política originales) sobre el mismo corpus y el mismo
arnés. «Después» = `WHEN_DEGRADED` con las correcciones del guard y de la política.

| Escenario | Correctas antes → después | Aceptadas inseguras | Llamadas a Groq | Llamadas por pedido | p95 de pedidos con proveedor (ms) |
| --- | --- | --- | --- | --- | --- |
| Proveedores sanos | 88/91 → 88/91 | 0 → 0 | 18 → **0** | 0,433 → **0,244** | 2000 → 2000 |
| Decisions con 5xx | 88/91 → 88/91 | **3 → 0** | 18 → 15 | 0,205 → 0,173 | 950 → 950 |
| Decisions sin respuesta | 88/91 → 88/91 | **3 → 0** | 18 → 15 | 0,205 → 0,173 | 4000 → 4000 |
| Decisions dice «unsupported» | 88/91 → 88/91 | 0 → 0 | 18 → 13 | 0,307 → 0,220 | 1100 → 1500 |
| Extracción de Gemini con 5xx | 88/91 → 88/91 | 0 → 0 | 18 → 5 | 0,433 → 0,283 | 2250 → 3050 |
| Extracción de Gemini sin respuesta | 88/91 → 88/91 | 0 → 0 | 18 → 5 | 0,433 → 0,283 | 5100 → 5900 |
| Todos caídos | 75/91 → 75/91 | 0 → 0 | 18 → 15 | 0,205 → 0,173 | 1050 → 1050 |

En todos los escenarios y políticas: 0 parámetros perdidos o cambiados y 0 derivaciones incorrectas a Instinct. Las
3 aceptaciones inseguras de «antes» eran hipótesis rioplatenses («Suponete que apagás el wifi», «Imaginate que
prendés la linterna», «Supongamos que bajás el volumen») que, con Decisions caído, terminaban ejecutando la propuesta
de Groq. Los 3 rechazos de órdenes válidas son los mismos antes y después: «Avisame cuando pasen diez minutos»
(«cuando» se trata como condición), «Dale, prendé la linterna y poné un timer de diez minutos» (el «Dale,» inicial
impide el plan local de dos pasos) y «terminá este modo temporal y volvé al estado anterior» del corpus original (pide una acción por vez).

**Por qué `WHEN_DEGRADED` y no `NEVER`.** Con Decisions caído, `NEVER` hace esperar a Groq: p95 de pedidos con
proveedor 950 → 1750 ms (5xx) y 4000 → 4800 ms (sin respuesta), sin ganar exactitud. `WHEN_DEGRADED` conserva la
latencia original en esos casos y elimina las consultas desperdiciadas con proveedores sanos.

**Costo aceptado.** +400 a +800 ms simulados en el p95 cuando Decisions clasifica mal una orden válida como
«unsupported» o cuando falla la extracción de Gemini con Decisions sano. La tasa de éxito no cambia.

## B. Suites semánticas existentes (sin cambios)

| Métrica | Antes | Después |
| --- | --- | --- |
| `SEMANTIC_METRICS` propuestas incorrectas aceptadas | 0/196 | 0/196 |
| `SEMANTIC_METRICS` parámetros perdidos | 0 | 0 |
| `SEMANTIC_METRICS` válidas rechazadas | 0/25 | 0/25 |
| `NEGATION_METRICS` falsos positivos / negativos | 0 / 0 | 0 / 0 |
| `replay escalonado` correctas | 80/81 (98,77 %), 0 incorrectas | 80/81 (98,77 %), 0 incorrectas |
| `METRICS` (155 pedidos) local / falsos positivos / Instinct incorrecto | 120 (77,42 %) / 0 / 0 | 120 (77,42 %) / 0 / 0 |
| `LEARNING_METRICS AFTER` correctas / incorrectas | 48 / 0 | 48 / 0 |

El `calls_per_request` de `replay escalonado` (0,18) no cambia porque `GeminiDiagnostics` solo cuenta a Groq cuando
se usa como respaldo; la reducción de consultas especulativas solo es visible en el arnés nuevo, que las cuenta al
empezar.

## A. Dependencias

| Artefacto (release) | Antes | Después |
| --- | --- | --- |
| `com.squareup.okhttp3:okhttp` | 4.10.0 | 4.12.0 |
| `com.squareup.okio:okio` (resuelto) | 3.4.0 | 3.6.0 |
| `kotlin-stdlib-jdk7` / `-jdk8` (resueltos; vacíos desde Kotlin 1.8) | 1.8.0 | 1.9.10 |

No se agregaron ni quitaron artefactos. `logging-interceptor` sigue comentado en `app/build.gradle.kts`; solo se
alineó su versión en el catálogo.

## A. Red

No hay mediciones de latencia de red real. Lo verificado:

- Todo límite es finito (antes, `writeTimeout(0)`); cada presupuesto JSON entra en el de su llamador.
- Timeout de lectura (300 ms), de escritura (300 ms con 64 MB sin leer) y de llamada completa (800 ms con respuesta
  gota a gota) contra un servidor HTTP local real: terminan en menos de 2, 6 y 3 s y se clasifican como TIMEOUT (la
  escritura, como TIMEOUT o NETWORK según cuándo cierra el socket el servidor).
- Una conexión cortada después del POST llega al servidor exactamente una vez.
- Cancelar durante una subida de 64 MB termina en menos de 1 s, con un solo pedido en el servidor.
- 401/403/408/422/429/5xx/3xx: un solo intercambio; el único segundo pedido es el cambio documentado de modelo de
  extracción ante un 404.
- Las pruebas de red con servidor real se repitieron 3 veces seguidas sin fallas.

## C. Sesiones

| Escenario | Resultado |
| --- | --- |
| Cancelar mientras responde Gemini | no se muestra ni ejecuta el plan; estado «Pedido cancelado.» |
| Groq responde después de cerrar el asistente | la respuesta llega (el proveedor ignora la cancelación) y se descarta |
| Dos pedidos casi simultáneos | solo queda el último |
| Editar la orden durante la interpretación | la respuesta vieja se descarta; el texto editado se conserva |
| Resultado viejo con un borrador nuevo | el borrador sigue y se completa normalmente |
| Recrear la actividad durante un pedido | el pedido sigue y se completa |
| Confirmar un plan ya cancelado | no se ejecuta nada |
| Callback de accesibilidad duplicado | se registra y aplica una vez |
| Cancelar después de despachar | el resultado queda registrado como incierto; no se revierte ni se repite |
| WhatsApp no confirma | un solo toque de Enviar y un solo reporte por pedido |
| Reintento después de un resultado incierto | antes: se repetía solo; ahora: pide confirmación explícita |

Prueba de mutación: con la identidad de sesión desactivada, 1 de los 7 escenarios de `FloatingSessionIsolationTest`
falla; los otros 6 siguen protegidos por la cancelación estructurada.

## Cómo reproducir

```sh
python3 scripts/test-phone-core.py com.example.phone.InterpretationRoutingMetricsTest   # líneas ROUTING_METRICS
./gradlew testDebugUnitTest assembleDebug lintDebug
# Métricas en app/build/test-results/testDebugUnitTest/*.xml (ROUTING_METRICS, SEMANTIC_METRICS, METRICS, ...)
```

El «antes» del enrutamiento se obtiene corriendo el mismo arnés con `FallbackSpeculation.ALWAYS` y con
`SemanticCommandGuard.kt` y `RequestSafetyPolicy.kt` de `edcafd2`.
