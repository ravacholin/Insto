# Instinct floating assistant

Invoke the configured Android assistant shortcut. The bottom panel listens for
four seconds. If there is no speech, it releases the microphone and stays open.
Tap the field to type (this immediately deletes provisional audio), or tap the
microphone to start another listening window. Voice is detected locally with WebRTC VAD and 300 ms preroll, then streamed
into AAC-LC mono (16 kHz, 64 kbit/s) inside a finalized MPEG-4/M4A container.
The attachment MIME is audio/mp4, with both EXTRA_STREAM and read-granted ClipData. Three seconds of silence after speech
opens WhatsApp with the audio; Send allows an earlier dispatch. Closing the
panel discards unsent data. Interruptions stop capture and retain voiced audio
for manual dispatch. Local launch failures retain text/audio for retry.

The normal assistant entrypoint always uses this panel, independent of legacy
activation-mode preferences. Existing explicit Open Chat shortcuts and optional
volume-key/legacy home-screen recording controls remain available.

## WhatsApp integration limits

There is no background WhatsApp sending API in this project. Dispatch targets
the configured phone number and installed WhatsApp flavor. Accessibility may
complete sending and return to the original app. Text automation starts explicitly
from our successful phone-targeted deep link, rather than relying on WhatsApp
Activity names or UI text-change events. It verifies the configured phone/name
in the chat header and the complete visible draft, then injects exactly one real
touch at the Send control's bounds with AccessibilityService.dispatchGesture.
The control does not need to advertise an accessibility ACTION_CLICK action.
A configured saved name is matched exactly; this is not independent proof of the
phone number when WhatsApp hides it. Chats with duplicate names and identical
drafts cannot be distinguished from that evidence alone. Device validation must
use the configured Instinct conversation, not claim arbitrary chat identity proof.
The keyboard's window is handled by also looking for WhatsApp's focused app window.

After the touch it checks that the composer cleared before confirming submission
and returning to the previous app. A touch with uncertain outcome is not repeated.
If enabled Accessibility is not actually connected, the app preserves the text
and reports that problem instead of silently opening a manual draft. Timeout
feedback says which verification failed. No notification is required. No ADB,
Shizuku, WhatsApp Business account, or notification-listener permission is added.

Recorded audio now starts its own polling immediately after the targeted share
intent opens successfully, using the same real-touch mechanism as text. The
WhatsApp dialog `Share with Instinct?` (or `¿Compartir con Instinct?`) is confirmed
automatically when its complete recipient matches the configured name or phone.
This dialog does not expose the attachment filename; the successfully launched
request binds it to the exact recording URI and target JID. An optional media
preview still requires the matching recipient and recording filename before its
Send control is touched. Confirmation and preview Send are each touched at most
once per request. Returning to the matching conversation with an empty composer
and no remaining preview Send control confirms submission before the return to
the previous app. Once a preview has appeared (including a loading/disabled Send),
its Send must have been touched; cancelling that preview never confirms submission.
A timeout or uncertain gesture never triggers another touch.
If Accessibility is enabled but disconnected, the floating panel retains the
recording and reports the connection problem instead of opening a manual dialog.
If Accessibility is disabled, the panel provides an explicit setup button.
The handset test is required before claiming fully automatic delivery for its
installed WhatsApp version. Files handed to WhatsApp remain in private cache
for URI access; new recordings clean up matching files older than 24 hours.
Audio is an attachment, not guaranteed to display as a native WhatsApp PTT note.

## Verification

Build: `./gradlew assembleDebug` with JDK 21, Android SDK 36.1, Gradle 9.3.1 and the
project's existing signing configuration.
A local debug keystore is generated for the APK and must not be committed.

Run `./gradlew testDebugUnitTest`. Pure tests cover 4 s initial silence, 3 s trailing
silence, manual Send, cancellation, recipient ambiguity, stale audio drafts and
Unicode message matching. Robolectric tests cover text deep links, panel focus,
button states and ViewModel capture cancellation. In the current environment,
Robolectric's Android runtime/native artifact downloads redirect to a blocked
Maven mirror; those tests can compile but cannot execute here.

`AudioSendAttemptTest` covers the exact English/Spanish confirmation prompt,
formatted phone numbers, wrong recipients, stale requests, unavailable buttons,
and duplicate suppression across confirmation and preview. Robolectric audio
dispatch tests cover the launched stream/JID, disconnected Accessibility, failed
launches and request invalidation. On the handset, record two consecutive clips
and let trailing silence submit each: neither should require tapping OK. Verify
the actual messages in the configured conversation and check the return behavior.

On the Android 16 Motorola: verify assistant role/hotkey, background transparency,
keyboard insets, 4 s idle behavior, first syllables, pauses below/above 3 s,
manual Send, noise, privacy toggle, permission denial, close/Back, interruption,
rotation, repeated hotkey, absent WhatsApp, Accessibility disabled, destination,
attachment appearance and return to the original application. Check the actual
WhatsApp version's accessibility tree before asserting automatic confirmation.

### Audio auto-send validation (1.4)

- Based on current `main` at `8017ac0`, including merged PRs #3 and #4.
- `assembleDebug`: passed; APK metadata is version 1.4, versionCode 5.
- 20 pure JVM tests: passed, including the confirmation/preview cancellation
  regression. An external Gradle init script excluded the inaccessible native
  Robolectric artifact for this filtered execution; project dependencies remain intact.
- All test sources, including audio dispatch and synthetic dialog/polling tests,
  compiled. Static review found no remaining critical/important issues.
- Unfiltered suite: blocked downloading `nativeruntime-dist-compat:1.0.18` with
  HTTP 403 from the Maven mirror. Even excluding that artifact externally,
  `AudioDispatchIntegrationTest` cannot initialize its Android runtime because
  its download is also blocked with HTTP 403. Its test methods did not execute.
- No handset is connected; actual WhatsApp delivery remains to be verified there.

### Previous floating assistant validation (1.2)

- `assembleDebug`: successful (JDK 21, Gradle 9.3.1, SDK 36.1).
- 14 pure JVM tests: passed (4 session policy, 4 dispatch guard, 3 text-touch attempt, 3 existing unit tests).
  An environment-only Gradle init script excluded the unavailable Robolectric native
  artifact for this filtered run; no project dependency was removed.
- All test sources, including Robolectric UI/session tests: compiled.
- `assembleDebugAndroidTest`: compiled an on-device AAC encode/extract/decode test
  (1-second tone, MPEG-4 signature, AAC track metadata, duration and decoded PCM).
  No device is attached, so this codec test has not executed here.
- Codec finalization and joins run on the IO dispatcher. Cancellation stops the
  mic immediately and discards off Main; session ownership prevents older workers
  from writing into a new session. A failed finalization returns the panel to idle.
- Full unfiltered suite: blocked resolving Robolectric native runtime from Maven's
  inaccessible mirror, before test execution.
- APK signature verification and ZIP alignment including 16 KiB page alignment: passed.
- Physical device/WhatsApp validation: not performed in this environment.
