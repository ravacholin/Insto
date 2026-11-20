package com.example.speech

import com.example.phone.FakeHttp
import com.example.phone.ProviderHttp
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GroqTranscriberTest {
  private fun ok(text: String) = FakeHttp.json(200, """{"text":"$text"}""")

  /** Minimal multipart reader: name -> (headers, content). Enough to check what Groq receives. */
  private fun parts(exchange: FakeHttp.Exchange): Map<String, Pair<String, ByteArray>> {
    val type = exchange.request.body!!.contentType()!!
    assertEquals("multipart", type.type)
    assertEquals("form-data", type.subtype)
    val boundary = "--" + type.parameter("boundary")!!
    val raw = exchange.body.toString(Charsets.ISO_8859_1)
    assertTrue(raw.endsWith("$boundary--\r\n"))
    return raw.split(boundary).drop(1).dropLast(1).associate { chunk ->
      val section = chunk.removePrefix("\r\n").removeSuffix("\r\n")
      val split = section.indexOf("\r\n\r\n")
      val headers = section.substring(0, split)
      val name = Regex("name=\"([^\"]+)\"").find(headers)!!.groupValues[1]
      name to (headers to section.substring(split + 4).toByteArray(Charsets.ISO_8859_1))
    }
  }

  @Test fun uploadsOnlyNonBlankPrompts() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a").apply { writeText("audio") }
    try {
      val hint = "Podés decir: Instinct, Café."
      for (value in listOf(hint, null, "", "   ")) {
        val http = ok("Abrí Café")
        val transcriber = GroqTranscriber({ "personal-key" }, http, prompt = { value })
        assertEquals("Abrí Café", transcriber.transcribe(file))
        val fields = parts(http.last)
        if (value.isNullOrBlank()) assertFalse("prompt" in fields)
        else assertEquals(value, fields.getValue("prompt").second.toString(Charsets.UTF_8))
      }
    } finally { file.delete() }
  }

  @Test fun originalAudioAndSpanishModelAreUploadedAsStandardMultipart() = runBlocking {
    val audio = byteArrayOf(0, 1, 2, 3, 127, -1, 13, 10)
    val file = File.createTempFile("test-audio", ".m4a").apply { writeBytes(audio) }
    try {
      val http = ok("Poné diez minutos")
      assertEquals("Poné diez minutos", GroqTranscriber({ "personal-key" }, http).transcribe(file))
      val exchange = http.last
      assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", exchange.url)
      assertEquals("Bearer personal-key", exchange.header("Authorization"))
      val fields = parts(exchange)
      assertEquals(listOf("model", "language", "response_format", "temperature", "file"), fields.keys.toList())
      assertEquals("whisper-large-v3-turbo", fields.getValue("model").second.toString(Charsets.UTF_8))
      assertEquals("es", fields.getValue("language").second.toString(Charsets.UTF_8))
      assertEquals("json", fields.getValue("response_format").second.toString(Charsets.UTF_8))
      assertEquals("0", fields.getValue("temperature").second.toString(Charsets.UTF_8))
      val (headers, content) = fields.getValue("file")
      assertTrue(headers.contains("filename=\"recording.m4a\""))
      assertTrue(headers.contains("Content-Type: audio/mp4"))
      assertArrayEquals(audio, content)
      assertArrayEquals(audio, file.readBytes())
      assertFalse(exchange.bodyText().contains("personal-key"))
      // Upload limits are finite: per write, per read and for the whole call.
      assertEquals(ProviderHttp.Limits.UPLOAD.writeMillis, exchange.writeMillis)
      assertEquals(ProviderHttp.Limits.UPLOAD.readMillis, exchange.readMillis)
      assertEquals(ProviderHttp.Limits.UPLOAD.connectMillis, exchange.connectMillis)
      assertTrue(exchange.request.body!!.isOneShot())
      assertTrue(exchange.request.body!!.contentLength() > file.length())
    } finally { file.delete() }
  }
  @Test fun errorsNeverExposeProviderBodyOrKeyAndAreNeverRetried() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a").apply { writeText("audio") }
    try {
      for ((code, needsKey) in listOf(401 to true, 403 to true, 429 to false, 500 to false, 503 to false)) {
        val http = FakeHttp.json(code, "personal-key secret-provider-body")
        try { GroqTranscriber({ "personal-key" }, http).transcribe(file); fail("Must fail") }
        catch (e: TranscriptionException) {
          assertFalse(e.message.orEmpty().contains("personal-key"))
          assertFalse(e.message.orEmpty().contains("secret-provider-body"))
          assertEquals(needsKey, e.needsKey)
          assertEquals(1, http.exchanges.size)
          if (code == 429) assertTrue(e.message!!.contains("cuota"))
        }
      }
      assertEquals("audio", file.readText())
    } finally { file.delete() }
  }
  @Test fun malformedOrOversizedResponsesKeepTheAudio() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a").apply { writeText("audio") }
    try {
      for (body in listOf("not-json", "{\"text\":\"" + "x".repeat(300 * 1024) + "\"}")) {
        try { GroqTranscriber({ "personal-key" }, FakeHttp.json(200, body)).transcribe(file); fail("Must fail") }
        catch (e: TranscriptionException) { assertTrue(e.message!!.contains("el audio se conserva")) }
      }
      assertEquals("audio", file.readText())
    } finally { file.delete() }
  }
  @Test fun cancellationDuringUploadCancelsTheCallAndKeepsTheAudio() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a").apply { writeText("audio") }
    try {
      val entered = java.util.concurrent.CountDownLatch(1)
      val http = FakeHttp { entered.countDown(); it.awaitCancel() }
      val job = async(Dispatchers.Default) { GroqTranscriber({ "personal-key" }, http).transcribe(file) }
      assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
      job.cancelAndJoin()
      assertTrue(job.isCancelled)
      assertTrue(http.last.isCanceled)
      assertEquals(1, http.exchanges.size)
      assertEquals("audio", file.readText())
    } finally { file.delete() }
  }
  @Test fun emptyRecognitionDoesNotBecomeAGeneralQuery() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a").apply { writeText("audio") }
    try {
      try { GroqTranscriber({ "personal-key" }, FakeHttp.json(200, "{\"text\":\"\"}")).transcribe(file); fail("Must fail") }
      catch (e: TranscriptionException) { assertEquals("audio", file.readText()) }
    } finally { file.delete() }
  }
  @Test fun oversizedInputDoesNotUpload() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a")
    try {
      java.io.RandomAccessFile(file, "rw").use { it.setLength(24L * 1024 * 1024 + 1) }
      try { GroqTranscriber({ "personal-key" }, FakeHttp { error("Must not connect") }).transcribe(file); fail("Must fail") }
      catch (e: TranscriptionException) { assertFalse(e.needsKey) }
    } finally { file.delete() }
  }
  @Test fun missingKeyDoesNotUpload() = runBlocking {
    val file = File.createTempFile("test-audio", ".m4a").apply { writeText("audio") }
    try {
      try { GroqTranscriber({ null }, FakeHttp { error("Must not connect") }).transcribe(file); fail("Must fail") }
      catch (e: TranscriptionException) { assertTrue(e.needsKey) }
    } finally { file.delete() }
  }
}
