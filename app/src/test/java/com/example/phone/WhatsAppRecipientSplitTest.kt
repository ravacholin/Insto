package com.example.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages are always WhatsApp, and «WhatsApp a Meme Te amo» needs no connector: the contacts split name and text. */
class WhatsAppRecipientSplitTest {
  private val parser = PhoneCommandParser()
  private fun send(to: String, body: String) = PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, to, content = body))
  private fun unsplit(tail: String) = PhoneRoute.Local(ExtendedLocalIntents.unsplitWhatsApp(tail))

  @Test fun requestsWithoutConnectorAreLocalAndKeepTheirText() {
    val cases = mapOf(
      "enviar WhatsApp a Meme Te amo" to unsplit("Meme Te amo"),
      "WhatsApp a Meme te amo" to unsplit("Meme te amo"),
      "mandale un wasap a Ana Pérez llego en 10" to unsplit("Ana Pérez llego en 10"),
      "Mensaje a Juan: ¿venís?" to unsplit("Juan: ¿venís?"),
      "escribile a Meme que la quiero" to unsplit("Meme que la quiero"),
      "mandá un whatsapp al 11 1234 5678 llego tarde" to send("1112345678", "llego tarde"),
    )
    for ((text, expected) in cases) assertEquals(text, expected, parser.parse(text))
  }

  @Test fun anyMessageOrSmsIsWhatsApp() {
    val cases = mapOf(
      "mandale un mensaje a Ana que diga hola" to send("Ana", "hola"),
      "enviá un SMS a Meme que diga Te amo" to send("Meme", "Te amo"),
      "redactá un SMS para +541112345678 que diga Estoy llegando" to send("+541112345678", "Estoy llegando"),
      "mandale a Meme que diga Te amo" to send("Meme", "Te amo"),
      "Mandá un mensaje a Juan que diga “no voy a ir”" to send("Juan", "no voy a ir"),
    )
    for ((text, expected) in cases) assertEquals(text, expected, parser.parse(text))
    assertTrue(LocalIntentRegistry.default.definitions.none { it.id in setOf("message_draft", "message_open") })
  }

  @Test fun withoutChannelNothingIsSent() {
    for (text in listOf("mandá a Ana el archivo", "mandá a Ana", "WhatsApp a Meme")) {
      val route = parser.parse(text)
      assertFalse(text, route is PhoneRoute.Local && (route.command as? PhoneCommand.Platform)?.action == PlatformAction.MESSAGE_SEND)
    }
  }

  @Test fun theLongestContactNameWins() {
    val contacts = listOf(ContactOption("Meme", "+541111111111"), ContactOption("Ana Pérez", "+542222222222"),
      ContactOption("Ana López", "+543333333333"), ContactOption("Memelo", "+544444444444"))
    assertEquals(WhatsAppRecipientSplit.Split("Meme", "Te amo", listOf(contacts[0])), WhatsAppRecipientSplit.split("Meme Te amo", contacts))
    assertEquals("Ana Pérez" to "llego en 10", WhatsAppRecipientSplit.split("Ana Pérez llego en 10", contacts)!!.let { it.name to it.body })
    assertEquals("Juan" to "¿venís?", WhatsAppRecipientSplit.split("Juan: ¿venís?", listOf(ContactOption("Juan Gómez", "+545555555555")))!!.let { it.name to it.body })
    assertNull(WhatsAppRecipientSplit.split("Pedro hola", contacts))
    assertNull(WhatsAppRecipientSplit.split("Meme", contacts))
    assertEquals("Meme", WhatsAppRecipientSplit.firstWord("Meme: Te amo"))
    val juan = listOf(ContactOption("Juan", "+545555555555"))
    assertEquals("llego tarde", WhatsAppRecipientSplit.split("Juan que llego tarde", juan)!!.body)
    assertEquals("no voy a ir", WhatsAppRecipientSplit.split("Juan “no voy a ir”", juan)!!.body)
  }

  @Test fun onePartialMatchIsEnoughSeveralAreChosen() {
    val one = WhatsAppRecipientSplit.split("Meme Te amo", listOf(ContactOption("Meme Rodríguez", "+54 11 1111-1111")))!!
    assertEquals(ContactResolution.Number("+541111111111"), WhatsAppRecipientSplit.resolve(one, truncated = false))
    val two = WhatsAppRecipientSplit.split("Ana hola", listOf(ContactOption("Ana Pérez", "+542222222222"), ContactOption("Ana López", "+543333333333")))!!
    assertTrue(WhatsAppRecipientSplit.resolve(two, truncated = false) is ContactResolution.Choose)
    val exact = WhatsAppRecipientSplit.split("Ana hola", listOf(ContactOption("Ana", "+542222222222"), ContactOption("Ana López", "+543333333333")))!!
    assertEquals(ContactResolution.Number("+542222222222"), WhatsAppRecipientSplit.resolve(exact, truncated = false))
  }

  @Test fun unsplitRequestsSurviveTheCodecAndTheGuard() {
    val command = ExtendedLocalIntents.unsplitWhatsApp("Meme: ¿venís a las 8?")
    assertEquals(command, PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)))
    val text = "WhatsApp a Meme: ¿venís a las 8?"
    assertEquals(InterpretationResult.Plan(listOf(command)), SemanticCommandGuard.review(InterpretationRequest(text), InterpretationResult.Plan(listOf(command))))
  }
}
