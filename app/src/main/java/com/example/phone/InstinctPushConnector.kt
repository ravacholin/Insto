package com.example.phone

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridEncrypt
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import org.unifiedpush.android.connector.keys.DefaultKeyManager
import org.unifiedpush.android.connector.keys.KeyManager
import org.unifiedpush.android.connector.UnifiedPush as Connector

/** Web Push's public keys of Insto's registration (RFC 8291): the P-256 point and the auth secret, base64url. */
data class PushKeys(val p256dh: String, val auth: String)

/**
 * The UnifiedPush connector Insto uses (1.19.0): the official library `org.unifiedpush.android:connector`, behind a
 * seam. It keeps the connection token, registers with the distributor (sharing Insto's identity on Android 14 and
 * later), acknowledges messages, follows fallbacks and owns the Web Push keys (the private key wrapped by the Android
 * Keystore). Insto never sees the private key.
 */
interface PushConnector {
  fun distributors(context: Context): List<String>
  fun register(context: Context, distributor: String)
  fun unregister(context: Context)
  fun publicKeys(context: Context): PushKeys?
}

object LibraryPushConnector : PushConnector {
  /** One registration: the doorbell. */
  const val INSTANCE = "insto-timbre"

  override fun distributors(context: Context): List<String> =
    runCatching { Connector.getDistributors(context) }.getOrDefault(emptyList()).filter { it != context.packageName }.distinct().sorted()

  override fun register(context: Context, distributor: String) {
    Connector.saveDistributor(context, distributor)
    // No VAPID: ntfy does not ask for it. A distributor that does answers VAPID_REQUIRED, and the diagnosis says so.
    Connector.register(context, INSTANCE, UnifiedPush.DESCRIPTION, null, InstinctDoorbell.keyManager(context))
  }

  override fun unregister(context: Context) {
    Connector.unregister(context, INSTANCE, InstinctDoorbell.keyManager(context))
  }

  override fun publicKeys(context: Context): PushKeys? =
    InstinctDoorbell.keyManager(context).getPublicKeySet(INSTANCE)?.let { PushKeys(it.pubKey, it.auth) }
}

/** RFC 8291 for Insto's own plain doorbell, with Tink's `WebPushHybridEncrypt` (the library's own copy). */
object InstinctPushCrypto {
  private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP

  /** [plaintext] encrypted for [keys]: a complete `aes128gcm` body, random salt and ephemeral key. */
  fun seal(keys: PushKeys, plaintext: ByteArray): ByteArray = WebPushHybridEncrypt.Builder()
    .withAuthSecret(decode(keys.auth))
    .withRecipientPublicKey(decode(keys.p256dh))
    .build()
    .encrypt(plaintext, null)

  fun decode(text: String): ByteArray = Base64.decode(text, FLAGS or Base64.NO_PADDING)
  /** Padded base64url: what Apps Script's `Utilities.base64DecodeWebSafe` reads. */
  fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, FLAGS)
}

/**
 * UnifiedPush's broadcasts, through the official library (1.19.0). Exported, because the distributor is another app; a
 * token the library did not register is ignored there. `onReceive()` takes `goAsync()` and queues: the library's
 * checks, the decryption and Insto's handling run on [InstinctPushIntake]'s thread. Declared with priority 0, so the
 * library's own receiver (priority -500) stays out, and the library acknowledges a message only after [onMessage]
 * stored it.
 */
@Suppress("DEPRECATION")
class InstinctPushReceiver : MessagingReceiver() {
  private val receivedAt = ThreadLocal<Long>()
  override fun getKeyManager(context: Context): KeyManager = InstinctDoorbell.keyManager(context)

  override fun onReceive(context: Context, intent: Intent) {
    runCatching { receive(context, intent) { goAsync()?.let { pending -> PushFinisher { pending.finish() } } } }
  }

  /** [onReceive] with the finisher given: `goAsync()` on the phone, a counter in the tests. Returns at once. */
  internal fun receive(context: Context, intent: Intent, async: () -> PushFinisher?) {
    val app = context.applicationContext
    val arrival = InstinctMailbox.clock()
    InstinctPushIntake.receive(app, async) {
      receivedAt.set(arrival)
      try { super.onReceive(app, intent) } finally { receivedAt.remove() }
    }
  }

  override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
    InstinctDoorbell.onNewEndpoint(context, endpoint.url, endpoint.pubKeySet?.let { PushKeys(it.pubKey, it.auth) }, endpoint.temporary)
  }

  override fun onMessage(context: Context, message: PushMessage, instance: String) {
    InstinctDoorbell.onMessage(context, message.content, message.decrypted, receivedAt.get() ?: InstinctMailbox.clock())
  }

  override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
    InstinctDoorbell.onRegistrationFailed(context, reason.name)
  }

  override fun onUnregistered(context: Context, instance: String) = InstinctDoorbell.onUnregistered(context)

  override fun onTempUnavailable(context: Context, instance: String) = InstinctDoorbell.onTempUnavailable(context)

  companion object {
    /** The library's default key manager: P-256 and auth per instance, the private key wrapped by the Android Keystore. */
    fun defaultKeyManager(context: Context): KeyManager = DefaultKeyManager(context)
  }
}
