package com.example

import android.Manifest
import android.app.Application
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

/** «Timbre» (1.17.0) on Android: the UnifiedPush connector, a ring that polls the mailbox, and the script. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctDoorbellFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private var wall = 1_790_000_000_000L
  private val url = "https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec"
  private val token = "token-largo-de-prueba"
  private val key = "clave-del-buzon-de-prueba-0123456789-abcdef"
  private val endpoint = "https://ntfy.sh/upAbCdEf0123456789?up=1"
  private val ntfy = "io.heckel.ntfy"
  private val inbox = mutableListOf<MailboxOrder>()
  private val taken = mutableListOf<String>()
  /** What the fake Apps Script received for the doorbell. */
  private val scriptBodies = mutableListOf<JSONObject>()
  private var scriptKnowsDoorbell = true
  private val toDistributor = mutableListOf<Intent>()
  private val ringJobs = mutableListOf<Long?>()
  private val followUps = mutableListOf<Long?>()
  private val mailboxJobs = mutableListOf<Long?>()
  private var gets = 0
  private var online = true
  private val audio get() = app.getSystemService(AudioManager::class.java)
  private val box get() = InstinctMailboxSettings(app)
  private val bell get() = InstinctDoorbell.settings(app)

  @Before fun setUp() {
    InstinctProposalCenter.clock = { 1_000_000L }
    InstinctScheduler.wallClock = { wall }
    InstinctScheduler.alarms = { _, _, _ -> }
    InstinctProposalCenter.budget.reset()
    InstinctProposalCenter.acks.clear()
    InstinctProposalCenter.accessibilityConnected = { false }
    InstinctProposalCenter.keyguardLocked = { false }
    InstinctProposalCenter.replySender = { _, _, _ -> true }
    InstinctProposalIntake.log = {}
    InstinctLog.schedule = {}
    InstinctStatePublisher.background = { it.run() }
    InstinctStatePublisher.heartbeat = { _, _ -> }
    InstinctMailbox.schedule = { _, delay -> mailboxJobs += delay }
    InstinctMailbox.background = { it.run() }
    InstinctMailbox.lastPollAt = 0L
    InstinctMailbox.gate.reset()
    InstinctDoorbell.distributors = { listOf(ntfy) }
    InstinctDoorbell.send = { _, intent -> toDistributor += intent }
    InstinctDoorbell.online = { online }
    InstinctDoorbell.background = { it.run() }
    // The endpoint reaches the script at once; ring jobs are recorded.
    InstinctDoorbell.schedule = { context, kind, delay ->
      when (kind) { "inform" -> InstinctDoorbell.informNow(context); "follow-up" -> followUps += delay; else -> ringJobs += delay }
    }
    val fake = FakeHttp { ex ->
      if (ex.request.method == "GET") {
        gets++
        FakeHttp.Reply(200, JSONObject().put("ok", true).put("ordenes", JSONArray(inbox.filter { it.id !in taken }.map {
          JSONObject().put("orden_id", it.id).put("at", it.at).put("lineas", JSONArray(it.lines)).put("firma", it.sig)
        })).toString())
      } else {
        val body = JSONObject(ex.bodyText())
        when (body.optString("action")) {
          "tomadas" -> { val ids = body.getJSONArray("ids"); (0 until ids.length()).forEach { taken += ids.getString(it) }; FakeHttp.Reply(200, """{"ok":true}""") }
          "timbre" -> {
            scriptBodies += body
            if (!scriptKnowsDoorbell) FakeHttp.Reply(200, """{"ok":true,"accepted":[]}""")
            else FakeHttp.Reply(200, JSONObject().put("ok", true).put("timbre", if (body.optString("endpoint").isEmpty()) "borrado" else "guardado")
              .apply { if (body.has("conOrden")) put("conOrden", body.getBoolean("conOrden")) }.toString())
          }
          else -> FakeHttp.Reply(200, """{"ok":true,"accepted":[]}""")
        }
      }
    }
    val http = InstinctLogClient(OkHttpClient.Builder().addInterceptor(fake).build())
    InstinctMailbox.client = { InstinctMailboxClient(http) }
    InstinctDoorbell.client = { http }
    InstinctLog.outbox(app).remove(InstinctLog.outbox(app).batch(500).map { it.id }.toSet())
    File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).delete()
    File(app.noBackupFilesDir, InstinctDoorbellSettings.STATE_FILE).delete()
    File(app.noBackupFilesDir, InstinctDoorbellSettings.ENDPOINT_FILE).delete()
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
    box.setIntervalMinutes(2)
  }

  @After fun tearDown() {
    InstinctDoorbell.setWithOrder(app, false)
    InstinctDoorbell.setEnabled(app, false)
    box.setIntervalMinutes(5)
    box.setEnabled(false)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.NEVER)
    InstinctProposalSettings(app).setEnabled(false)
    InstinctLogSettings(app).clear()
    InstinctProposalCenter.book.discardAll()
    InstinctProposalCenter.accessibilityConnected = { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
    InstinctProposalCenter.clock = android.os.SystemClock::elapsedRealtime
    InstinctScheduler.wallClock = System::currentTimeMillis
  }

  private fun order(vararg lines: String): MailboxOrder {
    val id = UUID.randomUUID().toString()
    val iso = InstinctLogEvents.iso(wall)
    return MailboxOrder(id, iso, lines.toList(), InstinctMailboxSignature.sign(key, InstinctMailboxSignature.canonical(id, iso, lines.toList())))
  }

  private fun deliver(action: String, token: String?, extras: Intent.() -> Unit = {}) {
    val intent = Intent(action).apply { token?.let { putExtra(UnifiedPush.EXTRA_TOKEN, it) }; extras() }
    InstinctDoorbellReceiver().onReceive(app, intent)
  }

  /** Turns the doorbell on and answers the REGISTER with [endpoint]; returns the token. */
  private fun register(): String {
    InstinctDoorbell.setEnabled(app, true)
    val register = toDistributor.last { it.action == UnifiedPush.ACTION_REGISTER }
    assertEquals(ntfy, register.`package`)
    val token = register.getStringExtra(UnifiedPush.EXTRA_TOKEN)!!
    deliver(UnifiedPush.ACTION_NEW_ENDPOINT, token) { putExtra(UnifiedPush.EXTRA_ENDPOINT, endpoint); putExtra(UnifiedPush.EXTRA_ID, "e-1") }
    return token
  }

  @Test fun `registering keeps the endpoint in noBackupFilesDir, tells the script and shows it masked`() {
    val token = register()
    assertTrue(UUID.fromString(token) != null)
    assertEquals(endpoint, File(app.noBackupFilesDir, "instinct-doorbell-endpoint").readText())
    assertEquals(endpoint, scriptBodies.single().getString("endpoint"))
    assertEquals(token, toDistributor.last { it.action == UnifiedPush.ACTION_MESSAGE_ACK }.getStringExtra(UnifiedPush.EXTRA_TOKEN))
    assertTrue(InstinctDoorbell.status(app), InstinctDoorbell.status(app).startsWith("Endpoint ••••" + endpoint.takeLast(6) + " · avisado al script "))
    assertFalse(InstinctDoorbell.diagnosis(app).contains(endpoint))
    assertFalse(InstinctProposalCenter.catalog(app).contains(endpoint))
    assertFalse(InstinctProposalCenter.catalog(app, forChat = true).contains(endpoint))
    assertTrue("with the doorbell on the catalog says so", InstinctProposalCenter.catalog(app).contains(InstinctDoorbellText.CATALOG_LINE))
  }

  @Test fun `a ring polls the mailbox at once, whatever it carries, and acknowledges it`() {
    val token = register()
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    inbox += order("#insto poner el volumen al 40 por ciento")
    deliver(UnifiedPush.ACTION_MESSAGE, token) {
      putExtra("bytesMessage", "#insto prender la linterna".toByteArray()); putExtra(UnifiedPush.EXTRA_ID, "m-1")
    }
    assertTrue("the signed order ran", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
    assertEquals(1, gets)
    assertEquals("m-1", toDistributor.last { it.action == UnifiedPush.ACTION_MESSAGE_ACK }.getStringExtra(UnifiedPush.EXTRA_ID))
    val state = bell.state()
    assertEquals(1, state.rings); assertEquals(1, state.ringPolls)
    assertTrue(InstinctDoorbell.diagnosis(app), InstinctDoorbell.diagnosis(app).startsWith("Timbre: último "))
  }

  @Test fun `a ring with another token is ignored`() {
    register()
    deliver(UnifiedPush.ACTION_MESSAGE, "inventado") { putExtra("bytesMessage", byteArrayOf(1)) }
    deliver(UnifiedPush.ACTION_MESSAGE, null)
    assertEquals(0, gets)
    assertEquals(0, bell.state().rings)
  }

  @Test fun `a ring inside the 5-second window is postponed, and one without network waits for it`() {
    val token = register()
    deliver(UnifiedPush.ACTION_MESSAGE, token)
    assertEquals(1, gets)
    deliver(UnifiedPush.ACTION_MESSAGE, token)
    assertEquals("not polled twice", 1, gets)
    val delay = ringJobs.single()!!
    assertTrue("$delay", delay in 1..5_000L)
    online = false
    InstinctMailbox.gate.reset()
    deliver(UnifiedPush.ACTION_MESSAGE, token)
    assertEquals(null, ringJobs.last())
    assertEquals(3, bell.state().rings)
  }

  @Test fun `while it rings the backup poll is every 15 minutes`() {
    val token = register()
    mailboxJobs.clear()
    InstinctMailbox.rearm(app)
    assertEquals("before any ring, the chosen 2 minutes", 2 * 60_000L, mailboxJobs.last())
    deliver(UnifiedPush.ACTION_MESSAGE, token)
    InstinctMailbox.rearm(app)
    assertEquals(15 * 60_000L, mailboxJobs.last())
    wall += 24 * 60 * 60_000L
    InstinctMailbox.rearm(app)
    assertEquals("a day without rings", 2 * 60_000L, mailboxJobs.last())
  }

  @Test fun `unregistered or turned off, the endpoint is deleted here and in the script`() {
    val token = register()
    deliver(UnifiedPush.ACTION_UNREGISTERED, token)
    assertFalse(File(app.noBackupFilesDir, "instinct-doorbell-endpoint").exists())
    assertEquals("", scriptBodies.last().getString("endpoint"))
    assertTrue(InstinctDoorbell.diagnosis(app).contains("dio de baja"))
    val again = register()
    assertNotEquals("a new registration, a new token", token, again)
    InstinctDoorbell.setEnabled(app, false)
    assertEquals(again, toDistributor.last { it.action == UnifiedPush.ACTION_UNREGISTER }.getStringExtra(UnifiedPush.EXTRA_TOKEN))
    assertEquals("", scriptBodies.last().getString("endpoint"))
    // The token is forgotten at once: a late message for it does nothing.
    deliver(UnifiedPush.ACTION_MESSAGE, again)
    assertEquals(0, gets)
  }

  @Test fun `a script older than 1_17_0 does not confirm the endpoint, and the diagnosis says so`() {
    scriptKnowsDoorbell = false
    register()
    assertTrue(InstinctDoorbell.diagnosis(app), InstinctDoorbell.diagnosis(app).contains(InstinctDoorbellScript.NOT_CONFIRMED))
    assertTrue(InstinctDoorbell.status(app).endsWith("todavía no se avisó al script"))
  }

  // The order inside the doorbell (1.18.0)

  /** The payload the Apps Script sends with «conOrden». */
  private fun payload(order: MailboxOrder): ByteArray = JSONObject().put("v", 1).put("id", order.id).put("at", order.at)
    .put("lines", JSONArray(order.lines)).put("sig", order.sig).toString().toByteArray(Charsets.UTF_8)

  private fun ringWith(token: String, bytes: ByteArray) = deliver(UnifiedPush.ACTION_MESSAGE, token) {
    putExtra(UnifiedPush.EXTRA_BYTES_MESSAGE, bytes); putExtra(UnifiedPush.EXTRA_ID, "m-" + UUID.randomUUID())
  }

  /** The events about [order] in the log's outbox, by kind. */
  private fun acks(order: String): List<String> = InstinctLog.outbox(app).batch(500).filter { it.order == order }.map { it.kind }

  @Test fun `the option goes to the script with conOrden and is off by default`() {
    register()
    assertFalse(bell.withOrder())
    assertEquals(false, scriptBodies.last().getBoolean("conOrden"))
    InstinctDoorbell.setWithOrder(app, true)
    assertEquals(true, scriptBodies.last().getBoolean("conOrden"))
    assertEquals(endpoint, scriptBodies.last().getString("endpoint"))
    assertTrue(bell.state().withOrderConfirmed)
    assertEquals(InstinctDoorbellText.withOrderStatus(bell.state()), InstinctDoorbell.withOrderStatus(app))
    assertTrue("the catalog says nothing changes for Instinct", InstinctProposalCenter.catalog(app).contains(InstinctDoorbellText.CATALOG_DIRECT_LINE))
    InstinctDoorbell.setWithOrder(app, false)
    assertEquals(false, scriptBodies.last().getBoolean("conOrden"))
    assertFalse(InstinctProposalCenter.catalog(app).contains(InstinctDoorbellText.CATALOG_DIRECT_LINE))
  }

  @Test fun `a script older than 1_18_0 keeps the endpoint but does not confirm the option`() {
    register()
    // An old script: it saves the endpoint and does not repeat conOrden.
    val old = FakeHttp { ex ->
      val body = JSONObject(ex.bodyText()); scriptBodies += body
      FakeHttp.Reply(200, JSONObject().put("ok", true).put("timbre", "guardado").toString())
    }
    InstinctDoorbell.client = { InstinctLogClient(OkHttpClient.Builder().addInterceptor(old).build()) }
    InstinctDoorbell.setWithOrder(app, true)
    assertFalse(bell.state().withOrderConfirmed)
    assertFalse(bell.state().informPending)
    assertTrue(InstinctDoorbell.withOrderStatus(app), InstinctDoorbell.withOrderStatus(app).contains("1.18.0"))
  }

  @Test fun `a signed order inside the doorbell runs at once without reading the sheet, once`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    val order = order("#insto poner el volumen al 40 por ciento")
    inbox += order
    ringWith(token, payload(order))
    assertTrue("it ran", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
    assertEquals("without reading the sheet", 0, gets)
    assertEquals(listOf("ok"), acks("poner el volumen al 40 por ciento"))
    assertEquals(1, bell.state().directOrders)
    assertEquals("a poll about 60 seconds later", listOf<Long?>(60_000L), followUps)
    assertTrue(InstinctDoorbell.diagnosis(app), InstinctDoorbell.diagnosis(app).contains("órdenes directas 1 · payload inválido 0"))
    // The follow-up poll marks it taken and never runs it again.
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    InstinctDoorbell.followUp(app)
    assertEquals(1, gets)
    assertTrue(order.id in taken)
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    // Nor the same doorbell delivered twice.
    ringWith(token, payload(order))
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(listOf("ok"), acks("poner el volumen al 40 por ciento"))
  }

  @Test fun `an order the poll took first is not run again by its doorbell`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    val order = order("#insto poner el volumen al 40 por ciento")
    inbox += order
    InstinctMailbox.poll(app)
    assertEquals(listOf("ok"), acks("poner el volumen al 40 por ciento"))
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    ringWith(token, payload(order))
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(listOf("ok"), acks("poner el volumen al 40 por ciento"))
    assertEquals(0, bell.state().directOrders)
  }

  @Test fun `a bad signature in the doorbell runs nothing, answers nobody and polls at once`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    val good = order("#insto poner el volumen al 40 por ciento")
    val forged = good.copy(lines = listOf("#insto poner el volumen al 100 por ciento"))
    ringWith(token, payload(forged))
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue("no answer to anyone", acks("poner el volumen al 100 por ciento").isEmpty())
    assertEquals("it polled instead", 1, gets)
    assertEquals(1, bell.state().badPayloads)
    assertEquals(0, bell.state().directOrders)
    assertTrue(followUps.isEmpty())
    // An old order (more than 15 minutes) is refused the same way.
    val stale = order("#insto poner el volumen al 40 por ciento")
    wall += 16 * 60_000L
    InstinctMailbox.gate.reset()
    ringWith(token, payload(stale))
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(2, bell.state().badPayloads)
    assertEquals(2, gets)
  }

  @Test fun `garbage in the doorbell is counted and polls, like insto-timbre without counting`() {
    val token = register()
    ringWith(token, "insto-timbre".toByteArray())
    assertEquals(1, gets)
    assertEquals(0, bell.state().badPayloads)
    InstinctMailbox.gate.reset()
    ringWith(token, "{\"v\":2}".toByteArray())
    assertEquals(2, gets)
    assertEquals(1, bell.state().badPayloads)
  }

  @Test fun `with the mailbox off, an order in the doorbell does nothing`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    box.setEnabled(false)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    ringWith(token, payload(order("#insto poner el volumen al 40 por ciento")))
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(0, gets)
  }

  @Test fun `a failed registration needs a new token`() {
    InstinctDoorbell.setEnabled(app, true)
    val first = toDistributor.last().getStringExtra(UnifiedPush.EXTRA_TOKEN)
    deliver(UnifiedPush.ACTION_REGISTRATION_FAILED, first) { putExtra(UnifiedPush.EXTRA_REASON, "NETWORK") }
    assertTrue(InstinctDoorbell.diagnosis(app).contains("no tenía red"))
    InstinctDoorbell.register(app)
    assertNotEquals(first, toDistributor.last().getStringExtra(UnifiedPush.EXTRA_TOKEN))
  }
}
