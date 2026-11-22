package com.example

import com.example.phone.InstinctPushCrypto
import com.example.phone.InstinctPushMessage
import com.example.phone.LibraryPushConnector
import com.example.phone.PushKeys
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RFC 8291 on Insto's side (1.19.0): the RFC's own example and the relay's messages, decrypted with Tink's
 * `WebPushHybridDecrypt`, the same code the UnifiedPush library runs on the phone; and Insto's sealed plain doorbell.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctPushCryptoTest {
  private fun b64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trimEnd('='))

  private fun decrypt(body: ByteArray, publicKey: String, privateKey: String, auth: String): ByteArray =
    WebPushHybridDecrypt.Builder().withAuthSecret(b64(auth)).withRecipientPublicKey(b64(publicKey))
      .withRecipientPrivateKey(b64(privateKey)).build().decrypt(body, null)

  @Test fun `the RFC 8291 example decrypts with the library's decrypter`() {
    val body = b64("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN")
    val text = decrypt(body, "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
      "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94", "BTBZMqHH6r4Tts7J_aSIgg")
    assertEquals("When I grow up, I want to be a watermelon", String(text))
    assertTrue(InstinctPushMessage.looksEncrypted(body))
  }

  @Test fun `every message the relay encrypted decrypts on the phone, byte for byte`() {
    val vectors = JSONObject(javaClass.classLoader!!.getResource("webpush/relay-vectors.json")!!.readText())
    val receiver = vectors.getJSONObject("receiver")
    val cases = vectors.getJSONArray("cases")
    assertEquals(3, cases.length())
    for (i in 0 until cases.length()) {
      val case = cases.getJSONObject(i)
      val body = b64(case.getString("body"))
      assertTrue("at most 4096 bytes", body.size <= 4096)
      val text = decrypt(body, receiver.getString("public"), receiver.getString("private"), vectors.getString("auth"))
      assertArrayEquals(case.getString("plaintext").toByteArray(Charsets.UTF_8), text)
    }
  }

  @Test fun `a tampered relay message does not decrypt`() {
    val vectors = JSONObject(javaClass.classLoader!!.getResource("webpush/relay-vectors.json")!!.readText())
    val receiver = vectors.getJSONObject("receiver")
    val body = b64(vectors.getJSONArray("cases").getJSONObject(1).getString("body")).also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
    try { decrypt(body, receiver.getString("public"), receiver.getString("private"), vectors.getString("auth")); fail("decrypted") }
    catch (_: java.security.GeneralSecurityException) { }
  }

  @Test fun `Insto's sealed plain doorbell is a standard aes128gcm body only its keys open`() {
    val keys = TestPushKeys().apply { generate(LibraryPushConnector.INSTANCE) }
    val set = keys.getPublicKeySet(LibraryPushConnector.INSTANCE)!!
    val a = InstinctPushCrypto.seal(PushKeys(set.pubKey, set.auth), "insto-timbre".toByteArray())
    val b = InstinctPushCrypto.seal(PushKeys(set.pubKey, set.auth), "insto-timbre".toByteArray())
    assertTrue("random salt and ephemeral key", !a.contentEquals(b))
    assertTrue(InstinctPushMessage.looksEncrypted(a))
    assertEquals(4096, ((a[16].toInt() and 0xff) shl 24) or ((a[17].toInt() and 0xff) shl 16) or ((a[18].toInt() and 0xff) shl 8) or (a[19].toInt() and 0xff))
    assertEquals("insto-timbre", String(keys.decrypt(LibraryPushConnector.INSTANCE, a)!!))
    // Padded base64url, as Apps Script's Utilities.base64DecodeWebSafe reads it, round-trips.
    assertArrayEquals(a, InstinctPushCrypto.decode(InstinctPushCrypto.encode(a).trimEnd('=')))
  }
}
