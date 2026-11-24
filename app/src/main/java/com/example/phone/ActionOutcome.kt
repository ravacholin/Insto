package com.example.phone

/**
 * Checking what an action really did (1.20.0), the pure part. Android reports the state through [DeviceReading]; the
 * rules here decide, for each command, whether the requested state was observed, whether it was already there, and
 * what cannot be observed at all. Handing an intent to another app (an alarm, a timer, a search, an app) is never
 * reported as verified: the other app accepted it, nothing more is known. Nothing here touches Android.
 */
enum class OutcomeVerdict {
  /** The requested state was observed. */
  VERIFIED,
  /** Enough evidence that the goal was not reached. */
  NOT_ACHIEVED,
  /** There is no reliable way to check it. */
  NOT_OBSERVABLE,
  /** Evidence incomplete or contradictory. */
  INDETERMINATE,
}

/**
 * What can be read back from the phone, field by field; null where it was not read or could not be. The Android
 * constants are copied as numbers so this stays pure: `AudioManager.RINGER_MODE_*` and
 * `NotificationManager.INTERRUPTION_FILTER_*`.
 */
data class DeviceReading(
  val volume: Int? = null,
  val volumeMax: Int? = null,
  val ringerMode: Int? = null,
  val interruptionFilter: Int? = null,
  /** From the camera's torch callback; null until Android reported it. */
  val torch: Boolean? = null,
  /** The media session's playback: true playing, false paused or stopped. */
  val playing: Boolean? = null,
  /** A temporary mode Insto owns holds its applied value now. */
  val temporaryHeld: Boolean? = null,
  /** A temporary mode exists. */
  val temporaryActive: Boolean? = null,
) {
  companion object {
    const val RINGER_SILENT = 0
    const val RINGER_VIBRATE = 1
    const val RINGER_NORMAL = 2
    const val FILTER_UNKNOWN = 0
    const val FILTER_ALL = 1
  }
}

data class Observation(val verdict: OutcomeVerdict, val attribution: ExecutionAttribution? = null, val detail: String = "")

/** Reads the phone (Android) and judges with [ActionOutcomeRules]. */
interface ActionOutcomeVerifier {
  /** What [command] can be checked against, read now; null when nothing about it can be read. */
  fun read(command: PhoneCommand): DeviceReading?

  /** Reads again and judges. [before] was read just before running it; null when it is unknown (a recovery, the panel). */
  fun verify(command: PhoneCommand, before: DeviceReading?, result: PhoneActionResult): Observation =
    ActionOutcomeRules.judge(command, before, runCatching { read(command) }.getOrNull(), result)
}

object ActionOutcomeRules {
  const val HANDED_OFF = "Entregado; Insto no puede comprobar el resultado final."

  fun judge(command: PhoneCommand, before: DeviceReading?, after: DeviceReading?, result: PhoneActionResult): Observation {
    when (result.outcome) {
      PhoneActionOutcome.REJECTED -> return Observation(OutcomeVerdict.NOT_ACHIEVED, detail = result.message)
      PhoneActionOutcome.UNCERTAIN -> return Observation(OutcomeVerdict.INDETERMINATE, detail = result.message)
      PhoneActionOutcome.NEEDS_INPUT -> return Observation(OutcomeVerdict.NOT_ACHIEVED, detail = result.message)
      // Another app accepted it: whether the alarm, the search or the app really did it is not known here. A media
      // session is the exception: when it already reports the requested playback, that is observed.
      PhoneActionOutcome.HANDOFF -> return if (command is PhoneCommand.Media) media(command, before, after)
        .takeIf { it.verdict == OutcomeVerdict.VERIFIED } ?: Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
        else Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
      PhoneActionOutcome.COMPLETED -> {
        if (result.verifiedLocally) return Observation(OutcomeVerdict.VERIFIED, detail = result.message)
      }
    }
    return when (command) {
      is PhoneCommand.Volume -> volume(command, before, after)
      is PhoneCommand.Torch -> state(after?.torch, before?.torch, command.enabled, before != null,
        if (command.enabled) "la linterna quedó encendida" else "la linterna quedó apagada")
      is PhoneCommand.Platform -> platform(command, before, after)
      is PhoneCommand.TemporaryMode -> when (after?.temporaryHeld) {
        true -> Observation(OutcomeVerdict.VERIFIED, ExecutionAttribution.CAUSED, "el modo temporal quedó aplicado")
        false -> Observation(OutcomeVerdict.NOT_ACHIEVED, detail = "el modo temporal no quedó aplicado")
        null -> Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
      }
      PhoneCommand.EndMode -> when (after?.temporaryActive) {
        false -> Observation(OutcomeVerdict.VERIFIED, if (before?.temporaryActive == false) ExecutionAttribution.ALREADY else ExecutionAttribution.CAUSED,
          "no queda ningún modo temporal")
        true -> Observation(OutcomeVerdict.NOT_ACHIEVED, detail = "el modo temporal sigue activo")
        null -> Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
      }
      is PhoneCommand.Media -> media(command, before, after)
      else -> Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
    }
  }

  /** The level [PhoneActionExecutor] sets for [percent]: the same integer arithmetic. */
  fun volumeIndex(max: Int, percent: Int): Int = max * percent.coerceIn(0, 100) / 100

  private fun volume(command: PhoneCommand.Volume, before: DeviceReading?, after: DeviceReading?): Observation {
    val now = after?.volume ?: return Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
    val max = after.volumeMax ?: before?.volumeMax ?: return Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
    val where = "quedó en $now de $max"
    return when (command.operation) {
      VolumeOp.SET -> {
        val expected = volumeIndex(max, command.percent)
        when {
          now == expected -> Observation(OutcomeVerdict.VERIFIED, when {
            before?.volume == null -> ExecutionAttribution.SATISFIED
            before.volume == expected -> ExecutionAttribution.ALREADY
            else -> ExecutionAttribution.CAUSED
          }, where)
          // Android may cap it (the hearing-safety limit with headphones, a fixed volume).
          else -> Observation(OutcomeVerdict.NOT_ACHIEVED, detail = "pedí $expected de $max y $where")
        }
      }
      VolumeOp.UP, VolumeOp.DOWN -> {
        val was = before?.volume ?: return Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
        val up = command.operation == VolumeOp.UP
        val edge = if (up) max else 0
        when {
          if (up) now > was else now < was -> Observation(OutcomeVerdict.VERIFIED, ExecutionAttribution.CAUSED, where)
          now == was && was == edge -> Observation(OutcomeVerdict.VERIFIED, ExecutionAttribution.ALREADY,
            if (up) "ya estaba al máximo" else "ya estaba en cero")
          now == was -> Observation(OutcomeVerdict.NOT_ACHIEVED, detail = "no cambió: $where")
          else -> Observation(OutcomeVerdict.INDETERMINATE, detail = "cambió al revés de lo pedido: $where")
        }
      }
    }
  }

  private fun state(now: Boolean?, was: Boolean?, wanted: Boolean, knewBefore: Boolean, label: String): Observation = when (now) {
    null -> Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
    wanted -> Observation(OutcomeVerdict.VERIFIED, when {
      !knewBefore || was == null -> ExecutionAttribution.SATISFIED
      was == wanted -> ExecutionAttribution.ALREADY
      else -> ExecutionAttribution.CAUSED
    }, label)
    else -> Observation(OutcomeVerdict.NOT_ACHIEVED, detail = "Android informa lo contrario de lo pedido")
  }

  private fun platform(command: PhoneCommand.Platform, before: DeviceReading?, after: DeviceReading?): Observation {
    fun ringer(wanted: Int, label: String): Observation {
      val now = after?.ringerMode ?: return Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
      return state(now == wanted, before?.ringerMode?.let { it == wanted }, true, before?.ringerMode != null, label)
    }
    fun dnd(on: Boolean): Observation {
      val now = after?.interruptionFilter?.takeIf { it != DeviceReading.FILTER_UNKNOWN }
        ?: return Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
      val was = before?.interruptionFilter?.takeIf { it != DeviceReading.FILTER_UNKNOWN }
      // Do not disturb is in effect with any filter but «all»: priority, alarms only or none.
      return state(now != DeviceReading.FILTER_ALL, was?.let { it != DeviceReading.FILTER_ALL }, on, was != null,
        if (on) "No molestar quedó activo" else "No molestar quedó desactivado")
    }
    return when (command.action) {
      PlatformAction.SILENT_ON -> ringer(DeviceReading.RINGER_SILENT, "el teléfono quedó en silencio")
      PlatformAction.SILENT_OFF -> ringer(DeviceReading.RINGER_NORMAL, "el sonido quedó normal")
      PlatformAction.VIBRATE_ON -> ringer(DeviceReading.RINGER_VIBRATE, "el teléfono quedó en vibración")
      PlatformAction.DND_ON -> dnd(true)
      PlatformAction.DND_OFF -> dnd(false)
      // A read: the answer itself is what was asked.
      PlatformAction.BATTERY, PlatformAction.ROUTINE_LIST -> Observation(OutcomeVerdict.VERIFIED, null, "consulta respondida")
      else -> Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
    }
  }

  private fun media(command: PhoneCommand.Media, before: DeviceReading?, after: DeviceReading?): Observation {
    val wanted = when (command.operation) {
      MediaOp.STATUS -> return Observation(OutcomeVerdict.VERIFIED, null, "consulta respondida")
      MediaOp.PLAY -> true
      MediaOp.PAUSE, MediaOp.STOP -> false
      // Next, previous and the like change the track: no reliable reading says that this order did it.
      else -> return Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
    }
    val now = after?.playing ?: return Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = HANDED_OFF)
    if (now != wanted) return Observation(OutcomeVerdict.INDETERMINATE, detail = "la app de música todavía no informó el cambio")
    return Observation(OutcomeVerdict.VERIFIED, when (before?.playing) {
      null -> ExecutionAttribution.SATISFIED
      wanted -> ExecutionAttribution.ALREADY
      else -> ExecutionAttribution.CAUSED
    }, if (wanted) "la música quedó sonando" else "la música quedó en pausa")
  }

  /** The journal state an observation leads to. */
  fun state(observation: Observation): ExecutionState = when (observation.verdict) {
    OutcomeVerdict.VERIFIED -> ExecutionState.VERIFIED
    OutcomeVerdict.NOT_ACHIEVED -> ExecutionState.FAILED
    OutcomeVerdict.NOT_OBSERVABLE -> ExecutionState.HANDED_OFF
    OutcomeVerdict.INDETERMINATE -> ExecutionState.UNCERTAIN
  }

  /**
   * What Instinct hears: [AckKind] keeps its meaning (a handoff is still `ok`, as in 1.19.0), and the text says which
   * one it was: verified, handed off without checking, not achieved, or not confirmed.
   */
  fun ack(order: String, result: PhoneActionResult, observation: Observation): AckEntry {
    val said = result.message.trim().let { if (it.isEmpty() || it.endsWith('.')) it else "$it." }
    return when (observation.verdict) {
      OutcomeVerdict.VERIFIED -> AckEntry(AckKind.OK, order, "$said Verificado: ${observation.detail}" +
        (observation.attribution?.takeIf { it == ExecutionAttribution.ALREADY }?.let { " (${it.label})" }.orEmpty()) + ".")
      OutcomeVerdict.NOT_OBSERVABLE -> AckEntry(AckKind.OK, order, "$said $HANDED_OFF")
      OutcomeVerdict.NOT_ACHIEVED -> AckEntry(AckKind.ERROR, order, "No se logró: ${observation.detail.trimEnd('.')}.")
      OutcomeVerdict.INDETERMINATE -> AckEntry(AckKind.UNCERTAIN, order, "$said No se pudo confirmar: ${observation.detail.trimEnd('.')}. No se repite.")
    }.let { it.copy(message = it.message.trim()) }
  }
}
