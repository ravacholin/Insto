package com.example.audio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-pointing over real WebRTC VAD decisions. `vad-traces-es.json` holds, per 20 ms frame, the decisions of the
 * WebRTC VAD (py-webrtcvad 2.0.14, the same algorithm the app's library wraps) for 186 simulated sessions built
 * from real Spanish speech (LibriVox, public domain), in four conditions: quiet, low voice (−20 dB), low voice
 * with noise and noise. Only decisions are stored, never audio. Rebuild with scripts/voice/build-vad-traces.py.
 *
 * This is simulated capture: real speech and a real VAD, but not a phone microphone. See docs/VOICE-CAPTURE.md.
 */
class VoiceCaptureTraceTest {
  private data class Session(val kind: String, val condition: String, val speech: List<IntRange>, val expectSend: Boolean,
    val mode2: String, val mode3: String)

  private val sessions: List<Session> by lazy {
    val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/vad-traces-es.json")).bufferedReader().use { it.readText() })
    val items = json.getJSONArray("sessions")
    (0 until items.length()).map { index ->
      val item = items.getJSONObject(index)
      val spans = item.getJSONArray("speech")
      Session(item.getString("kind"), item.getString("condition"),
        (0 until spans.length()).map { spans.getJSONArray(it).let { span -> span.getInt(0)..span.getInt(1) } },
        item.getBoolean("expectSend"), item.getString("mode2"), item.getString("mode3"))
    }
  }

  /** Frame index of the first SEND, or null. */
  private fun current(decisions: String): Int? {
    val policy = VoiceSessionPolicy()
    decisions.forEachIndexed { index, c ->
      when (policy.update(index * VoiceSessionPolicy.FRAME_MS, c == '1')) {
        VoiceSessionPolicy.State.SEND -> return index
        VoiceSessionPolicy.State.IDLE -> return null
        else -> Unit
      }
    }
    return null
  }

  /**
   * Insto 1.12.0 for comparison only: VERY_AGGRESSIVE decisions smoothed by android-vad 2.0.10
   * (speechDurationMs = 60, silenceDurationMs = 0, transcribed from its bytecode), then 900/1600/2400 ms.
   */
  private fun legacy(decisions: String): Int? {
    var speechFrames = 0; var silenceFrames = 0
    var first: Long? = null; var last = 0L; var recording = false
    decisions.forEachIndexed { index, c ->
      val smoothed = if (c == '1') {
        if (speechFrames <= 3) speechFrames++
        if (speechFrames > 3) { silenceFrames = 0; true } else false
      } else {
        if (silenceFrames <= 0) silenceFrames++
        speechFrames = 0; false
      }
      val t = index * 20L
      if (smoothed) { if (first == null) first = t; last = t; recording = true }
      if (!recording && t >= 4_000) return null
      val span = last - (first ?: last)
      val trailing = if (span < 4_000) 900L else if (span < 12_000) 1_600L else 2_400L
      if (recording && t - last >= trailing) return index
    }
    return null
  }

  private data class Outcome(val cuts: Map<String, Int>, val dropped: Int, val noiseSends: Map<String, Int>,
    val silenceSends: Int, val latency: Map<String, List<Long>>)

  private fun evaluate(policy: (Session) -> Int?): Outcome {
    val cuts = mutableMapOf<String, Int>(); val noise = mutableMapOf<String, Int>(); val latency = mutableMapOf<String, MutableList<Long>>()
    var dropped = 0; var silence = 0
    for (session in sessions) {
      val send = policy(session)
      val group = session.kind.substringBefore('_')
      if (session.expectSend) {
        val end = session.speech.last().last
        when {
          send == null -> dropped++
          send <= end -> cuts.merge(session.kind, 1, Int::plus)
          else -> latency.getOrPut(group) { mutableListOf() } += (send - end) * VoiceSessionPolicy.FRAME_MS
        }
      } else if (send != null) {
        if (session.kind.startsWith("noise_")) noise.merge(session.kind.removePrefix("noise_"), 1, Int::plus) else silence++
      }
    }
    return Outcome(cuts, dropped, noise, silence, latency)
  }

  private fun List<Long>.p(fraction: Double) = sorted()[((size - 1) * fraction).toInt()]

  private fun report(name: String, outcome: Outcome) {
    val speech = sessions.count { it.expectSend }
    val noise = sessions.count { it.kind.startsWith("noise_") }
    println("VOICE_METRICS policy=%s speech_sessions=%d premature_cuts=%d cuts_by_kind=%s dropped=%d noise_sends=%d/%d noise_by_kind=%s silence_sends=%d %s".format(
      name, speech, outcome.cuts.values.sum(), outcome.cuts.toSortedMap(), outcome.dropped, outcome.noiseSends.values.sum(), noise,
      outcome.noiseSends.toSortedMap(), outcome.silenceSends,
      outcome.latency.toSortedMap().entries.joinToString(" ") { (kind, values) -> "end_latency_${kind}_p50=${values.p(.5)} p95=${values.p(.95)}" }))
  }

  @Test fun theCalibratedPolicyDoesNotCutCommandsNorRecordBriefNoise() {
    val after = evaluate { current(it.mode2) }
    val before = evaluate { legacy(it.mode3) }
    report("1.12.0", before)
    report("adaptive", after)
    // Brief and long commands and 600 ms hesitations, in every condition: never cut, never dropped.
    for (kind in listOf("short", "long", "pause600")) assertEquals(kind, null, after.cuts[kind])
    assertEquals(0, after.dropped)
    // Silence and single brief noises (clicks, a knock) never produce a recording.
    assertEquals(0, after.silenceSends)
    assertEquals(null, after.noiseSends["click"])
    assertEquals(null, after.noiseSends["knock"])
    // It does not trade those for a slower system: fewer cuts and fewer noise recordings than before overall.
    assertTrue(after.cuts.values.sum() < before.cuts.values.sum())
    assertTrue(after.noiseSends.values.sum() < before.noiseSends.values.sum())
    // A short command still ends about a second after the voice: median at most the short trailing silence.
    assertTrue(after.latency.getValue("short").p(.5) <= VoiceSessionPolicy.SHORT_TRAILING_MS)
  }

  /**
   * First syllables: the recording keeps audio from the pre-roll before the first positive decision. Counts speech
   * sessions whose first positive decision came later than that pre-roll after the real start of speech.
   */
  @Test fun thePrerollCoversTheMeasuredOnsetLag() {
    fun clipped(preroll: Long, firstPositive: (Session) -> Int?) = sessions.filter { it.expectSend }.count { session ->
      val start = session.speech.first().first
      val first = firstPositive(session) ?: return@count true
      (first - start) * VoiceSessionPolicy.FRAME_MS > preroll
    }
    val before = clipped(300) { session ->
      var run = 0
      session.mode3.withIndex().firstOrNull { (index, c) ->
        run = if (c == '1') run + 1 else 0
        index >= session.speech.first().first - 5 && run >= 4
      }?.index
    }
    val after = clipped(VoiceSessionPolicy.PREROLL_MS) { session ->
      session.mode2.withIndex().firstOrNull { (index, c) -> c == '1' && index >= session.speech.first().first - 5 }?.index
    }
    println("ONSET_METRICS speech_sessions=%d before_clipped=%d after_clipped=%d".format(sessions.count { it.expectSend }, before, after))
    assertEquals(0, after)
  }

  @Test fun theFixtureCoversEveryRequestedScenario() {
    val kinds = sessions.map { it.kind }.toSet()
    for (kind in listOf("short", "long", "pause600", "pause900", "pause1200", "silence", "noise_click", "noise_knock", "noise_beep"))
      assertTrue(kind, kind in kinds)
    assertEquals(setOf("clean", "soft", "soft_noisy", "noisy"), sessions.filter { it.expectSend }.map { it.condition }.toSet())
  }
}
