# Conversational Phone Workflows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add conversational drafts, explicit multi-step requests, media transport and temporary phone modes, excluding saved routines as requested.

**Architecture:** A pure conversation coordinator produces validated typed commands and previews. A persisted workflow state machine consumes existing PhoneActionResult contracts and never equates handoff with observed completion. Android adapters implement media sessions and guarded scheduled restoration. MainViewModel connects these components to the existing single-recording flow.

**Tech Stack:** Kotlin, coroutines, Compose, Android MediaSessionManager, NotificationListenerService, AlarmManager, AtomicFile/JSON, JUnit and Robolectric.

**Spec:** docs/superpowers/specs/2026-11-16-conversational-workflows-design.md (approved with saved routines excluded).

## Global Constraints

- Maximum ten sequential steps; no parallel execution, conditions or loops.
- Preserve one Groq recording and original M4A for actual Instinct queries.
- Never retry completed, handed-off or uncertain effects automatically.
- Calendar remains a reviewed native draft, not an event database.
- Optional Gemini proposes typed JSON only; local validation owns execution.
- No saved routines, background microphone or general screen automation.

## Review Focus

- Quoted titles containing “y” must not split into extra commands (Task 1).
- Editing/cancelling while remote interpretation is pending must discard stale candidates (Task 3).
- Process death during dispatch must recover as uncertain, not resend (Task 2).
- Handoff and permission dialogs must not close a whole workflow or skip its step (Task 3).
- Manual changes, overlapping modes and reboot must not cause restoration to overwrite user intent (Task 4).

### Task 1: Conversation drafts and typed interpretation

Files: create phone/ConversationSession.kt, phone/StructuredCommandClient.kt and phone/PhoneCommandCodec.kt; modify PhoneCommand.kt/PhoneCommandParser.kt/HybridPhoneRouter.kt; tests phone/ConversationSessionTest.kt and phone/StructuredCommandClientTest.kt.

Interfaces: ConversationSession.accept(text: String): ConversationResult; draft retains calendar title/date/time/duration and incomplete single-action context. ConversationResult carries commands, prompt, preview and handled flag. StructuredCommandClient.extract(text: String): List<PhoneCommand> returns locally validated catalog commands, not intents. PhoneCommandCodec encodes only approved typed commands and validates on decoding.

- [ ] Write tests for calendar missing hour, correction preserving title/date, ambiguous hour, quoted conjunctions, ten-step limit, unknown step/conditions causing zero execution and negative cancellation.
- [ ] Run focused tests and observe missing implementation failure.
- [ ] Implement deterministic draft/sequence interpretation, structured JSON validation and optional bounded Gemini generateContent request with cancellation. Preserve existing parser rejection behavior for callers outside the coordinator.
- [ ] Run pure parsing/HTTP tests; invalid types/extra fields/invalid dates must never produce runnable commands.
- [ ] Commit the independently testable interpretation layer.

### Task 2: Durable sequential execution

Files: create phone/WorkflowRunner.kt, phone/WorkflowStore.kt; tests phone/WorkflowRunnerTest.kt and phone/WorkflowStoreTest.kt.

Interfaces: WorkflowRunner.prepare(commands), confirm(), next(): PhoneCommand?, accept(result: PhoneActionResult), resumeHandoff(), retry(), cancel(). Persist WorkflowSnapshot before each dispatch. Loading an EXECUTING step changes it to UNCERTAIN. Store uses AtomicFile and command codec.

- [ ] Write tests for failed third step retaining successful timer, NEEDS_INPUT retaining cursor, handoff pausing, cancellation preventing later dispatch, process death and uncertain retry rejection.
- [ ] Observe failure, implement state transitions and atomic persistence, then verify the focused suite.
- [ ] Commit runner and store.

### Task 3: Floating integration and parameter extraction

Files: modify ui/MainViewModel.kt, ui/screens/AssistOverlayScreen.kt, assist/AssistActivity.kt; tests FloatingWorkflowTest.kt.

Interfaces: expose workflow summary and confirm/continue/retry/cancel through existing text/voice and confirmation controls; route native executor results back to WorkflowRunner, including async stopwatch callbacks. Context goes before legacy classification; provider cancellation shares generation checks.

- [ ] Write integration cases for two-step preview/confirmation, no completion-close after first handoff, permission continuation, calendar correction and stale structured answers after edit/close.
- [ ] Observe failure, implement coordinator/runner integration, explicit handoff continuation and recovery prompt. Do not send unresolved workflow audio to Instinct.
- [ ] Verify new integration sources and regressions; run Android tests where runtime artifacts are available.
- [ ] Commit usable conversational sequences.

### Task 4: Media and temporary modes

Files: create phone/MediaControllerAdapter.kt, phone/MediaAccessService.kt, phone/TemporaryModeManager.kt, phone/TemporaryModeReceiver.kt, phone/TemporaryModePolicy.kt; modify manifest, typed commands/parser/codec/executor and floating access links; tests MediaCommandTest.kt and TemporaryModePolicyTest.kt plus Android adapter tests.

Interfaces: Media operation enum PLAY/PAUSE/NEXT/PREVIOUS/STATUS, optional selected package; execute returns PhoneActionResult with explicit app choices. Temporary modes cover multimedia volume and silence/DND through explicit accesses. Persistent mode lease stores original/applied state, expiry and external-change ownership.

- [ ] Write tests for unsupported media operations, multiple sessions, mode conflicts, manual state changes, expiry/reboot recovery and approximate scheduling consent.
- [ ] Observe failure; implement service access, native dispatch, atomic mode state, expiry receiver and user-change invalidation. Unsupported access preserves request and offers settings.
- [ ] Verify modes cannot silently replace a lease, restore over a changed setting or claim exact restoration without access; verify media never dispatches to an arbitrary session.
- [ ] Commit adapters and flow integration.

### Task 5: Whole-flow verification and documentation

Files: docs/FLOATING-ASSISTANT.md, this plan, app/build.gradle.kts version.

- [ ] Run assembleDebug, compileDebugUnitTestKotlin and appropriate pure/Android suites. Record dependency blocks distinctly from failed assertions.
- [ ] Review all new transitions and API contracts, fix material issues, run affected verification again.
- [ ] Document commands, permissions, partial completion, scheduling/force-stop limits and physical-device checks; bump version to 1.7.0/code 12.
- [ ] Commit final verified changes. Do not claim handset/provider validation without credentials/device.

## Execution ledger

- Native inline execution under existing user approval; no additional permission gate.
- Remote main merged into local work preserving approved design commit.
- Self-review: each approved subsystem has an implementation task; saved routines are explicitly excluded.
