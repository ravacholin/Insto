package com.example.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Session lifecycle of the floating capture with a scripted microphone, a scripted VAD and a frame-recording
 * writer, on a virtual clock of 20 ms per frame. The real AudioRecord, WebRTC VAD and AAC encoder need a device.
 */
class FloatingVoiceCaptureTest {
  private val dir: File = Files.createTempDirectory("insto-voice").toFile()
  private val clock = AtomicLong(0)
  private val inputs = mutableListOf<ScriptedInput>()
  private var detectorsClosed = 0

  @After fun cleanUp() { dir.deleteRecursively() }

  /** Frame i is speech when [script] says so; after the script it blocks like a live microphone, or keeps going. */
  private inner class ScriptedInput(
    private val script: List<Boolean>,
    private val errorAt: Int? = null,
    private val after: Boolean? = null,
    private val silencedAt: Int? = null,
  ) : CaptureInput {
    @Volatile var stopped = false
    @Volatile var released = false
    @Volatile private var index = 0
    private val lock = Object()
    override val silenced: Boolean get() = silencedAt != null && index > silencedAt
    override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
      if (stopped) return -3
      if (index == errorAt) return -3
      val speech = script.getOrNull(index) ?: after ?: run {
        synchronized(lock) { while (!stopped) lock.wait(20) }
        return -3
      }
      if (index >= script.size) Thread.sleep(1)
      buffer[offset] = if (speech) 1 else 0
      buffer[offset + 1] = index.toShort()
      index++
      clock.addAndGet(VoiceSessionPolicy.FRAME_MS)
      return size
    }
    override fun stop() { synchronized(lock) { stopped = true; lock.notifyAll() } }
    override fun release() { released = true }
  }

  private fun capture(vararg next: ScriptedInput) = FloatingVoiceCapture(
    directory = { dir },
    openInput = { next[inputs.size].also { inputs += it } },
    newDetector = { object : SpeechDetector {
      override fun isSpeech(frame: ShortArray) = frame[0] == 1.toShort()
      override fun close() { detectorsClosed++ }
    } },
    newWriter = { file -> object : FrameWriter {
      private val out = DataOutputStream(FileOutputStream(file))
      override fun write(frame: ShortArray) = out.writeShort(frame[1].toInt())
      override fun close() = out.close()
    } },
    clock = clock::get,
  )

  private fun frames(file: File): List<Int> = DataInputStream(file.inputStream()).use { input ->
    (0 until (file.length() / 2).toInt()).map { input.readShort().toInt() }
  }

  private fun script(vararg parts: Pair<Int, Boolean>) = parts.flatMap { (count, speech) -> List(count) { speech } }

  private fun awaitState(capture: FloatingVoiceCapture, vararg states: VoiceSessionPolicy.State) {
    val deadline = System.currentTimeMillis() + 5_000
    while (capture.snapshot.state !in states && capture.snapshot.error == null) {
      check(System.currentTimeMillis() < deadline) { "Timed out in ${capture.snapshot}" }
      Thread.sleep(2)
    }
  }

  private fun awaitTrue(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition()) { check(System.currentTimeMillis() < deadline) { what }; Thread.sleep(2) }
  }

  @Test fun aShortOrderEndsByItselfAndKeepsItsFirstSyllables() {
    // 1 s of silence, 0.8 s of speech, then silence.
    val capture = capture(ScriptedInput(script(50 to false, 40 to true, 200 to false)))
    assertTrue(capture.start().isSuccess)
    awaitState(capture, VoiceSessionPolicy.State.SEND)
    val audio = checkNotNull(capture.finish()) { "audio expected" }
    val written = frames(audio.file)
    // Pre-roll of 500 ms (25 frames) before the first voiced frame, then every frame up to the send.
    assertEquals(50 - 25, written.first())
    assertEquals((written.first()..written.last()).toList(), written)
    assertEquals(89 + (VoiceSessionPolicy.SHORT_TRAILING_MS / VoiceSessionPolicy.FRAME_MS).toInt(), written.last())
    assertTrue(inputs.single().released)
    assertEquals(1, detectorsClosed)
    audio.file.delete()
  }

  @Test fun briefNoiseAndInitialSilenceLeaveNoRecordingNorFile() {
    for (script in listOf(script(20 to false, 3 to true, 300 to false), script(300 to false))) {
      inputs.clear()
      val capture = capture(ScriptedInput(script))
      assertTrue(capture.start().isSuccess)
      awaitState(capture, VoiceSessionPolicy.State.IDLE)
      assertNull(capture.finish())
      assertTrue(inputs.single().released)
      assertEquals(emptyList<String>(), dir.list()!!.toList())
    }
  }

  @Test fun cancellingDuringCaptureReleasesTheMicrophoneAndDeletesTheFile() {
    val capture = capture(ScriptedInput(script(10 to false), after = true))
    assertTrue(capture.start().isSuccess)
    awaitState(capture, VoiceSessionPolicy.State.RECORDING)
    capture.cancel()
    assertTrue(inputs.single().stopped)
    awaitTrue("released and deleted") { inputs.single().released && dir.list()!!.isEmpty() }
    assertNull(capture.finish())
  }

  @Test fun aSecondSessionDiscardsTheFirstOneCompletely() {
    val first = ScriptedInput(script(10 to false), after = true)
    val second = ScriptedInput(script(10 to false, 30 to true, 200 to false))
    val capture = capture(first, second)
    assertTrue(capture.start().isSuccess)
    awaitState(capture, VoiceSessionPolicy.State.RECORDING)
    assertTrue(capture.start().isSuccess)
    assertTrue(first.stopped)
    awaitTrue("first released") { first.released }
    awaitState(capture, VoiceSessionPolicy.State.SEND)
    val audio = checkNotNull(capture.finish()) { "audio expected" }
    awaitTrue("only the second file remains") { dir.list()!!.toList() == listOf(audio.file.name) }
    assertTrue(second.released)
    assertEquals(2, detectorsClosed)
    audio.file.delete()
  }

  @Test fun aMicrophoneErrorKeepsWhatWasAlreadySaid() {
    val capture = capture(ScriptedInput(script(10 to false, 40 to true), errorAt = 50))
    assertTrue(capture.start().isSuccess)
    awaitTrue("error reported") { capture.snapshot.error != null }
    assertTrue(capture.snapshot.error!!.contains("micrófono"))
    val audio = checkNotNull(capture.finish()) { "audio expected" }
    assertEquals((0..49).toList(), frames(audio.file))
    assertTrue(inputs.single().released)
    audio.file.delete()
  }

  @Test fun anErrorOrAManualSendWhileConfirmingKeepsTheCandidateSpeech() {
    // Speech still being confirmed (3 voiced frames) when the microphone fails.
    val failing = capture(ScriptedInput(script(30 to false, 3 to true), errorAt = 33))
    assertTrue(failing.start().isSuccess)
    awaitTrue("error reported") { failing.snapshot.error != null }
    val kept = checkNotNull(failing.finish()) { "audio expected" }
    assertEquals((30 - 25..32).toList(), frames(kept.file))
    kept.file.delete()
    // The user taps send while the first frames are being confirmed.
    inputs.clear()
    val manual = capture(ScriptedInput(script(30 to false, 3 to true)))
    assertTrue(manual.start().isSuccess)
    awaitTrue("frames read") { manual.snapshot.elapsedMs >= 33 * VoiceSessionPolicy.FRAME_MS }
    val sent = checkNotNull(manual.finish()) { "audio expected" }
    assertEquals((30 - 25..32).toList(), frames(sent.file))
    sent.file.delete()
  }

  @Test fun androidSilencingTheMicrophoneIsAnErrorThatKeepsTheAudio() {
    val capture = capture(ScriptedInput(script(10 to false, 40 to true), after = true, silencedAt = 45))
    assertTrue(capture.start().isSuccess)
    awaitTrue("error reported") { capture.snapshot.error != null }
    assertTrue(capture.snapshot.error!!.contains("silenció"))
    val audio = checkNotNull(capture.finish()) { "audio expected" }
    assertTrue(frames(audio.file).isNotEmpty())
    audio.file.delete()
  }

  @Test fun aFailedStartReleasesTheMicrophone() {
    val input = ScriptedInput(script(10 to false))
    val capture = FloatingVoiceCapture(directory = { File(dir, "missing/\u0000invalid") }, openInput = { input.also { inputs += it } },
      newDetector = { object : SpeechDetector { override fun isSpeech(frame: ShortArray) = false; override fun close() { detectorsClosed++ } } },
      newWriter = { error("never") }, clock = clock::get)
    assertTrue(capture.start().isFailure)
    awaitTrue("released") { input.released }
    assertEquals(1, detectorsClosed)
  }
}
