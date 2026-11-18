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
python scripts/test-phone-core.py com.example.phone.IntentCoverageTest
```
