package com.example.phone

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun interface DecisionClassifier {
  suspend fun classify(text: String): SemanticDecision
}

data class SemanticDecision(
  val choice: String,
  val probability: Double,
  val confidence: Double,
  val unconditional: Double,
  val safetyConfidence: Double,
)

class DecisionException(message: String) : Exception(message)

/** Select only complete, predefined commands. Free parameters still belong to the parser. */
class HybridPhoneRouter(
  private val enabled: () -> Boolean,
  private val classifier: DecisionClassifier,
) {
  fun needsDecision(text: String, local: PhoneRoute): Boolean {
    val unresolved = local == PhoneRoute.Instinct || local is PhoneRoute.Unsupported && local.recoverable
    if (!unresolved || !enabled()) return false
    val normalized = PhoneCommandParser.normalize(text)
    return !(local == PhoneRoute.Instinct && Regex("^(?:instinct\\b|(?:me )?explica(?:me)?\\b|contame\\b|que es\\b|como funciona\\b)").containsMatchIn(normalized))
  }

  suspend fun route(text: String, local: PhoneRoute): PhoneRoute {
    if (!needsDecision(text, local)) return local
    val result = classifier.classify(text)
    currentCoroutineContext().ensureActive()
    val scores = listOf(result.probability, result.confidence, result.unconditional, result.safetyConfidence)
    if (scores.any { !it.isFinite() || it !in .95..1.0 }) {
      return PhoneRoute.Unsupported("No pude decidir con suficiente certeza. Reformulá el pedido; no se ejecutó ni se envió.")
    }
    return when (result.choice) {
      "instinct" -> if (local is PhoneRoute.Unsupported) local else PhoneRoute.Instinct
      "torch_on" -> PhoneRoute.Local(PhoneCommand.Torch(true))
      "torch_off" -> PhoneRoute.Local(PhoneCommand.Torch(false))
      "volume_up" -> PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.UP))
      "volume_down" -> PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.DOWN))
      "camera_photo" -> PhoneRoute.Local(PhoneCommand.Camera())
      "camera_video" -> PhoneRoute.Local(PhoneCommand.Camera(true))
      "stopwatch_start" -> PhoneRoute.Local(PhoneCommand.Stopwatch(StopwatchOp.START))
      "stopwatch_pause" -> PhoneRoute.Local(PhoneCommand.Stopwatch(StopwatchOp.PAUSE))
      "stopwatch_resume" -> PhoneRoute.Local(PhoneCommand.Stopwatch(StopwatchOp.RESUME))
      "stopwatch_reset" -> PhoneRoute.Local(PhoneCommand.Stopwatch(StopwatchOp.RESET))
      "stopwatch_show" -> PhoneRoute.Local(PhoneCommand.Stopwatch(StopwatchOp.STATUS))
      "alarms_show" -> PhoneRoute.Local(PhoneCommand.ClockList(true))
      "timers_show" -> PhoneRoute.Local(PhoneCommand.ClockList(false))
      "timer" -> PhoneRoute.Clarify("Indicá la duración completa: temporizador de diez minutos.", "timer")
      "alarm" -> PhoneRoute.Clarify("Indicá la hora completa: alarma a las siete de la mañana.", "alarm")
      "navigate" -> PhoneRoute.Clarify("¿A dónde querés ir?", "navigate")
      "maps" -> PhoneRoute.Clarify("¿Qué lugar querés buscar?", "maps")
      "dial" -> PhoneRoute.Clarify("¿A quién querés llamar? Indicá un nombre o número.", "dial")
      "youtube" -> PhoneRoute.Clarify("¿Qué querés buscar en YouTube?", "youtube")
      "music" -> PhoneRoute.Clarify("¿Qué música querés escuchar?", "music")
      "calendar" -> PhoneRoute.Clarify("Repetí título, fecha y hora: agendá reunión mañana a las 17:00.", "calendar")
      "volume_set" -> PhoneRoute.Clarify("Indicá un volumen entre cero y cien por ciento.", "volume")
      else -> PhoneRoute.Unsupported("Ese pedido no está disponible o necesita más detalles. Reformulalo; no se envió a Instinct.")
    }
  }
}
