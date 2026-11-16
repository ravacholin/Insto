package com.example.speech

import java.io.File

fun interface AudioTranscriber {
  suspend fun transcribe(file: File): String
}
class TranscriptionException(message: String, val needsKey: Boolean = false) : Exception(message)
