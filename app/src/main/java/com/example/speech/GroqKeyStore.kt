package com.example.speech

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the secure store holds for one provider. The value never appears in [toString], logs or the UI. */
sealed interface StoredKey {
  val status: Status

  /** Nothing saved. */
  data object Missing : StoredKey { override val status get() = Status.MISSING }

  class Present(val value: String) : StoredKey {
    override val status get() = Status.PRESENT
    override fun toString() = "StoredKey.Present(***)"
    override fun equals(other: Any?) = other is Present && other.value == value
    override fun hashCode() = value.hashCode()
  }

  /**
   * Saved but not decryptable: the Keystore key was invalidated or replaced, or the file is damaged. Not a valid key,
   * and not «no key» either: the user has to load it again.
   */
  data object Unreadable : StoredKey { override val status get() = Status.UNREADABLE }

  enum class Status { MISSING, PRESENT, UNREADABLE }

  fun valueOrNull(): String? = (this as? Present)?.value
}

/** Ciphertext is outside backup; the AES key never leaves Android Keystore. */
class GroqKeyStore internal constructor(
  directory: File,
  provider: String,
  /** The AES key. Android Keystore in the app; tests inject a software key or a failure. */
  private val secret: (alias: String) -> SecretKey,
) {
  constructor(context: Context, provider: String = "groq") : this(context.noBackupFilesDir, provider, ::keystoreSecret)

  init { require(provider in setOf("groq", "gemini")) }
  private val file = File(directory, "$provider-key.enc")
  private val alias = "insto.$provider.personal.v1"

  @Synchronized fun read(): StoredKey {
    if (!file.exists()) return StoredKey.Missing
    // Any failure means the saved key cannot be used; the cause is deliberately not kept, since it may quote data.
    return runCatching {
      val bytes = file.readBytes()
      require(bytes.size > 28)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, secret(alias), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
      cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8).also { require(it.isNotBlank()) }
    }.fold(onSuccess = { StoredKey.Present(it) }, onFailure = { StoredKey.Unreadable })
  }

  /** The usable key, or null when it is missing or unreadable. For providers, which only need a valid value. */
  fun readValue(): String? = read().valueOrNull()

  @Synchronized fun save(value: String) {
    val text = value.trim()
    require(text.isNotEmpty() && text.length <= 512 && text.none { it.isWhitespace() })
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secret(alias))
    val bytes = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    // Atomic replacement: a reader sees the old file or the new one, never a partial write.
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeBytes(bytes)
    check(temporary.renameTo(file)) { "No se pudo guardar la clave" }
  }

  @Synchronized fun clear() { if (file.exists()) check(file.delete()) }

  companion object {
    /** Shown instead of «Sin clave» when a saved key exists but cannot be decrypted. */
    const val UNREADABLE_MESSAGE = "La clave guardada no se puede leer; volvé a cargarla."

    private fun keystoreSecret(alias: String): SecretKey {
      val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      (store.getKey(alias, null) as? SecretKey)?.let { return it }
      return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
      }.generateKey()
    }
  }
}
