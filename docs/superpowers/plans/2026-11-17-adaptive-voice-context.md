# Voz adaptativa y contexto — Implementation Plan

**Goal:** cierre adaptativo y contexto seleccionado/compartido en el flotante.
**Architecture:** ampliar las fronteras existentes de captura, interpretación y envío, manteniendo el catálogo del PR #15.
**Tech Stack:** Kotlin, Android, Compose, WebRTC VAD, Groq, Gemini Decisions, JUnit/Robolectric.
**Spec:** `docs/superpowers/specs/2026-11-17-adaptive-voice-context-design.md`

## Global Constraints
- Sin otro micrófono ni endpoint de streaming; sin permisos nuevos.
- Silencio: 900/1600/2400 ms según duración de voz <4/<12/>=12 s.
- Contexto máximo 10000 caracteres; rechazar exceso, nunca truncar.
- Mantener revisión de calendario y cancelación por generación.

## Review Focus
- Texto compartido no ejecuta órdenes por sí mismo.
- Reemplazo o eliminación de contexto invalida decisiones/planes pendientes.
- Audio contextual llega a Instinct con su referente.
- Sin contexto, el audio original conserva el comportamiento anterior.
- URL compartida no se presenta como contenido de página leído.

### Task 1: Captura adaptativa
- [x] Cambiar pruebas de `VoiceSessionPolicyTest`; ejecutar y comprobar fallo con política fija.
- [x] Implementar umbrales y metadatos de fin de voz en captura; ejecutar pruebas.

### Task 2: Contexto explícito
- [x] Añadir pruebas de intents y `InterpretationRequest` y calendario contextual.
- [x] Crear modelo acotado y lector Android de texto; integrar en actividad, ViewModel, Gemini y envío a Instinct.
- [x] Mostrar contexto removible y añadir pruebas de reemplazo, cancelación y envío por voz (compiladas; ejecución Android bloqueada por descarga HTTP 403).

### Task 3: Medición y cierre
- [x] Añadir tiempos monotónicos de etapas y visualización local.
- [x] Ejecutar suite Kotlin y Android, compilar APK, revisar diff y documentar protocolo físico y límites.

## Resultado y decisiones

- Implementación inicial revisada sobre `9fcc034`. Actualizada después sobre main `196685e` / PR #16 antes de publicar el PR; sin fusionar en main.
- 167 casos del núcleo sin fallos (uno opt-in omitido); build APK y compilación de pruebas Android correctos. Suite Robolectric bloqueada antes de ejecutar por dependencia externa HTTP 403.
- Se conservaron los defaults y la arquitectura actual; no se agrega streaming, lectura de pantalla ni permisos. El usuario autorizó implementar ambas mejoras: las decisiones rutinarias se resolvieron en esta sesión.
- La revisión independiente detectó una regresión al saltear todos los intents registrados con contexto. Se reprodujo con batería y se corrigió: solo la aclaración con datos extraíbles del contexto pasa al extractor; las acciones existentes conservan el resolver.
- Al recibir referencia nueva se descarta un adjunto anterior, para evitar que la rama de adjuntos omita el contexto. Se agregó cobertura Android, todavía sin ejecución por el bloqueo documentado.
- Protocolo y evidencia completa: `docs/VOICE-CONTEXT.md`.

### Integración de disponibilidad local
- El PR #16 se había integrado mientras se trabajaba. Se rebasó la rama y se reprodujo en rojo la pérdida de extracción del calendario contextual por la nueva aclaración local anticipada.
- La excepción se limita a aclaraciones con referencia explícita y Gemini habilitado. Los comandos completos siguen sin esperar a Google; las consultas mantienen el fallback de main.
- La prueba de latencia usa una paráfrasis semántica para medir la etapa del proveedor; las órdenes locales completas conservan sus tiempos inmediatos.
- Versión de entrega: 1.10.0 / código 17. Firma debug local nueva, advertida en la nota de entrega.
