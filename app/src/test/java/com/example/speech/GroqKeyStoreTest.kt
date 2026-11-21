package com.example.speech

import java.io.File
import java.nio.file.Files
import java.security.InvalidKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Keystore is replaced by an injectable software key, so invalidation can be simulated on the JVM. */
class GroqKeyStoreTest {
  private val dir: File = Files.createTempDirectory("keys").toFile()
  private fun aes(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
  private var key = aes()
  private var invalidated = false
  private val store = GroqKeyStore(dir, "groq") { if (invalidated) throw InvalidKeyException("Key permanently invalidated") else key }
  private val file get() = File(dir, "groq-key.enc")

  @After fun cleanUp() { dir.deleteRecursively() }

  @Test fun distinguishesMissingPresentAndUnreadable() {
    assertEquals(StoredKey.Missing, store.read())
    store.save("  gsk_secret-value  ")
    assertEquals(StoredKey.Present("gsk_secret-value"), store.read())
    assertEquals("gsk_secret-value", store.readValue())

    invalidated = true // Android invalidated the Keystore key (lock screen removed, biometric change, restore).
    assertEquals(StoredKey.Unreadable, store.read())
    assertNull("An unreadable key is never usable", store.readValue())
    assertTrue("The ciphertext is kept; only reloading replaces it", file.exists())
  }

  @Test fun aReplacedKeystoreKeyOrDamagedFileIsUnreadableNotMissing() {
    store.save("gsk_one")
    key = aes()
    assertEquals(StoredKey.Unreadable, store.read())
    file.writeBytes(byteArrayOf(1, 2, 3))
    assertEquals(StoredKey.Unreadable, store.read())
    file.writeBytes(ByteArray(64) { it.toByte() })
    assertEquals(StoredKey.Unreadable, store.read())
  }

  @Test fun reloadingAfterUnreadableStoresAValidKeyAtomically() {
    store.save("gsk_old")
    invalidated = true
    assertEquals(StoredKey.Status.UNREADABLE, store.read().status)
    invalidated = false
    key = aes()
    store.save("gsk_new")
    assertEquals(StoredKey.Present("gsk_new"), store.read())
    assertFalse("The temporary file is renamed over the old one", File(dir, "groq-key.enc.tmp").exists())
    store.clear()
    assertEquals(StoredKey.Missing, store.read())
  }

  @Test fun theValueNeverAppearsInTextRepresentations() {
    store.save("gsk_do-not-print")
    val present = store.read()
    assertFalse(present.toString().contains("gsk_do-not-print"))
    assertFalse(StoredKey.Unreadable.toString().contains("gsk"))
    assertFalse(file.readText(Charsets.ISO_8859_1).contains("gsk_do-not-print"))
  }
}
