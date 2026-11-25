package com.example.phone

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The push transport's pure part (1.19.0): the durable inbox and what a push becomes. */
class InstinctPushInboxTest {
  private val dir: File = Files.createTempDirectory("insto-push").toFile()
  private var now = 1_790_000_000_000L
  private val key = "clave-del-buzon-de-prueba-0123456789-abcdef"

  private fun inbox(name: String = InstinctPushInbox.FILE) = InstinctPushInbox(File(dir, name)) { now }

  private fun order(at: Long = now): MailboxOrder {
    val id = UUID.randomUUID().toString()
    val iso = InstinctLogEvents.iso(at)
    val lines = listOf("#insto poner el volumen al 40 por ciento")
    return MailboxOrder(id, iso, lines, InstinctMailboxSignature.sign(key, InstinctMailboxSignature.canonical(id, iso, lines)))
  }

  private fun payload(order: MailboxOrder): ByteArray = JSONObject().put("v", 1).put("id", order.id).put("at", order.at)
    .put("lines", JSONArray(order.lines)).put("sig", order.sig).toString().toByteArray()

  // The inbox

  @Test fun `an entry survives a new instance, as after the death of the process`() {
    val o = order()
    assertTrue(inbox().add(PushEntry.Kind.ORDER, o, encrypted = true) is InstinctPushInbox.Added.Queued)
    val again = inbox().entries().single()
    assertEquals(PushEntry.Kind.ORDER, again.kind)
    assertEquals(o, again.order)
    assertTrue(again.encrypted)
    assertFalse(again.claimed)
  }

  @Test fun `rings fold into one and the same order is never queued twice`() {
    val box = inbox()
    assertTrue(box.add(PushEntry.Kind.RING) is InstinctPushInbox.Added.Queued)
    assertEquals(InstinctPushInbox.Added.Coalesced, box.add(PushEntry.Kind.RING))
    val o = order()
    assertTrue(box.add(PushEntry.Kind.ORDER, o) is InstinctPushInbox.Added.Queued)
    assertEquals(InstinctPushInbox.Added.Duplicate, box.add(PushEntry.Kind.ORDER, o.copy(id = o.id.uppercase())))
    assertEquals(2, box.size())
  }

  @Test fun `a full inbox keeps a ring so the poll reads what did not fit`() {
    val box = inbox()
    repeat(InstinctPushPolicy.INBOX_CAPACITY) { assertTrue(box.add(PushEntry.Kind.ORDER, order()) is InstinctPushInbox.Added.Queued) }
    assertEquals(InstinctPushInbox.Added.Full, box.add(PushEntry.Kind.ORDER, order()))
    val entries = box.entries()
    assertEquals(InstinctPushPolicy.INBOX_CAPACITY, entries.size)
    assertEquals("the oldest order made room for a ring", 1, entries.count { it.kind == PushEntry.Kind.RING })
  }

  @Test fun `an order past its window leaves the inbox, a claimed one waits for its verdict, a ring stays`() {
    val box = inbox()
    val stale = box.add(PushEntry.Kind.ORDER, order()) as InstinctPushInbox.Added.Queued
    val claimed = box.add(PushEntry.Kind.ORDER, order()) as InstinctPushInbox.Added.Queued
    box.add(PushEntry.Kind.RING)
    assertTrue(box.markClaimed(claimed.entry.seq))
    now += InstinctPushPolicy.ORDER_TTL_MILLIS + 1
    val left = box.entries()
    assertFalse(left.any { it.seq == stale.entry.seq })
    assertTrue(left.single { it.seq == claimed.entry.seq }.claimed)
    assertEquals(1, left.count { it.kind == PushEntry.Kind.RING })
  }

  @Test fun `sequence numbers keep growing after the inbox empties`() {
    val box = inbox()
    val first = (box.add(PushEntry.Kind.RING) as InstinctPushInbox.Added.Queued).entry.seq
    box.remove(first)
    val second = (inbox().add(PushEntry.Kind.RING) as InstinctPushInbox.Added.Queued).entry.seq
    assertTrue("$first < $second", second > first)
  }

  @Test fun `a write that fails stores nothing and says so`() {
    // A directory where the file should be: every atomic write fails.
    val blocked = File(dir, "bloqueado").apply { mkdirs(); File(this, "x").writeText("x") }
    val box = InstinctPushInbox(blocked) { now }
    assertEquals(InstinctPushInbox.Added.Failed, box.add(PushEntry.Kind.RING))
    assertEquals(0, box.size())
  }

  @Test fun `a corrupt file reads as empty and is replaced`() {
    File(dir, InstinctPushInbox.FILE).writeText("{no es json")
    assertEquals(0, inbox().size())
    assertTrue(inbox().add(PushEntry.Kind.RING) is InstinctPushInbox.Added.Queued)
    assertEquals(1, inbox().size())
  }

  @Test fun `twenty threads adding at once lose nothing and repeat nothing`() {
    val orders = (1..20).map { order() }
    val pool = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    val queued = AtomicInteger()
    // Each order twice, from two «transports».
    (orders + orders).forEach { o ->
      pool.execute { start.await(); if (inbox().add(PushEntry.Kind.ORDER, o) is InstinctPushInbox.Added.Queued) queued.incrementAndGet() }
    }
    start.countDown()
    pool.shutdown(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
    assertEquals(20, queued.get())
    assertEquals(orders.map { it.id }.toSet(), inbox().entries().mapNotNull { it.order?.id }.toSet())
  }

  // What a push becomes

  @Test fun `a decrypted payload is a ring or an order, never anything else`() {
    val o = order()
    assertEquals(InstinctPushMessage.Kind.Order(o), InstinctPushMessage.classify(payload(o), decrypted = true, encryptionRequired = true).kind)
    val plain = InstinctPushMessage.classify(InstinctDoorbellPayload.PLAIN.toByteArray(), decrypted = true, encryptionRequired = true)
    assertEquals(InstinctPushMessage.Kind.Ring, plain.kind)
    assertTrue(plain.encrypted); assertFalse(plain.malformed)
    val garbage = InstinctPushMessage.classify("{\"v\":2}".toByteArray(), decrypted = true, encryptionRequired = true)
    assertEquals(InstinctPushMessage.Kind.Ring, garbage.kind)
    assertTrue(garbage.malformed)
  }

  @Test fun `once the script encrypts, a clear message can only poll`() {
    val o = order()
    val clear = InstinctPushMessage.classify(payload(o), decrypted = false, encryptionRequired = true)
    assertEquals(InstinctPushMessage.Kind.Ring, clear.kind)
    assertTrue(clear.downgraded)
    assertFalse(clear.encrypted)
  }

  @Test fun `before the script encrypts, a clear order is accepted as in 1_18_0 and marked as such`() {
    val o = order()
    val legacy = InstinctPushMessage.classify(payload(o), decrypted = false, encryptionRequired = false)
    assertEquals(InstinctPushMessage.Kind.Order(o), legacy.kind)
    assertTrue(legacy.plainLegacy)
    assertFalse(legacy.encrypted)
  }

  @Test fun `content that looks like RFC 8291 but did not decrypt is told apart from clear text`() {
    // salt(16) · rs=4096 · idlen=65 · 0x04… · ciphertext
    val sealed = ByteArray(16) + byteArrayOf(0, 0, 16, 0, 65, 4) + ByteArray(64) + ByteArray(40)
    assertTrue(InstinctPushMessage.looksEncrypted(sealed))
    assertFalse(InstinctPushMessage.looksEncrypted("insto-timbre".toByteArray()))
    assertFalse(InstinctPushMessage.looksEncrypted(null))
    val failed = InstinctPushMessage.classify(sealed, decrypted = false, encryptionRequired = true)
    assertTrue(failed.undecryptable); assertFalse(failed.downgraded)
    val legacy = InstinctPushMessage.classify(sealed, decrypted = false, encryptionRequired = false)
    assertTrue(legacy.undecryptable); assertFalse(legacy.malformed); assertFalse(legacy.plainLegacy)
  }

  @Test fun `an oversized payload is never an order`() {
    val big = ByteArray(InstinctDoorbellPayload.MAX_BYTES + 1) { 'a'.code.toByte() }
    val classified = InstinctPushMessage.classify(big, decrypted = true, encryptionRequired = true)
    assertEquals(InstinctPushMessage.Kind.Ring, classified.kind)
    assertNull(InstinctDoorbellPayload.parse(big))
  }

  @Test fun `a reference requires encryption and binds the exact order bytes`() {
    val id = "00000000-0000-4000-8000-000000000042"
    val at = "2026-11-21T09:30:00-03:00"
    val signed = MailboxOrder(id, at, listOf("#insto plan-json á"), "a".repeat(64))
    val fingerprint = sha256("insto-v4\n$id\n$at\n#insto plan-json á")
    val bytes = """{"v":1,"id":"$id","sha256":"$fingerprint"}""".toByteArray(Charsets.UTF_8)
    val reference = (InstinctPushMessage.classify(bytes, true, true).kind as InstinctPushMessage.Kind.Reference).reference
    assertTrue(reference.matches(signed))
    assertFalse(reference.matches(signed.copy(lines = listOf("#insto plan-json a"))))
    assertFalse(reference.matches(signed.copy(id = "00000000-0000-4000-8000-000000000043")))
    assertEquals(InstinctPushMessage.Kind.Ring, InstinctPushMessage.classify(bytes, false, false).kind)
  }
}
