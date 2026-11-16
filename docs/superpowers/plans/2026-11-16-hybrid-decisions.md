# Hybrid Decisions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Add optional Gemini Decisions fallback without changing native action guarantees or original audio dispatch.
**Architecture:** Keep the parser API compatible; expose whether its result is an unresolved fallback. A suspend hybrid router applies typed decisions to those requests. MainViewModel awaits that router inside its cancellable session before using existing execution/WhatsApp paths.
**Tech Stack:** Kotlin, coroutines, HttpURLConnection, org.json, Android Keystore, Compose, JUnit.
**Spec:** docs/superpowers/specs/2026-11-16-hybrid-decisions-design.md (approved).

## Global Constraints

- Base includes PR #8/#9. Do not modify open PRs or merge remotely.
- One original recording; Groq transcription remains; no new dependencies.
- REST /v1beta/decisions, gemini-decision-1; 10 s timeout, 64 KiB response.
- Probability and confidence >= 0.95; invalid/contradictory decisions retain request.
- No automatic retries or arbitrary intent/parameter generation.

## Review Focus

- Local Unsupported is not synonymous with unknown; never bypass explicit restrictions.
- Cancelled text requests must be retained as carefully as audio.
- Late responses after editing/closing must not execute or dispatch.
- Missing/disabled Gemini preserves legacy behavior, including no new network request.
- Confirmations/options and clarification replies resolve before inference.

### Task 1: Hybrid policy and Decisions client

Files: create phone/HybridPhoneRouter.kt, phone/GeminiDecisionsClient.kt and matching unit tests under app/src/{main,test}/java/com/example.
Interfaces: suspend DecisionClassifier.classify(text: String): SemanticDecision; suspend HybridPhoneRouter.route(text: String, local: PhoneRoute): PhoneRoute. Reject errors through DecisionException; no dispatch side effects.

- [x] Write tests for local bypass, disabled fallback, semantic torch recovery, query routing, incomplete parameters, explicit restriction preservation, unsafe and low-confidence rejection.
- [x] Run tests and observe missing implementation failure.
- [x] Implement closed choices plus independent safety check; strict response validation and cancellable bounded HTTP.
- [x] Add HTTP contract, invalid JSON/numbers/categories, HTTP 401/403/429/500, response size and cancellation tests; run policy/client suite.

### Task 2: Personal settings and cancellable session integration

Files: modify speech/GroqKeyStore.kt (parameterized aliases with legacy defaults), speech/GroqSettingsActivity.kt, ui/MainViewModel.kt, ui/screens/AssistOverlayScreen.kt; add phone/GeminiSettings.kt and integration tests.
Interfaces: GeminiSettings.readKey(), saveKey(String), clear(), isEnabled(), setEnabled(Boolean); preserve Groq files/aliases. Constructor-inject HybridPhoneRouter into MainViewModel.

- [x] Write session regression tests covering text/audio failure retention, stale inference, retry using saved transcript, pending state and legacy disabled mode; verify RED.
- [x] Add Gemini key/settings section to existing provider settings screen, masked fields, no backups, enable/disable/delete, no content/key logging.
- [x] Await classification for text/audio in same generation-aware job; retain original requests on failure; cancel on pause/edit/dismiss; preserve attachment path.
- [x] Run available unit and integration tests, compile all test sources and assemble debug APK.

### Task 3: Documentation and review

Files: docs/FLOATING-ASSISTANT.md, plan and design status.

- [x] Document setup, free tier terms, supported fallback scope, confidence and latency limits.
- [x] Compare labeled Spanish fixtures for deterministic routing and simulated hybrid results; distinguish mocked validation from live measurement.
- [x] Review diff, test verification, fresh reviewer; address material findings, commit final result.

## Execution ledger

- Base: e00b8ac merged into isolated cloud work branch; no user changes present.
- Method: inline execution under existing authorization; no extra worktree or approval pause.
- Pre-flight: Task 2 consumes Task 1 suspend route and error contract. Legacy parser results remain source-compatible.

- Task 1: complete. Missing-client/router RED observed with standalone Kotlin compiler; 11 new policy/HTTP tests GREEN. Parser/regression combined standalone suite: 38/38.
- Task 2: complete with integration execution blocked by environment. All test sources compile; missing deciding-state RED observed before UI-state implementation. Gradle available JVM suite: 87/87. assembleDebug, v2 signature and 16 KiB alignment verified.
- Task 3: complete. Independent review plus focused follow-up found no material issues. Documentation distinguishes mocked API coverage, compiled integration tests, and live/hardware gaps.
- Full suite: attempted without exclusions; Maven redirected native runtime download to mirror returning HTTP 403. External init exclusion used only for JVM execution, not committed or applied to project dependencies.
- Integration attempt: FloatingHybridRoutingTest and FloatingOverlayTest fail during runtime fetch (android-all-instrumented SDK 28/36 HTTP 403); no assertions executed.
- APK uses a newly generated local debug key because the previous signing key is absent. No update compatibility claim; original signing key required for an in-place update to a differently signed installation.
- Live Gemini classification accuracy and latency cannot be measured without credentials. Spanish test fixtures validate routing decisions with simulated provider answers only.
