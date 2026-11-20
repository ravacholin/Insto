# Reconocimiento de intents en español

La línea base se midió sobre `1689b30` con el test de medición, antes de editar el código de producción. Las dos mediciones usaron Java 21 y el script JVM del repositorio.

| Métrica offline | Antes | Después |
| --- | ---: | ---: |
| Rutas resueltas en local sin Gemini | 96/155 (61,94 %) | 120/155 (77,42 %) |
| Llamadas a Decisions por pedido | 35/155 (0,2258) | 11/155 (0,0710) |
| Solicitud a Decisions en bytes UTF-8, promedio (mín.–máx.) | 34 713,61 (34 691–34 749) | 10 741,61 (10 719–10 777) |
| Resolución local, mediana / p95 | 120 761 / 316 255 ns | 86 660 / 224 707 ns |
| Falsos positivos en restricciones | 0 | 0 |
| Acciones locales indebidas en casos ambiguos | 1 | 0 |
| Derivaciones incorrectas a Instinct | 0 | 0 |
| Consultas esperadas sin Query / con ejecución indebida | 0 / 0 | 0 / 0 |

`IntentCoverageTest.offlineMetrics` registra el resultado, los IDs devueltos por el clasificador falso, las extracciones simuladas y los bytes de `buildRequest(texto)` para cada pedido, incluso cuando Decisions no interviene. El corpus aporta 110 pedidos iniciales y 21 continuaciones de borrador. Los 24 casos nuevos se suman como entradas independientes, incluidas las variantes coincidentes con el corpus. Las continuaciones usan `DraftPlan.reply`, como en las pruebas existentes.

El recuento de rutas locales incluye acciones, planes, aclaraciones y consultas que no necesitaron clasificador ni extractor. Excluye rechazos y borradores que todavía necesitan datos. Mide tráfico, por lo que incluye el `OpenApp("musica")` indebido de la línea base. La fila de ambiguos recoge ese error por separado.

El tiempo mide solo `PhoneCommandParser.parse` (incluido el registro), con 10 pasadas de calentamiento y 30 pasadas medidas sobre los 134 pedidos iniciales. Se usa el percentil de rango más próximo. Los tiempos dependen de la JVM y del entorno. No se midió latencia de red ni precisión de Gemini.

«Dame un poco de luz» y «Poné algo de los Beatles» se ejercitaron con las respuestas simuladas `torch_on` y `music`, respectivamente. «Necesito una cuenta regresiva de diez minutos» ya fue local en la línea base. Las otras alternativas pasan por el parser según los resultados registrados por el test.

Comandos de reproducción de la medición

```sh
./gradlew :app:testDebugUnitTest --tests com.example.phone.IntentCoverageTest --info
# Alternativa JVM del repositorio
# (hasta 1.11.1) python scripts/test-phone-core.py com.example.phone.IntentCoverageTest
```

## Frases aprendidas con parámetros y formas parecidas (1.12.0)

Medido el 2026-11-20 con `IntentCoverageTest.learnedPhrasesKeepRestrictionsAndAmbiguitiesOutOfLocalActions`. Usa un `LearnedPhraseStore` real sobre `PhraseTestDataStore`, con 12 frases sembradas (7 manuales y 5 aprendidas solas) y 38 pedidos en cinco grupos: expresiones rioplatenses (7), frases con un valor nuevo (8), variantes de forma o de transcripción (7), ambigüedades (8) y negaciones y restricciones (8). El clasificador y el extractor son simulados y devuelven la acción plausible, incluso para negaciones, para medir qué detiene el teléfono por sí mismo. «Antes» es el mismo arnés sobre `ee724f3`, en un worktree; ahí el almacén no tiene `resolveApp`, porque no existía.

| Métrica (38 pedidos con frases aprendidas) | Antes (`ee724f3`) | Después |
| --- | ---: | ---: |
| Resoluciones locales sin Gemini | 1/38 (2,63 %) | 21/38 (55,26 %) |
| Llamadas a Decisions por pedido | 34/38 (0,8947) | 10/38 (0,2632) |
| Falsos positivos en restricciones | 3 | 0 |
| Acciones locales indebidas | 0 | 0 |
| Derivaciones incorrectas a Instinct | 0 | 0 |

El corpus de 155 pedidos con una instalación limpia no cambia: 120/155 (77,42 %) local, 11 llamadas a Decisions (0,0710), 0 falsos positivos y 0 derivaciones incorrectas a Instinct.

Notas:

- Los tres falsos positivos de «antes» no eran frases aprendidas. «Ni se te ocurra prender la luz del pasillo», «Luz del pasillo salvo que esté durmiendo» y «Dale, no, luz del pasillo no» pasaban la política de seguridad y el clasificador simulado los ejecutaba. `RequestSafetyPolicy.rejection` ahora reconoce «ni», «jamás» y «tampoco» al comienzo, también después de «che», «dale», «bueno» o «a ver»; las condiciones «salvo que», «excepto que», «a no ser que» y «sin que»; y un «no» final.
- «El fideo en media hora» sigue yendo a Decisions: `CommandDuration` no interpreta «media hora» como duración de temporizador. La plantilla tampoco la acepta, porque valida igual que el comando nativo.
- «Prendeme modo fiesta» coincide con dos frases manuales de acción distinta. El índice no elige y el pedido sigue el flujo normal.
- Los ambiguos y negativos que llegan a Decisions terminan en aclaración, rechazo o una acción propuesta por el proveedor, nunca en una acción local.

Reproducir:

```sh
./gradlew :app:testDebugUnitTest --tests com.example.phone.IntentCoverageTest
# Las líneas LEARNED_METRICS y METRICS quedan en app/build/test-results/testDebugUnitTest/TEST-com.example.phone.IntentCoverageTest.xml
```

`IntentCoverageTest` usa el almacén de frases (DataStore), así que ahora corre con Gradle. `scripts/test-phone-core.py` saltea las pruebas que importan `com.example.data`.
