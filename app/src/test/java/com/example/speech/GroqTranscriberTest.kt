package com.example.speech

import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GroqTranscriberTest {
  private class Connection(val code: Int, val body: String) : HttpURLConnection(URL("https://api.groq.com")) {
    val output = ByteArrayOutputStream()
    override fun connect() {}
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun getResponseCode() = code
    override fun getInputStream() = body.byteInputStream()
    override fun getOutputStream() = output
  }
  @Test fun uploadsOnlyNonBlankPrompts() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a").apply { writeText("audio") }
    try {
      val hint = "Podés decir: Instinct, Café."
      for (value in listOf(hint, null, "", "   ")) {
        val connection = Connection(200, """{"text":"Abrí Café"}""")
        val transcriber = GroqTranscriber({ "personal-key" }, { connection }, prompt = { value })
        assertEquals("Abrí Café", transcriber.transcribe(file))
        val body = connection.output.toString("UTF-8")
        val header = "name=\"prompt\""
        if (value.isNullOrBlank()) {
          assertFalse(body.contains(header))
        } else {
          assertTrue(body.contains("$header\r\n\r\n$value\r\n"))
        }
      }
    } finally { file.delete() }
  }

  @Test fun originalAudioAndSpanishModelAreUploaded() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 127)) }
    try {
      val c = Connection(200, "{\"text\":\"Poné diez minutos\"}")
      assertEquals("Poné diez minutos", GroqTranscriber({ "personal-key" }, { c }).transcribe(file))
      val body = c.output.toByteArray().toString(Charsets.ISO_8859_1)
      assertTrue(body.contains("whisper-large-v3-turbo"))
      assertTrue(body.contains("name=\"language\"\r\n\r\nes"))
      assertTrue(body.contains(file.readBytes().toString(Charsets.ISO_8859_1)))
      assertArrayEquals(byteArrayOf(0, 1, 2, 3, 127), file.readBytes())
    } finally { file.delete() }
  }
  @Test fun errorsNeverExposeProviderBodyOrKey() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a").apply { writeText("audio") }
    try {
      for (code in listOf(401, 429, 500)) {
        val c = Connection(code, "personal-key secret-provider-body")
        try { GroqTranscriber({ "personal-key" }, { c }).transcribe(file); fail("Must fail") }
        catch (e: TranscriptionException) {
          assertFalse(e.message.orEmpty().contains("personal-key"))
          assertFalse(e.message.orEmpty().contains("secret-provider-body"))
        }
      }
    } finally { file.delete() }
  }
  @Test fun emptyRecognitionDoesNotBecomeAGeneralQuery() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a").apply { writeText("audio") }
    try {
      try { GroqTranscriber({ "personal-key" }, { Connection(200, "{\"text\":\"\"}") }).transcribe(file); fail("Must fail") }
      catch (e: TranscriptionException) { assertEquals("audio", file.readText()) }
    } finally { file.delete() }
  }
  @Test fun oversizedInputDoesNotUpload() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a")
    try {
      java.io.RandomAccessFile(file, "rw").use { it.setLength(24L * 1024 * 1024 + 1) }
      try { GroqTranscriber({ "personal-key" }, { error("Must not connect") }).transcribe(file); fail("Must fail") }
      catch (e: TranscriptionException) { assertFalse(e.needsKey) }
    } finally { file.delete() }
  }
  @Test fun missingKeyDoesNotUpload() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a").apply { writeText("audio") }
    try {
      try { GroqTranscriber({ null }, { error("Must not connect") }).transcribe(file); fail("Must fail") }
      catch (e: TranscriptionException) { assertTrue(e.needsKey) }
    } finally { file.delete() }
  }
}
