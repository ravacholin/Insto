# Insto 1.12.0: frases parametrizadas, auto-envío con diagnóstico y copia de seguridad

Fecha: 2026-11-20. Estado: **PASO 0, pendiente de aprobación**. No se modificó código de producción ni versiones.

Base auditada: `ee724f3` (merge del PR #42), versión 1.11.1, `versionCode 20`, `minSdk 24`, `targetSdk 36`.

Tres mejoras independientes, en commits separados y en este orden:

- **A.** Frases aprendidas con parámetros y matching difuso.
- **B.** Autodiagnóstico y robustez del auto-envío por accesibilidad.
- **C.** Exportación e importación integral de la configuración, incluidas las API keys.

Documentos leídos: `docs/LOCAL-INTENTS.md`, `docs/FLOATING-ASSISTANT.md`, `docs/LOCAL-LEARNING.md`, `docs/releases/Insto-frases-aprendidas.md`, `docs/metrics/intents-baseline.md`, `docs/metrics/local-learning.md` y todos los diseños y planes de `docs/superpowers/`.

---

## Restricciones globales (aplican a A, B y C)

1. Sin permisos nuevos. `accessibility_service_config.xml` no cambia. Tampoco el bloque `<queries>`: ya declara `com.whatsapp` y `com.whatsapp.w4b`.
2. El servicio de accesibilidad sigue ignorando las ventanas de otras apps. La compuerta actual `AutoSendCoordinator.isArmedFor(pkg)` se mantiene como primera línea de `onAccessibilityEvent`.
3. Los logs no registran audios, transcripciones, mensajes, teléfonos, API keys ni contraseñas. Hoy hay dos `Log.d`, ambos de latencia y solo en debug. No se agregan otros con datos del usuario.
4. Se mantiene la cancelación por generación: una respuesta tardía nunca ejecuta. Nada de A, B o C toca `ConversationSession` ni las guardas de generación del `MainViewModel`.
5. El M4A original sigue yendo a Instinct. No cambian los flujos de Groq ni de Gemini.
6. Exportar e importar no hacen llamadas de red.
7. No se agregan dependencias. Se usan `javax.crypto`, `org.json`, DataStore y el Storage Access Framework, que ya están disponibles.
8. Los cambios son localizados. No se reescribe `PingAutoSendAccessibilityService` entero: se extraen los selectores a un módulo puro y el servicio los llama.
9. Sin datos nuevos, o con las funciones nuevas sin usar, el comportamiento es idéntico al actual.
10. Nada borra ni modifica información existente del usuario sin confirmación.
11. Cada parte tiene sus propias pruebas de regresión.
12. A no toca `assist/`. B no toca `data/LearnedPhraseStore.kt` ni `phone/PhrasePatterns.kt`. C solo **agrega** a los almacenes métodos de instantánea, fusión y restauración, sin cambiar sus caminos de lectura ni de ejecución.

---

# PARTE A. Frases aprendidas parametrizadas y matching difuso

## A1. Auditoría

### Cómo se guardan las frases

`LearnedPhraseStore` (DataStore `learned_phrases`, excluido del backup de Android):

| Clave | Contenido |
| --- | --- |
| `enabled` | Booleano «Aprender y usar frases». |
| `entries` | Arreglo JSON de `Entry {phrase, command, date, uses, source, target?, package?}`. `phrase` es la **clave normalizada** (`LearnedPhraseStore.key`). Hasta 200 entradas. `trim` descarta primero las automáticas más viejas. |
| `last_used` | Última frase usada o aprendida, para «olvidá esta frase» y «corregí esta frase». |
| `patterns` | `{"version":1,"patterns":[…]}`. Son las estructuras aprendidas de 1.11.1 (`PhrasePatterns`): prefijo + parámetro + sufijo. Requieren 3 valores distintos observados en ejecuciones de Gemini. Hasta 60. |

### Cómo se comparan

- `key(request)` descarta borradores, texto compartido, varios pasos, `?`/`¿`, pedidos generales y cualquier `RequestSafetyPolicy.rejection`. Después normaliza con `PhoneCommandParser.normalize`, que pasa a minúsculas, quita tildes y puntuación y colapsa espacios. Por último rechaza negaciones y condiciones (`no`, `nunca`, `ni`, `sin`, `si`, `cuando`, `salvo`…) y comienzos de pregunta (`podés`, `se puede`…).
- `lookup` es una **igualdad exacta** de `phrase` sobre la clave normalizada. No hay matching difuso.
- `lookupPattern` usa `PhrasePatterns.Index`: indexa por la primera palabra del prefijo, compara prefijo y sufijo literales, valida la forma del parámetro (`shapeAllowed`) y extrae el valor **parseando un pedido canónico** con `PhoneCommandParser`. Si dos estructuras dan resultados distintos, no devuelve nada.

### Cómo las consulta el coordinador

`GeminiCoordinator.interpret` (líneas 82-108):

1. Si `PhoneCommandParser` resolvió (`Local`), pidió aclaración (`Clarify`), canceló o rechazó de forma definitiva, **no se consultan las frases**. El catálogo local siempre tiene prioridad.
2. `lookupLearnedPhrase` → `Direct(command, learned = true)`.
3. Solo en un turno nuevo, de un paso y sin texto compartido: `lookupLearnedPattern` → `Direct(…, pattern = template)` o `Clarification`.
4. `OperationalContext`.
5. Decisions y extracción, con presupuestos de 4 s y 8 s (`withTimeoutOrNull(4_000)` y `extract(… 8_000 / 4_000)`) dentro de un `withTimeout(12_000)`.

`LocalIntentRegistry` y `LocalIntentPhrases` **no consultan** las frases aprendidas. Definen el catálogo y los ejemplos que usan Decisions y el parser. `PhraseTeaching.checkPhrase` impide enseñar una frase que el parser ya entiende o que no llegaría al lookup. Para eso simula el coordinador sin red.

### Ejecución y confirmaciones

Un `Direct(learned = true)` pasa por el mismo `PhoneActionExecutor`. `MainViewModel` (líneas 765-777) pide confirmación cuando el comando la requiere (`learnedCommandConfirmation`). Ninguna frase saltea confirmaciones.

### Aprendizaje

- **Automático:** `saveConfirmed` → `learn`. Requiere `confirmed`, `allows(command)`, sin `retainRequest`, confirmación pendiente, opciones de app ni ajustes de acceso. Además, `COMPLETED` o `HANDOFF` de cámara o app. **Nunca pisa una entrada `MANUAL`.**
- **Manual:** `teach` y `correctLast`, validados por `PhraseTeaching` y `allowsManual`.
- Acciones automáticas (`allows`): linterna, volumen, cámara, abrir app, multimedia, cronómetro, silencio y vibración.
- Acciones manuales (`allowsManual`): las anteriores más temporizador, alarma sin fecha, modo temporal sin plazo, navegación y búsquedas, ajustes y algunas `Platform` (DND, batería, rutina…).

### Hallazgos

1. «bajá la música al 30» aprendida no sirve para «bajá la música al 50»: la igualdad es exacta. Las estructuras de 1.11.1 sí generalizan, pero solo con tres ejemplos de Gemini y solo para hora, duración y porcentaje.
2. `normalize` no unifica voseo («prendé»), tuteo («prende»), infinitivo («prender») ni enclíticos («prendeme»). Tampoco quita muletillas: «che, prendé la luz porfa» no coincide con «prende la luz».
3. `recordUse` busca `phrase == key && command == command`. Una coincidencia generalizada necesita registrar el uso por plantilla, como ya hace `savePatternUse`.
4. La pantalla muestra la clave normalizada sin tildes («baja la musica al 30»). No se guarda una forma legible.

## A2. Diseño: parámetros tipados en frases aprendidas

**Decisión:** no se crea un tercer mecanismo. Una frase aprendida, manual o automática, cuyo comando contiene un valor parametrizable **deriva su plantilla con el mismo `PhrasePatterns.observe`** que ya usan las estructuras. `observe` busca el **único** tramo de la frase que el parser decodifica exactamente al valor guardado y exige texto literal suficiente (≥ 2 palabras, una de contenido, sin números, fechas ni etiquetas).

- La plantilla se calcula al guardar o al leer y se cachea con el índice existente. **No se persiste la plantilla**: se persisten la frase y el comando, como hoy. Así un archivo editado no puede introducir una plantilla más amplia: se vuelve a derivar y validar.
- Basta un ejemplo. Una frase aprendida ya fue confirmada por el usuario y Android informó éxito, o la enseñó el usuario explícitamente. Por eso no rige `MIN_EXAMPLES = 3`, pensado para observaciones de Gemini sin confirmación.
- Cada entrada tiene un interruptor nuevo, `generalize` (por defecto `true` si se pudo derivar una plantilla), para desactivar la generalización sin borrar la frase.

### Slots admitidos

| Slot | Placeholder visible | Acción | Validación |
| --- | --- | --- | --- |
| `PERCENT` | `{número}` | `Volume(SET, n)` | Existente: forma `^n( %| por ciento)?$`, `0..100`, extraído por el parser de «poner el volumen al …». |
| `DURATION` | `{duración}` | `Timer(seconds)` sin etiqueta (solo frases manuales) | Existente: `CommandDuration.seconds`. Mismo rango que el comando nativo. |
| `TIME` | `{hora}` | `Alarm` sin días, fecha ni etiqueta (solo manuales) | Existente: `ClockTime.shape/parse`. Una hora ambigua pide «¿De la mañana o de la tarde?». |
| `APP` | `{app}` | `OpenApp` | **Nuevo.** De 1 a 3 palabras, sin números ni palabras prohibidas. Debe coincidir con la etiqueta normalizada de **exactamente una** app lanzable (`launcherApps`, la misma fuente que `VocabularyHintProvider`). El paquete resuelto pasa por el `isLaunchable` existente. Si hay 0 o 2 o más apps, no hay coincidencia. |

**No se generalizan** (justificación para la spec):

- Búsquedas de música o video y navegación: el parámetro es texto libre y podría absorber otra orden o una restricción («poné X y mandale…»).
- Rutinas: ejecutan varios pasos. Generalizar el nombre podría disparar otra rutina.
- Linterna, multimedia, cámara, silencio y cronómetro: no tienen parámetros.
- Llamadas, mensajes, archivos y calendario: nunca, como hasta ahora.

**No se amplían las acciones aprendibles automáticamente.** Siguen siendo linterna, volumen, modos, cámara, apps, multimedia y cronómetro. Temporizador y alarma solo generalizan desde frases **manuales**, como ya permitía `allowsManual`.

### Validación idéntica al comando nativo

El valor nunca se toma del texto: se arma el pedido canónico (`Action.canonical(slot)`) y lo parsea `PhoneCommandParser`. El comando resultante tiene que dar el mismo `PhrasePatterns.actionFor`. Por eso los rangos, el redondeo y las aclaraciones son exactamente los del comando nativo. Para `APP` el comando es `OpenApp(label, package)`, que el ejecutor valida como siempre.

### Orden de consulta (sin cambios hacia afuera)

1. Frase exacta (clave normalizada), manual o automática. Igual que hoy.
2. **Nuevo:** frase exacta sobre la **forma canónica** (A3).
3. **Nuevo:** plantillas derivadas de frases **manuales**, sobre la forma canónica.
4. **Nuevo:** plantillas derivadas de frases **automáticas**.
5. Estructuras de 1.11.1 (`patterns`), también comparadas en forma canónica.
6. Contexto operativo, Decisions e Instinct, sin cambios.

Dentro de un mismo nivel, dos coincidencias con comandos distintos se anulan y se pasa al nivel siguiente, nunca a una acción. Si un nivel superior resuelve, los inferiores no se consultan. Una frase exacta siempre gana sobre una plantilla. Así, «modo cine» → volumen 20 no se pisa con «modo {número}».

## A3. Matching difuso local y determinista

Una función pura nueva, `PhraseCanonicalizer.canonical(key): String`, se aplica **después** de `key()`. Las negaciones, condiciones y preguntas ya están rechazadas en ese punto. Se aplica tanto a las frases guardadas, al construir el índice, como al pedido.

1. **Muletillas**, solo como palabras completas. Al inicio: `che`, `dale`, `bueno`, `a ver`, `eh`. En cualquier posición: `porfa`, `porfis`, `por favor`, `plis`, `please`. Si después de quitarlas no quedan al menos dos palabras, no hay canonicalización.
2. **Verbos.** Tabla cerrada de verbos usados por las acciones aprendibles (`prender`, `encender`, `apagar`, `subir`, `bajar`, `poner`, `abrir`, `cerrar`, `pausar`, `parar`, `frenar`, `seguir`, `pasar`, `sacar`, `activar`, `desactivar`, `silenciar`, `iniciar`, `arrancar`, `reiniciar`, `reproducir`, `mostrar`, `fijar`, `dejar`). Cada uno lleva sus formas de voseo (`prendé`, que tras quitar la tilde queda `prende`), tuteo (`prende`, `enciende`, `pon`), infinitivo (`prender`) y enclíticos frecuentes (`prendeme`, `prendela`, `ponemela`). Todas se reducen al infinitivo. **No mapea sinónimos**: `prender` ≠ `encender`. La equivalencia semántica es tarea del catálogo local y de Decisions.
3. **Variantes de transcripción.** Se admite como máximo **un** token distinto con distancia Damerau-Levenshtein 1 entre pedido y frase guardada, bajo estas reglas:
   - ambos tokens tienen ≥ 5 letras;
   - ninguno es número, slot, palabra de negación o condición ni palabra protegida (`no`, `ni`, `sin`, `si`, `nunca`, `jamas`, `tampoco`, `apaga*`/`prende*` frente a su opuesto, `sube*`/`baja*`);
   - el resto de los tokens es idéntico en forma canónica;
   - hay **un único** candidato a distancia ≤ 1. Un empate o dos candidatos con comandos distintos significan «no ejecutar».
4. **Umbral conservador:** coincidencia exacta canónica, o una sola sustitución bajo las reglas anteriores. No hay puntajes probabilísticos ni coincidencias parciales por prefijo.
5. **Negaciones y restricciones.** Además del filtro de `key()`, el canonicalizador **nunca** elimina tokens de negación ni de condición. La distancia de edición no se aplica a ellos ni a los verbos opuestos. «No prendas la linterna» se rechaza antes (`RequestSafetyPolicy`) y se agrega como caso de prueba explícito, junto con «prendé la linterna si…», «no la prendas» y «ni se te ocurra prender la luz».
6. **Colisiones en el índice.** Si dos frases guardadas distintas tienen la misma forma canónica con comandos distintos, esa forma canónica queda fuera del índice. Sus formas exactas siguen funcionando.

Ante empate, ambigüedad o duda, el lookup devuelve `null` y el coordinador sigue con aclaración, Decisions o Instinct, sin cambios.

## A4. Protección del aprendizaje

- `learn` sigue sin pisar entradas `MANUAL`. Además, **una frase automática nueva cuya forma canónica coincide con una manual no se guarda** (antes solo se comparaba la clave exacta).
- Una plantilla automática nunca anula una manual: la prioridad está en A2.
- El aprendizaje automático sigue exigiendo confirmación y éxito informado por Android. No cambia `learn`, salvo la comprobación canónica anterior.
- `recordUse` registra el uso de una coincidencia generalizada por la frase de origen (`saveUse(sourcePhrase)`). Las coincidencias generalizadas **no aprenden** frases nuevas: devuelven `learned = true`, igual que hoy, y el `MainViewModel` no las vuelve a guardar.

## A5. Interfaz

En Configuración → Frases aprendidas:

- Una frase con plantilla se muestra como `Bajá la música al {número}` y debajo dice «Ejemplo: al 30 · también funciona con otros valores».
- Para que sea legible se guarda un campo opcional nuevo, `display`, con el texto que escribió el usuario, sin normalizar y limitado a 200 caracteres. En frases automáticas es el texto del pedido confirmado. Al mostrarlo se reemplaza el tramo del slot por el placeholder. Las entradas anteriores no tienen `display` y muestran la clave normalizada con el placeholder.
- Editar: el diálogo actual de edición agrega el interruptor «Usar también con otros valores» (`generalize`). El texto se edita escribiendo un ejemplo con valor concreto («bajá la música al 30»), porque así se vuelve a validar con `PhraseTeaching`. No se aceptan llaves escritas a mano.
- Eliminar: el botón actual, sin cambios.

## A6. Medición y regresiones

`IntentCoverageTest.offlineMetrics` conserva sin cambios la sección del corpus de 155 pedidos con instalación limpia. Debe seguir en 120/155 y 11 llamadas a Decisions: una instalación limpia no tiene frases. Se agrega una sección **«con frases aprendidas»** con un `LearnedPhraseStore` sobre `PhraseTestDataStore`, sembrado con unas 12 frases manuales y automáticas, y unos 50 pedidos nuevos en cinco grupos:

| Grupo | Ejemplos | Esperado |
| --- | --- | --- |
| `learned_rioplatense` | «che, prendeme la luz porfa», «dale, poné modo cine» | Local, sin Decisions. |
| `learned_param` | «bajá la música al 50», «poné el temporizador del fideo en 12 minutos», «abrime la app de Netflix» | Local, con el comando del valor nuevo. |
| `learned_variant` | «bajar la música al 40», «baja la musica al 40», «bajá la músca al 40» | Local. |
| `learned_ambiguous` | Dos frases con la misma forma canónica y distinta acción, dos apps con la misma etiqueta, valores fuera de rango («al 150») | **Sin ejecución local.** Siguen el flujo normal. |
| `learned_negation` | «no bajes la música al 30», «bajá la música al 30 si no hay nadie», «ni se te ocurra prender la luz» | **Sin ejecución.** |

`docs/metrics/intents-baseline.md` registra antes y después: resoluciones locales sin Gemini, llamadas a Decisions por pedido, falsos positivos en restricciones, acciones locales indebidas y derivaciones incorrectas a Instinct. «Antes» se mide con el mismo arnés sobre `ee724f3`, en un worktree y con las mismas semillas. **Si las acciones locales indebidas o las derivaciones incorrectas a Instinct superan cero, A no se acepta.**

Pruebas nuevas: `PhraseCanonicalizerTest` (JVM puro), `LearnedPhraseTemplateTest` (store + plantillas, protección manual y colisiones) y casos en `LearnedPhraseStoreTest`, `PhraseTeachingTest` y `LearnedPhrasesScreenTest`.

## A7. Criterios de aceptación

- «bajá la música al 30» aprendida resuelve «bajá la música al 50» en el teléfono, con `Volume(SET, 50)` y sin Decisions.
- Los valores fuera de rango, las formas inválidas y las apps ambiguas no ejecutan nada.
- Las negaciones y condiciones nunca ejecutan nada. Las métricas de acciones indebidas y de derivaciones incorrectas a Instinct siguen en 0.
- Las frases manuales nunca se reemplazan automáticamente.
- Con «Aprender y usar frases» apagado, o sin frases, las rutas son idénticas a las de 1.11.1.
- Las entradas viejas, sin `display` ni `generalize`, se leen sin migración destructiva.

---

# PARTE B. Autodiagnóstico y robustez del auto-envío

## B1. Auditoría de selectores

`PingAutoSendAccessibilityService.kt` tiene 1021 líneas. Las máquinas de estado están en `TextSendAttempt`, `ShareSendAttempt` y `AutoSendCoordinator`. Las políticas de autorización están en `AutoSendGuard` y `ShareConfirmationPolicy`.

### Inventario por elemento

| Elemento | Función | Estrategias actuales, en orden | Fragilidad |
| --- | --- | --- | --- |
| Cuadro de texto | `findTextComposer` | ID `entry`, `message_input`. Prefiere el nodo cuyo texto coincide con el esperado, si no el único visible. Si no hay, recorre buscando `isEditable` (≤ 800 nodos, profundidad ≤ 28). | IDs cambiantes. El recorrido toma el único editable: en la vista de audio el campo de pie de foto también es editable. |
| Botón Enviar (texto y audio) | `findTextSendTarget` → `findSendButtonNode` | IDs `send`, `send_button`, `send_btn`, `media_caption_send_btn`. Luego `findAccessibilityNodeInfosByText` con «Send/Enviar/Invia/Envoyer/Senden» e igualdad exacta. Por último `findClickableByKeywords`: `contentDescription.contains(keyword)` o un ID que termine en `:id/send`. | **Frágil:** el último recurso usa `contains`, así que «Enviar a…» o «Enviar ubicación» podrían coincidir. Además toma el **primer** nodo y no detecta ambigüedad. Depende del idioma (5 idiomas). La versión con IDs recicla nodos de forma inconsistente. |
| Diálogo para compartir (OK, Aceptar, Enviar) | `findSharePositiveButton` | Recorrido por etiqueta exacta (`ShareConfirmationPolicy.isPositiveLabel/isConfirmationLabel`), clickable o `*Button`, y prefiere confirmaciones. Después 12 IDs: `android:id/button1`, `confirm`, `ok`, `positive_button`, `dialog_positive_button`, `send`… | Las etiquetas dependen del idioma. Los IDs de diálogo cambian entre builds (el test `nativeQuestionWithCustomButtonId` lo cubre con `new_dialog_positive`). |
| Selección de conversación | — | **No se automatiza.** `WhatsAppDispatcher` abre el chat con `jid` o deep link. El servicio **verifica** la cabecera (`matchesRecipient`: IDs `conversation_contact_name`, `recipient_name`, `toolbar_title`, `action_bar_title`). Una lista «Enviar a» nunca se autoriza (1.4.2). | Los IDs de cabecera cambian. Sin coincidencia se falla de forma cerrada, sin enviar, que es el comportamiento correcto. |
| Confirmación final (chat vacío) | `confirmTextSubmitted`, `confirmShareSubmitted`, `shareSubmittedInChat` | Destinatario + cuadro vacío + sin botón Enviar (+ micrófono de nota de voz para audio, estable 500 ms). | Depende de `findNativeVoiceMicNode`: IDs `voice_note_btn`, `record_button` y etiquetas en español e inglés con `contains`. |
| Archivo de audio | `handleAutoClickSend` | IDs `file_name`, `filename`, `document_name`, `audio_title`, más un recorrido buscando el nombre exacto `instinct_*.m4a`. | Robusto por nombre único. Alternativa: `AudioPreviewActivity` fresca, ya cubierta por `ShareLaunchTimingRegressionTest`. |

### Esperas fijas

| Lugar | Espera | Propósito |
| --- | --- | --- |
| `scheduleTextSendPoll`, `scheduleShareSendPoll` | 450 ms iniciales, luego sondeo cada 180 ms mientras dura el armado (10 s, `DEFAULT_ARM_WINDOW_MS`) | Esperar a que WhatsApp muestre el chat o la vista previa. |
| `confirmTextSubmitted`, `confirmShareSubmitted` | 250 ms iniciales, sondeo cada 180 ms, plazo de 3 s | Verificar el envío. |
| `ShareSendAttempt` | Estabilidad de 500 ms | Chat final estable. |
| PTT / pastilla / volumen | 320 y 420 ms | Mantener presionado. Queda fuera del alcance. |
| `scheduleBoomerangReturn` | Retardo fijo | Volver a la app anterior. Queda fuera del alcance. |

`onAccessibilityEvent` hoy **ignora** los eventos de contenido mientras hay texto o audio esperado: «Explicit successful launches drive polling».

## B2. Diseño: estrategias ordenadas con fallback

Archivo nuevo `assist/WhatsAppSelectors.kt`, **puro y sin Android**. Opera sobre una interfaz mínima `UiNode` (`id`, `text`, `description`, `className`, `clickable`, `editable`, `visible`, `enabled`, `bounds`, `children`). Un adaptador `AccessibilityUiNode` envuelve `AccessibilityNodeInfo`, maneja el reciclado y aplica los mismos límites de 800 nodos y profundidad 28.

Para cada elemento hay una lista ordenada de `Strategy(id, find)`. Su resultado es `Found(node, strategyId)`, `Ambiguous(strategyId)` o `NotFound`. Reglas:

1. Las estrategias van de la más específica a la más general: ID exacto → etiqueta exacta (lista por idioma) → clase y posición acotada (solo para Enviar: un clickable visible en el tercio inferior derecho de la ventana **y** a la par del cuadro de texto).
2. Si una estrategia encuentra **más de un** candidato visible, habilitado y con límites distintos, devuelve `Ambiguous` y **se detiene la búsqueda**. No se baja a una estrategia más débil.
3. Se elimina el `contains` del último recurso para Enviar: solo valen etiquetas **exactas**. `findClickableByKeywords` queda solo para el micrófono de nota de voz, con la lista exacta.
4. El servicio registra el `strategyId` que encontró el elemento y lo guarda en las métricas (B5).
5. La autorización no cambia: destinatario, contenido, ventana fresca y un solo gesto siguen en `AutoSendGuard`, `ShareConfirmationPolicy`, `TextSendAttempt` y `ShareSendAttempt`. Los selectores solo **encuentran**, nunca **autorizan**.

**Esperas guiadas por eventos.** Con el armado activo, `onAccessibilityEvent` (solo del paquete armado) agenda un chequeo inmediato, con un debounce de 60 ms, en vez de ignorar el evento. El sondeo periódico **queda como respaldo**, con el mismo plazo de 10 s y de 3 s. Se reemplaza el retardo fijo de 450 ms por «el primer evento del paquete armado después del lanzamiento confirmado, o 450 ms como máximo». Las guardas de un único gesto vuelven idempotentes los chequeos duplicados. No se tocan las esperas de PTT, la pastilla ni el boomerang.

## B3. Fixtures y regresiones

`test/.../assist/WhatsAppSelectorFixtures.kt` crea árboles sintéticos de `UiNode`, sin datos reales, con nombres «Contacto Prueba» y números `+44 7700 900xxx` del rango de ficción de Ofcom:

| Fixture | Variante |
| --- | --- |
| `WA_A` | WhatsApp, IDs clásicos (`entry`, `send`, `conversation_contact_name`), en español. |
| `WA_B` | WhatsApp, IDs renombrados (`message_input`, `send_btn`, `toolbar_title`) y etiquetas en inglés. |
| `WA_C` | WhatsApp sin IDs útiles: solo `contentDescription` «Enviar» y la estrategia de posición. Incluye un botón «Enviar ubicación» como distractor. |
| `WAB_A` | WhatsApp Business (`com.whatsapp.w4b`), IDs clásicos. |
| `WAB_B` | Business, diálogo «¿Compartir con …?» con ID propio (`new_dialog_positive`) y vista previa de audio. |
| `WAB_C` | Business con dos botones «Enviar» visibles: **debe dar `Ambiguous` y no tocar nada.** |

`WhatsAppSelectorsTest` (JVM puro, se ejecuta con `scripts/test-phone-core.py`) verifica, para cada fixture y elemento, el nodo y la estrategia esperados. Una prueba Robolectric nueva, `WhatsAppSelectorStrategyRegressionTest`, sigue el estilo de `WhatsAppShareDialogRegressionTest`: árboles sellados, `ConnectedAccessibility`, `gesturesDispatched`. Comprueba que el servicio integrado toca una sola vez en WA_C y ninguna en WAB_C, y que un evento de contenido adelanta el chequeo. Las pruebas actuales de auto-envío deben pasar sin cambios.

## B4. Indicador de salud

`AutoSendHealth.evaluate(enabled, connected, installed, lastConfirmed, lastAttempt)` es una función pura:

| Estado | Condición | Texto |
| --- | --- | --- |
| **Accesibilidad desactivada** | `PingAutoSendAccessibilityService.isEnabled() == false` | «Activá Accesibilidad de Insto para enviar sin tocar.» Abre los ajustes con el botón que ya existe. |
| **Necesita revisión** | Habilitada pero no conectada; o sin WhatsApp; o **la versión instalada (`longVersionCode`) es distinta de la última con un envío confirmado**; o el último intento fue un fallo o quedó incierto; o todavía no hay ningún envío confirmado. | Dice el motivo concreto, por ejemplo: «WhatsApp se actualizó (2.26.x) y todavía no hay un envío confirmado con esta versión». |
| **Auto-envío listo** | Habilitada y conectada, con un envío confirmado con la versión instalada y el último intento sin fallo. | «Último envío confirmado con WhatsApp 2.26.x.» |

«Listo» describe evidencia, no garantiza el envío. El texto lo dice así.

El panel (`AssistOverlayScreen`) muestra el estado al abrirse, solo si hay contacto configurado y modo de envío automático. Con «Necesita revisión» o «Desactivada» ofrece **«Abrir el chat y enviar a mano»**: abre la conversación con el texto o el audio preparado, por el camino manual que ya existe en `WhatsAppDispatcher`, **sin armar** el auto-envío. Esa apertura es una acción explícita del usuario, nunca automática. Así se respeta la política vigente de no abrir un borrador manual en silencio.

## B5. Métricas locales

Archivo nuevo `noBackupFilesDir/autosend-metrics.json` (`AtomicFile`, igual que `WorkflowStore`), con `AutoSendMetricsStore`:

```json
{"version":1,
 "rows":[{"flavor":"whatsapp","versionCode":262000,"kind":"text|audio","strategy":"send.id.send",
          "outcome":"confirmed|not_found|ambiguous|uncertain|recipient_mismatch|timeout","count":3,"lastAt":1763600000000}],
 "lastConfirmed":{"whatsapp":262000,"business":null},
 "lastAttempt":{"flavor":"whatsapp","versionCode":262000,"outcome":"confirmed","at":1763600000000}}
```

- **Nunca** guarda texto, audio, nombre de archivo, transcripción, teléfono ni contacto. `kind`, `strategy` y `outcome` son enumeraciones cerradas.
- Como máximo 100 filas, agrupadas por clave. Las más viejas se descartan.
- Configuración → WhatsApp y notas de voz → «Diagnóstico del auto-envío» muestra los contadores y un botón **«Borrar métricas»**. Borrar también reinicia `lastConfirmed`, así que el estado vuelve a «Necesita revisión» hasta el próximo envío confirmado. La UI lo explica.
- Solo se registra un resultado por solicitud, en el mismo punto donde hoy se reproduce el sonido de enviado (`playSentCue`) o se muestra `showUnconfirmedTextHint`, o donde se fija un `textWaitReason` terminal.

## B6. Recuperación segura

Se mantienen las políticas de `FLOATING-ASSISTANT.md`: un solo gesto, nunca repetir un toque incierto y conservar el pedido si la accesibilidad no está conectada.

- **Fallo antes del gesto** (`not_found`, `ambiguous`, `recipient_mismatch`, `timeout` sin tocar): el pedido y el borrador se conservan, como hoy. El texto queda en el compositor de WhatsApp y el M4A en la caché privada durante 24 h. El panel ofrece «Abrir el chat y enviar a mano».
- **Gesto enviado sin confirmación** (`uncertain`): **no se reintenta**. El panel indica revisar WhatsApp antes de cualquier otra acción, y la opción manual dice «Abrir el chat para revisar». No vuelve a preparar el mismo contenido para evitar un doble envío.
- Las métricas y el indicador son de solo lectura respecto del flujo: no cambian la decisión de enviar.

## B7. Criterios de aceptación

- Cada selector encuentra el elemento correcto en las 6 fixtures. Ante una ambigüedad no toca nada.
- No hay más de un gesto por paso. Las pruebas de `WhatsAppShareDialogRegressionTest`, `ShareLaunchTimingRegressionTest`, `WhatsAppAudioAutoSendTest`, `TextDispatchIntegrationTest`, `AutoSendGuardTest` y `ShareConfirmationPolicyTest` pasan sin cambios.
- El indicador nunca dice «listo» sin un envío confirmado con la versión instalada.
- Las métricas no contienen datos personales. Una prueba recorre el JSON y busca el teléfono, el nombre, el texto y el archivo de las fixtures.
- Con las métricas vacías y sin abrir el panel, el envío se comporta como en 1.11.1, salvo la búsqueda de Enviar más estricta, que está documentada.
- **Pendiente físico:** probar con WhatsApp y WhatsApp Business reales en el Motorola. Este entorno no tiene dispositivo.

---

# PARTE C. Exportar e importar toda la configuración

## C1. Auditoría exhaustiva de datos persistentes

Relevamiento de todo `getSharedPreferences`, `preferencesDataStore`, `noBackupFilesDir`, `filesDir`, `cacheDir`, `AtomicFile`, `writeText/writeBytes` y `KeyStore` en `app/src/main`. Son siete almacenes:

| # | Almacén | Ubicación | Tipo |
| --- | --- | --- | --- |
| S1 | `AssistantPreferencesRepository` | DataStore `ping_assist_prefs` | Preferencias. Entra en el backup de Android. |
| S2 | `LearnedPhraseStore` | DataStore `learned_phrases` | Frases y estructuras. Excluido del backup de Android. |
| S3 | `GroqKeyStore("groq")` | `noBackupFilesDir/groq-key.enc` + alias Keystore `insto.groq.personal.v1` | AES-GCM con clave no exportable. |
| S4 | `GeminiSettings` | `noBackupFilesDir/gemini-key.enc` (alias `insto.gemini.personal.v1`), `gemini-enabled`, `gemini-local-choice` | Clave cifrada + dos archivos de texto. |
| S5 | `ConfiguredRoutineStore` | `noBackupFilesDir/configured-routines.json` | JSON validado, escritura atómica por renombrado. |
| S6 | `SavedPlacesRepository` | SharedPreferences `saved_places` (`casa`, `trabajo`) | Direcciones. |
| S7 | Estado operativo | `phone-workflow.json`, `temporary-phone-mode.json`, `cacheDir/voice_notes`, `filesDir/captures` | No es configuración. |

`GroqSettingsActivity` no tiene un almacén propio: usa S3 y S4. **No hay** preferencias configurables de modelo, proveedor ni fallback más allá de la activación de Gemini Decisions y de los dos interruptores de vocabulario de Groq. `ContextRoutingEngine` es un cálculo sin estado propio. Los «alias personales» que existen son los lugares `casa` y `trabajo` y los nombres de rutinas. No hay otro vocabulario persistente: `VocabularyHintProvider` arma su texto en memoria a partir de S2, S5 y S6. `OperationalContext` vive solo en memoria.

**Hallazgo:** `night_start_hour` y `night_end_hour` se leen, con valores por defecto 21 y 7, pero el código actual no tiene un setter. Se exportan si están presentes y se restauran con el mismo rango (0..23).

## C2. Matriz de datos

Riesgo: impacto si se restaura mal o si se filtra.

| Dato (clave real) | Almac. | Categoría | Exportación | Restauración | Validación | Riesgo |
| --- | --- | --- | --- | --- | --- | --- |
| API key de Groq | S3 | `credentials` | `GroqKeyStore.read()` en memoria → dentro del payload cifrado | `GroqKeyStore.save()`: el destino genera su propia clave de Keystore | No vacía, ≤ 512, sin espacios (mismas reglas que `save`) | **Alto.** Secreto. |
| API key de Gemini | S4 | `credentials` | `GeminiSettings.readKey()` | `GeminiSettings.saveKey()` | Igual | **Alto.** |
| Gemini Decisions activo (`gemini-enabled`) | S4 | `credentials` | `isEnabled()` | `setEnabled()`, solo si hay clave en el destino | Booleano | Medio: activa el uso de red recién en el próximo pedido. |
| Elección «seguir en local» (`gemini-local-choice`) | S4 | `credentials` | `exists()` | `continueLocally()` si es `true`. Nunca se borra al fusionar. | Booleano | Bajo. |
| Contacto fijado (`contact_name`, `contact_phone`) | S1 | `assistant` | Valores presentes o `null` explícito | Método nuevo `applyImported` en un solo `edit {}` | Nombre ≤ 100; teléfono válido para `PhoneNumberUtils.toWhatsAppJid` | **Alto:** dato personal. Un error desvía los envíos. |
| Contacto de conducción (`driving_contact_*`) | S1 | `assistant` | Igual | Igual | Igual | Alto. |
| Modo de activación (`activation_mode`) | S1 | `assistant` | Nombre del enum | `applyImported` | `AssistantActivationMode.valueOf` | Bajo. |
| Mantener orbe (`orb_hold_behavior`) | S1 | `assistant` | enum | Igual | `valueOf` | Bajo. |
| WhatsApp preferido (`whatsapp_flavor`) | S1 | `assistant` | enum | Igual | `valueOf`. Si la variante no está instalada, se informa en el resultado. | Bajo. |
| Modo de grabación (`voice_recording_mode`) | S1 | `assistant` | enum | Igual | `valueOf` | Bajo. |
| Botón de volumen (`volume_key_trigger`) | S1 | `assistant` | enum | Igual | `valueOf`. Requiere Accesibilidad: se avisa. | Bajo. |
| Botón flotante (`floating_pill_mode`) | S1 | `assistant` | enum | Igual | `valueOf`. Requiere Accesibilidad: se avisa. | Bajo. Puede mostrar la pastilla, que es un efecto de configuración y no una acción. |
| Señales sonoras (`eyes_free_cues`) | S1 | `assistant` | bool | Igual | bool | Bajo. |
| Volver a la app anterior (`boomerang_return`) | S1 | `assistant` | bool | Igual | bool | Bajo. |
| Voz en auto/Bluetooth (`car_bt_auto_voice`) | S1 | `assistant` | bool | Igual | bool | Bajo. |
| Horario nocturno (`night_window_enabled`, `night_start_hour`, `night_end_hour`) | S1 | `assistant` | bool + int | Igual | Horas 0..23 | Bajo. |
| Vocabulario de Groq (`groq_vocabulary`, `groq_pinned_contact_hints`) | S1 | `assistant` | bool | Igual | bool | Bajo. |
| Aprender y usar frases (`enabled`) | S2 | `phrases` | bool | Método nuevo `restore/merge` | bool | Bajo. |
| Frases (`entries`), con `source`, `target`, `package`, y `display`/`generalize` de A | S2 | `phrases` | Codificación existente (`encode`) | `merge`/`replace` nuevos que reutilizan `decodeExport`, `key`, `allows`, `trim` | Validación completa de `decodeExport` + `PhoneCommandCodec`. Las plantillas se vuelven a derivar. | Medio. Una frase podría disparar una acción, pero no al importar. |
| Estructuras (`patterns`) | S2 | `phrases` | `encodePatterns` | Fusión por `template` | `decodePatterns` + `validPattern` | Medio. |
| Rutinas | S5 | `routines` | `ConfiguredRoutineStore.encode` | `save()` (atómico) | `ConfiguredRoutineStore.decode`: ≤ 50 rutinas, de 1 a 10 pasos, sin anidar, sin nombres duplicados | Medio. Una rutina solo se ejecuta cuando se la pide, y con confirmación. |
| Lugares `casa` y `trabajo` | S6 | `places` | `address(alias)` | `save()`, más un `remove()` nuevo | Alias permitido, ≤ 500 caracteres, no vacío | **Alto:** dato personal. |

**Excluidos**, con su motivo:

| Dato | Almac. | Motivo |
| --- | --- | --- |
| `recent_logs_json` | S1 | Historial operativo con nombres de contactos. |
| `mic_prompted_once` | S1 | Estado de un permiso del dispositivo. |
| `last_used` | S2 | Estado de la conversación en curso. |
| `*.enc` y alias de Keystore | S3/S4 | No portables. Se re-cifran desde la memoria. |
| `phone-workflow.json`, `temporary-phone-mode.json` | S7 | Ejecución en curso. Restaurarlos podría reanudar acciones. |
| Audios, capturas, caché | S7 | Contenido, no configuración. |
| Métricas de auto-envío (B) | nuevo | Historial operativo y dependiente del dispositivo y de su WhatsApp. |
| Permisos, rol de asistente, accesibilidad, acceso a notificaciones y a No molestar, `WRITE_SETTINGS` | Android | No se pueden exportar. Solo se informa qué hay que reactivar. |

## C3. Formato `.insto`

Nombre sugerido: `insto-backup-AAAA-MM-DD.insto`.

**Sobre binario externo.** Solo contiene lo necesario para descifrar y todo se autentica como AAD:

| Bytes | Campo |
| --- | --- |
| 0-7 | Magic ASCII `INSTOBAK` |
| 8 | Versión del sobre = `1` |
| 9 | KDF = `1` (PBKDF2-HMAC-SHA256) |
| 10-13 | Iteraciones, uint32 big-endian |
| 14-29 | Sal aleatoria de 16 bytes (`SecureRandom`) |
| 30-41 | IV aleatorio de 12 bytes, nuevo en cada exportación |
| 42-… | Texto cifrado AES-256-GCM con etiqueta de 128 bits. AAD = bytes 0-41. |

**Payload descifrado:** JSON UTF-8 con nombres de campo estables. No incluye archivos internos ni volcados de `Preferences`:

```json
{
  "format": "insto-config",
  "schema": 1,
  "createdAt": "2026-11-20T15:04:05Z",
  "app": {"versionName": "1.12.0", "versionCode": 21},
  "includesCredentials": true,
  "categories": {
    "credentials": {"v": 1, "groqKey": "…", "geminiKey": "…", "geminiEnabled": true, "geminiLocalChoice": false},
    "assistant":   {"v": 1, "pinnedContact": {"name": "…", "phone": "…"}, "drivingContact": null,
                    "activationMode": "TAP_CHAT_HOLD_AUDIO", "…": "…"},
    "phrases":     {"v": 1, "enabled": true, "entries": [], "patterns": []},
    "routines":    {"v": 1, "routines": []},
    "places":      {"v": 1, "casa": "…", "trabajo": null}
  }
}
```

- **Campo ausente** significa desconocido: **nunca borra**. **`null` explícito** significa que estaba vacío al exportar: solo borra en el modo Reemplazar de esa categoría y después de una advertencia.
- **Migraciones:** `BackupSchema.migrate(json)` encadena funciones `fromN → N+1` del esquema global y de cada `categories.*.v`. Un `schema` mayor que el soportado da el error «Creado por una versión más nueva de Insto». Uno desconocido, «Formato no reconocido». La primera migración real importa el JSON `insto-learned-phrases` v1 de 1.11 como un respaldo parcial: sin cifrar, solo `phrases`, y sin credenciales.
- **Límites:** archivo ≤ 1 MiB, controlado **antes** de derivar la clave. Payload ≤ 1 MiB. Frases ≤ 200 y estructuras ≤ 60, iguales a los del almacén. Rutinas ≤ 50. Campos de texto con los límites de cada validador. Iteraciones aceptadas entre 100 000 y 5 000 000, para que un archivo malicioso no bloquee el teléfono.
- **Lectura inerte:** el decodificador construye solo data classes. No llama a ejecutores ni a `PhoneActionExecutor`, no arma `AutoSendCoordinator` ni crea workflows. Las rutinas y frases importadas solo se ejecutarán cuando el usuario las pida.

## C4. Criptografía

- **AES-256-GCM** (`AES/GCM/NoPadding`, disponible desde API 24) con IV de 12 bytes y etiqueta de 128 bits. El GCM verifica la etiqueta **antes** de entregar el texto plano: `doFinal` falla si hay alteración, y entonces no se procesa nada.
- **PBKDF2-HMAC-SHA256** con **600 000 iteraciones** (recomendación OWASP 2023), sal de 16 bytes y clave de 32 bytes.
  - **Riesgo de compatibilidad:** `SecretKeyFactory "PBKDF2WithHmacSHA256"` existe recién desde **API 26**, y `minSdk` es 24.
  - **Decisión:** se usa `SecretKeyFactory` en API ≥ 26. En API 24-25 se usa una implementación de **RFC 8018 §5.2** sobre `Mac("HmacSHA256")`, que es estándar en todas las versiones. No es un cifrado casero: es la construcción normativa de PBKDF2 sobre una primitiva del sistema. Se valida con los vectores publicados de PBKDF2-HMAC-SHA256 (RFC 7914 §11) y con una prueba cruzada contra `SecretKeyFactory` en la JVM.
  - La derivación corre en `Dispatchers.Default`, con indicador de progreso. Se estima entre 1 y 4 s según el teléfono. Se medirá.
- **Contraseña:** mínimo 8 caracteres, con confirmación. Se maneja como `CharArray` y se pone en cero al terminar, igual que los bytes de la clave derivada. **No se almacena.** No hay contraseñas fijas, claves embebidas ni Keystore como protección del archivo.
- **API keys:** se leen con `GroqKeyStore.read()` y `GeminiSettings.readKey()` solo dentro de `ConfigurationBackupManager.export`. Se escriben en el JSON en memoria, se cifran y el buffer se sobrescribe. **No hay archivos temporales**: el archivo final se escribe una sola vez, con los bytes ya cifrados.
- **Mensajes:** ningún error, log ni vista previa muestra una clave. La vista previa dice «Clave de Groq: incluida (termina en ••••)», **sin** caracteres reales. Una contraseña incorrecta y un archivo alterado dan el mismo mensaje, «Contraseña incorrecta o archivo dañado», porque GCM no permite distinguirlos.
- **«Exportar sin claves»:** el mismo formato y la misma contraseña, sin la categoría `credentials` (`includesCredentials: false`). La UI avisa que **no restaurará credenciales**. **Decisión propuesta:** contraseña obligatoria siempre, porque el archivo también tiene teléfonos y direcciones (ver Preguntas).

## C5. Exportación (UI)

Sección nueva en Configuración, **«Copia de seguridad y restauración»**, después de «Reconocimiento e interpretación».

1. Resumen de lo disponible: «Contactos: 2 · Frases: 34 (12 manuales) · Rutinas: 3 · Lugares: 2 · Claves: Groq y Gemini».
2. Casillas por categoría, todas marcadas. «Claves de API» está marcada por defecto en «Respaldo completo» y nunca muestra el valor.
3. Contraseña y confirmación, con indicador de coincidencia.
4. Advertencia: «Si perdés la contraseña, no hay forma de recuperar este respaldo».
5. Botones «Crear copia de seguridad» y «Exportar sin claves».
6. Selector `ActivityResultContracts.CreateDocument("application/octet-stream")` con el nombre sugerido. **No se agregan permisos de almacenamiento.**
7. El archivo se cifra en memoria **antes** de abrir el flujo. Se escribe con `openOutputStream(uri, "wt")`, `write`, `flush` y `close`, y solo después de cerrar sin error se muestra «Copia creada: 18 KB». Si hay un error, se muestra el mensaje real («No se pudo escribir en la ubicación elegida») y se intenta borrar el documento parcial con `DocumentsContract.deleteDocument` (el resultado de ese intento se informa). Cancelar el selector no muestra nada ni deja estado.

## C6. Importación (UI)

1. `OpenDocument(arrayOf("*/*"))`, porque `.insto` no tiene un tipo MIME registrado. Cancelar no cambia nada.
2. Se lee hasta 1 MiB + 1 byte. Si se pasa, se rechaza.
3. Se detecta el formato: `INSTOBAK` (sobre v1) o JSON `insto-learned-phrases` (legado). Cualquier otro contenido da «Formato no reconocido».
4. Se pide la contraseña si el archivo está cifrado.
5. Se deriva la clave y se descifra con GCM. Un fallo no cambia nada.
6. Migración y **validación completa** de todas las categorías, con los validadores de cada almacén. El resultado es un `RestorePlan` inmutable. **Todavía no se escribe nada.**
7. Vista previa: categorías, cantidades y conflictos, como «La clave de Gemini del respaldo es distinta de la actual» o «3 frases manuales locales tienen el mismo texto». Nunca se muestran secretos.
8. Casillas por categoría.
9. Modo por categoría: «Fusionar» (por defecto) o «Reemplazar», con el detalle de lo que se sobrescribe o se borra.
10. Confirmación: «Restaurar».
11. Aplicación por etapas (C7).
12. Informe final: lo recuperado, lo que se omitió por conflictos y lo que **necesita atención**. Incluye permisos a reactivar, según el estado real (Accesibilidad, asistente predeterminado, acceso a notificaciones y multimedia, No molestar, `WRITE_SETTINGS`, micrófono, contactos, calendario, cámara, llamadas, notificaciones), WhatsApp preferido no instalado, frases de apps no instaladas que quedaron sin usar hasta que se instale la app, y rutinas con pasos que requieren permisos.

## C7. Fusionar, reemplazar, etapas y rollback

### Reglas por categoría

| Categoría | Fusionar | Reemplazar (solo la categoría marcada) |
| --- | --- | --- |
| `credentials` | Una clave **ausente** en el teléfono se restaura. Una clave **distinta** existente se conserva, salvo que el usuario marque «Reemplazar la clave de Groq/Gemini actual» (sin marcar por defecto). `geminiEnabled` se aplica solo si el destino tiene clave y no se marcó lo contrario. | Escribe las claves del respaldo, con una advertencia explícita por cada clave existente. **Una clave ausente en el respaldo nunca borra la local.** |
| `assistant` | Solo completa las claves de DataStore **ausentes** en el destino (`prefs.contains`). Un contacto existente distinto se conserva y se informa como conflicto. | Sobrescribe los campos presentes. Un `null` explícito borra solo después de la advertencia. Un campo ausente se mantiene. |
| `phrases` | Agrega las frases cuya clave no existe. Si la clave existe: **la manual local siempre gana**; una manual importada reemplaza a una automática local; entre dos automáticas gana la de fecha más reciente. Las estructuras se unen por `template`, con valores y ejemplos unidos y el estado `enabled` local respetado. Se aplica `trim`. | Reemplaza `entries`, `patterns` y `enabled` por los del respaldo. Advierte cuántas frases manuales locales se pierden. |
| `routines` | Agrega por nombre normalizado. **Gana la local.** Si el total pasa de 50, se rechaza en la validación, antes de escribir. | Reemplaza el archivo con `save()`. |
| `places` | Completa solo los alias ausentes. | Sobrescribe. Un `null` explícito borra con advertencia. |

### Etapas y recuperación

`ConfigurationBackupManager.restore(plan)`:

1. **Validar** todo (C6.6). Cualquier error aborta sin escribir.
2. **Capturar** en memoria el estado previo de **cada categoría que se va a tocar**: instantánea de DataStore S1 (solo las claves de la matriz), `entries`, `patterns` y `enabled` de S2, contenido de S5, alias de S6, y claves y flags de S3/S4. Las claves en texto plano quedan solo en memoria.
3. **Marcador:** se escribe `noBackupFilesDir/restore-in-progress.json` con las categorías y la hora, **sin datos ni secretos**.
4. **Aplicar** en orden de menor a mayor riesgo: `places` → `routines` → `phrases` → `assistant` → `credentials`. Las claves van al final para que un fallo previo no las toque. Cada escritura es atómica en su almacén (DataStore `edit`, renombrado en S5, `commit()` en S6, renombrado en `GroqKeyStore.save`).
5. **Rollback:** si una etapa lanza una excepción, se restauran las instantáneas de las etapas ya aplicadas, en orden inverso, y se informa qué se revirtió y qué no se pudo revertir.
6. Se borra el marcador y se refresca la UI (C8).

**Límite real, que se documenta y no se oculta:** la importación **no es atómica globalmente**. Si el proceso muere entre etapas (el sistema lo mata, se agota la batería), puede quedar una restauración parcial, porque las instantáneas viven en memoria. Persistirlas obligaría a escribir las claves descifradas en un archivo. Al iniciar, si existe el marcador, Configuración muestra: «La última restauración se interrumpió. Revisá estas categorías: …» y propone volver a importar. En `GeminiSettings.saveKey`, la clave y el flag son dos archivos: un corte entre ambos deja la clave guardada con Decisions apagado, que es el estado seguro.

## C8. Integración con el código existente

- Componente nuevo `backup/ConfigurationBackupManager.kt`, desacoplado de la UI. Depende de una interfaz por categoría (`BackupSection<T>`: `snapshot()`, `validate(json)`, `merge(…)`, `replace(…)`, `restore(snapshot)`), implementada por adaptadores finos sobre los almacenes reales:
  - `AssistantPreferencesRepository`: se agregan `exportSnapshot()` y `applyImported(map, mode)`, ambos en un solo `edit {}`. No hay un segundo sistema de preferencias.
  - `LearnedPhraseStore`: se agregan `backupSnapshot()`, `restoreBackup(snapshot)` y `mergeBackup(entries, patterns, mode)`. Reutilizan `decodeExport`, `decodePatterns`, `key`, `allows`, `trim` y `trimPatterns`. **No cambian** `lookup`, `learn` ni `teach`.
  - `ConfiguredRoutineStore`: `read`, `save`, `decode` y `encode`, sin cambios.
  - `SavedPlacesRepository`: se agrega `remove(alias)`.
  - `GroqKeyStore` y `GeminiSettings`: `read`, `save` y `clear` existentes. Se agrega `GeminiSettings.hasLocalChoice()`.
- `backup/BackupCrypto.kt` (sobre + KDF + GCM) y `backup/BackupSchema.kt` (JSON + migraciones) son puros y se prueban en la JVM.
- **Refresco sin reiniciar:** S1 y S2 son flujos, así que Compose se actualiza solo. El `MainViewModel` expone `configurationRevision: StateFlow<Int>`, que se incrementa después de importar. Las pantallas que leen S4, S5 y S6 bajo demanda lo usan como clave de `remember`, y `vocabularyHints.refresh` se invoca con invalidación. El servicio de accesibilidad ya observa `preferencesFlow`: actualiza la pastilla, pero no ejecuta acciones.
- **Sin efectos:** importar no llama a `PhoneActionExecutor`, `WhatsAppDispatcher`, `AutoSendCoordinator`, `WorkflowRunner`, Groq ni Gemini. No reinicia servicios. Una prueba lo verifica con fakes que fallan si se los invoca.
- La exportación e importación de frases que ya existe en Frases aprendidas (JSON) **se mantiene**, por compatibilidad. Su importación sigue igual. El flujo nuevo además acepta ese archivo.

## C9. Pruebas

JVM puras (`scripts/test-phone-core.py`):

- `BackupCryptoTest`: vectores de PBKDF2, ida y vuelta, contraseña incorrecta, archivo truncado (header y etiqueta), byte alterado en el header (AAD), el IV y el texto cifrado, iteraciones fuera de rango, archivo de más de 1 MiB, IV y sal distintos en dos exportaciones.
- `BackupSchemaTest`: esquema desconocido, versión futura, campos ausentes y `null` explícito, duplicados, entradas malformadas, migración `insto-learned-phrases` v1 → esquema 1, migración sintética de una categoría v0 → v1.
- `ConfigurationBackupManagerTest` con almacenes falsos:
  - exportar e importar una configuración completa;
  - teléfono A → respaldo → teléfono B limpio, con almacenes independientes, verificando la configuración y las claves;
  - frases manuales y parametrizadas, con su `display` y `generalize`;
  - rutinas, lugares y preferencias;
  - fusión sin perder valores previos;
  - reemplazo solo de las categorías elegidas;
  - respaldo sin claves, que no borra las existentes;
  - falla de escritura en la etapa N y rollback de las anteriores;
  - el marcador de restauración interrumpida;
  - la importación no ejecuta nada (fakes que fallan si se los invoca);
  - ningún secreto aparece en los mensajes, el `toString` del plan, la vista previa ni los archivos escritos (se recorre el directorio temporal de la prueba buscando la clave en texto plano).

Robolectric:

- `BackupRestoreFlowTest`: con DataStore, SharedPreferences y archivos reales, y **claves con un `KeyCipher` falso**, porque Robolectric no implementa `AndroidKeyStore`.
- `BackupScreenTest`: selector cancelado, error de escritura del `ContentResolver` y el informe de permisos pendientes con los servicios sin activar.

**Pendiente físico:** exportar en el Motorola, desinstalar o usar otro teléfono, importar y comprobar que Groq y Gemini responden sin volver a cargar las claves. Hasta entonces se informa como no verificado.

## C10. Criterios de aceptación

Los de la consigna, sin cambios: las claves se recuperan sin volver a ingresarlas; viajan siempre cifradas; el respaldo funciona entre instalaciones; no se pierden frases; se conservan las opciones; el usuario elige qué importar y qué reemplazar; un archivo inválido no destruye nada; importar no dispara acciones; sin usar esta función todo funciona igual; y las limitaciones (no atomicidad global, Keystore, permisos) están documentadas en `docs/releases/Insto-1.12.0-backup.md`.

---

## Dependencias entre partes

- **C depende de A** por los campos nuevos de `Entry` (`display`, `generalize`). Por eso C se implementa después y su esquema los incluye.
- **C depende de B** solo para decidir qué **no** exportar (métricas y versión confirmada).
- **B no depende de A.** A y B no comparten archivos.
- Las tres tocan Configuración (`AssistantHomeScreen`) en secciones distintas. Los conflictos son triviales.

## Riesgos principales

| Riesgo | Mitigación |
| --- | --- |
| A: un falso positivo difuso ejecuta otra acción | Tabla cerrada de verbos sin sinónimos. Una sola edición en tokens de ≥ 5 letras. Palabras protegidas. Ante un empate, nada. Métrica de acciones indebidas = 0 como condición de aceptación. |
| A: el slot `{app}` captura texto que no es una app | Una única app lanzable con esa etiqueta exacta. De 1 a 3 palabras. Sin números ni palabras de restricción. |
| A: latencia del lookup | Índice canónico precalculado al escribir, como `PatternCache`. Se mide el p50 y el p95 de la ruta local. |
| B: la búsqueda estricta de Enviar falla donde antes «funcionaba» con `contains` | Ese `contains` es justamente el riesgo de tocar «Enviar ubicación». Se reporta como `not_found` y el usuario tiene el camino manual. |
| B: un chequeo por eventos duplica gestos | Las guardas de un gesto por paso no cambian. Hay pruebas que cuentan `gesturesDispatched`. |
| C: PBKDF2-SHA256 no está en API 24-25 | Implementación RFC 8018 sobre HMAC, con vectores de prueba. |
| C: la restauración no es atómica | Etapas, instantáneas en memoria, rollback, marcador y documentación honesta. |
| C: Keystore no se puede probar en Robolectric ni en la JVM | Abstracción `KeyCipher`. La prueba física queda pendiente y declarada. |
| Entorno: Robolectric descargaba `nativeruntime-dist-compat` con HTTP 403 en sesiones anteriores | Se intenta `testDebugUnitTest`. Si falla, se informa con la salida y se ejecuta todo lo posible con `test-phone-core.py`. |

## Preguntas para aprobar el PASO 0

1. **«Exportar sin claves»:** ¿también exige contraseña? Propuesta: sí, porque incluye teléfonos y direcciones.
2. **Indicador de salud sin historial:** ¿«Necesita revisión» hasta el primer envío confirmado con la versión instalada? Propuesta: sí, para no confundir un servicio habilitado con una garantía de envío.
3. **Generalización desde una sola frase:** ¿activa por defecto, con el interruptor por frase? Propuesta: sí. Es lo que pide el ejemplo «al 30 → al 50».
4. **Versión:** 1.12.0 / `versionCode 21`, con el cambio de versión y las notas de entrega en un commit final después de C.
