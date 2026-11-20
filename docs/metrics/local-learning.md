# Estructuras aprendidas y contexto: medición offline

Todas las cifras son **simulaciones en la JVM** (Java 21, Robolectric no interviene). No hubo llamadas reales a Gemini ni a Groq ni pruebas en un dispositivo Android. Los proveedores son clasificadores y extractores falsos que cuentan invocaciones. Las latencias miden solo el enrutamiento local y no incluyen la red. Por eso no muestran cuánto tiempo de red se ahorra.

## Corpus existente (`IntentCoverageTest.offlineMetrics`)

Los 155 pedidos del corpus (`gemini-es-corpus.json` y los casos adicionales) no cambian de ruta. El test construye el coordinador sin estructuras ni contexto, que es el estado de un teléfono recién instalado.

| Métrica | Base `11e3115` | Con los cambios |
| --- | ---: | ---: |
| Rutas locales | 120/155 (77,42 %) | 120/155 (77,42 %) |
| Llamadas a Decisions | 11 (0,0710 por pedido) | 11 (0,0710 por pedido) |
| Falsos positivos / derivaciones incorrectas | 0 / 0 | 0 / 0 |

## Escenarios nuevos (`LocalLearningMetricsTest`)

Son 48 pedidos escritos para esta medición, agrupados por tipo:

- **Valores nuevos** para 5 estructuras entrenadas con 3 ejemplos cada una. Hay alarmas con dos formas, temporizadores y volumen con dos formas.
- **Secuencias con una app comprobada.**
- **Contexto que no debe usarse**: vencido, con la apertura fallida, con un `HANDOFF` sin paquete, después de abrir otra app o tras tres turnos.
- **Casos negativos**: negaciones, preguntas, condiciones, hipótesis, citas, órdenes múltiples, parámetros adicionales y valores fuera de rango.

| Métrica (48 pedidos) | Base `11e3115` | Antes (mismo código, sin etapas nuevas) | Después |
| --- | ---: | ---: | ---: |
| Resueltos en el teléfono | 6 (12,5 %) | 7 (14,6 %) | 22 (45,8 %) |
| Llamadas simuladas a Gemini y Groq cada 100 pedidos | 145,8 | 141,7 | 81,3 |
| Gemini (Decisions + extracción) cada 100 | — | 72,9 | 41,7 |
| Groq cada 100 | — | 68,8 | 39,6 |
| Acciones locales indebidas en casos negativos | 0 | 0 | 0 |
| Comandos distintos del esperado | — | 6 | 0 |
| Referencias contextuales resueltas | — | 0/5 | 5/5 |
| Usos de contexto donde no correspondía | — | 0 | 0 |
| Enrutamiento local p50 / p95 | 146 / 377 µs | 141 / 416 µs | 254 / 537 µs |

Notas:

- La diferencia entre la base y «antes» es una frase local agregada: «Bajá un poco el volumen».
- «Comandos distintos del esperado» del modo «antes» corresponden a lo que un proveedor puede proponer sin contexto. Por ejemplo, música sin la app que el usuario acaba de abrir, o «Volvé a abrirla» sin paquete. Además, una hora ambigua no se aclaró. El proveedor simulado devuelve el comando correcto sin referencia contextual. En la base solo se contaron rutas y llamadas.
- La latencia «después» cubre más pedidos resueltos en el teléfono. Una estructura o una referencia contextual cuestan entre 0,25 y 0,53 ms (p50 por pedido), porque el parser se ejecuta dos veces y se aplican las reglas de seguridad. Los mismos pedidos iban antes a Gemini o Groq. Esa latencia de red no se midió aquí.
- La primera versión decodificaba las estructuras en cada consulta y costaba alrededor de 2 ms por pedido. La caché por versión almacenada lo corrigió antes de entregar.

### Reproducir

```sh
./gradlew :app:testDebugUnitTest --tests com.example.data.LocalLearningMetricsTest --tests com.example.phone.IntentCoverageTest
# Las líneas LEARNING_METRICS, LATENCY y METRICS quedan en app/build/test-results/testDebugUnitTest/*.xml
```

La columna «Base» se obtuvo en un worktree de `11e3115`. Se usaron los mismos 48 textos, el coordinador original y proveedores falsos que cuentan llamadas.

## Pendiente de verificación real

- Probar en un dispositivo Android: «Abrí Spotify» → «Ahora poné Soda Stereo». La reproducción depende de cómo Spotify o YouTube atiendan `MEDIA_PLAY_FROM_SEARCH`.
- Comprobar la selección entre varias sesiones multimedia reales.
- Medir con Gemini y Groq reales cuántas llamadas se evitan con el uso habitual. Depende de cuántas formas repite cada persona.
