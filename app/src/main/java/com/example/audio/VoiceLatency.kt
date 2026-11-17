package com.example.audio

/** Durations only; scoped to the current voice request. Null means that stage was not measured. */
data class VoiceLatency(
  val endpointMs: Long? = null,
  val finalizationMs: Long? = null,
  val transcriptionMs: Long? = null,
  val interpretationMs: Long? = null,
) {
  val totalMs: Long get() = listOfNotNull(endpointMs, finalizationMs, transcriptionMs, interpretationMs).sum()
  fun summary(): String = listOf("Silencio" to endpointMs, "Audio" to finalizationMs,
    "Transcripción" to transcriptionMs, "Interpretación" to interpretationMs)
    .mapNotNull { (stage, duration) -> duration?.let { "$stage: $it ms" } }.joinToString(" · ")
}
