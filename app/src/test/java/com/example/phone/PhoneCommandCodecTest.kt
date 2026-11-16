package com.example.phone
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class PhoneCommandCodecTest {
 @Test fun commandRoundtripPreservesParameters() {
  val command = PhoneCommand.CalendarEvent("Reunión con María", 1800000000000L, 1800001800000L)
  assertEquals(command, PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)))
 }
 @Test fun invalidAndInventedPayloadsAreRejected() {
  for (input in listOf("{\"type\":\"volume\",\"operation\":\"SET\",\"percent\":101}", "{\"type\":\"open_app\",\"name\":\"Maps\",\"url\":\"evil\"}", "{\"type\":\"timer\",\"seconds\":-1}", "{\"type\":\"timer\",\"seconds\":\"60\"}", "{\"type\":\"arbitrary_intent\"}")) {
   assertThrows(IllegalArgumentException::class.java) { PhoneCommandCodec.decode(JSONObject(input)) }
  }
 }
 @Test fun setVolumeWithoutPercentageMustNotBecomeSilence() {
  assertThrows(IllegalArgumentException::class.java){PhoneCommandCodec.decode(JSONObject("""{"type":"volume","operation":"SET"}"""))}
  assertThrows(IllegalArgumentException::class.java){PhoneCommandCodec.decode(JSONObject("""{"type":"temporary_mode","kind":"MEDIA_VOLUME","seconds":60}"""))}
 }

}
