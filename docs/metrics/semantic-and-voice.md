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

## Negaciones y rectificaciones

`ConversationalNegationTest.negationMetrics`: 20 frases que no deben ejecutar nada (prohibiciones, condiciones,
«No molestes», «No prendé…» sin puntuación) y 12 que sí («No, prendé la linterna», «Activá No molestar», un
mensaje citado con «no»). «Antes» es `RequestSafetyPolicy.rejection` sola, que no cambió.

| Métrica (32 frases) | Antes | Después |
| --- | ---: | ---: |
| Falsos positivos (se ejecuta una prohibición) | 0 | 0 |
| Falsos negativos (se rechaza una rectificación legítima) | 8 | 0 |

## Captura de voz (simulada)

`VoiceCaptureTraceTest` sobre decisiones reales de WebRTC VAD para 186 sesiones armadas con habla real en
español. Detalle del método y de los límites en [../VOICE-CAPTURE.md](../VOICE-CAPTURE.md).

| Métrica | 1.12.0 | Ahora |
| --- | ---: | ---: |
| Envíos prematuros, 140 sesiones con voz | 71 | 30 |
| … órdenes breves, largas y con pausa de 600 ms | 31 | 0 |
| Inicio recortado | 10 | 0 |
| Grabaciones por ruidos breves (30) | 30 | 18 |
| Envíos en silencio (16) | 5 | 0 |
| Fin de orden breve tras la voz, p50 / p95 | 720 / 800 ms | 940 / 1020 ms |

Reproducir:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.example.audio.*' --tests com.example.phone.ConversationalNegationTest
# Líneas VOICE_METRICS, ONSET_METRICS y NEGATION_METRICS en app/build/test-results/testDebugUnitTest/
# Regenerar las trazas (requiere audio de LibriVox y py-webrtcvad):
#   python scripts/voice/build-vad-traces.py a.pcm b.pcm app/src/test/resources/vad-traces-es.json
```

## Pruebas

| Ejecución | Antes (`74eb9a5`) | Después |
| --- | ---: | ---: |
| `./gradlew :app:testDebugUnitTest` | 633 tests, 0 fallos, 1 omitido | 675 tests, 0 fallos, 1 omitido |
| `python scripts/test-phone-core.py` | 401 tests, OK | 430 tests, OK |
