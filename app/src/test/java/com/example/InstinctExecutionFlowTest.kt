package com.example

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.AssistantPreferencesRepository
import com.example.phone.*
import com.example.testsupport.awaitCachedPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

/**
 * The execution journal (1.20.0) on the real paths: push, poll, WhatsApp, deferred orders, the claim and the rows in
 * one transaction, the migration of 1.19.0's record and the interruptions recovery must survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctExecutionFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private var wall = 1_790_000_000_000L
  private val url = "https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec"
  private val token = "token-largo-de-prueba"
  private val key = "clave-del-buzon-de-prueba-0123456789-abcdef"
  private val sheet = mutableListOf<MailboxOrder>()
  private val taken = mutableListOf<String>()
  private val audio get() = app.getSystemService(AudioManager::class.java)
  private val box get() = InstinctMailboxSettings(app)
  private val journal get() = InstinctExecutions.journal(app)
  private val live = InstinctExecutionJournal.owner

  @Before fun setUp() {
    InstinctExecutionJournal.resetAll()
    InstinctExecutions.reset()
    InstinctExecutionJournal.owner = live
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
    InstinctMailbox.schedule = { _, _ -> }
    InstinctMailbox.background = { it.run() }
    InstinctMailbox.lastPollAt = 0L
    InstinctMailbox.gate.reset()
    InstinctPushIntake.execute = { it.run() }
    InstinctPushDispatcher.execute = { it.run() }
    InstinctPushDispatcher.reset()
    InstinctDoorbell.schedule = { _, _, _ -> }
    InstinctDoorbell.cancel = { _, _ -> }
    val fake = FakeHttp { ex ->
      if (ex.request.method == "GET") FakeHttp.Reply(200, JSONObject().put("ok", true).put("ordenes", JSONArray(sheet.filter { it.id !in taken }.map {
        JSONObject().put("orden_id", it.id).put("at", it.at).put("lineas", JSONArray(it.lines)).put("firma", it.sig)
      })).toString())
      else {
        val ids = JSONObject(ex.bodyText()).optJSONArray("ids") ?: JSONArray()
        (0 until ids.length()).forEach { taken += ids.getString(it) }
        FakeHttp.Reply(200, """{"ok":true}""")
      }
    }
    InstinctMailbox.client = { InstinctMailboxClient(InstinctLogClient(OkHttpClient.Builder().addInterceptor(fake).build())) }
    InstinctLog.outbox(app).remove(InstinctLog.outbox(app).batch(500).map { it.id }.toSet())
    File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).delete()
    File(app.noBackupFilesDir, InstinctExecutionJournal.FILE).delete()
    File(app.noBackupFilesDir, InstinctPushInbox.FILE).delete()
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"; applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+54 9 11 2233-4455") }
    awaitCachedPreferences(app) { it.pinnedContact?.displayName == "Instinct" }
    InstinctProposalSettings(app).setEnabled(true)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.BITACORA)
    assertTrue(InstinctLogSettings(app).save(url, token))
    assertTrue(box.saveKey(key))
    box.setEnabled(true)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
  }

  @After fun tearDown() {
    InstinctExecutionJournal.owner = live
    box.setEnabled(false)
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
    InstinctProposalCenter.outcomeVerifier = { AndroidActionOutcomeVerifier(it) }
    InstinctExecutionJournal.resetAll()
  }

  private fun order(vararg lines: String, id: String = UUID.randomUUID().toString(), at: Long = wall): MailboxOrder {
    val iso = InstinctLogEvents.iso(at)
    return MailboxOrder(id, iso, lines.toList(), InstinctMailboxSignature.sign(key, InstinctMailboxSignature.canonical(id, iso, lines.toList())))
  }

  private fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
  private fun max() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
  private fun events() = InstinctLog.outbox(app).batch(500).filter { it.kind != "estado" }
  private fun push(order: MailboxOrder) {
    InstinctPushDispatcher.inbox(app).add(PushEntry.Kind.ORDER, order, encrypted = true)
    InstinctPushDispatcher.drain(app)
  }

  /** Rows of [order] made by a process that died: as if it was claimed there and nothing else happened. */
  private fun claimedByDeadProcess(order: MailboxOrder, transport: ExecutionTransport = ExecutionTransport.PUSH) {
    InstinctExecutionJournal.owner = "proceso-muerto"
    assertEquals(setOf(order.id.lowercase()), InstinctMailbox.seen(app).claimOrders(listOf(order), transport))
    InstinctExecutionJournal.owner = live
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Identity
  // ---------------------------------------------------------------------------------------------------------------

  @Test fun `the three orders of one message get independent rows and run independently`() {
    val o = order("#insto poner el volumen al 40 por ciento", "#insto ver programadas", "#insto qué hora es")
    push(o)
    val rows = journal.forMessage(o.id)
    assertEquals(3, rows.size)
    assertEquals(3, rows.map { it.executionId }.distinct().size)
    assertEquals(listOf(0, 1, 2), rows.map { it.index })
    assertTrue(rows.all { it.state.terminal })
    assertEquals(ExecutionState.FAILED, rows[2].state)
    assertNotEquals(ExecutionState.FAILED, rows[0].state)
    assertEquals(max() * 40 / 100, volume())
  }

  @Test fun `two legitimate orders with the same text and different ids both run`() {
    val first = order("#insto bajar el volumen")
    val second = order("#insto bajar el volumen")
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
    // Both arrive before the first one runs: offered together, as a push and a poll can.
    InstinctPushDispatcher.inbox(app).add(PushEntry.Kind.ORDER, first, encrypted = true)
    InstinctPushDispatcher.inbox(app).add(PushEntry.Kind.ORDER, second, encrypted = true)
    InstinctPushDispatcher.drain(app)
    val a = journal.forMessage(first.id).single()
    val b = journal.forMessage(second.id).single()
    assertNotEquals(a.executionId, b.executionId)
    assertTrue("$a", a.state == ExecutionState.VERIFIED || a.state == ExecutionState.HANDED_OFF)
    assertTrue("$b", b.state == ExecutionState.VERIFIED || b.state == ExecutionState.HANDED_OFF)
    assertEquals(8, volume())
  }

  @Test fun `the same message by push and by poll runs once and has one row per order`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    sheet += o
    push(o)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    InstinctMailbox.poll(app)
    assertEquals(0, volume())
    assertEquals(1, journal.forMessage(o.id).size)
    assertTrue(o.id in taken)
  }

  @Test fun `a push and a poll racing on real threads claim the id once`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    val claims = InstinctMailbox.seen(app)
    val pool = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    val won = java.util.concurrent.ConcurrentLinkedQueue<String>()
    repeat(16) { i ->
      pool.execute { start.await(); won += claims.claimOrders(listOf(o), if (i % 2 == 0) ExecutionTransport.PUSH else ExecutionTransport.POLL) }
    }
    start.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
    assertEquals(listOf(o.id.lowercase()), won.toList())
    assertEquals(1, journal.forMessage(o.id).size)
  }

  @Test fun `an order from WhatsApp has its row too, with its own message identity`() {
    val records = (journal.admit(ExecutionIds.whatsapp("abc123abc123", wall, "n1"), ExecutionTransport.WHATSAPP, listOf("subir el volumen")) as
      InstinctExecutionJournal.Admission.Admitted).records
    val again = (journal.admit(ExecutionIds.whatsapp("abc123abc123", wall, "n2"), ExecutionTransport.WHATSAPP, listOf("subir el volumen")) as
      InstinctExecutionJournal.Admission.Admitted).records
    assertNotEquals(records.single().executionId, again.single().executionId)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Transactions and storage
  // ---------------------------------------------------------------------------------------------------------------

  @Test fun `claiming without the journal runs nothing and tells the sheet nothing`() {
    // 1.19.0's record unreadable (a directory where the file was): the journal refuses to start without it.
    File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).apply { delete(); mkdirs() }
    val o = order("#insto poner el volumen al 40 por ciento")
    sheet += o
    assertEquals(InstinctMailbox.Result.Failed(InstinctMailbox.STORAGE_FAILURE), InstinctMailbox.poll(app))
    assertEquals(0, volume())
    assertFalse(o.id in taken)
    File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).deleteRecursively()
    InstinctExecutionJournal.resetAll()
    InstinctMailbox.poll(app)
    assertEquals(max() * 40 / 100, volume())
  }

  @Test fun `a terminal row is never left and a row is dispatched at most once`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    claimedByDeadProcess(o)
    val id = journal.forMessage(o.id).single().executionId
    assertTrue(journal.beginDispatch(id, null))
    assertFalse("dispatched twice", journal.beginDispatch(id, null))
    assertTrue(journal.transition(id, ExecutionState.UNCERTAIN, reason = "x"))
    assertFalse(journal.transition(id, ExecutionState.VERIFIED))
    assertFalse(journal.beginDispatch(id, null))
    assertEquals(ExecutionState.UNCERTAIN, journal.get(id)!!.state)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Migration from 1.19.0
  // ---------------------------------------------------------------------------------------------------------------

  @Test fun `ids 1_19_0 saw are migrated, never repeated, and its file is kept as it was`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    val text = JSONObject().put(o.id.lowercase(), wall - 60_000).put("otro-id", wall - 120_000).toString()
    File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).writeText(text)
    sheet += o
    InstinctMailbox.poll(app)
    assertEquals("already seen by 1.19.0: not run again", 0, volume())
    assertEquals(2, journal.migratedClaims())
    assertEquals(text, File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).readText())
    assertTrue(journal.forMessage(o.id).isEmpty())
  }

  @Test fun `an unreadable 1_19_0 record is set aside, kept, and read as empty as 1_19_0 did`() {
    File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).writeText("{no es json")
    val o = order("#insto poner el volumen al 40 por ciento")
    sheet += o
    InstinctMailbox.poll(app)
    assertEquals(max() * 40 / 100, volume())
    val aside = journal.legacyCorrupt()!!
    assertEquals("{no es json", File(app.noBackupFilesDir, aside).readText())
  }

  @Test fun `a doorbell entry 1_19_0 claimed without a row stays uncertain, is said once and never runs`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    val inbox = InstinctPushDispatcher.inbox(app)
    val entry = (inbox.add(PushEntry.Kind.ORDER, o, encrypted = true) as InstinctPushInbox.Added.Queued).entry
    // 1.19.0 wrote the claim in its file and marked the entry; the journal never heard of it.
    File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).writeText(JSONObject().put(o.id.lowercase(), wall).toString())
    assertTrue(inbox.markClaimed(entry.seq))
    InstinctPushDispatcher.drain(app)
    InstinctPushDispatcher.drain(app)
    assertEquals(0, volume())
    val row = journal.forMessage(o.id).single()
    assertEquals(ExecutionState.UNCERTAIN, row.state)
    assertEquals(ExecutionTransport.LEGACY, row.transport)
    assertEquals(1, events().count { it.kind == "incierto" })
    assertEquals(0, inbox.size())
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Interruptions
  // ---------------------------------------------------------------------------------------------------------------

  @Test fun `interrupted after receiving it in the push queue, before claiming - the next drain runs it once`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    InstinctPushDispatcher.inbox(app).add(PushEntry.Kind.ORDER, o, encrypted = true)
    // The process dies here; a new one drains.
    InstinctPushDispatcher.drain(app)
    InstinctPushDispatcher.drain(app)
    assertEquals(max() * 40 / 100, volume())
    assertEquals(1, journal.forMessage(o.id).size)
  }

  @Test fun `interrupted after claiming, before the proposal - recovery runs it once, as long as it is in time`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    claimedByDeadProcess(o)
    assertEquals(1, InstinctExecutionRecoveryManager.recover(app))
    assertEquals(max() * 40 / 100, volume())
    assertTrue(journal.forMessage(o.id).single().state.terminal)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    assertEquals(0, InstinctExecutionRecoveryManager.recover(app))
    assertEquals(0, volume())
  }

  @Test fun `interrupted after claiming, found too late - expired, said once, never run`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    claimedByDeadProcess(o)
    wall += InstinctMailboxSignature.MAX_AGE_MILLIS + 60_000
    InstinctExecutionRecoveryManager.recover(app)
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals(0, volume())
    assertEquals(ExecutionState.EXPIRED, journal.forMessage(o.id).single().state)
    assertEquals(1, events().count { it.kind == "vencida" })
  }

  @Test fun `interrupted after the proposal, before dispatch - recovery offers it again once`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    claimedByDeadProcess(o)
    val row = journal.forMessage(o.id).single()
    InstinctExecutionJournal.owner = "proceso-muerto"
    assertTrue(journal.transition(row.executionId, ExecutionState.VALIDATED))
    assertTrue(journal.transition(row.executionId, ExecutionState.READY, proposalId = 1))
    InstinctExecutionJournal.owner = live
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals(max() * 40 / 100, volume())
  }

  @Test fun `interrupted during dispatch - uncertain, never repeated, said once`() {
    val o = order("#insto subir el volumen")
    claimedByDeadProcess(o)
    val row = journal.forMessage(o.id).single()
    InstinctExecutionJournal.owner = "proceso-muerto"
    assertTrue(journal.beginDispatch(row.executionId, null))
    InstinctExecutionJournal.owner = live
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
    InstinctExecutionRecoveryManager.recover(app)
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals("a relative step is never repeated", 5, volume())
    assertEquals(ExecutionState.UNCERTAIN, journal.get(row.executionId)!!.state)
    assertEquals(1, events().count { it.kind == "incierto" })
  }

  @Test fun `with the switch turned off meanwhile, a recovered order is cancelled, not run`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    claimedByDeadProcess(o)
    box.setEnabled(false)
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals(0, volume())
    assertEquals(ExecutionState.CANCELLED, journal.forMessage(o.id).single().state)
  }

  @Test fun `a refused line never becomes executable through the journal`() {
    InstinctAutonomySettings(app).set(InstinctCategory.VOLUMEN, InstinctAutonomy.NUNCA)
    val o = order("#insto poner el volumen al 40 por ciento", "#insto mandá un whatsapp a Juan")
    push(o)
    val rows = journal.forMessage(o.id)
    assertEquals(ExecutionState.CANCELLED, rows[0].state)
    assertEquals(ExecutionState.FAILED, rows[1].state)
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals(0, volume())
    InstinctAutonomySettings(app).set(InstinctCategory.VOLUMEN, InstinctAutonomy.SOLO)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Verification (fase 2)
  // ---------------------------------------------------------------------------------------------------------------

  @Test fun `a volume order is read back - verified, changed by it, and said so to Instinct`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    push(o)
    val row = journal.forMessage(o.id).single()
    assertEquals(ExecutionState.VERIFIED, row.state)
    assertEquals(ExecutionAttribution.CAUSED, row.attribution)
    val ok = events().single { it.kind == "ok" }
    assertTrue(ok.detail, ok.detail.contains("Verificado: quedó en ${max() * 40 / 100} de ${max()}"))
  }

  @Test fun `a volume already at the level is verified as already so, not as caused`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, max() * 40 / 100, 0)
    val o = order("#insto poner el volumen al 40 por ciento")
    push(o)
    assertEquals(ExecutionAttribution.ALREADY, journal.forMessage(o.id).single().attribution)
  }

  @Test fun `Do not disturb is verified by the effective filter`() {
    shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
    val o = order("#insto activar no molestar")
    push(o)
    val row = journal.forMessage(o.id).single()
    assertEquals("$row ${row.reason} ${row.result}", ExecutionState.VERIFIED, row.state)
  }

  @Test fun `when the phone does not reach the requested state it is reported as not done`() {
    // Android accepted the call but the reading says otherwise (a cap, a policy, another app).
    InstinctProposalCenter.outcomeVerifier = { object : ActionOutcomeVerifier {
      override fun read(command: PhoneCommand) = DeviceReading(volume = 2, volumeMax = 15)
    } }
    val o = order("#insto poner el volumen al 40 por ciento")
    push(o)
    val row = journal.forMessage(o.id).single()
    assertEquals(ExecutionState.FAILED, row.state)
    val error = events().single { it.kind == "error" }
    assertTrue(error.detail, error.detail.startsWith("No se logró"))
    assertEquals(0, events().count { it.kind == "ok" })
  }

  @Test fun `the torch without a camera report is handed off, never verified`() {
    shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
    InstinctProposalCenter.outcomeVerifier = { object : ActionOutcomeVerifier {
      override fun read(command: PhoneCommand) = DeviceReading(torch = null)
    } }
    val o = order("#insto prender la linterna")
    push(o)
    val row = journal.forMessage(o.id).single()
    assertTrue("$row ${row.reason}", row.state == ExecutionState.HANDED_OFF || row.state == ExecutionState.FAILED)
    assertNotEquals(ExecutionState.VERIFIED, row.state)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Recovery (fase 3)
  // ---------------------------------------------------------------------------------------------------------------

  /** A row a dead process left in [state], with its command stored as a dispatch would. */
  private fun leftBy(line: String, state: ExecutionState, command: PhoneCommand? = null): ExecutionRecord {
    val o = order("#insto $line")
    claimedByDeadProcess(o)
    val row = journal.forMessage(o.id).single()
    InstinctExecutionJournal.owner = "proceso-muerto"
    if (state == ExecutionState.DISPATCHING || state == ExecutionState.VERIFYING) {
      assertTrue(journal.beginDispatch(row.executionId, command?.let { PhoneCommandCodec.encode(it).toString() }))
      if (state == ExecutionState.VERIFYING) assertTrue(journal.transition(row.executionId, ExecutionState.VERIFYING))
    } else assertTrue(journal.transition(row.executionId, state, reason = "la persona pide confirmación para volumen"))
    InstinctExecutionJournal.owner = live
    return journal.get(row.executionId)!!
  }

  @Test fun `a confirmation a dead process left is offered again, still waiting, and never runs by itself`() {
    val row = leftBy("poner el volumen al 40 por ciento", ExecutionState.WAITING)
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals(0, volume())
    val proposal = InstinctProposalCenter.book.pending().single()
    assertEquals(row.executionId, proposal.executionId)
    assertEquals(ExecutionState.WAITING, journal.get(row.executionId)!!.state)
    // The person confirms it: it runs once and the log hears the outcome.
    InstinctProposalCenter.claim(app, proposal.id, proposal.token, fromNotification = true).let { claim ->
      assertTrue(claim is ProposalBook.Claim.Granted)
      val result = PhoneActionExecutor(app).execute(proposal.command)
      InstinctProposalCenter.report(app, proposal, result)
    }
    assertEquals(max() * 40 / 100, volume())
    assertEquals(ExecutionState.VERIFIED, journal.get(row.executionId)!!.state)
    assertEquals(ExecutionAttribution.SATISFIED, journal.get(row.executionId)!!.attribution)
    assertTrue(events().any { it.kind == "ok" })
  }

  @Test fun `a confirmation past its time expires, is said once and is not offered`() {
    val row = leftBy("poner el volumen al 40 por ciento", ExecutionState.WAITING)
    wall += ProposalBook.TTL_MILLIS + 1
    InstinctExecutionRecoveryManager.recover(app)
    InstinctExecutionRecoveryManager.recover(app)
    assertTrue(InstinctProposalCenter.book.pending().isEmpty())
    assertEquals(ExecutionState.EXPIRED, journal.get(row.executionId)!!.state)
    assertEquals(1, events().count { it.kind == "vencida" })
  }

  @Test fun `an interrupted volume set whose level holds is verified as satisfied, never caused, and not run again`() {
    val command = PhoneCommand.Volume(VolumeOp.SET, 40)
    val row = leftBy("poner el volumen al 40 por ciento", ExecutionState.DISPATCHING, command)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, max() * 40 / 100, 0)
    InstinctExecutionRecoveryManager.recover(app)
    val after = journal.get(row.executionId)!!
    assertEquals(ExecutionState.VERIFIED, after.state)
    assertEquals(ExecutionAttribution.SATISFIED, after.attribution)
    assertEquals(1, events().count { it.kind == "ok" && it.detail.contains("recuperada") })
  }

  @Test fun `an interrupted volume set whose level does not hold stays uncertain and is not run again`() {
    val row = leftBy("poner el volumen al 40 por ciento", ExecutionState.DISPATCHING, PhoneCommand.Volume(VolumeOp.SET, 40))
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals(0, volume())
    assertEquals(ExecutionState.UNCERTAIN, journal.get(row.executionId)!!.state)
  }

  @Test fun `interrupted during verification - only the read is repeated`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, max() * 40 / 100, 0)
    val row = leftBy("poner el volumen al 40 por ciento", ExecutionState.VERIFYING, PhoneCommand.Volume(VolumeOp.SET, 40))
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals(ExecutionState.VERIFIED, journal.get(row.executionId)!!.state)
    val alarm = leftBy("poner una alarma a las 7", ExecutionState.VERIFYING, PhoneCommand.Alarm(7, 0))
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals(ExecutionState.HANDED_OFF, journal.get(alarm.executionId)!!.state)
  }

  @Test fun `a scheduled row whose series the scheduler no longer holds is cancelled without a second message`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    claimedByDeadProcess(o)
    val row = journal.forMessage(o.id).single()
    assertTrue(journal.transition(row.executionId, ExecutionState.SCHEDULED, occurrence = "serie-que-ya-no-existe"))
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals(ExecutionState.CANCELLED, journal.get(row.executionId)!!.state)
    assertTrue(events().isEmpty())
  }

  @Test fun `a doorbell entry a dead process claimed with its rows is left to the journal - one outcome, not two`() {
    val o = order("#insto poner el volumen al 40 por ciento")
    val inbox = InstinctPushDispatcher.inbox(app)
    val entry = (inbox.add(PushEntry.Kind.ORDER, o, encrypted = true) as InstinctPushInbox.Added.Queued).entry
    claimedByDeadProcess(o)
    assertTrue(inbox.markClaimed(entry.seq))
    InstinctPushDispatcher.drain(app)
    // Only received: nothing touched the phone, so it is resumed in time and runs once.
    assertEquals(max() * 40 / 100, volume())
    assertEquals(0, events().count { it.kind == "incierto" })
    assertEquals(1, events().count { it.kind == "ok" })
    assertEquals(0, inbox.size())
  }

  @Test fun `a temporary mode's restoration is an execution of its own, verified against the original value`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
    val manager = TemporaryModeManager(app)
    val applied = manager.execute(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 60, percent = 80, approximate = true), confirmed = true)
    assertEquals(applied.message, PhoneActionOutcome.COMPLETED, applied.outcome)
    val lease = manager.read()!!
    TemporaryModeRestorations.restore(app, manager, lease)
    assertEquals(3, volume())
    val row = journal.forMessage("restore:${lease.id}").single()
    assertEquals(ExecutionState.VERIFIED, row.state)
    assertEquals(ExecutionTransport.RESTORE, row.transport)
  }

  @Test fun `a manual change during a temporary mode is kept and recorded as not restored`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
    val manager = TemporaryModeManager(app)
    manager.execute(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 60, percent = 80, approximate = true), confirmed = true)
    val lease = manager.read()!!
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)
    manager.observeManualChange()
    TemporaryModeRestorations.restore(app, manager, manager.read()!!)
    assertEquals(9, volume())
    assertEquals(ExecutionState.CANCELLED, journal.forMessage("restore:${lease.id}").single().state)
  }

  @Test fun `a new process keeps a temporary mode it can still restore, and drops one that was changed by hand`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
    val manager = TemporaryModeManager(app)
    manager.execute(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 60, percent = 80, approximate = true), confirmed = true)
    manager.markUnobserved()
    assertTrue(manager.read()!!.owned)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)
    manager.markUnobserved()
    assertFalse(manager.read()!!.owned)
    manager.end(force = true)
  }
}
