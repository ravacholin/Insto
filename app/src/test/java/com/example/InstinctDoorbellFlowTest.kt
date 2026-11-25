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
import com.example.testsupport.installNativeHandler
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
  private val PLAIN = InstinctDoorbellPayload.PLAIN.toByteArray()
  private val taken = mutableListOf<String>()
  /** What the fake Apps Script received for the doorbell. */
  private val scriptBodies = mutableListOf<JSONObject>()
  private var scriptKnowsDoorbell = true
  /** What Insto sent by hand (only a 1.18.0 registration's UNREGISTER now). */
  private val sentByInsto = mutableListOf<Intent>()
  /** Every broadcast to the distributor: the library's (REGISTER, MESSAGE_ACK, UNREGISTER) and Insto's own. */
  private val toDistributor: List<Intent> get() = shadowOf(app).broadcastIntents.toList() + sentByInsto
  /** The library's Web Push keys, without the Android Keystore. */
  private val keys = TestPushKeys()
  /** The script answers like 1.19.0 (it saves the sealed doorbell); false: like 1.18.0. */
  private var scriptSeals = true
  private var scriptRelay = false
  /** What the fake script does after storing an order: ring the phone, or nothing. */
  private var ringOnOrder: ((MailboxOrder) -> Unit)? = null
  private val ringJobs = mutableListOf<Long?>()
  private val followUps = mutableListOf<Long?>()
  private val mailboxJobs = mutableListOf<Long?>()
  private val drainJobs = mutableListOf<Long?>()
  private val takeJobs = mutableListOf<Long?>()
  private val cancelled = mutableListOf<String>()
  private var gets = 0
  private val targeted = mutableListOf<String>()
  private var extraFetchMillis = 0L
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
    InstinctDoorbell.send = { _, intent -> sentByInsto += intent }
    InstinctDoorbell.keyManager = { keys }
    InstinctDoorbell.io = { it.run() }
    resetConnectorDatabase()
    InstinctDoorbell.online = { online }
    // 1.19.0: receiving and dispatching run inline here; the rescue job is recorded.
    InstinctPushIntake.execute = { it.run() }
    InstinctPushDispatcher.execute = { it.run() }
    InstinctPushDispatcher.reset()
    InstinctDoorbell.cancel = { _, kind -> cancelled += kind }
    // The endpoint reaches the script at once; ring jobs are recorded.
    InstinctDoorbell.schedule = { context, kind, delay ->
      when (kind) {
        "inform" -> InstinctDoorbell.informNow(context); "follow-up" -> followUps += delay; "drain" -> drainJobs += delay
        "take" -> { takeJobs += delay; InstinctDoorbell.takeNow(context) }
        else -> ringJobs += delay
      }
    }
    val fake = FakeHttp { ex ->
      if (ex.request.method == "GET") {
        val parameters = ex.request.url
        val id = parameters.queryParameter("id").takeIf { parameters.queryParameter("action") == "orden" }
        if (id == null) gets++ else targeted += id
        wall += extraFetchMillis
        FakeHttp.Reply(200, JSONObject().put("ok", true).put("ordenes", JSONArray(inbox.filter {
          it.id !in taken && (id == null || id.equals(it.id, ignoreCase = true))
        }.map {
          JSONObject().put("orden_id", it.id).put("at", it.at).put("lineas", JSONArray(it.lines)).put("firma", it.sig)
        })).toString())
      } else {
        val body = JSONObject(ex.bodyText())
        when (body.optString("action")) {
          "orden" -> {
            // The script stores it and rings: the relay's encrypted order when [scriptRings], nothing otherwise.
            val o = MailboxOrder(body.getString("id"), body.getString("at"), (0 until body.getJSONArray("lines").length()).map { body.getJSONArray("lines").getString(it) }, body.getString("sig"))
            inbox += o
            ringOnOrder?.invoke(o)
            FakeHttp.Reply(200, JSONObject().put("ok", true).put("id", o.id).toString())
          }
          "tomadas" -> { val ids = body.getJSONArray("ids"); (0 until ids.length()).forEach { taken += ids.getString(it) }; FakeHttp.Reply(200, """{"ok":true}""") }
          "timbre" -> {
            scriptBodies += body
            if (!scriptKnowsDoorbell) FakeHttp.Reply(200, """{"ok":true,"accepted":[]}""")
            else FakeHttp.Reply(200, JSONObject().put("ok", true).put("timbre", if (body.optString("endpoint").isEmpty()) "borrado" else "guardado")
              .apply {
                if (body.has("conOrden")) put("conOrden", body.getBoolean("conOrden"))
                if (scriptSeals && body.has("cifrado")) {
                  val sealed = !body.isNull("cifrado")
                  put("cifrado", sealed).put("rele", sealed && scriptRelay && body.getJSONObject("cifrado").has("p256dh")).put("cadaOrden", true)
                }
              }.toString())
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
    File(app.noBackupFilesDir, InstinctPushInbox.FILE).delete()
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
    InstinctDoorbell.endToEndWaitMillis = InstinctDoorbellPolicy.TEST_WAIT_MILLIS
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

  /** The library keeps one database per process; each test gets a fresh one, like a fresh install. */
  private fun resetConnectorDatabase() {
    val store = Class.forName("org.unifiedpush.android.connector.internal.DBStore")
    store.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
  }

  private fun deliver(action: String, token: String?, extras: Intent.() -> Unit = {}) {
    val intent = Intent(action).setPackage(app.packageName).apply { token?.let { putExtra(UnifiedPush.EXTRA_TOKEN, it) }; extras() }
    InstinctPushReceiver().onReceive(app, intent)
  }

  /** Turns the doorbell on and answers the library's REGISTER with [endpoint] and its keys; returns the token. */
  private fun register(): String {
    InstinctDoorbell.setEnabled(app, true)
    val register = toDistributor.last { it.action == UnifiedPush.ACTION_REGISTER }
    assertEquals(ntfy, register.`package`)
    val token = register.getStringExtra(UnifiedPush.EXTRA_TOKEN)!!
    deliver(UnifiedPush.ACTION_NEW_ENDPOINT, token) { putExtra(UnifiedPush.EXTRA_ENDPOINT, endpoint); putExtra(UnifiedPush.EXTRA_ID, "e-1") }
    return token
  }

  /** The registration's public keys, as the script and the relay get them. */
  private fun publicKeys(): PushKeys = keys.getPublicKeySet(LibraryPushConnector.INSTANCE)!!.let { PushKeys(it.pubKey, it.auth) }

  /** [bytes] encrypted for Insto (RFC 8291), as the relay or the sealed doorbell arrive. */
  private fun sealed(bytes: ByteArray): ByteArray = InstinctPushCrypto.seal(publicKeys(), bytes)

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
    deliver(UnifiedPush.ACTION_MESSAGE, null) { putExtra(UnifiedPush.EXTRA_BYTES_MESSAGE, PLAIN) }
    assertEquals(0, gets)
    assertEquals(0, bell.state().rings)
  }

  @Test fun `a ring inside the 5-second window is postponed, and one without network waits for it`() {
    val token = register()
    ringWith(token, PLAIN)
    assertEquals(1, gets)
    ringWith(token, PLAIN)
    assertEquals("not polled twice", 1, gets)
    val delay = ringJobs.single()!!
    assertTrue("$delay", delay in 1..5_000L)
    online = false
    InstinctMailbox.gate.reset()
    ringWith(token, PLAIN)
    assertEquals(null, ringJobs.last())
    assertEquals(3, bell.state().rings)
  }

  @Test fun `while it rings the backup poll is every 15 minutes`() {
    val token = register()
    mailboxJobs.clear()
    InstinctMailbox.rearm(app)
    assertEquals("before any ring, the chosen 2 minutes", 2 * 60_000L, mailboxJobs.last())
    ringWith(token, PLAIN)
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
    ringClear(again, PLAIN)
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

  /** A push whose content is [bytes] encrypted for Insto (RFC 8291), as UnifiedPush requires. */
  private fun ringWith(token: String, bytes: ByteArray) = ringClear(token, sealed(bytes))

  /** A push whose content is [bytes] as they are: in clear (a 1.18.0 script) or already sealed. */
  private fun ringClear(token: String, bytes: ByteArray) = deliver(UnifiedPush.ACTION_MESSAGE, token) {
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
    // 1.19.0: the script rings per order, so the sheet only hears «tomada»; no follow-up poll.
    assertTrue(followUps.isEmpty())
    assertEquals(listOf<Long?>(0L), takeJobs)
    assertTrue(order.id in taken)
    assertTrue(bell.state().pendingTaken.isEmpty())
    assertEquals(0, gets)
    assertTrue(InstinctDoorbell.diagnosis(app), InstinctDoorbell.diagnosis(app).contains("órdenes directas 1 · payload inválido 0"))
    // Nor the same doorbell delivered twice, nor a later poll.
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    ringWith(token, payload(order))
    InstinctMailbox.poll(app)
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(listOf("ok"), acks("poner el volumen al 40 por ciento"))
  }

  @Test fun `an encrypted reference retrieves exactly one signed order and records time to execution`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    val other = order("#insto poner el volumen al 90 por ciento")
    val wanted = order("#insto poner el volumen al 40 por ciento")
    inbox += listOf(other, wanted)
    extraFetchMillis = 321
    val reference = referencePayload(wanted)
    ringWith(token, reference)
    ringWith(token, reference)
    assertEquals(listOf(wanted.id), targeted)
    assertEquals(0, gets)
    assertEquals(listOf(wanted.id), taken)
    assertEquals(0, bell.state().directOrders)
    assertTrue(InstinctDoorbell.diagnosis(app).contains("órdenes por referencia 1"))
    val journal = InstinctExecutionJournal.of(app)
    assertTrue(journal.forMessage(other.id).isEmpty())
    val execution = journal.forMessage(wanted.id).single()
    assertEquals(ExecutionTransport.PUSH_REFERENCE, execution.transport)
    assertEquals(321L, execution.dispatchLatencyMillis)
    assertEquals(1, execution.attempts)
  }

  @Test fun `a matching reference cannot authorize a forged order and a changed digest never takes the order`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    val forged = order("#insto poner el volumen al 40 por ciento").copy(sig = "0".repeat(64))
    val changed = order("#insto poner el volumen al 20 por ciento")
    inbox += listOf(forged, changed)
    ringWith(token, referencePayload(forged))
    ringWith(token, referencePayload(changed.copy(lines = listOf("#insto poner el volumen al 90 por ciento"))))
    assertEquals(listOf(forged.id, changed.id), targeted)
    assertEquals(0, gets)
    assertTrue(taken.isEmpty())
    assertFalse(InstinctMailbox.seen(app).contains(forged.id))
    assertFalse(InstinctMailbox.seen(app).contains(changed.id))
  }

  private fun referencePayload(order: MailboxOrder): ByteArray = JSONObject().put("v", 1).put("id", order.id)
    .put("sha256", sha256(InstinctMailboxSignature.canonical(order.id, order.at, order.lines))).toString().toByteArray()

  @Test fun `with a script older than 1_19_0 a direct order is followed by a poll about 60 seconds later`() {
    scriptSeals = false
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    val order = order("#insto poner el volumen al 40 por ciento")
    inbox += order
    ringClear(token, payload(order))
    assertEquals(listOf<Long?>(60_000L), followUps)
    assertTrue(takeJobs.isEmpty())
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    InstinctDoorbell.followUp(app)
    assertEquals(1, gets)
    assertTrue(order.id in taken)
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
  }

  @Test fun `without network the direct order still runs and tomadas is retried later`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    online = false
    val down = FakeHttp { throw java.io.IOException("sin red") }
    InstinctMailbox.client = { InstinctMailboxClient(InstinctLogClient(OkHttpClient.Builder().addInterceptor(down).build())) }
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    val order = order("#insto poner el volumen al 40 por ciento")
    ringWith(token, payload(order))
    assertTrue("it ran without network", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
    assertEquals(listOf(order.id.lowercase()), bell.state().pendingTaken)
    assertTrue("the job retries", InstinctDoorbell.takeNow(app))
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

  @Test fun `a failed registration is diagnosed and registering again asks the distributor again`() {
    InstinctDoorbell.setEnabled(app, true)
    val first = toDistributor.last { it.action == UnifiedPush.ACTION_REGISTER }.getStringExtra(UnifiedPush.EXTRA_TOKEN)
    deliver(UnifiedPush.ACTION_REGISTRATION_FAILED, first) { putExtra(UnifiedPush.EXTRA_REASON, "NETWORK") }
    assertTrue(InstinctDoorbell.diagnosis(app).contains("no tenía red"))
    val before = toDistributor.count { it.action == UnifiedPush.ACTION_REGISTER }
    InstinctDoorbell.register(app)
    assertEquals(before + 1, toDistributor.count { it.action == UnifiedPush.ACTION_REGISTER })
  }

  // 1.19.0: receiving is not running

  /** A broadcast as the system delivers it, with a finisher that counts. */
  private fun deliverAsync(action: String, token: String?, finishes: java.util.concurrent.atomic.AtomicInteger, extras: Intent.() -> Unit = {}) {
    val intent = Intent(action).apply { token?.let { putExtra(UnifiedPush.EXTRA_TOKEN, it) }; extras() }
    val intent2 = intent.setPackage(app.packageName)
    InstinctPushReceiver().receive(app, intent2) { PushFinisher { finishes.incrementAndGet() } }
  }

  @Test fun `onReceive only queues, nothing is read, polled or run until the receiving thread runs`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    val queued = mutableListOf<Runnable>()
    InstinctPushIntake.execute = { queued += it }
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    val finishes = java.util.concurrent.atomic.AtomicInteger()
    val order = order("#insto poner el volumen al 40 por ciento")
    deliverAsync(UnifiedPush.ACTION_MESSAGE, token, finishes) { putExtra(UnifiedPush.EXTRA_BYTES_MESSAGE, sealed(payload(order))); putExtra(UnifiedPush.EXTRA_ID, "m-q") }
    assertEquals("not finished yet", 0, finishes.get())
    assertEquals(0, bell.state().rings)
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertFalse("not acknowledged before it is stored", toDistributor.any { it.action == UnifiedPush.ACTION_MESSAGE_ACK && it.getStringExtra(UnifiedPush.EXTRA_ID) == "m-q" })
    queued.single().run()
    assertEquals("finished exactly once", 1, finishes.get())
    assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
    assertTrue(toDistributor.any { it.action == UnifiedPush.ACTION_MESSAGE_ACK && it.getStringExtra(UnifiedPush.EXTRA_ID) == "m-q" })
    assertEquals(0, gets)
  }

  @Test fun `the main thread is never held while the mailbox is slow`() {
    val token = register()
    // Real threads for receiving, dispatching and polling; the sheet answers after 1.5 seconds.
    InstinctPushIntake.execute = { Thread(it).start() }
    InstinctPushDispatcher.execute = { Thread(it).start() }
    InstinctMailbox.background = { Thread(it).start() }
    val slow = FakeHttp { Thread.sleep(1_500); FakeHttp.Reply(200, """{"ok":true,"ordenes":[]}""") }
    InstinctMailbox.client = { InstinctMailboxClient(InstinctLogClient(OkHttpClient.Builder().addInterceptor(slow).build())) }
    val finishes = java.util.concurrent.atomic.AtomicInteger()
    val started = System.nanoTime()
    deliverAsync(UnifiedPush.ACTION_MESSAGE, token, finishes) { putExtra(UnifiedPush.EXTRA_BYTES_MESSAGE, sealed(PLAIN)) }
    val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    assertTrue("onReceive took $elapsed ms", elapsed < 300)
    val deadline = System.currentTimeMillis() + 10_000
    while (finishes.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assertEquals("finished once, after the poll", 1, finishes.get())
    assertTrue("the ring left the inbox once polled", waitFor { InstinctPushDispatcher.inbox(app).size() == 0 })
  }

  private fun waitFor(condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + 10_000
    while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    return condition()
  }

  @Test fun `a saturated receiving queue lets the broadcast go at once and counts it`() {
    val token = register()
    InstinctPushIntake.execute = { throw java.util.concurrent.RejectedExecutionException() }
    val finishes = java.util.concurrent.atomic.AtomicInteger()
    deliverAsync(UnifiedPush.ACTION_MESSAGE, token, finishes) { putExtra(UnifiedPush.EXTRA_BYTES_MESSAGE, sealed(PLAIN)) }
    assertEquals(1, finishes.get())
    assertEquals(1, bell.state().queueRejected)
    assertTrue(InstinctDoorbell.diagnosis(app), InstinctDoorbell.diagnosis(app).contains("cola de recepción llena"))
  }

  @Test fun `finish is called once even when the work throws`() {
    register()
    val finishes = java.util.concurrent.atomic.AtomicInteger()
    InstinctPushIntake.receive(app, { PushFinisher { finishes.incrementAndGet() } }) { error("falla") }
    assertEquals(1, finishes.get())
    val once = OnceFinisher { finishes.incrementAndGet() }
    once.finish(); once.finish()
    assertEquals(2, finishes.get())
  }

  @Test fun `an order received before the process died runs after it, once, verified again`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    // The process «dies» right after storing: the dispatcher never runs.
    InstinctPushDispatcher.execute = { }
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    val order = order("#insto poner el volumen al 40 por ciento")
    ringWith(token, payload(order))
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(1, InstinctPushDispatcher.inbox(app).size())
    assertEquals("the rescue job", InstinctPushPolicy.RESCUE_DELAY_MILLIS, drainJobs.last())
    // A new process: the rescue job drains it.
    InstinctPushDispatcher.reset()
    InstinctPushDispatcher.execute = { it.run() }
    InstinctPushDispatcher.drain(app)
    assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
    assertEquals(0, InstinctPushDispatcher.inbox(app).size())
    assertTrue("the rescue job is cancelled once empty", "drain" in cancelled)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    InstinctPushDispatcher.drain(app)
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(listOf("ok"), acks("poner el volumen al 40 por ciento"))
  }

  @Test fun `an order recovered after its window is refused, not run`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    InstinctPushDispatcher.execute = { }
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    ringWith(token, payload(order("#insto poner el volumen al 40 por ciento")))
    wall += 16 * 60_000L
    InstinctPushDispatcher.reset(); InstinctPushDispatcher.execute = { it.run() }
    InstinctPushDispatcher.drain(app)
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
  }

  @Test fun `an order recovered with the mailbox turned off meanwhile does nothing`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    InstinctPushDispatcher.execute = { }
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    ringWith(token, payload(order("#insto poner el volumen al 40 por ciento")))
    box.setEnabled(false)
    InstinctPushDispatcher.reset(); InstinctPushDispatcher.execute = { it.run() }
    InstinctPushDispatcher.drain(app)
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(0, InstinctPushDispatcher.inbox(app).size())
  }

  @Test fun `an order a dead process claimed is reported as incierta and never repeated`() {
    register()
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    val order = order("#insto poner el volumen al 40 por ciento")
    val inbox = InstinctPushDispatcher.inbox(app)
    val entry = (inbox.add(PushEntry.Kind.ORDER, order, encrypted = true) as InstinctPushInbox.Added.Queued).entry
    assertTrue(InstinctMailbox.seen(app).claim(order.id))
    assertTrue(inbox.markClaimed(entry.seq))
    InstinctPushDispatcher.drain(app)
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(listOf("incierto"), acks("poner el volumen al 40 por ciento"))
    assertEquals(1, bell.state().uncertain)
    assertEquals(0, inbox.size())
  }

  @Test fun `twenty orders in a burst run once each, without reading the sheet`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    val orders = (1..20).map { order("#insto ver estado") }
    orders.forEach { ringWith(token, payload(it)) }
    assertEquals(20, bell.state().directOrders)
    assertEquals(0, gets)
    assertEquals(0, InstinctPushDispatcher.inbox(app).size())
    // The same twenty again, as a distributor that resends: nothing runs.
    orders.forEach { ringWith(token, payload(it)) }
    assertEquals(20, bell.state().directOrders)
    assertEquals(20, bell.state().duplicates)
  }

  @Test fun `a push and a poll that read the same order at the same time run it once`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    repeat(10) {
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
      InstinctMailbox.gate.reset()
      val order = order("#insto poner el volumen al 40 por ciento")
      inbox += order
      val start = java.util.concurrent.CountDownLatch(1)
      val poll = Thread { start.await(); InstinctMailbox.poll(app) }
      val push = Thread { start.await(); ringWith(token, payload(order)) }
      poll.start(); push.start(); start.countDown(); poll.join(10_000); push.join(10_000)
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      assertEquals("round $it", 1, acks("poner el volumen al 40 por ciento").size)
      InstinctLog.outbox(app).remove(InstinctLog.outbox(app).batch(500).map { e -> e.id }.toSet())
    }
  }

  @Test fun `repeated pushes while an order is running do not run it again`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    val order = order("#insto poner el volumen al 40 por ciento")
    var nested = 0
    // While the first copy is being dispatched, two more copies arrive.
    InstinctPushDispatcher.execute = { task -> task.run(); if (nested++ == 0) { ringWith(token, payload(order)); ringWith(token, payload(order)) } }
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    ringWith(token, payload(order))
    assertEquals(listOf("ok"), acks("poner el volumen al 40 por ciento"))
    assertEquals(1, bell.state().directOrders)
  }


  // 1.19.0: UnifiedPush with the official library and RFC 8291

  @Test fun `the script gets a sealed plain doorbell that only Insto's keys open, and no keys without the order`() {
    register()
    val body = scriptBodies.last()
    val sealedDoorbell = InstinctPushCrypto.decode(body.getJSONObject("cifrado").getString("timbre").trimEnd('='))
    assertTrue(InstinctPushMessage.looksEncrypted(sealedDoorbell))
    assertEquals("insto-timbre", String(keys.decrypt(LibraryPushConnector.INSTANCE, sealedDoorbell)!!))
    assertFalse("the keys go only with the order inside", body.getJSONObject("cifrado").has("p256dh"))
    assertTrue(bell.state().encryptionConfirmed)
    assertTrue(bell.state().perOrderRing)
    InstinctDoorbell.setWithOrder(app, true)
    assertEquals(publicKeys().p256dh, scriptBodies.last().getJSONObject("cifrado").getString("p256dh"))
    assertEquals(publicKeys().auth, scriptBodies.last().getJSONObject("cifrado").getString("auth"))
    val diagnosis = InstinctDoorbell.diagnosis(app)
    assertFalse(diagnosis.contains(publicKeys().p256dh)); assertFalse(diagnosis.contains(publicKeys().auth))
    assertFalse(InstinctProposalCenter.catalog(app).contains(publicKeys().auth))
  }

  @Test fun `the sealed doorbell as the script sends it rings and polls`() {
    val token = register()
    val blob = InstinctPushCrypto.decode(scriptBodies.last().getJSONObject("cifrado").getString("timbre").trimEnd('='))
    inbox += order("#insto poner el volumen al 40 por ciento")
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    ringClear(token, blob)
    assertEquals(1, gets)
    assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
    assertEquals(1, bell.state().encryptedPushes)
    assertEquals(0, bell.state().plainPushes)
  }

  @Test fun `an encrypted order from the relay runs at once without reading the sheet`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    ringWith(token, payload(order("#insto poner el volumen al 40 por ciento")))
    assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
    assertEquals(0, gets)
    assertEquals(1, bell.state().encryptedPushes)
  }

  @Test fun `once the script encrypts, a clear signed order is not run from the push, only polled`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    assertTrue(bell.state().encryptionConfirmed)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    val order = order("#insto poner el volumen al 40 por ciento")
    inbox += order
    ringClear(token, payload(order))
    assertEquals("it polled instead", 1, gets)
    assertEquals(0, bell.state().directOrders)
    assertEquals(1, bell.state().plainPushes)
    assertEquals("the poll ran it, once", listOf("ok"), acks("poner el volumen al 40 por ciento"))
    assertTrue(InstinctDoorbell.diagnosis(app), InstinctDoorbell.diagnosis(app).contains("sin cifrar"))
  }

  @Test fun `with a 1_18_0 script the clear doorbell works as before and is marked as not compliant`() {
    scriptSeals = false
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    assertFalse(bell.state().encryptionConfirmed)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    ringClear(token, payload(order("#insto poner el volumen al 40 por ciento")))
    assertTrue("1.18.0 behaviour kept", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
    assertEquals(0, gets)
    assertEquals(1, bell.state().plainPushes)
    assertTrue(InstinctDoorbell.encryptionStatus(app), InstinctDoorbell.encryptionStatus(app).contains("no conforme"))
  }

  @Test fun `a sealed message Insto cannot decrypt is counted and only polls`() {
    val token = register()
    val other = TestPushKeys().apply { generate("x") }
    val foreign = InstinctPushCrypto.seal(other.getPublicKeySet("x")!!.let { PushKeys(it.pubKey, it.auth) }, payload(order("#insto poner el volumen al 40 por ciento")))
    ringClear(token, foreign)
    assertEquals(1, bell.state().decryptFailures)
    assertEquals(0, bell.state().directOrders)
    assertEquals(1, gets)
  }

  @Test fun `a too big push is no order`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    ringClear(token, ByteArray(5_000) { 'a'.code.toByte() })
    assertEquals(0, bell.state().directOrders)
  }

  @Test fun `a new endpoint gets a new sealed doorbell and the script must confirm it again`() {
    register()
    val first = scriptBodies.last().getJSONObject("cifrado").getString("timbre")
    val token = toDistributor.last { it.action == UnifiedPush.ACTION_REGISTER }.getStringExtra(UnifiedPush.EXTRA_TOKEN)
    deliver(UnifiedPush.ACTION_NEW_ENDPOINT, token) { putExtra(UnifiedPush.EXTRA_ENDPOINT, "https://ntfy.sh/upOtro9876543210?up=1") }
    assertEquals("https://ntfy.sh/upOtro9876543210?up=1", scriptBodies.last().getString("endpoint"))
    assertNotEquals(first, scriptBodies.last().getJSONObject("cifrado").getString("timbre"))
    assertTrue(bell.state().encryptionConfirmed)
  }

  @Test fun `a 1_18_0 registration is unregistered and replaced by the library's`() {
    // What 1.18.0 left: its own token and distributor in the state.
    bell.update { it.copy(token = "11111111-2222-3333-4444-555555555555", distributor = ntfy, confirmed = true) }
    bell.writeEndpoint(endpoint)
    InstinctDoorbell.setEnabled(app, true)
    val legacy = sentByInsto.single { it.action == UnifiedPush.ACTION_UNREGISTER }
    assertEquals("11111111-2222-3333-4444-555555555555", legacy.getStringExtra(UnifiedPush.EXTRA_TOKEN))
    assertEquals(ntfy, legacy.`package`)
    assertNull(bell.state().token)
    val token = toDistributor.last { it.action == UnifiedPush.ACTION_REGISTER }.getStringExtra(UnifiedPush.EXTRA_TOKEN)
    assertNotEquals("11111111-2222-3333-4444-555555555555", token)
    // A message for the old token is ignored.
    ringClear("11111111-2222-3333-4444-555555555555", PLAIN)
    assertEquals(0, bell.state().rings)
  }

  @Test fun `the distributor's temporary unavailability is diagnosed and the backup poll stays`() {
    val token = register()
    deliver(UnifiedPush.ACTION_TEMP_UNAVAILABLE, token)
    assertTrue(InstinctDoorbell.diagnosis(app), InstinctDoorbell.diagnosis(app).contains("no está disponible"))
  }


  // 1.19.0: diagnosis and «Probar de punta a punta»

  @Test fun `end to end through the encrypted doorbell tells every step apart and runs nothing`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
    ringOnOrder = { o -> ringWith(token, payload(o)) }
    InstinctDoorbell.endToEndWaitMillis = 2_000
    val steps = InstinctDoorbell.endToEnd(app)
    assertEquals(steps.joinToString("\n"), listOf("Registro", "Entrega del push", "Descifrado", "Verificación de la orden", "Despacho", "Resultado"), steps.map { it.name })
    assertTrue(steps.joinToString("\n"), steps.all { it.ok == true })
    assertTrue(steps.single { it.name == "Despacho" }.detail.contains("sin leer la planilla"))
    assertEquals("nothing changed on the phone", 3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(0, gets)
  }

  @Test fun `end to end without push says Insto is only polling and still verifies through the poll`() {
    register()
    InstinctDoorbell.endToEndWaitMillis = 300
    val steps = InstinctDoorbell.endToEnd(app)
    val delivery = steps.single { it.name == "Entrega del push" }
    assertEquals(false, delivery.ok)
    assertTrue(delivery.detail.contains("solo con la revisión periódica"))
    assertEquals(true, steps.single { it.name == "Verificación de la orden" }.ok)
    assertTrue(steps.single { it.name == "Despacho" }.detail.contains("sondeo"))
  }

  @Test fun `end to end with the doorbell not registered stops at the registration`() {
    InstinctDoorbell.setEnabled(app, true)
    val steps = InstinctDoorbell.endToEnd(app)
    assertEquals(false, steps.single().ok)
    assertEquals("Registro", steps.single().name)
  }

  @Test fun `the details show every part of the transport and no secret`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    ringWith(token, PLAIN)
    val lines = InstinctDoorbell.details(app)
    val text = lines.joinToString("\n")
    listOf("Distribuidor:", "Registro: registrado", "Cifrado RFC 8291", "Endpoint ••••", "Última recepción push:", "Última orden directa validada:",
      "Último sondeo del buzón:", "Duplicados descartados", "Cola de recepción:", "Último error:").forEach { assertTrue("$it in\n$text", text.contains(it)) }
    listOf(endpoint, publicKeys().p256dh, publicKeys().auth, token, key).forEach { assertFalse(text.contains(it)) }
  }


  // 1.19.0: the same policies whatever the transport

  @Test fun `with the screen locked a direct order is received, verified and, being a setting, runs (1_20_0)`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    InstinctProposalCenter.keyguardLocked = { true }
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    ringWith(token, payload(order("#insto poner el volumen al 40 por ciento")))
    assertEquals("received and handed over", 1, bell.state().directOrders)
    assertEquals(0, gets)
    assertEquals("same rule as a polled order", listOf("ok"), acks("poner el volumen al 40 por ciento"))
    assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 40 / 100, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
  }

  @Test fun `a direct order that would open another app from the background waits instead of opening it`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    installNativeHandler(app, android.provider.AlarmClock.ACTION_SET_TIMER)
    ringWith(token, payload(order("#insto poner un temporizador de 3 minutos")))
    assertNull(shadowOf(app).nextStartedActivity)
    assertEquals(listOf("espera"), acks("poner un temporizador de 3 minutos"))
  }

  @Test fun `autonomy Nunca refuses a direct order and Confirmar makes it wait`() {
    val token = register()
    InstinctDoorbell.setWithOrder(app, true)
    val autonomy = InstinctAutonomySettings(app)
    try {
      autonomy.set(InstinctCategory.VOLUMEN, InstinctAutonomy.NUNCA)
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
      ringWith(token, payload(order("#insto poner el volumen al 40 por ciento")))
      assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
      assertEquals(listOf("error"), acks("poner el volumen al 40 por ciento"))
      autonomy.set(InstinctCategory.VOLUMEN, InstinctAutonomy.CONFIRMAR)
      ringWith(token, payload(order("#insto poner el volumen al 30 por ciento")))
      assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
      assertEquals(listOf("espera"), acks("poner el volumen al 30 por ciento"))
    } finally {
      autonomy.set(InstinctCategory.VOLUMEN, InstinctAutonomy.SOLO)
    }
  }

}
