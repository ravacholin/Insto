package com.example.data

import com.example.phone.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhraseActionsTest {
  private fun sample(input: PhraseActions.Input) = when (input) {
    PhraseActions.Input.PERCENT -> "30"
    PhraseActions.Input.MINUTES -> "4"
    PhraseActions.Input.ROUTINE -> "Estudio"
    PhraseActions.Input.PLACE -> "Córdoba"
    PhraseActions.Input.SEARCH -> "jazz"
    else -> ""
  }

  @Test fun cadaOpcionDelMenuSePuedeGuardarEnUnaFrase() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { true })
    for (option in PhraseActions.options) {
      val command = if (option.input == PhraseActions.Input.APP) PhraseActions.openApp("Spotify", "com.spotify.music")
        else option.build(sample(option.input))
      assertNotNull(option.id, command)
      assertTrue(option.id, LearnedPhraseStore.allowsManual(command!!))
      assertTrue(option.id, PhoneCommandCodec.summary(command).isNotBlank())
      assertEquals(option.id, option, PhraseActions.optionFor(command))
      assertTrue(option.id, store.teach("atajo ${option.id.replace('_', ' ')}", command))
      assertEquals(option.id, command, store.lookup(InterpretationRequest("atajo ${option.id.replace('_', ' ')}")))
    }
  }

  @Test fun losValoresFueraDeRangoNoArmanUnaAccion() {
    val byId = PhraseActions.options.associateBy { it.id }
    for ((id, value) in listOf("volume_set" to "101", "volume_set" to "", "timer" to "0", "timer" to "diez", "navigate" to "", "routine_run" to " ")) {
      assertNull("$id $value", byId.getValue(id).build(value))
    }
  }
}
