package com.example.alarmvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmVoiceLogicTest {
  private val clock = "com.google.android.deskclock"

  // ---- Detection -------------------------------------------------------------------------------------------------

  @Test fun ringingAlarmOfGoogleClockIsDetected() {
    val shape = ClockNotificationShape(clock, "alarm", listOf("Posponer", "Descartar"), hasFullScreenIntent = true)
    assertEquals(AlarmKind.ALARM, AlarmNotificationDetector.classify(shape))
  }

  @Test fun expiredTimerIsDetectedAsTimer() {
    val shape = ClockNotificationShape(clock, "alarm", listOf("Detener", "+1:00"), hasFullScreenIntent = true)
    assertEquals(AlarmKind.TIMER, AlarmNotificationDetector.classify(shape))
    val byChannel = ClockNotificationShape("com.android.deskclock", null, listOf("Stop"), insistent = true, channelId = "Timers")
    assertEquals(AlarmKind.TIMER, AlarmNotificationDetector.classify(byChannel))
  }

  @Test fun upcomingAlarmAndRunningTimerAreNotRinging() {
    val upcoming = ClockNotificationShape(clock, "alarm", listOf("Descartar ahora"))
    assertNull(AlarmNotificationDetector.classify(upcoming))
    val running = ClockNotificationShape(clock, "alarm", listOf("Pausar", "+1:00", "Restablecer"))
    assertNull(AlarmNotificationDetector.classify(running))
  }

  @Test fun otherAppsAndOtherCategoriesAreIgnored() {
    assertNull(AlarmNotificationDetector.classify(
      ClockNotificationShape("com.whatsapp", "alarm", listOf("Descartar"), hasFullScreenIntent = true)))
    assertNull(AlarmNotificationDetector.classify(
      ClockNotificationShape(clock, "reminder", listOf("Posponer", "Descartar"), hasFullScreenIntent = true)))
  }

  @Test fun motorolaClockPackagesAreKnownButNotOtherMotorolaApps() {
    assertTrue(ClockPackages.isKnown("com.motorola.blur.alarmclock"))
    assertTrue(ClockPackages.isKnown("com.motorola.timeweatherclock"))
    assertFalse(ClockPackages.isKnown("com.motorola.camera3"))
    assertFalse(ClockPackages.isKnown(null))
  }

  // ---- «Detener», never «Posponer» -------------------------------------------------------------------------------

  @Test fun picksStopAndNeverSnooze() {
    assertEquals(1, StopActionPicker.pick(listOf("Posponer", "Descartar")))
    assertEquals(1, StopActionPicker.pick(listOf("SNOOZE", "Dismiss")))
    assertEquals(1, StopActionPicker.pick(listOf("Posponer 10 min", "DETENER")))
    assertEquals(0, StopActionPicker.pick(listOf("Párar", "+1:00")))
    assertEquals(0, StopActionPicker.pick(listOf("Stop timer")))
  }

  @Test fun neverChoosesSnoozeEvenAlone() {
    assertNull(StopActionPicker.pick(listOf("Posponer")))
    assertNull(StopActionPicker.pick(listOf("Snooze", null, "")))
    assertNull(StopActionPicker.pick(listOf("Detener y posponer")))
    assertNull(StopActionPicker.pick(listOf("Descartar ahora")))
    assertFalse(StopActionPicker.isStop("Posponer"))
  }

  // ---- Keywords --------------------------------------------------------------------------------------------------

  @Test fun matchesWholeWordsOnlyIgnoringAccents() {
    val words = KeywordMatcher.DEFAULT_KEYWORDS
    assertEquals("basta", KeywordMatcher.matchText("basta", words))
    assertEquals("pará", KeywordMatcher.matchText("para", words))
    assertEquals("pará", KeywordMatcher.matchText("PARÁ", words))
    assertEquals("listo", KeywordMatcher.matchText("[unk] listo", words))
    assertNull(KeywordMatcher.matchText("bastante", words))
    assertNull(KeywordMatcher.matchText("listos okey", words))
    assertNull(KeywordMatcher.matchText("[unk]", words))
    assertNull(KeywordMatcher.matchText("", words))
  }

  @Test fun lowConfidenceWordsDoNotCount() {
    val words = KeywordMatcher.DEFAULT_KEYWORDS
    assertNull(KeywordMatcher.matchWords(listOf("basta" to 0.3), words))
    assertEquals("ok", KeywordMatcher.matchWords(listOf("[unk]" to 1.0, "ok" to 0.9), words))
  }

  @Test fun grammarHasBothFormsAndUnknown() {
    val grammar = KeywordMatcher.grammar(listOf("Pará", "basta"))
    assertEquals(listOf("pará", "para", "basta", "[unk]"), grammar)
    assertEquals("[\"pará\",\"para\",\"basta\",\"[unk]\"]", KeywordMatcher.grammarJson(listOf("Pará", "basta")))
  }

  @Test fun parsesTypedKeywords() {
    assertEquals(listOf("basta", "chau"), KeywordMatcher.parse(" Basta,  chau ; BASTA, ,"))
  }

  @Test fun partialsNeedTwoAgreeingResults() {
    val confirm = PartialConfirmation(KeywordMatcher.DEFAULT_KEYWORDS)
    assertNull(confirm.onPartial("basta"))
    assertEquals("basta", confirm.onPartial("basta"))
    confirm.reset()
    assertNull(confirm.onPartial("listo"))
    assertNull(confirm.onPartial("[unk]"))
    assertNull(confirm.onPartial("listo"))
  }

  @Test fun duckedVolumeStaysWithinLimits() {
    assertEquals(4, duckedVolume(10, 40, 1))
    assertEquals(1, duckedVolume(1, 40, 1))
    assertEquals(1, duckedVolume(2, 10, 1))
  }
}
