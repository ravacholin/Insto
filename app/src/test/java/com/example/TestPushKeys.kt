package com.example

import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import com.google.crypto.tink.subtle.EllipticCurves
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.unifiedpush.android.connector.data.PublicKeySet
import org.unifiedpush.android.connector.keys.KeyManager

/**
 * The library's [KeyManager] contract without the Android Keystore, which Robolectric does not have: the same P-256
 * pair and 16-byte auth secret, and the same Tink decryption the library uses on the phone.
 */
class TestPushKeys : KeyManager {
  private val keys = HashMap<String, Pair<KeyPair, ByteArray>>()

  override fun decrypt(instance: String, sealed: ByteArray): ByteArray? {
    val (pair, auth) = keys[instance] ?: return null
    return WebPushHybridDecrypt.Builder().withAuthSecret(auth).withRecipientPublicKey(pair.public as ECPublicKey)
      .withRecipientPrivateKey(pair.private as ECPrivateKey).build().decrypt(sealed, null)
  }

  override fun generate(instance: String) {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    keys[instance] = pair to ByteArray(16).also(SecureRandom()::nextBytes)
  }

  override fun getPublicKeySet(instance: String): PublicKeySet? = keys[instance]?.let { (pair, auth) ->
    PublicKeySet(b64(EllipticCurves.pointEncode(EllipticCurves.CurveType.NIST_P256, EllipticCurves.PointFormatType.UNCOMPRESSED,
      (pair.public as ECPublicKey).w)), b64(auth))
  }

  override fun exists(instance: String): Boolean = instance in keys
  override fun delete(instance: String) { keys.remove(instance) }

  private fun b64(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
