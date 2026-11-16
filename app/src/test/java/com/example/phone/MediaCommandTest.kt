package com.example.phone
import org.junit.Assert.*
import org.junit.Test
class MediaCommandTest {
 @Test fun mediaCommandsTargetTransportNotSearch() {
  val p = PhoneCommandParser()
  assertEquals(PhoneRoute.Local(PhoneCommand.Media(MediaOp.PAUSE)), p.parse("Pausá la música"))
  assertEquals(PhoneRoute.Local(PhoneCommand.Media(MediaOp.NEXT)), p.parse("Pasá al siguiente tema"))
  assertEquals(PhoneRoute.Local(PhoneCommand.Media(MediaOp.PLAY)), p.parse("Continuá la música"))
 }
 @Test fun temporaryRequestsKeepDurationAndChannel() {
  val p = PhoneCommandParser()
  assertEquals(PhoneRoute.Local(PhoneCommand.TemporaryMode(ModeKind.SILENT, 3600)), p.parse("Silenciá el teléfono durante una hora"))
  assertEquals(PhoneRoute.Local(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1800, 20)), p.parse("Poné el volumen al veinte por ciento durante treinta minutos"))
  assertTrue(p.parse("silenciá el teléfono durante un rato") is PhoneRoute.Clarify)
 }
}
