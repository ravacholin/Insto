# Reconocimiento de intents en español

Línea base medida sobre `1689b30` con el test de medición y antes de editar código de producción.

| Métrica offline | Línea base |
| --- | ---: |
| Pedidos resueltos en local sin Gemini | 96/155 (61,94 %) |
| Llamadas a Decisions por pedido | 35/155 (0,2258) |
| Solicitud a Decisions en bytes UTF-8, promedio (mín.–máx.) | 34 713,61 (34 691–34 749) |
| Resolución local, mediana / p95 | 120 761 / 316 255 ns |
| Falsos positivos en restricciones (incluidas las consultas del grupo) | 0 |
| Ejecuciones directas indebidas en ambiguos | 1 |
| Derivaciones incorrectas a Instinct | 0 |
| Casos de derivación sin Query / con ejecución indebida | 0 / 0 |

`IntentCoverageTest.offlineMetrics` registra el resultado, los IDs devueltos por el clasificador falso, las extracciones simuladas y los bytes de `buildRequest(texto)` para cada pedido, incluso cuando Decisions no interviene. El corpus aporta 110 pedidos iniciales y 21 continuaciones de borrador. Los 24 casos del pedido se suman como entradas independientes, incluidas las variantes coincidentes con el corpus. Las continuaciones usan `DraftPlan.reply`, como en las pruebas existentes.

«Resuelto en local» incluye acciones, planes, aclaraciones y consultas sin clasificador ni extractor. Excluye rechazos y borradores que todavía necesitan datos. El tiempo mide solo `PhoneCommandParser.parse` (incluido el registro), con 10 pasadas de calentamiento y 30 pasadas medidas sobre los 134 pedidos iniciales. Se usa el percentil de rango más próximo. No se midió latencia de red ni precisión de Gemini.

El único falso positivo en ambiguos fue «Abrí música», que produjo `OpenApp("musica")`. «Dame un poco de luz», «Bajá un poquito el sonido» y «Poné algo de los Beatles» necesitaron Decisions en el replay. «Necesito una cuenta regresiva de diez minutos» ya fue local.

Para reproducir:

```sh
./gradlew :app:testDebugUnitTest --tests com.example.phone.IntentCoverageTest --info
# Alternativa JVM del repositorio
python scripts/test-phone-core.py com.example.phone.IntentCoverageTest
```
