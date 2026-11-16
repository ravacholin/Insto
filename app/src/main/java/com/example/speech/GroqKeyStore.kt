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

/** Ciphertext is outside backup; the AES key never leaves Android Keystore. */
class GroqKeyStore(context: Context) {
  private val file = File(context.noBackupFilesDir, "groq-key.enc")
  private val alias = "insto.groq.personal.v1"
  @Synchronized fun read(): String? {
    if (!file.exists()) return null
    return runCatching {
      val bytes = file.readBytes()
      require(bytes.size > 28)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
      cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }.getOrNull()
  }
  @Synchronized fun save(value: String) {
    val text = value.trim()
    require(text.isNotEmpty() && text.length <= 512 && text.none { it.isWhitespace() })
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secret())
    val bytes = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    val temporary = File(file.parentFile, "groq-key.tmp")
    temporary.writeBytes(bytes)
    check(temporary.renameTo(file)) { "No se pudo guardar la clave" }
  }
  @Synchronized fun clear() { if (file.exists()) check(file.delete()) }
  private fun secret(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
      init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
  }
}
