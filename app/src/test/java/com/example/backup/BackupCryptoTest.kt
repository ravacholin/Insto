package com.example.backup

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {
  private fun hex(text: String) = text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  private val password = "contraseña de prueba".toCharArray()
  private val fast = BackupCrypto.MIN_ITERATIONS

  private fun expect(kind: BackupException.Kind, block: () -> Unit) {
    try { block(); fail("Expected $kind") } catch (error: BackupException) { assertEquals(error.message, kind, error.kind) }
  }

  @Test fun pbkdf2MatchesRfc7914Vectors() {
    assertArrayEquals(hex("55 ac 04 6e 56 e3 08 9f ec 16 91 c2 25 44 b6 05 f9 41 85 21 6d de 04 65 e6 8b 9d 57 c2 0d ac bc" +
      "49 ca 9c cc f1 79 b6 45 99 16 64 b3 9d 77 ef 31 7c 71 b8 45 b1 e3 0b d5 09 11 20 41 d3 a1 97 83"),
      BackupCrypto.pbkdf2("passwd".toCharArray(), "salt".toByteArray(), 1, 64))
    assertArrayEquals(hex("4d dc d8 f6 0b 98 be 21 83 0c ee 5e f2 27 01 f9 64 1a 44 18 d0 4c 04 14 ae ff 08 87 6b 34 ab 56" +
      "a1 d4 25 a1 22 58 33 54 9a db 84 1b 51 c9 b3 17 6a 27 2b de bb a1 d0 78 47 8f 62 b3 97 f3 3c 8d"),
      BackupCrypto.pbkdf2("Password".toCharArray(), "NaCl".toByteArray(), 80000, 64))
  }

  @Test fun pbkdf2MatchesTheJvmFactoryWithNonAsciiPasswords() {
    val salt = ByteArray(16) { it.toByte() }
    for (text in listOf("contraseña ñandú", "simple-password", "€uro✓")) {
      val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(text.toCharArray(), salt, 1000, 256)).encoded
      assertArrayEquals(text, factory, BackupCrypto.pbkdf2(text.toCharArray(), salt, 1000))
    }
  }

  @Test fun roundTripWithFreshSaltAndNonce() {
    val plain = "{\"hola\":\"mundo\"}".toByteArray()
    val a = BackupCrypto.encrypt(plain, password, SecureRandom(), fast)
    val b = BackupCrypto.encrypt(plain, password, SecureRandom(), fast)
    assertFalse(a.contentEquals(b))
    assertFalse("The nonce changes", a.copyOfRange(30, 42).contentEquals(b.copyOfRange(30, 42)))
    assertArrayEquals(plain, BackupCrypto.decrypt(a, password))
    assertTrue(BackupCrypto.isEnvelope(a))
    assertFalse("No plaintext in the file", String(a, Charsets.ISO_8859_1).contains("mundo"))
  }

  @Test fun wrongPasswordTruncatedTamperedAndOversizedFilesAreRejected() {
    val file = BackupCrypto.encrypt("secreto".toByteArray(), password, SecureRandom(), fast)
    expect(BackupException.Kind.WRONG_PASSWORD_OR_DAMAGED) { BackupCrypto.decrypt(file, "otra contraseña".toCharArray()) }
    expect(BackupException.Kind.TRUNCATED) { BackupCrypto.decrypt(file.copyOfRange(0, 40), password) }
    expect(BackupException.Kind.WRONG_PASSWORD_OR_DAMAGED) { BackupCrypto.decrypt(file.copyOfRange(0, file.size - 1), password) }
    for (index in listOf(20, 35, BackupCrypto.HEADER_BYTES + 1, file.size - 1)) {
      val tampered = file.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
      expect(BackupException.Kind.WRONG_PASSWORD_OR_DAMAGED) { BackupCrypto.decrypt(tampered, password) }
    }
    val iterations = file.copyOf().also { it[10] = 0x7f }
    expect(BackupException.Kind.BAD_PARAMETERS) { BackupCrypto.decrypt(iterations, password) }
    val future = file.copyOf().also { it[8] = 2 }
    expect(BackupException.Kind.NEWER_VERSION) { BackupCrypto.decrypt(future, password) }
    expect(BackupException.Kind.TOO_LARGE) { BackupCrypto.decrypt(ByteArray(BackupCrypto.MAX_FILE + 1), password) }
    expect(BackupException.Kind.NOT_A_BACKUP) { BackupCrypto.decrypt("{\"x\":1}".toByteArray(), password) }
    expect(BackupException.Kind.WEAK_PASSWORD) { BackupCrypto.encrypt(ByteArray(1), "corta".toCharArray()) }
  }
}
