# Precisión semántica y captura de voz

Todas las cifras son simuladas y reproducibles en la JVM. No hay mediciones en un teléfono real en este
documento.

## Verificación semántica final

Corpus fijo: `SemanticGuardCorpusTest`. 38 pedidos de seguridad y precisión, cada uno interpretado por la
gramática local y por propuestas defectuosas simuladas de Groq (respaldo con Decisions caído), de Decisions con
extracción y de una frase aprendida: 196 interpretaciones en total. Más 25 órdenes legítimas y completas que
deben seguir ejecutándose. «Antes» es el mismo test sobre `74eb9a5` en un worktree.

| Métrica | Antes (`74eb9a5`) | Después |
| --- | ---: | ---: |
| Acciones incorrectas aceptadas (ejecución directa o plan para confirmar) | 36 / 196 | 0 / 196 |
| Parámetros explícitos perdidos o cambiados | 36 | 0 |
| Órdenes válidas rechazadas | 1 / 25 | 0 / 25 |
| Alias aprendidos con otro destino u objeto (ver nota) | 2 | 2 |
| `interpret` local, mediana / p95 (ns) | 289 443 / 1 170 508 | 221 760 / 410 242 |
| Costo del guard solo, mediana / p95 (ns) | — | 27 627 / 64 273 |

Las latencias de `interpret` vienen de JVM distintas y tienen mucho ruido; no indican una mejora. La cifra
relevante es el costo del guard solo: decenas de microsegundos.

El corpus de 155 pedidos de `IntentCoverageTest` no cambia: 120/155 (77,42 %) local, 11 llamadas a Decisions,
0 falsos positivos, 0 derivaciones incorrectas a Instinct. Tampoco el de 38 frases aprendidas (21/38 local,
0 acciones locales indebidas).

Nota sobre los alias: «Andá a Rosario caminando» → `Navigate("cordoba")` y «…Charly García…» →
`MusicSearch("luis alberto spinetta")` como frase aprendida. Una frase aprendida es un alias y su acción no
tiene que figurar en el texto, por eso el guard no le exige las palabras del destino. El índice real solo tolera
una letra de diferencia en palabras de cinco letras o más, así que no puede devolver esas sustituciones para
esos textos. Se cuentan aparte y no se ocultan.

Reproducir:

```sh
./gradlew :app:testDebugUnitTest --tests com.example.phone.SemanticGuardCorpusTest
# Líneas SEMANTIC_METRICS y GUARD_COST en
# app/build/test-results/testDebugUnitTest/TEST-com.example.phone.SemanticGuardCorpusTest.xml
```
