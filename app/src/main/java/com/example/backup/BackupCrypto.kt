package com.example.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Why a backup file could not be opened. Messages never contain file content or secrets. */
class BackupException(val kind: Kind, message: String) : Exception(message) {
  enum class Kind { TOO_LARGE, NOT_A_BACKUP, UNSUPPORTED_VERSION, NEWER_VERSION, TRUNCATED, BAD_PARAMETERS,
    WRONG_PASSWORD_OR_DAMAGED, WEAK_PASSWORD, INVALID_CONTENT }
}

/**
 * The portable envelope of a `.insto` file: AES-256-GCM with a key derived from the user's password by
 * PBKDF2-HMAC-SHA256. The header is authenticated as associated data, so changing any parameter is detected.
 *
 * | bytes | field |
 * | 0-7 | magic `INSTOBAK` |
 * | 8 | envelope version (1) |
 * | 9 | key derivation (1 = PBKDF2-HMAC-SHA256) |
 * | 10-13 | iterations, big-endian |
 * | 14-29 | salt (16 random bytes) |
 * | 30-41 | GCM nonce (12 random bytes, new for every export) |
 * | 42- | ciphertext and 128-bit tag |
 *
 * PBKDF2 is implemented here over the platform's `HmacSHA256` (RFC 8018, section 5.2), with the password encoded as
 * UTF-8. `SecretKeyFactory("PBKDF2WithHmacSHA256")` does not exist below Android 8 and providers differ in how they
 * encode the password, so one implementation everywhere keeps files portable between phones. It is checked against
 * published test vectors and against the JVM's factory.
 */
object BackupCrypto {
  private val MAGIC = "INSTOBAK".toByteArray(Charsets.US_ASCII)
  const val ENVELOPE_VERSION: Byte = 1
  const val KDF_PBKDF2_SHA256: Byte = 1
  /** OWASP 2023 recommendation for PBKDF2-HMAC-SHA256. */
  const val ITERATIONS = 600_000
  const val MIN_ITERATIONS = 100_000
  /** Upper bound so a crafted file cannot freeze the phone. */
  const val MAX_ITERATIONS = 5_000_000
  const val SALT_BYTES = 16
  const val NONCE_BYTES = 12
  const val TAG_BITS = 128
  const val HEADER_BYTES = 8 + 1 + 1 + 4 + SALT_BYTES + NONCE_BYTES
  const val MAX_PLAINTEXT = 1_048_576
  const val MAX_FILE = HEADER_BYTES + MAX_PLAINTEXT + TAG_BITS / 8
  const val MIN_PASSWORD = 8
  const val MAX_PASSWORD = 256

  fun isEnvelope(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

  fun checkPassword(password: CharArray) {
    if (password.size < MIN_PASSWORD) throw BackupException(BackupException.Kind.WEAK_PASSWORD,
      "La contraseña tiene que tener al menos $MIN_PASSWORD caracteres.")
    if (password.size > MAX_PASSWORD) throw BackupException(BackupException.Kind.WEAK_PASSWORD,
      "La contraseña puede tener hasta $MAX_PASSWORD caracteres.")
  }

  fun encrypt(plain: ByteArray, password: CharArray, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): ByteArray {
    checkPassword(password)
    require(plain.size <= MAX_PLAINTEXT) { "Backup too large" }
    require(iterations in MIN_ITERATIONS..MAX_ITERATIONS)
    val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
    val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(ENVELOPE_VERSION).put(KDF_PBKDF2_SHA256)
      .putInt(iterations).put(salt).put(nonce).array()
    val key = pbkdf2(password, salt, iterations)
    try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
      cipher.updateAAD(header)
      return header + cipher.doFinal(plain)
    } finally { key.fill(0) }
  }

  /** Checks size and header before the slow key derivation; the tag is verified before any plaintext is returned. */
  fun decrypt(file: ByteArray, password: CharArray): ByteArray {
    if (file.size > MAX_FILE) throw BackupException(BackupException.Kind.TOO_LARGE, "El archivo es demasiado grande para ser una copia de Insto.")
    if (!isEnvelope(file)) throw BackupException(BackupException.Kind.NOT_A_BACKUP, "El archivo no es una copia de seguridad de Insto.")
    if (file.size < HEADER_BYTES + TAG_BITS / 8) throw BackupException(BackupException.Kind.TRUNCATED, "El archivo está incompleto.")
    val buffer = ByteBuffer.wrap(file)
    buffer.position(MAGIC.size)
    val version = buffer.get()
    if (version > ENVELOPE_VERSION) throw BackupException(BackupException.Kind.NEWER_VERSION,
      "La copia se creó con una versión más nueva de Insto. Actualizá Insto para restaurarla.")
    if (version != ENVELOPE_VERSION) throw BackupException(BackupException.Kind.UNSUPPORTED_VERSION, "Versión de copia no reconocida.")
    if (buffer.get() != KDF_PBKDF2_SHA256) throw BackupException(BackupException.Kind.BAD_PARAMETERS, "Parámetros de cifrado no reconocidos.")
    val iterations = buffer.getInt()
    if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupException(BackupException.Kind.BAD_PARAMETERS, "Parámetros de cifrado no válidos.")
    if (password.isEmpty()) throw BackupException(BackupException.Kind.WRONG_PASSWORD_OR_DAMAGED, "Contraseña incorrecta o archivo dañado.")
    val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
    val nonce = ByteArray(NONCE_BYTES).also { buffer.get(it) }
    val header = file.copyOfRange(0, HEADER_BYTES)
    val key = pbkdf2(password, salt, iterations)
    try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
      cipher.updateAAD(header)
      return cipher.doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
    } catch (_: AEADBadTagException) {
      throw BackupException(BackupException.Kind.WRONG_PASSWORD_OR_DAMAGED, "Contraseña incorrecta o archivo dañado.")
    } catch (_: java.security.GeneralSecurityException) {
      throw BackupException(BackupException.Kind.WRONG_PASSWORD_OR_DAMAGED, "Contraseña incorrecta o archivo dañado.")
    } finally { key.fill(0) }
  }

  /** PBKDF2-HMAC-SHA256 (RFC 8018 §5.2), password as UTF-8. Intermediate buffers are cleared. */
  fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, length: Int = 32): ByteArray {
    val passwordBytes = utf8(password)
    val mac = Mac.getInstance("HmacSHA256")
    try {
      // An empty HMAC key is valid in RFC 2104, but SecretKeySpec rejects it; a 0x00 byte pads to the same block.
      mac.init(SecretKeySpec(if (passwordBytes.isEmpty()) ByteArray(1) else passwordBytes, "HmacSHA256"))
    } finally { passwordBytes.fill(0) }
    val hLen = mac.macLength
    val blocks = (length + hLen - 1) / hLen
    val out = ByteArray(length)
    val u = ByteArray(hLen)
    val t = ByteArray(hLen)
    for (block in 1..blocks) {
      mac.update(salt)
      mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
      mac.doFinal(u, 0)
      u.copyInto(t)
      for (i in 2..iterations) {
        mac.update(u)
        mac.doFinal(u, 0)
        for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
      }
      val offset = (block - 1) * hLen
      t.copyInto(out, offset, 0, minOf(hLen, length - offset))
    }
    u.fill(0); t.fill(0)
    return out
  }

  private fun utf8(chars: CharArray): ByteArray {
    val buffer = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(chars))
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    if (buffer.hasArray()) buffer.array().fill(0)
    return bytes
  }
}
