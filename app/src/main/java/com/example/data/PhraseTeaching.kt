package com.example.data

import com.example.phone.DecisionClassifier
import com.example.phone.GeminiCoordinator
import com.example.phone.HybridPhoneRouter
import com.example.phone.InterpretationRequest
import com.example.phone.InterpretationResult
import com.example.phone.PhoneCommand
import com.example.phone.PhoneCommandCodec
import com.example.phone.PhoneCommandParser
import com.example.phone.PhoneRoute
import com.example.phone.SemanticCommandGuard
import com.example.phone.StructuredCommandExtractor

/**
 * Checks what a user teaches explicitly: «cuando diga luz, prendé la linterna».
 *
 * The action always comes from a request the local parser already understands, so a taught phrase can never
 * do more than the full request would. The phrase must reach the learned-phrase lookup: a phrase the parser
 * already understands, a question or a control word would never use the saved action.
 */
object PhraseTeaching {
  sealed interface Target {
    data class Valid(val command: PhoneCommand, val summary: String) : Target
    data class Invalid(val message: String) : Target
  }

  sealed interface Phrase {
    /** [key] is the normalized form the store saves. [replaces] is true when another entry already uses it. */
    data class Valid(val key: String, val replaces: Boolean = false) : Phrase
    data class Invalid(val message: String) : Phrase
  }

  /** Words the assistant reads as controls of the current request before any lookup. */
  private val controls = setOf(
    "si", "no", "dale", "ok", "listo", "acepto", "confirmar", "confirmo", "si confirmo", "ejecutar",
    "cancelar", "cancela", "descartar", "dejalo", "olvidalo", "nada", "continua", "continuar", "ya esta",
    "verificado", "reintenta", "reintentar", "intenta de nuevo", "omiti ese paso",
  )

  fun resolveTarget(target: String, parser: PhoneCommandParser = PhoneCommandParser()): Target {
    val text = target.trim()
    if (text.isEmpty()) return Target.Invalid("Escribí qué tiene que hacer Insto, por ejemplo «prendé la linterna».")
    val command = when (val route = parser.parse(text)) {
      is PhoneRoute.Local -> route.command
      is PhoneRoute.Clarify -> return Target.Invalid("Faltan datos en la acción: ${route.prompt}")
      else -> return Target.Invalid(
        "No reconozco «$text» como una acción del teléfono. Escribila como un pedido completo, " +
          "por ejemplo «prendé la linterna», «abrí Spotify» o «ejecutá la rutina Estudio».",
      )
    }
    if (!LearnedPhraseStore.allowsManual(command)) return Target.Invalid(
      "Esa acción no se puede asociar a una frase. Las llamadas, los mensajes, los archivos y los cambios " +
        "de calendario siempre se piden completos.",
    )
    return Target.Valid(command, PhoneCommandCodec.summary(command))
  }

  /**
   * [existing] are the phrases already saved. [editing] is the phrase being edited, which may keep its text.
   * The check runs the same coordinator path a request takes, without network, so a valid phrase is reachable.
   */
  suspend fun checkPhrase(
    phrase: String,
    command: PhoneCommand,
    existing: Collection<String> = emptyList(),
    editing: String? = null,
    parser: PhoneCommandParser = PhoneCommandParser(),
  ): Phrase {
    val text = phrase.trim()
    if (text.isEmpty()) return Phrase.Invalid("Escribí la frase que vas a decir.")
    val normalized = PhoneCommandParser.normalize(text)
    if (normalized in controls || PhraseVoiceCommands.parse(text) != null)
      return Phrase.Invalid("«$text» es una orden de control de Insto. Elegí otra frase.")
    val key = LearnedPhraseStore.key(InterpretationRequest(text)) ?: return Phrase.Invalid(
      "Usá una frase de un solo paso, sin preguntas, negaciones ni condiciones como «si» o «cuando».",
    )
    when (val local = parser.parse(text)) {
      is PhoneRoute.Local -> return Phrase.Invalid(
        "Insto ya entiende «$text»: ${PhoneCommandCodec.summary(local.command)}. No hace falta enseñársela.",
      )
      is PhoneRoute.Clarify -> return Phrase.Invalid("Insto ya reconoce «$text» como un pedido propio. Elegí otra frase.")
      is PhoneRoute.Unsupported -> if (!local.recoverable) return Phrase.Invalid(
        "Insto reconoce «$text» como un pedido que no puede usar como frase. Elegí otra.",
      )
      else -> Unit
    }
    if (parser.chatGptSearchQuery(text) != null)
      return Phrase.Invalid("Insto manda «$text» a una búsqueda. Elegí una frase que no empiece como una búsqueda.")
    when (val meaning = SemanticCommandGuard.check(text, listOf(command), java.util.Calendar.getInstance(), grounded = false)) {
      SemanticCommandGuard.Verdict.Accepted -> Unit
      is SemanticCommandGuard.Verdict.Rejected -> return Phrase.Invalid(
        "«$text» incluye datos que la acción elegida no conserva. ${meaning.reason.substringBefore(" No se ejecut")}")
      is SemanticCommandGuard.Verdict.NeedsClarification -> return Phrase.Invalid(
        "«$text» tiene una hora ambigua. Indicá el período, por ejemplo «de la mañana», o una hora como 17:30.")
    }
    if (!reachesLookup(text, command, parser)) return Phrase.Invalid(
      "Insto interpreta «$text» como una consulta para Instinct. Elegí una frase más corta y directa.",
    )
    return Phrase.Valid(key, replaces = key != editing && key in existing)
  }

  private suspend fun reachesLookup(text: String, command: PhoneCommand, parser: PhoneCommandParser): Boolean {
    val offline = GeminiCoordinator(
      HybridPhoneRouter({ false }, DecisionClassifier { error("Sin red al validar frases") }),
      StructuredCommandExtractor { error("Sin red al validar frases") },
      lookupLearnedPhrase = { command },
    )
    val request = InterpretationRequest(text)
    val result = try { offline.interpret(request, parser.parse(text)) }
      catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (_: Exception) { return false }
    return result == InterpretationResult.Direct(command, learned = true)
  }
}
