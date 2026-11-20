# Insto 1.12.0 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. **No implementar hasta que el usuario apruebe el PASO 0** (spec y este plan). Commits separados en el orden A → B → C, y después el commit de versión.

**Goal:** Generalizar las frases aprendidas con parámetros tipados y matching difuso conservador. Diagnosticar y endurecer el auto-envío de WhatsApp. Exportar e importar toda la configuración, claves incluidas, en un archivo cifrado y portable.
**Architecture:** A reutiliza `PhrasePatterns.observe` y agrega un canonicalizador puro delante de los índices de `LearnedPhraseStore`. B extrae los selectores a un módulo puro con estrategias ordenadas y agrega salud y métricas sin cambiar la autorización. C agrega `ConfigurationBackupManager` con un sobre AES-256-GCM/PBKDF2, un esquema JSON versionado y adaptadores finos sobre los almacenes existentes.
**Tech Stack:** Kotlin, coroutines, DataStore, `javax.crypto`, `org.json`, SAF, Compose, JUnit/Robolectric. Sin dependencias nuevas.
**Spec:** ../specs/2026-11-20-learned-params-autosend-backup-design.md

## Global Constraints

- Sin permisos nuevos ni cambios en `accessibility_service_config.xml`.
- Sin logs con audio, transcripciones, mensajes, teléfonos, claves ni contraseñas.
- Se mantienen la cancelación por generación, el M4A a Instinct y los flujos de Groq y Gemini.
- Sin red en exportar o importar. Sin dependencias pesadas.
- Sin datos nuevos, el comportamiento es idéntico al de 1.11.1.
- A no toca `assist/`. B no toca `LearnedPhraseStore` ni `PhrasePatterns`. C no cambia los caminos de lectura ni de ejecución.

## Review Focus

- A: una negación, una condición o una ambigüedad nunca ejecutan. Una frase manual nunca se pisa. Las métricas de acciones indebidas y de derivaciones incorrectas a Instinct siguen en 0.
- B: nunca más de un gesto por paso. `Ambiguous` no toca nada. «Listo» exige un envío confirmado con la versión instalada. Las métricas no contienen datos personales.
- C: un archivo inválido o una contraseña incorrecta dejan todo intacto. Un campo ausente nunca borra. Las claves nunca quedan en claro fuera de la memoria. Importar no ejecuta nada.

### Task 0: Spec, auditoría y plan

**Files:** crear `docs/superpowers/specs/2026-11-20-learned-params-autosend-backup-design.md` y este plan.

- [x] Auditar las frases (A1), los selectores de WhatsApp (B1) y los almacenes persistentes (C1-C2).
- [x] Escribir la spec con la matriz de datos, los riesgos, las decisiones y los criterios.
- [ ] **Esperar la aprobación del usuario** y las respuestas a las cuatro preguntas de la spec.

### Task A1: Canonicalizador y plantillas desde frases

**Files:** crear `phone/PhraseCanonicalizer.kt`; modificar `data/LearnedPhraseStore.kt` (índice canónico, plantillas derivadas, `display`, `generalize`, uso por plantilla) y `phone/PhrasePatterns.kt` (slot `APP` y una variante de `observe` sin el mínimo de ejemplos, sin tocar la lógica de las estructuras existentes); pruebas `phone/PhraseCanonicalizerTest.kt`, `data/LearnedPhraseTemplateTest.kt` y casos en `data/LearnedPhraseStoreTest.kt`.
**Interfaces:** `PhraseCanonicalizer.canonical(key): String?`, `PhraseCanonicalizer.nearMatch(a, b): Boolean`; `LearnedPhraseStore.lookup` devuelve el mismo `PhoneCommand?` y agrega `LearnedMatch(command, sourcePhrase, template?)` internamente; `Entry(display: String?, generalize: Boolean)`.

- [ ] Medir primero «antes»: crear un worktree de `ee724f3` con el arnés nuevo de métricas y las mismas semillas.
- [ ] Escribir las pruebas que fallan: voseo, tuteo, infinitivo y enclíticos; muletillas; una edición; empates; palabras protegidas; colisiones canónicas; «al 30» → «al 50»; fuera de rango; dos apps con la misma etiqueta; negaciones y condiciones.
- [ ] Implementar el canonicalizador puro con una tabla cerrada de verbos y muletillas.
- [ ] Derivar las plantillas con `PhrasePatterns.observe` (un ejemplo) y cachearlas con el índice por versión almacenada.
- [ ] Prioridad: exacta → exacta canónica → plantillas manuales → plantillas automáticas → estructuras. Los empates por nivel se anulan.
- [ ] `learn`: no guardar una automática que coincide canónicamente con una manual. `recordUse` por la frase de origen.
- [ ] Leer las entradas viejas sin `display` ni `generalize`, sin migración destructiva.

### Task A2: UI y métricas de A

**Files:** modificar `ui/screens/LearnedPhrasesScreen.kt`, `data/PhraseTeaching.kt` (guardar `display`), `ui/MainViewModel.kt` (solo el `saveUse` por plantilla), `test/.../phone/IntentCoverageTest.kt`, `docs/metrics/intents-baseline.md`, `docs/releases/Insto-frases-aprendidas.md` y `test/.../LearnedPhrasesScreenTest.kt`.

- [ ] Mostrar «Bajá la música al {número}» con su ejemplo. Interruptor «Usar también con otros valores». Editar con un ejemplo concreto y eliminar.
- [ ] Ampliar `offlineMetrics` con la sección «con frases aprendidas» (cinco grupos de la spec) y mantener sin cambios la sección del corpus de 155.
- [ ] Ejecutar y registrar antes y después. **Abortar A si las acciones locales indebidas o las derivaciones incorrectas a Instinct superan 0.**
- [ ] Commit A: «Frases aprendidas con parámetros y matching difuso».

### Task B1: Selectores con estrategias y fixtures

**Files:** crear `assist/WhatsAppSelectors.kt` (`UiNode`, `Strategy`, `SelectorResult`) y `assist/AccessibilityUiNode.kt`; modificar `assist/PingAutoSendAccessibilityService.kt` (reemplazar `findTextComposer`, `findTextSendTarget`, `findSendButtonNode`, `findSharePositiveButton`, `findNativeVoiceMicNode` y los IDs de cabecera por llamadas al módulo, y agregar el disparo por eventos con debounce); crear `test/.../assist/WhatsAppSelectorFixtures.kt`, `WhatsAppSelectorsTest.kt` y `test/.../WhatsAppSelectorStrategyRegressionTest.kt`.

- [ ] Escribir las 6 fixtures (WA_A, WA_B, WA_C, WAB_A, WAB_B y WAB_C) y las pruebas por elemento y estrategia, incluidos el distractor «Enviar ubicación» y el caso ambiguo.
- [ ] Implementar las estrategias ordenadas: un resultado ambiguo detiene la búsqueda y no hay `contains` para Enviar.
- [ ] Conectar el servicio sin tocar `AutoSendGuard`, `ShareConfirmationPolicy`, `TextSendAttempt`, `ShareSendAttempt` ni la compuerta `isArmedFor`.
- [ ] Usar los eventos del paquete armado para adelantar el chequeo, con el sondeo como respaldo y los mismos plazos.
- [ ] Ejecutar todas las regresiones de auto-envío existentes sin modificarlas.

### Task B2: Salud, métricas y recuperación

**Files:** crear `assist/AutoSendHealth.kt`, `assist/AutoSendMetricsStore.kt` y sus pruebas; modificar `ui/screens/AssistOverlayScreen.kt` (indicador y botón manual), `ui/screens/AssistantHomeScreen.kt` (diagnóstico y «Borrar métricas»), `ui/MainViewModel.kt` (estado de salud y acción manual explícita) y `PingAutoSendAccessibilityService.kt` (registrar un resultado por solicitud).

- [ ] `AutoSendHealth.evaluate` puro, con pruebas de los tres estados y sus motivos.
- [ ] Métricas en un `AtomicFile`, con enumeraciones cerradas y como máximo 100 filas. La prueba de privacidad busca los datos de las fixtures en el JSON.
- [ ] Panel: estado al abrir y «Abrir el chat y enviar a mano» (o «para revisar» si el resultado es incierto), sin armar el auto-envío.
- [ ] Verificar que un fallo antes del gesto conserva el pedido y que uno incierto nunca reintenta.
- [ ] Actualizar `docs/FLOATING-ASSISTANT.md` con las estrategias, la salud y las métricas.
- [ ] Commit B: «Auto-envío con estrategias, diagnóstico y métricas locales».

### Task C1: Cripto y esquema

**Files:** crear `backup/BackupCrypto.kt`, `backup/BackupSchema.kt`, `backup/BackupModels.kt` y las pruebas `BackupCryptoTest` y `BackupSchemaTest`.

- [ ] Pruebas: vectores de PBKDF2-HMAC-SHA256 (RFC 7914 §11), prueba cruzada con `SecretKeyFactory`, ida y vuelta, contraseña incorrecta, truncado, alterado (AAD, IV y texto cifrado), tamaño, iteraciones fuera de rango, esquema desconocido o futuro, duplicados y malformados, migración del JSON de frases v1.
- [ ] Sobre `INSTOBAK` v1, 600 000 iteraciones, sal 16, IV 12, GCM 128, AAD = cabecera. `SecretKeyFactory` en API ≥ 26 y RFC 8018 sobre `Mac` en API 24-25. `CharArray` y claves derivadas en cero al terminar.
- [ ] Esquema 1 con categorías versionadas, distinción entre campo ausente y `null`, y límites.

### Task C2: Manager, adaptadores y rollback

**Files:** crear `backup/ConfigurationBackupManager.kt` y `backup/BackupSections.kt`; modificar `data/AssistantPreferencesRepository.kt` (`exportSnapshot` y `applyImported`), `data/LearnedPhraseStore.kt` (`backupSnapshot`, `restoreBackup` y `mergeBackup`), `phone/SavedPlacesRepository.kt` (`remove`) y `phone/GeminiSettings.kt` (`hasLocalChoice`); pruebas `ConfigurationBackupManagerTest` y `BackupRestoreFlowTest`.

- [ ] Pruebas que fallan para cada caso de C9 con almacenes falsos, incluidos A → B limpio, el rollback en la etapa N, el marcador, la ausencia de efectos y la ausencia de secretos en archivos y mensajes.
- [ ] `export(selection, password)` → `ByteArray`; `inspect(bytes, password)` → `RestorePlan`; `restore(plan, choices)` → `RestoreReport`. El orden es places → routines → phrases → assistant → credentials, con instantáneas en memoria y rollback inverso.
- [ ] Fusionar y reemplazar por categoría según la tabla C7. Las claves existentes solo se reemplazan con una marca explícita.
- [ ] Marcador `restore-in-progress.json` sin datos y aviso al iniciar.

### Task C3: UI y entrega

**Files:** crear `ui/screens/BackupScreen.kt`; modificar `ui/screens/AssistantHomeScreen.kt` (sección nueva), `ui/MainViewModel.kt` (`configurationRevision` y refresco de vocabulario) y `MainActivity.kt` si hace falta para la navegación; prueba `BackupScreenTest`; crear `docs/releases/Insto-1.12.0-backup.md`.

- [ ] Exportar: resumen, categorías, contraseña y confirmación, advertencia, `CreateDocument`, éxito solo después de `close()` y errores reales.
- [ ] Importar: `OpenDocument`, contraseña, vista previa sin secretos, categorías, modo, confirmación e informe con los permisos a reactivar.
- [ ] Refrescar Compose, rutinas, lugares, Gemini y vocabulario sin reiniciar ni ejecutar nada.
- [ ] Commit C: «Copia de seguridad y restauración cifrada de la configuración».

### Task D: Versión y verificación

**Files:** `app/build.gradle.kts` (1.12.0 / 21), `docs/releases/`.

- [ ] Ejecutar `python scripts/test-phone-core.py`, `./gradlew compileDebugKotlin compileDebugUnitTestKotlin` y `./gradlew testDebugUnitTest`. Si el último falla por el entorno (Robolectric con HTTP 403), informarlo con la salida, ejecutar todo lo posible y dejar declarado lo pendiente.
- [ ] Dejar como pendientes físicos: WhatsApp y WhatsApp Business reales, y respaldo entre dos teléfonos o tras reinstalar.
- [ ] Commit D: «Insto 1.12.0».
