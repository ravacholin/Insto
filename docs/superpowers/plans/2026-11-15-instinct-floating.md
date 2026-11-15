# Instinct floating assistant

Implement the approved conversation plan: translucent Compose panel on assist activation; local voice activity detection; four seconds initial listening then idle; three seconds trailing silence to send, also manual Send; text focus cancels/discards audio and disables listening until explicitly reactivated. Dispatch text/audio through WhatsApp and optional Accessibility, returning to the previous app. Preserve drafts on local failures and never automatically retry uncertain sends. No response viewer in this version.

Tasks: (1) capture and deterministic session policy + tests; (2) WhatsApp text dispatch and recipient checks + tests; (3) panel and activity wiring + UI tests; (4) build, suite and review.

Validation: Gradle testDebugUnitTest and assembleDebug, then physical Android 16 verification of hotkey, keyboard, capture, actual WhatsApp send/return and noisy surroundings.
