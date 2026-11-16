# Phone Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Interpret one floating recording, execute phone requests, or send its original audio to Instinct.

**Architecture:** Keep AudioRecord and AAC capture. A cancellable Groq transcription yields text for a deterministic Spanish command interpreter. Android executes local actions; general requests reuse the original WhatsApp dispatch. Credentials are configured once on the handset and encrypted with Android Keystore.

**Tech Stack:** Kotlin, Compose, Android intents, HttpsURLConnection, AES/GCM, existing coroutines and DataStore.

**Spec:** docs/superpowers/specs/2026-11-16-phone-actions-design.md

## Global Constraints

- minSdk 24, targetSdk 36; retain current build/signing setup.
- One microphone, one recording; no destination selector, no text substitution for voice sent to Instinct.
- Groq whisper-large-v3-turbo with language=es; no embedded or committed API key.
- Local actions work without WhatsApp, pinned contact or Accessibility.
- Preserve drafts on failure; never repeat uncertain side effects automatically.
- Local interpreter only; unsupported phone requests ask or explain rather than leak to Instinct.

## Review Focus

- Explanations and negations containing action words must not execute phone actions (Task 1).
- Ambiguous times, past times and unsupported dates must not silently produce wrong alarms (Tasks 1, 3).
- Cancelling a request during transcription must not execute or dispatch a stale result (Task 4).
- Groq errors and missing keys retain original audio and expose no secrets (Tasks 2, 4).
- Stopwatch process death and reboot must not mix monotonic clock epochs (Task 3).

## Task 1: Spanish command interpreter

Files: create `app/src/main/java/com/example/phone/PhoneCommand.kt`, `PhoneCommandParser.kt`; test `app/src/test/java/com/example/phone/PhoneCommandParserTest.kt`.

Interfaces: `PhoneCommandParser.parse(text: String): PhoneRoute`; sealed PhoneRoute has Local(command), Clarify(prompt, context), Unsupported(message), Instinct. Commands cover timer seconds/label, alarm hour/minute/days/label, clock lists, stopwatch operation, app name, volume operation, torch state, connectivity settings. `parseReply(context: String, text: String)` resolves pending requests.

- [ ] Write tests for Spanish digits/words/durations/hours, negation, explanations, incomplete and unsupported local actions, application commands and context replies; run and observe missing implementation.
- [x] Implement pure normalization and anchored intent grammar, parameter validation and reply handling. Do not guess ambiguous hour, date or destructive intent.
- [ ] Run parser tests, check exact results; commit parser and tests.

## Task 2: Personal Groq transcription and key configuration

Files: create `speech/AudioTranscriber.kt`, `speech/GroqTranscriber.kt`, `speech/GroqKeyStore.kt`, `speech/GroqSettingsActivity.kt`; modify AndroidManifest; test `speech/GroqTranscriberTest.kt` and key storage integration.

Interfaces: `AudioTranscriber.transcribe(file: File): String` is suspend, cancellable and returns final nonempty text or a safe error. `GroqKeyStore` has read/save/clear; the activity edits without exposing the existing value.

- [ ] Test multipart original bytes, endpoint/model/language fields, empty/failed/oversized input, and sanitized 401/429 errors. Observe failing tests before implementation.
- [x] Implement HTTPS multipart on IO with bounded upload, timeouts and cancellation disconnect. Use 24 MiB conservative file limit, 60 s total coroutine timeout and no retries. Keep success body bounded. Encrypt key using AndroidKeyStore AES/GCM, private noBackupFilesDir, safe failure if keystore is unavailable.
- [x] Add personal setup UI explaining remote audio processing and quotas, API-key input with masking, replace/delete, external Groq console link. Add INTERNET and private Activity manifest declaration.
- [ ] Run tests and compile; commit.

## Task 3: Phone executor and persistent stopwatch

Files: create `phone/PhoneActionExecutor.kt`, `phone/StopwatchState.kt`, `phone/StopwatchRepository.kt`; test `phone/PhoneActionExecutorTest.kt` and `phone/StopwatchStateTest.kt`; update manifest queries/permissions.

Interfaces: `execute(command: PhoneCommand): PhoneActionResult` reports success, request dispatched, clarification or error. Stopwatch pure state consumes monotonic time and boot identity; repository persists the state, executor drives commands. Results may include pending context for app choices, past alarm confirmation and reset confirmation.

- [ ] Write intent/extras and pure stopwatch tests, including rollover, resume, restart, reboot, missing clock handler and ambiguity; observe red.
- [x] Delegate clock actions through AlarmClock public intents, SET_ALARM permission, checked extras and explicit handoff copy. Ask before next occurrence for past hours. Reject unsupported calendar dates.
- [x] Implement persisted monotonic stopwatch and boot count check, app lookup limited to launchable visible apps, volume bounds, camera torch and settings intents. Respect keyguard, runtime CAMERA permission via Activity, Android restrictions and absent capabilities.
- [ ] Run tests; commit.

## Task 4: One floating audio pipeline and automatic routing

Files: modify `ui/MainViewModel.kt`, `assist/AssistActivity.kt`, `ui/screens/AssistOverlayScreen.kt`; create `phone/FloatingPhoneController.kt` if extraction needed. Tests: `FloatingPhoneRoutingTest.kt` plus update existing floating tests for approved routing.

Interfaces: finalize audio -> transcribe -> parser -> executor or existing sendWhatsAppAudio(file). Floating state adds processing, transcript, feedback, pending clarification and stopwatch snapshot. Cancellation/session generation rejects stale work; retries reuse finalized audio.

- [ ] Test local voice without WhatsApp/contact, general voice sends original file, transcription failure retains file, duplicate/stale callbacks cannot dispatch, text routing, clarification and setup return; observe red.
- [x] Remove pre-panel contact requirement, route text before contact lookup. Separate audio finalization from dispatch. Add transcription and routing with generation checks, no repeat on ambiguous dispatch, local result state and configurable key setup action.
- [x] Preserve audio across setup/pause; explicit retry reuses recording. Clear pending clarification on close/cancel/new complete request. Keep attachments directed to Instinct and legacy explicit recording flows unchanged.
- [x] Update Compose copy, single microphone and existing Send; add key setup, local feedback, stopwatch controls and camera permission handling. No alternate voice mode.
- [ ] Run all feasible tests, assembleDebug and regression checks; commit.

## Task 5: Review and delivery

Files: update `docs/FLOATING-ASSISTANT.md`, this plan, and execution ledger.

- [ ] Run full unit suite, build APK and inspect results. Document network/dependency restrictions separately from behavior failures.
- [x] Fresh reviewer checks complete changes against spec and Review Focus. Fix important issues with regression coverage.
- [x] Document one-time Groq key setup, quota/network behavior, supported commands and physical-device checks. Never claim live Groq or Android hardware validation without evidence.
- [ ] Commit and deliver reviewable branch/files; no merge or publish without instruction.

## Resultados y revisión

Implementación incluida en la rama de trabajo. La revisión independiente encontró alarmas que ignoraban calificadores, medianoche incorrecta y borradores descartados ante rechazo local. Se corrigieron con sintaxis completa, medianoche explícita y resultados tipados que conservan borradores. La advertencia de Accesibilidad ahora depende de un error real de envío por WhatsApp. Las pruebas de integración y sus regresiones compilan, pero su ejecución está bloqueada por descargas de Robolectric (403); no se afirma suite completa verde.

Verificación final JVM: 30 pruebas aprobadas (6 intérprete, 5 Groq, 4 cronómetro y 15 regresiones existentes). Suite completa y pruebas Android siguen bloqueadas por dependencias externas, no por aserciones conocidas de producto.

Estado de tareas: 1 completa con pruebas JVM; 2 implementada y protocolo probado, Keystore/UI pendiente de dispositivo; 3 implementada y cronómetro puro probado, integración de intents bloqueada por Robolectric; 4 implementada y fuentes de sus pruebas compiladas, ejecución de integración bloqueada; 5 revisión y documentación terminadas, con límites de validación explícitos. No se mezcló ni publicó la rama.
