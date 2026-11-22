package com.example.phone

import org.junit.Assert.*
import org.junit.Test

/** What counts as a verified result (1.20.0), command by command. */
class ActionOutcomeTest {
  private val done = PhoneActionResult("Hecho.")
  private val handoff = PhoneActionResult("Solicitud enviada al Reloj.", outcome = PhoneActionOutcome.HANDOFF)
  private fun vol(v: Int, max: Int = 15) = DeviceReading(volume = v, volumeMax = max)

  @Test fun `a volume set is verified only at the exact discrete level, and says whether it was already there`() {
    val set40 = PhoneCommand.Volume(VolumeOp.SET, 40)
    assertEquals(6, ActionOutcomeRules.volumeIndex(15, 40))
    val caused = ActionOutcomeRules.judge(set40, vol(0), vol(6), done)
    assertEquals(OutcomeVerdict.VERIFIED, caused.verdict)
    assertEquals(ExecutionAttribution.CAUSED, caused.attribution)
    assertEquals(ExecutionAttribution.ALREADY, ActionOutcomeRules.judge(set40, vol(6), vol(6), done).attribution)
    assertEquals(ExecutionAttribution.SATISFIED, ActionOutcomeRules.judge(set40, null, vol(6), done).attribution)
    // Capped by Android (hearing safety): not achieved, never «done».
    val capped = ActionOutcomeRules.judge(PhoneCommand.Volume(VolumeOp.SET, 100), vol(5), vol(10), done)
    assertEquals(OutcomeVerdict.NOT_ACHIEVED, capped.verdict)
    assertTrue(capped.detail, capped.detail.contains("15") && capped.detail.contains("10"))
  }

  @Test fun `a relative step needs the reading before and accepts the edge`() {
    val up = PhoneCommand.Volume(VolumeOp.UP)
    assertEquals(OutcomeVerdict.VERIFIED, ActionOutcomeRules.judge(up, vol(3), vol(4), done).verdict)
    assertEquals(ExecutionAttribution.ALREADY, ActionOutcomeRules.judge(up, vol(15), vol(15), done).attribution)
    assertEquals(OutcomeVerdict.NOT_ACHIEVED, ActionOutcomeRules.judge(up, vol(3), vol(3), done).verdict)
    assertEquals(OutcomeVerdict.INDETERMINATE, ActionOutcomeRules.judge(up, vol(3), vol(2), done).verdict)
    assertEquals(OutcomeVerdict.NOT_OBSERVABLE, ActionOutcomeRules.judge(up, null, vol(4), done).verdict)
    assertEquals(OutcomeVerdict.VERIFIED, ActionOutcomeRules.judge(PhoneCommand.Volume(VolumeOp.DOWN), vol(0), vol(0), done).verdict)
  }

  @Test fun `silence, vibration and Do not disturb are judged by the effective state`() {
    val silent = PhoneCommand.Platform(PlatformAction.SILENT_ON)
    assertEquals(OutcomeVerdict.VERIFIED, ActionOutcomeRules.judge(silent, DeviceReading(ringerMode = 2), DeviceReading(ringerMode = 0), done).verdict)
    assertEquals(OutcomeVerdict.NOT_ACHIEVED, ActionOutcomeRules.judge(silent, null, DeviceReading(ringerMode = 1), done).verdict)
    val vibrate = PhoneCommand.Platform(PlatformAction.VIBRATE_ON)
    assertEquals(ExecutionAttribution.ALREADY, ActionOutcomeRules.judge(vibrate, DeviceReading(ringerMode = 1), DeviceReading(ringerMode = 1), done).attribution)
    val dnd = PhoneCommand.Platform(PlatformAction.DND_ON)
    // Priority, alarms only or none: all mean Do not disturb is on.
    for (filter in listOf(2, 3, 4)) assertEquals(OutcomeVerdict.VERIFIED,
      ActionOutcomeRules.judge(dnd, DeviceReading(interruptionFilter = 1), DeviceReading(interruptionFilter = filter), done).verdict)
    assertEquals(OutcomeVerdict.NOT_ACHIEVED, ActionOutcomeRules.judge(dnd, null, DeviceReading(interruptionFilter = 1), done).verdict)
    assertEquals(OutcomeVerdict.NOT_OBSERVABLE, ActionOutcomeRules.judge(dnd, null, DeviceReading(interruptionFilter = 0), done).verdict)
    val off = PhoneCommand.Platform(PlatformAction.DND_OFF)
    assertEquals(OutcomeVerdict.VERIFIED, ActionOutcomeRules.judge(off, DeviceReading(interruptionFilter = 2), DeviceReading(interruptionFilter = 1), done).verdict)
  }

  @Test fun `the torch is verified only once the camera reported it`() {
    val on = PhoneCommand.Torch(true)
    assertEquals(OutcomeVerdict.VERIFIED, ActionOutcomeRules.judge(on, DeviceReading(torch = false), DeviceReading(torch = true), done).verdict)
    assertEquals(OutcomeVerdict.NOT_OBSERVABLE, ActionOutcomeRules.judge(on, null, DeviceReading(torch = null), done).verdict)
    assertEquals(OutcomeVerdict.NOT_ACHIEVED, ActionOutcomeRules.judge(on, null, DeviceReading(torch = false), done).verdict)
  }

  @Test fun `handing an alarm, a timer, a search or an app to another app is never verified`() {
    for (command in listOf(PhoneCommand.Alarm(7, 0), PhoneCommand.Timer(60), PhoneCommand.OpenApp("Spotify"),
      PhoneCommand.YouTubeSearch("lluvia"), PhoneCommand.Platform(PlatformAction.CALENDAR_AGENDA))) {
      val o = ActionOutcomeRules.judge(command, null, DeviceReading(), handoff)
      assertEquals("$command", OutcomeVerdict.NOT_OBSERVABLE, o.verdict)
      assertEquals(ExecutionState.HANDED_OFF, ActionOutcomeRules.state(o))
      val ack = ActionOutcomeRules.ack("poner una alarma a las 7", handoff, o)
      assertEquals(AckKind.OK, ack.kind)
      assertFalse(ack.message, ack.message.contains("Verificado"))
      assertTrue(ack.message, ack.message.contains("no puede comprobar"))
    }
  }

  @Test fun `media playback is verified when the session reports it, otherwise only handed off`() {
    val play = PhoneCommand.Media(MediaOp.PLAY)
    val sent = PhoneActionResult("Orden de reanudación enviada.", outcome = PhoneActionOutcome.HANDOFF)
    assertEquals(OutcomeVerdict.VERIFIED, ActionOutcomeRules.judge(play, DeviceReading(playing = false), DeviceReading(playing = true), sent).verdict)
    assertEquals(OutcomeVerdict.NOT_OBSERVABLE, ActionOutcomeRules.judge(play, DeviceReading(playing = false), DeviceReading(playing = false), sent).verdict)
    assertEquals(OutcomeVerdict.NOT_OBSERVABLE, ActionOutcomeRules.judge(PhoneCommand.Media(MediaOp.NEXT), null, DeviceReading(playing = true), sent).verdict)
  }

  @Test fun `a temporary mode is verified while Insto holds it, and ending it when none is left`() {
    val mode = PhoneCommand.TemporaryMode(ModeKind.SILENT, 3600)
    assertEquals(OutcomeVerdict.VERIFIED, ActionOutcomeRules.judge(mode, null, DeviceReading(temporaryHeld = true, temporaryActive = true), done).verdict)
    assertEquals(OutcomeVerdict.NOT_ACHIEVED, ActionOutcomeRules.judge(mode, null, DeviceReading(temporaryHeld = false, temporaryActive = true), done).verdict)
    assertEquals(OutcomeVerdict.VERIFIED, ActionOutcomeRules.judge(PhoneCommand.EndMode, DeviceReading(temporaryActive = true),
      DeviceReading(temporaryActive = false), done).verdict)
  }

  @Test fun `refused and uncertain results keep their meaning and map to the journal`() {
    val refused = ActionOutcomeRules.judge(PhoneCommand.Torch(true), null, null, PhoneActionResult("No.", outcome = PhoneActionOutcome.REJECTED))
    assertEquals(ExecutionState.FAILED, ActionOutcomeRules.state(refused))
    val maybe = ActionOutcomeRules.judge(PhoneCommand.Torch(true), null, null, PhoneActionResult("Tal vez.", outcome = PhoneActionOutcome.UNCERTAIN))
    assertEquals(ExecutionState.UNCERTAIN, ActionOutcomeRules.state(maybe))
    assertEquals(AckKind.UNCERTAIN, ActionOutcomeRules.ack("x", done, maybe).kind)
  }

  @Test fun `a verified acknowledgement says so and passes the log's guard`() {
    val o = ActionOutcomeRules.judge(PhoneCommand.Volume(VolumeOp.SET, 40), vol(0), vol(6), PhoneActionResult("Volumen multimedia ajustado."))
    val ack = ActionOutcomeRules.ack("poner el volumen al 40 por ciento", PhoneActionResult("Volumen multimedia ajustado."), o)
    assertEquals("Volumen multimedia ajustado. Verificado: quedó en 6 de 15.", ack.message)
    assertTrue(InstinctAck.safe(ack.message))
    val already = ActionOutcomeRules.ack("x", done, ActionOutcomeRules.judge(PhoneCommand.Volume(VolumeOp.SET, 40), vol(6), vol(6), done))
    assertTrue(already.message, already.message.contains("ya estaba así"))
  }
}
