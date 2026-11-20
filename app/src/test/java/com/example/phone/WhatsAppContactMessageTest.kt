package com.example.phone

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** WhatsApp messages to a contact by name: parsed locally, resolved in the contacts and confirmed before sending. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WhatsAppContactMessageTest {
  private val parser = PhoneCommandParser()
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private fun send(to: String, body: String) = PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, to, content = body))

  @Test fun namedRecipientsAreParsedLocallyWithTheirLiteralText() {
    val cases = mapOf(
      "enviar un mensaje de whatsapp a meme que diga Te amo" to send("meme", "Te amo"),
      "mandarle un mensaje de whatsapp a meme que diga Te amo" to send("meme", "Te amo"),
      "Mandale un WhatsApp a Meme que diga Te amo" to send("Meme", "Te amo"),
      "Enviale a Ana por WhatsApp que diga Llego tarde." to send("Ana", "Llego tarde."),
      "¿Podés mandar un mensaje por WhatsApp a José Pérez diciendo ¿Venís?" to send("José Pérez", "¿Venís?"),
      "mandá por WhatsApp a +541112345678 que diga Estoy llegando" to send("+541112345678", "Estoy llegando"),
      "enviá un mensaje de WhatsApp al 11 1234 5678 que diga Hola" to send("1112345678", "Hola"),
    )
    for ((text, expected) in cases) assertEquals(text, expected, parser.parse(text))
    assertEquals(send("meme", "Te amo"), LocalIntentRegistry.default.resolve("message_send", "enviar un mensaje de whatsapp a meme que diga Te amo"))
  }

  @Test fun withoutChannelOrWithSeveralRecipientsItIsNotAWhatsAppSend() {
    for (text in listOf("mandale un mensaje a Ana que diga hola", "mandá por WhatsApp a Ana y Juan que diga hola",
        "mandá por WhatsApp a todos que diga hola", "mandá por WhatsApp a Ana2 que diga hola")) {
      val route = parser.parse(text)
      assertFalse(text, route is PhoneRoute.Local && (route.command as? PhoneCommand.Platform)?.action == PlatformAction.MESSAGE_SEND)
    }
  }

  @Test fun codecAcceptsOneContactNameButNotInventedTargets() {
    val command = PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, "José Pérez", content = "Hola")
    assertEquals(command, PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)))
    for (target in listOf("Ana y Juan", "Ana123", "", "com.whatsapp/.Main")) assertThrows(target, IllegalArgumentException::class.java) {
      PhoneCommandCodec.decode(JSONObject().put("type", "platform").put("action", "MESSAGE_SEND").put("argument", target).put("content", "Hola"))
    }
    // Other number-only actions keep rejecting names.
    assertThrows(IllegalArgumentException::class.java) {
      PhoneCommandCodec.decode(PhoneCommandCodec.encode(PhoneCommand.Platform(PlatformAction.MESSAGE_DRAFT, "+541112345678", content = "Hola")).put("argument", "Ana"))
    }
  }

  @Test fun aRecipientNameTheUserDidNotSayIsRejected() {
    val text = "mandale un WhatsApp a Ana que diga hola"
    assertTrue(SemanticCommandGuard.review(InterpretationRequest(text), InterpretationResult.Plan(listOf(
      PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, "Juan", content = "hola")))) is InterpretationResult.Rejected)
  }

  @Test fun aNameNeedsContactsAccessAndIsNeverSentUnresolved() {
    shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
    val command = PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, "Meme", content = "Te amo")
    val result = PhoneActionExecutor(app).execute(command)
    assertTrue(result.needsContacts)
    assertNull(result.confirmation)
    assertTrue(result.retainRequest)
    assertNull(shadowOf(app).nextStartedActivity)
    val confirmed = PhoneActionExecutor(app).execute(command, confirmed = true)
    assertEquals(PhoneActionOutcome.REJECTED, confirmed.outcome)
    assertNull(shadowOf(app).nextStartedActivity)
  }
}
