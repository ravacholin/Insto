package com.example.phone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class MediaCommandTest {
 @Test fun currentMediaQuestionUsesPhoneWhileExplanationsRemainQueries() {
  val p = PhoneCommandParser()
  assertEquals(PhoneRoute.Local(PhoneCommand.Media(MediaOp.STATUS)), p.parse("¿Qué está sonando?"))
  assertEquals(PhoneRoute.Instinct, p.parse("¿Qué es la reproducción multimedia?"))
 }
 @Test fun mediaCommandsTargetTransportNotSearch() {
  val p = PhoneCommandParser()
  assertEquals(PhoneRoute.Local(PhoneCommand.Media(MediaOp.PAUSE)), p.parse("Pausá la música"))
  assertEquals(PhoneRoute.Local(PhoneCommand.Media(MediaOp.NEXT)), p.parse("Pasá al siguiente tema"))
  assertEquals(PhoneRoute.Local(PhoneCommand.Media(MediaOp.PLAY)), p.parse("Continuá la música"))
 }
 @Test fun directSoundAndTransportOrdersWorkWithoutGemini() = runBlocking {
  val parser = PhoneCommandParser()
  val coordinator = GeminiCoordinator(HybridPhoneRouter({ false }, { error("Gemini Decisions is disabled") }),
   StructuredCommandExtractor { error("Gemini fallback is disabled") })
  val requests = listOf(
   Triple("Silenciar", "platform", "SILENT_ON"),
   Triple("Poner en silenciar.", "platform", "SILENT_ON"),
   Triple("Modo vibrar", "platform", "VIBRATE_ON"),
   Triple("Poner en vibrar", "platform", "VIBRATE_ON"),
   Triple("Pausar Spotify", "media", "PAUSE"),
   Triple("Pausar YouTube", "media", "PAUSE"),
   Triple("Parar Spotify", "media", "STOP"),
   Triple("Parar YouTube", "media", "STOP"),
   Triple("Parar la música", "media", "STOP"),
   Triple("Subir volumen", "volume", "UP"),
   Triple("Bajar volumen", "volume", "DOWN"),
  )
  for ((phrase, type, action) in requests) {
   val result = coordinator.interpret(InterpretationRequest(phrase), parser.parse(phrase))
   assertTrue("$phrase: $result", result is InterpretationResult.Direct)
   val command = (result as InterpretationResult.Direct).command
   val payload = PhoneCommandCodec.encode(command)
   assertEquals(phrase, type, payload.getString("type"))
   assertEquals(phrase, action, payload.getString(if (type == "platform") "action" else "operation"))
   val packageName = when (phrase.substringAfterLast(' ')) {
    "Spotify" -> "com.spotify.music"
    "YouTube" -> "com.google.android.youtube"
    else -> null
   }
   assertEquals(phrase, packageName, (command as? PhoneCommand.Media)?.packageName)
  }
 }
 @Test fun playByTitleRequestsPlaybackInTheNamedAppAndPreservesTheTitle() {
  val parser = PhoneCommandParser()
  for ((platform, pkg) in listOf("Spotify" to "com.spotify.music", "YouTube" to "com.google.android.youtube")) {
   val phrase = "Reproducir \"Björk & París\" en $platform."
   val route = parser.parse(phrase)
   assertTrue("$phrase: $route", route is PhoneRoute.Local)
   val command = (route as PhoneRoute.Local).command
   val intent = NativeIntentSpec.forCommand(command)!!
   assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", intent.action)
   assertEquals(pkg, intent.preferredPackage)
   assertEquals("Björk & París", intent.stringExtras["query"])
   assertEquals(command, PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)))
  }
 }
 @Test fun playbackCannotAbsorbAnAdditionalMediaCommandAsPartOfTheTitle() {
  val parser = PhoneCommandParser()
  for (verb in listOf("pausar", "parar", "pará", "detené")) {
   assertTrue(verb, parser.parse("Reproducir Jazz y $verb YouTube en Spotify") is PhoneRoute.Unsupported)
  }
  assertTrue(parser.parse("Reproducir Jazz, pausar YouTube en Spotify") is PhoneRoute.Unsupported)
  assertEquals(PhoneRoute.Local(PhoneCommand.MusicSearch("Yo y para ti", MediaApp.SPOTIFY)),
   parser.parse("Reproducir Yo y para ti en Spotify"))
  assertFalse(parser.parse("Poner el volumen al 20 por ciento en Spotify") is PhoneRoute.Local)
 }
 @Test fun temporaryRequestsKeepDurationAndChannel() {
  val p = PhoneCommandParser()
  assertEquals(PhoneRoute.Local(PhoneCommand.TemporaryMode(ModeKind.SILENT, 3600)), p.parse("Silenciá el teléfono durante una hora"))
  assertEquals(PhoneRoute.Local(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1800, 20)), p.parse("Poné el volumen al veinte por ciento durante treinta minutos"))
  assertTrue(p.parse("silenciá el teléfono durante un rato") is PhoneRoute.Clarify)
 }
}
