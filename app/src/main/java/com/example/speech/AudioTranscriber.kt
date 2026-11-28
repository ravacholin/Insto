package com.example.speech

import java.io.File

fun interface AudioTranscriber {
  suspend fun transcribe(file: File): String
}
class TranscriptionException(message: String, val needsKey: Boolean = false) : Exception(message) {
  internal var healthCode: com.example.phone.HealthCode = com.example.phone.HealthCode.INTERPRETATION
    private set

  internal constructor(message: String, needsKey: Boolean, code: com.example.phone.HealthCode) : this(message, needsKey) {
    healthCode = code
  }
}
