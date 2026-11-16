package com.example.phone

internal object GeminiSetupPolicy {
  fun shouldPrompt(hasKey: Boolean, choseLocal: Boolean): Boolean = !hasKey && !choseLocal
}
