package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.AssistantPreferencesRepository
import com.example.phone.*
import com.example.testsupport.awaitCachedPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** «Buzón firmado» on Android: the same execution path as WhatsApp, the shared brake, the log and «avisarme». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctMailboxFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private var fakeNow = 1_000_000L
  private var wall = 1_790_000_000_000L
  private val sent = mutableListOf<String>()
  private val scheduled = mutableListOf<Long?>()
  private val url = "https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec"
  private val token = "token-largo-de-prueba"
  private val key = "clave-del-buzon-de-prueba-0123456789-abcdef"
  /** What the fake Apps Script holds: orders not taken yet, and the ids taken. */
  private val inbox = mutableListOf<MailboxOrder>()
  private val taken = mutableListOf<String>()
  private val audio get() = app.getSystemService(AudioManager::class.java)
  private val notifications get() = app.getSystemService(NotificationManager::class.java)
  private val box get() = InstinctMailboxSettings(app)

  @Before fun setUp() {
    InstinctProposalCenter.clock = { fakeNow }
    InstinctScheduler.wallClock = { wall }
    InstinctScheduler.alarms = { _, _, _ -> }
    InstinctProposalCenter.budget.reset()
    InstinctNotices.budget.reset()
    InstinctProposalCenter.acks.clear()
    InstinctProposalCenter.accessibilityConnected = { false }
    InstinctProposalCenter.keyguardLocked = { false }
    InstinctProposalCenter.replySender = { _, _, text -> sent += text; true }
    InstinctProposalIntake.log = {}
    InstinctLog.schedule = {}
    InstinctStatePublisher.background = { it.run() }
    InstinctStatePublisher.heartbeat = { _, _ -> }
    InstinctMailbox.schedule = { _, delay -> scheduled += delay }
    InstinctMailbox.background = { it.run() }
    InstinctMailbox.lastPollAt = 0L
    val fake = FakeHttp { ex ->
      if (ex.request.method == "GET") FakeHttp.Reply(200, JSONObject().put("ok", true).put("ordenes", JSONArray(inbox.filter { it.id !in taken }.map {
        JSONObject().put("orden_id", it.id).put("at", it.at).put("lineas", JSONArray(it.lines)).put("firma", it.sig)
      })).toString())
      else {
        val ids = JSONObject(ex.bodyText()).getJSONArray("ids")
        (0 until ids.length()).forEach { taken += ids.getString(it) }
        FakeHttp.Reply(200, """{"ok":true}""")
      }
    }
    InstinctMailbox.client = { InstinctMailboxClient(InstinctLogClient(OkHttpClient.Builder().addInterceptor(fake).build())) }
    InstinctLog.outbox(app).remove(InstinctLog.outbox(app).batch(500).map { it.id }.toSet())
    File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).delete()
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"; applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+54 9 11 2233-4455") }
    awaitCachedPreferences(app) { it.pinnedContact?.displayName == "Instinct" }
    InstinctProposalSettings(app).setEnabled(true)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    assertTrue(InstinctLogSettings(app).save(url, token))
    assertTrue(box.saveKey(key))
    box.setEnabled(true)
  }

  @After fun tearDown() {
    box.setEnabled(false)
    box.setAccept(InstinctMailboxAccept.WHATSAPP_Y_BUZON)
    box.setWhatsappCap(InstinctAutonomy.SOLO)
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.WHATSAPP)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.NEVER)
    InstinctProposalSettings(app).setEnabled(false)
    InstinctLogSettings(app).clear()
    InstinctProposalCenter.book.discardAll()
    InstinctProposalCenter.replySender = { context, handle, text -> handle.send(context, text) }
    InstinctProposalCenter.accessibilityConnected = { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
    InstinctProposalCenter.clock = android.os.SystemClock::elapsedRealtime
    InstinctScheduler.wallClock = System::currentTimeMillis
    InstinctMailbox.clock = { InstinctScheduler.wallClock() }
  }

  private fun order(vararg lines: String, id: String = UUID.randomUUID().toString(), at: Long = wall, signWith: String = key): MailboxOrder {
    val iso = InstinctLogEvents.iso(at)
    return MailboxOrder(id, iso, lines.toList(), InstinctMailboxSignature.sign(signWith, InstinctMailboxSignature.canonical(id, iso, lines.toList())))
  }

  private fun whatsApp(text: String): Notification {
    val intent = PendingIntent.getBroadcast(app, 1, Intent("com.whatsapp.REPLY").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE)
    val reply = Notification.Action.Builder(null, "Responder", intent)
      .addRemoteInput(RemoteInput.Builder("android.intent.extra.text").setLabel("Responder").build()).build()
    val style = Notification.MessagingStyle(Person.Builder().setName("Yo").build())
      .addMessage(text, System.currentTimeMillis(), Person.Builder().setName("Instinct").build())
    return Notification.Builder(app, "whatsapp").setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Instinct").setContentText(text).setStyle(style).addAction(reply).build()
  }

  private companion object { var nextId = 140_000 }
  private fun post(text: String) {
    val notification = whatsApp(text)
    InstinctProposalIntake.onPosted(app, { ExtrasProposalNotification("com.whatsapp", "box-${nextId++}") { notification.extras } },
      { InstinctReplyHandle.from(notification) })
  }

  private fun events() = InstinctLog.outbox(app).batch(500).filter { it.kind != "estado" }
  private fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

  @Test fun `a signed order runs on the same path and its acknowledgement goes to the log even with the WhatsApp channel`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    val good = order("#insto poner el volumen al 40 por ciento")
    val forged = order("#insto prender la linterna", signWith = "otra-clave-0123456789-0123456789-xyz")
    inbox += listOf(forged, good)
    val result = InstinctMailbox.poll(app) as InstinctMailbox.Result.Done
    assertEquals(1, result.orders); assertEquals(1, result.badSignatures)
    assertTrue(volume() > 0)
    assertTrue("nothing goes to the chat", sent.isEmpty())
    val ack = events().single()
    assertEquals("ok", ack.kind)
    assertEquals("poner el volumen al 40 por ciento", ack.order)
    assertEquals(InstinctMailboxSignature.messageId(good.id), ack.message)
    // Every id read is taken, the forged one too; and the valid one was seen on disk.
    assertEquals(setOf(good.id, forged.id), taken.toSet())
    assertTrue(InstinctMailbox.seen(app).contains(good.id))
    assertTrue(InstinctMailbox.diagnosis(app), InstinctMailbox.diagnosis(app).contains("órdenes 1 · firmas inválidas 1 · vencidas 0"))
    // The «Instinct hizo» notice with Deshacer, as from WhatsApp.
    assertTrue(shadowOf(notifications).allNotifications.any { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.startsWith("Instinct hizo") == true })
  }

  @Test fun `a repeated id never runs twice even if tomadas failed`() {
    val good = order("#insto poner el volumen al 40 por ciento")
    inbox += good
    InstinctMailbox.poll(app)
    taken.clear() // «POST tomadas» was lost: the order comes back.
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    InstinctMailbox.poll(app)
    assertEquals(0, volume())
  }

  @Test fun `an old order is refused and counted`() {
    inbox += order("#insto poner el volumen al 40 por ciento", at = wall - 16 * 60_000)
    val result = InstinctMailbox.poll(app) as InstinctMailbox.Result.Done
    assertEquals(0, result.orders); assertEquals(1, result.expired)
    assertTrue(events().isEmpty())
  }

  @Test fun `the anti-loop brake is shared between WhatsApp and the mailbox`() {
    // Six orders from WhatsApp run alone and spend the brake…
    repeat(6) { i -> fakeNow += 1_000; post("#insto poner el volumen al ${10 + i} por ciento") }
    assertFalse(InstinctProposalCenter.budget.tryAcquire())
    InstinctProposalCenter.budget.reset()
    repeat(6) { assertTrue(InstinctProposalCenter.budget.tryAcquire()) }
    // …and a seventh from the mailbox waits.
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    inbox += order("#insto poner el volumen al 40 por ciento")
    InstinctMailbox.poll(app)
    assertEquals(0, volume())
    assertEquals("espera", events().last().kind)
    assertEquals(InstinctAutonomyGate.BUDGET, events().last().detail)
  }

  @Test fun `avisarme is refused from WhatsApp and shown from the mailbox, three an hour`() {
    post("#insto avisarme Hola desde WhatsApp")
    assertEquals(listOf("#insto-error avisarme Hola desde WhatsApp → avisarme solo por el buzón"), sent)
    inbox += (1..4).map { order("#insto avisarme Aviso $it") }
    InstinctMailbox.poll(app)
    val shown = shadowOf(notifications).allNotifications.filter { it.channelId == InstinctNotices.CHANNEL_ID }
    assertEquals(3, shown.size)
    assertEquals("Aviso 1", shown.first { it.extras.getCharSequence(Notification.EXTRA_TEXT) == "Aviso 1" }.extras.getCharSequence(Notification.EXTRA_TEXT))
    val acks = events().filter { it.order.startsWith("avisarme") }
    assertEquals(listOf("ok", "ok", "ok", "error"), acks.map { it.kind })
    assertTrue(acks.last().detail.startsWith("ya hubo 3 avisos"))
  }

  @Test fun `avisarme respects the quiet hours without sound`() {
    InstinctAutonomySettings(app).setQuiet(QuietHours(0, 23 * 60 + 59))
    try {
      inbox += order("#insto avisarme De noche")
      InstinctMailbox.poll(app)
      assertTrue(shadowOf(notifications).allNotifications.any { it.channelId == InstinctNotices.QUIET_CHANNEL_ID })
      assertEquals("aviso mostrado sin sonido.", events().single().detail)
    } finally { InstinctAutonomySettings(app).setQuiet(null) }
  }

  @Test fun `Solo buzon ignores WhatsApp orders and says so`() {
    box.setAccept(InstinctMailboxAccept.SOLO_BUZON)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto poner el volumen al 40 por ciento")
    assertEquals(0, volume())
    assertTrue(sent.isEmpty())
    assertEquals(InstinctProposalIntake.Diagnosis.ONLY_MAILBOX, InstinctProposalIntake.lastDiagnosis)
    inbox += order("#insto poner el volumen al 40 por ciento")
    InstinctMailbox.poll(app)
    assertTrue(volume() > 0)
  }

  @Test fun `the WhatsApp cap makes unsigned orders wait but not signed ones`() {
    box.setWhatsappCap(InstinctAutonomy.CONFIRMAR)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto poner el volumen al 40 por ciento")
    assertEquals(0, volume())
    assertEquals(listOf("#insto-espera poner el volumen al 40 por ciento → ${InstinctSourceCap.REASON}"), sent)
    InstinctProposalCenter.book.discardAll()
    inbox += order("#insto poner el volumen al 50 por ciento")
    InstinctMailbox.poll(app)
    assertTrue(volume() > 0)
  }

  @Test fun `a WhatsApp message of Instinct triggers a poll at once`() {
    inbox += order("#insto poner el volumen al 40 por ciento")
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("Hola, ¿cómo va?")
    assertTrue(volume() > 0)
  }

  @Test fun `turning off Recibir ordenes cancels the mailbox job`() {
    scheduled.clear()
    InstinctProposalSettings(app).setEnabled(false)
    assertEquals(null, scheduled.last())
    InstinctProposalSettings(app).setEnabled(true)
    assertEquals(5 * 60_000L, scheduled.last())
    box.setIntervalMinutes(2)
    assertEquals(2 * 60_000L, scheduled.last())
    box.setIntervalMinutes(5)
  }

  @Test fun `the catalog never carries the key and the chat copy carries none of the three secrets`() {
    val paste = InstinctProposalCenter.catalog(app)
    assertTrue(paste.contains("Buzón firmado"))
    assertFalse(paste.contains(key))
    val chat = InstinctProposalCenter.catalog(app, forChat = true)
    for (secret in listOf(key, token, url)) assertFalse(chat.contains(secret))
    assertFalse(File(app.noBackupFilesDir, InstinctMailboxSettings.KEY_FILE).readText().isEmpty())
    assertEquals("••••" + key.takeLast(4), box.maskedKey())
  }

  @Test fun `Probar buzon counts pending orders without taking or running them`() {
    inbox += listOf(order("#insto poner el volumen al 40 por ciento"), order("#insto prender la linterna"))
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    assertEquals("El buzón respondió bien: hay 2 órdenes pendientes.", InstinctMailbox.test(app))
    assertTrue(taken.isEmpty())
    assertEquals(0, volume())
  }

  // 1.19.0

  @Test fun `an order runs before the sheet hears tomadas`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    var volumeWhenTaken = -1
    val fake = FakeHttp { ex ->
      if (ex.request.method == "GET") FakeHttp.Reply(200, JSONObject().put("ok", true).put("ordenes", JSONArray(inbox.filter { it.id !in taken }.map {
        JSONObject().put("orden_id", it.id).put("at", it.at).put("lineas", JSONArray(it.lines)).put("firma", it.sig)
      })).toString())
      else { volumeWhenTaken = audio.getStreamVolume(AudioManager.STREAM_MUSIC); FakeHttp.Reply(200, """{"ok":true}""") }
    }
    InstinctMailbox.client = { InstinctMailboxClient(InstinctLogClient(OkHttpClient.Builder().addInterceptor(fake).build())) }
    inbox += order("#insto poner el volumen al 40 por ciento")
    InstinctMailbox.poll(app)
    assertTrue("already ran when tomadas was sent: $volumeWhenTaken", volumeWhenTaken > 0)
  }

  @Test fun `a record that cannot be written runs nothing and marks nothing taken`() {
    // A directory where the record should be: every write fails.
    File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).apply { delete(); mkdirs(); File(this, "x").writeText("x") }
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    val order = order("#insto poner el volumen al 40 por ciento")
    inbox += order
    val result = InstinctMailbox.poll(app)
    assertEquals(InstinctMailbox.Result.Failed(InstinctMailbox.STORAGE_FAILURE), result)
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertFalse(order.id in taken)
    File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).deleteRecursively()
  }

  @Test fun `Apps Script unreachable is a failure the next poll recovers from`() {
    val down = FakeHttp { throw java.io.IOException("sin red") }
    InstinctMailbox.client = { InstinctMailboxClient(InstinctLogClient(OkHttpClient.Builder().addInterceptor(down).build())) }
    inbox += order("#insto poner el volumen al 40 por ciento")
    assertTrue(InstinctMailbox.poll(app) is InstinctMailbox.Result.Failed)
    assertTrue(InstinctMailbox.diagnosis(app), InstinctMailbox.diagnosis(app).contains("no se pudo sondear"))
  }

}
