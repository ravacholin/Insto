package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.example.data.AssistantPreferencesRepository
import com.example.phone.*
import com.example.testsupport.awaitCachedPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** «Canal de estados» and the log on Android: WhatsApp's «Responder» is never touched with «Bitácora», and secrets stay put. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctLogFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private var fakeNow = 5_000_000L
  private val sent = mutableListOf<String>()
  private var scheduled = 0
  private var handleReads = 0
  private val url = "https://script.google.com/macros/s/AKfycbxTestDeployment123/exec"
  private val token = "token-de-prueba-4321"

  @Before fun setUp() {
    InstinctProposalCenter.clock = { fakeNow }
    InstinctProposalCenter.budget.reset()
    InstinctProposalCenter.acks.clear()
    InstinctProposalCenter.accessibilityConnected = { false }
    InstinctProposalCenter.keyguardLocked = { false }
    InstinctProposalCenter.replySender = { _, _, text -> sent += text; true }
    InstinctProposalIntake.log = {}
    InstinctLog.schedule = { scheduled++ }
    InstinctLog.outbox(app).remove(InstinctLog.outbox(app).batch(500).map { it.id }.toSet())
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"; applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+54 9 11 2233-4455") }
    awaitCachedPreferences(app) { it.pinnedContact?.displayName == "Instinct" }
    InstinctProposalSettings(app).setEnabled(true)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
  }

  @After fun tearDown() {
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.WHATSAPP)
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.SOLO)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.NEVER)
    InstinctProposalSettings(app).setEnabled(false)
    InstinctLogSettings(app).clear()
    InstinctProposalCenter.book.discardAll()
    InstinctProposalCenter.replySender = { context, handle, text -> handle.send(context, text) }
    InstinctProposalCenter.accessibilityConnected = { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
    InstinctProposalCenter.clock = android.os.SystemClock::elapsedRealtime
    InstinctLog.client = { InstinctLogClient() }
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

  private companion object { var nextId = 120_000 }

  /** Posts through the intake with a reply handle that counts every read. */
  private fun post(text: String) {
    val notification = whatsApp(text)
    InstinctProposalIntake.onPosted(app, { ExtrasProposalNotification("com.whatsapp", "log-${nextId++}") { notification.extras } },
      { handleReads++; InstinctReplyHandle.from(notification) })
  }

  private fun pending() = InstinctLog.outbox(app).batch(500)

  @Test fun `the channel is WhatsApp by default and lives in noBackupFilesDir`() {
    assertEquals(InstinctAckChannel.WHATSAPP, InstinctProposalSettings(app).channel())
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.BITACORA)
    assertEquals("BITACORA", File(app.noBackupFilesDir, "instinct-proposals-ack-channel").readText())
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.WHATSAPP)
    assertFalse(File(app.noBackupFilesDir, "instinct-proposals-ack-channel").exists())
  }

  @Test fun `with Bitácora WhatsApp's Responder is never read nor used, and the events go to the outbox`() {
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.BITACORA)
    post("Te ajusto el teléfono.\n#insto poner el volumen al 30 por ciento\n#insto llamar a Ana")
    assertEquals("the RemoteInput is never read", 0, handleReads)
    assertTrue(sent.isEmpty())
    assertNull(InstinctProposalCenter.acks.latestHandle())
    val events = pending()
    assertEquals(listOf("ok", "error"), events.map { it.kind })
    assertEquals("poner el volumen al 30 por ciento", events[0].order)
    assertEquals("esa acción no está permitida para Instinct.", events[1].detail)
    assertTrue(events.all { it.message.matches(Regex("[0-9a-f]{12}")) && it.message == events[0].message })
    assertTrue(events.all { it.protocol == "Protocolo Insto v7" })
    assertTrue(scheduled > 0)
    assertFalse(InstinctProposalCenter.sendCatalog(app))
    assertTrue(sent.isEmpty())
  }

  @Test fun `with Ambos the chat gets the reply and the log gets the events`() {
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.AMBOS)
    post("#insto poner el volumen al 35 por ciento")
    assertEquals(1, handleReads)
    assertTrue(sent.single(), sent.single().startsWith("#insto-ok poner el volumen al 35 por ciento → "))
    assertEquals(listOf("ok"), pending().map { it.kind })
  }

  @Test fun `with WhatsApp nothing is written to the log`() {
    post("#insto poner el volumen al 25 por ciento")
    assertEquals(1, sent.size)
    assertTrue(pending().isEmpty())
  }

  @Test fun `a waiting order is closed in the log when it lapses`() {
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.BITACORA)
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.CONFIRMAR)
    post("#insto prender la linterna")
    assertEquals(listOf("espera"), pending().map { it.kind })
    fakeNow += ProposalBook.TTL_MILLIS - 1_000L
    InstinctProposalCenter.sweepLog(app)
    assertEquals(listOf("espera", "vencida"), pending().map { it.kind })
    assertEquals(0, handleReads)
  }

  @Test fun `the configuration validates the URL and never shows the token whole`() {
    val log = InstinctLogSettings(app)
    assertFalse(log.save("https://script.google.com/macros/s/AKfycbxTestDeployment123/dev", token))
    assertFalse(log.save(url, "corto"))
    assertFalse(log.configured())
    assertTrue(log.save(url, token))
    assertEquals("••••4321", log.maskedToken())
    assertEquals(url, log.url())
    assertTrue(File(app.noBackupFilesDir, "instinct-log-token").exists())
    // A blank token keeps the saved one.
    assertTrue(log.save(url, ""))
    assertTrue(log.configured())
  }

  @Test fun `the copy for the prompt carries the URL and token, the WhatsApp copy never does`() {
    InstinctLogSettings(app).save(url, token)
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.AMBOS)
    val copy = InstinctProposalCenter.catalog(app)
    assertTrue(copy.contains("GET $url?token=$token&since=<ISO>&limit=50"))
    post("#insto poner el volumen al 15 por ciento")
    sent.clear()
    assertTrue(InstinctProposalCenter.sendCatalog(app))
    val chat = sent.single()
    assertFalse(chat.contains(token))
    assertFalse(chat.contains(url))
    assertTrue(chat.contains(InstinctCatalogLog.ASK))
    // No acknowledgement in the chat carries them either.
    post("#insto poner el volumen al 16 por ciento")
    assertTrue(sent.none { it.contains(token) || it.contains(url) })
  }

  @Test fun `draining removes only what the script accepted and records the result without content`() {
    InstinctLogSettings(app).save(url, token)
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.BITACORA)
    post("#insto poner el volumen al 20 por ciento\n#insto llamar a Ana")
    val ids = pending().map { it.id }
    val bodies = mutableListOf<String>()
    InstinctLog.client = {
      InstinctLogClient(OkHttpClient.Builder().addInterceptor(FakeHttp { ex ->
        if (ex.request.method == "POST") {
          bodies += ex.bodyText()
          FakeHttp.Reply(302, "", mapOf("Location" to "https://script.googleusercontent.com/macros/echo?user_content_key=k"))
        } else FakeHttp.Reply(200, """{"ok":true,"accepted":["${ids[0]}"]}""")
      }).build())
    }
    // Only the first was accepted: the second stays, and the job asks for a retry once it fails to make progress.
    assertTrue(InstinctLog.drain(app))
    assertEquals(listOf(ids[1]), pending().map { it.id })
    assertEquals(token, JSONObject(bodies.first()).getString("token"))
    InstinctLog.client = { InstinctLogClient(OkHttpClient.Builder().addInterceptor(FakeHttp { FakeHttp.Reply(403) }).build()) }
    assertFalse("a rejected token waits for the user", InstinctLog.drain(app))
    assertTrue(InstinctLog.diagnosis(app), InstinctLog.diagnosis(app).startsWith("Bitácora: no se pudo escribir (motivo: token rechazado)"))
    assertTrue(InstinctLog.diagnosis(app).contains("pendientes 1"))
    assertFalse(InstinctLog.diagnosis(app).contains(token))
  }
}
