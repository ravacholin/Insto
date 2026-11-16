package com.example.phone

import org.junit.Assert.*
import org.junit.Test

class GeminiSetupPolicyTest {
  @Test fun existingGroqUsersAreAskedForGeminiUntilTheyMakeAnExplicitChoice() {
    assertTrue(GeminiSetupPolicy.shouldPrompt(hasKey = false, choseLocal = false))
    assertFalse(GeminiSetupPolicy.shouldPrompt(hasKey = true, choseLocal = false))
    assertFalse(GeminiSetupPolicy.shouldPrompt(hasKey = false, choseLocal = true))
  }
}
