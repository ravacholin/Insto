package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
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

/**
 * Plans of several steps (1.23.0) on the real paths: the signed mailbox, the journal, the executor, the verifier, the
 * questions, the calendar triggers and recovery after a process death.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctPlanFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private var wall = 1_790_000_000_000L
  private val url = "https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec"
  private val token = "token-largo-de-prueba"
  private val key = "clave-del-buzon-de-prueba-0123456789-abcdef"
  private val audio get() = app.getSystemService(AudioManager::class.java)
  private val notifications get() = app.getSystemService(NotificationManager::class.java)
  private val box get() = InstinctMailboxSettings(app)
  private val journal get() = InstinctExecutions.journal(app)
  private val live = InstinctExecutionJournal.owner
  private val ran = mutableListOf<PhoneCommand>()
  private val chat = mutableListOf<String>()
  private var events = listOf<CalendarInstance>()

  @Before fun setUp() {
    InstinctExecutionJournal.resetAll()
    InstinctExecutions.reset()
    InstinctPlanEngine.reset()
    InstinctExecutionJournal.owner = live
    InstinctProposalCenter.clock = { 1_000_000L }
    InstinctScheduler.wallClock = { wall }
    InstinctScheduler.alarms = { _, _, _ -> }
    InstinctScheduler.calendar = { _, _ -> events }
    InstinctPlanEngine.background = { it.run() }
    InstinctPlanEngine.alarms = { _, _ -> }
    InstinctPlanEngine.execute = { context, command ->
      ran += command
      // Robolectric has no media session: Android takes the command and its effect cannot be observed.
      if (command is PhoneCommand.Media) PhoneActionResult("Pausé la música.", outcome = PhoneActionOutcome.HANDOFF)
      else PhoneActionExecutor(context).execute(command, confirmed = InstinctConfirmations.preAuthorized(command))
    }
    InstinctProposalCenter.budget.reset()
    InstinctProposalCenter.acks.clear()
    InstinctProposalCenter.accessibilityConnected = { false }
    InstinctProposalCenter.keyguardLocked = { false }
    InstinctProposalCenter.replySender = { _, _, text -> chat += text; true }
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
    val fake = FakeHttp { FakeHttp.Reply(200, JSONObject().put("ok", true).put("ordenes", JSONArray()).toString()) }
    InstinctMailbox.client = { InstinctMailboxClient(InstinctLogClient(OkHttpClient.Builder().addInterceptor(fake).build())) }
    InstinctLog.outbox(app).remove(InstinctLog.outbox(app).batch(500).map { it.id }.toSet())
    File(app.noBackupFilesDir, InstinctMailboxSeen.FILE).delete()
    File(app.noBackupFilesDir, InstinctExecutionJournal.FILE).delete()
    File(app.noBackupFilesDir, InstinctPushInbox.FILE).delete()
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowOf(notifications).setNotificationPolicyAccessGranted(true)
    notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
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
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
  }

  @After fun tearDown() {
    InstinctExecutionJournal.owner = live
    InstinctCategory.entries.forEach { InstinctAutonomySettings(app).set(it, InstinctAutonomy.SOLO) }
    box.setEnabled(false)
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.WHATSAPP)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.NEVER)
    InstinctProposalSettings(app).setEnabled(false)
    InstinctLogSettings(app).clear()
    InstinctProposalCenter.book.discardAll()
    InstinctProposalCenter.replySender = { context, handle, text -> handle.send(context, text) }
    InstinctProposalCenter.accessibilityConnected = { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
    InstinctProposalCenter.keyguardLocked = { it.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true }
    InstinctProposalCenter.clock = android.os.SystemClock::elapsedRealtime
    InstinctScheduler.wallClock = System::currentTimeMillis
    InstinctScheduler.calendar = { context, now -> InstinctCalendarReader(context).instances(now) }
    InstinctMailbox.clock = { InstinctScheduler.wallClock() }
    InstinctPlanEngine.reset()
    InstinctExecutionJournal.resetAll()
  }

  private fun order(vararg lines: String, id: String = UUID.randomUUID().toString(), at: Long = wall): MailboxOrder {
    val iso = InstinctLogEvents.iso(at)
    return MailboxOrder(id, iso, lines.toList(), InstinctMailboxSignature.sign(key, InstinctMailboxSignature.canonical(id, iso, lines.toList())))
  }

  private fun push(order: MailboxOrder) {
    InstinctPushDispatcher.inbox(app).add(PushEntry.Kind.ORDER, order, encrypted = true)
    InstinctPushDispatcher.drain(app)
  }

  private fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
  private fun percent(value: Int) = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * value / 100
  private fun dnd() = notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
  private fun planOf(order: MailboxOrder): PlanRecord {
    val root = journal.forMessage(order.id).single()
    return requireNotNull(journal.plan(PlanIds.of(root.executionId))) { "sin plan para ${root.state} ${root.result} ${root.reason}" }
  }
  private fun planEvents() = InstinctLog.outbox(app).batch(500).filter { it.kind == InstinctPlanEngine.LOG_KIND }
  private fun stepRows(plan: PlanRecord) = journal.byOccurrence(plan.id).filterNot { InstinctQuestion.isRelated(it.line) }
  private fun question(plan: PlanRecord): Notification = notifications.activeNotifications.single { it.tag == plan.question }.notification
  private fun tap(notification: Notification, index: Int) =
    InstinctQuestionNotifications.handle(app, shadowOf(notification.actions[index].actionIntent).savedIntent)

  @Test fun `a plan runs each step after checking the previous one, with the phone locked, and reports only to the log`() {
    InstinctProposalCenter.keyguardLocked = { true }
    shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(false)
    val incoming = order("#insto plan: activar no molestar; poner el volumen al 20 por ciento; pausar la música")
    push(incoming)
    val root = journal.forMessage(incoming.id).single()
    assertEquals(ExecutionState.VERIFIED, root.state)
    assertTrue(root.result.orEmpty().startsWith("Plan p"))
    val plan = planOf(incoming)
    assertEquals(PlanState.DONE, plan.state)
    assertTrue(plan.closed)
    assertTrue(dnd())
    assertEquals(percent(20), volume())
    assertEquals(3, ran.size)
    val rows = stepRows(plan)
    assertEquals(3, rows.size)
    assertTrue(rows.all { it.transport == ExecutionTransport.PLAN && it.attempts == 1 && it.state.terminal && it.state != ExecutionState.FAILED })
    assertEquals(ExecutionState.VERIFIED, rows.single { it.line == "activar no molestar" }.state)
    assertEquals(ExecutionState.HANDED_OFF, rows.single { it.line == "pausar la música" }.state)
    // Progress and the result in the log, as events of kind «plan»; nothing in the chat.
    val log = planEvents()
    assertTrue(log.size >= 4)
    assertTrue(log.any { it.order.contains("· fin ·") && it.detail.startsWith(PlanState.DONE.code) })
    assertTrue(log.all { InstinctLogEvents.safe(it) && it.protocol == InstinctProtocolCatalog.TITLE })
    assertTrue(chat.isEmpty())
    assertTrue(InstinctPlanEngine.describe(app).contains("${plan.shortId} completado"))
    assertTrue(InstinctPlanEngine.status(app, plan.shortId).contains("1.1 ok"))
  }

  @Test fun `the same order twice is one plan and no action runs twice`() {
    val incoming = order("#insto plan: poner el volumen al 20 por ciento; pausar la música")
    push(incoming)
    push(incoming)
    InstinctProposalIntake.onMailbox(app, incoming)
    InstinctPlanEngine.recover(app)
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals(1, journal.plans().size)
    assertEquals(2, ran.size)
    assertTrue(stepRows(planOf(incoming)).all { it.attempts == 1 })
  }

  @Test fun `a failed step stops the plan unless it has an explicit alternative`() {
    shadowOf(notifications).setNotificationPolicyAccessGranted(false)
    val stopped = order("#insto plan: activar no molestar; poner el volumen al 20 por ciento")
    push(stopped)
    val plan = planOf(stopped)
    assertEquals(PlanState.FAILED, plan.state)
    assertEquals(10, volume())
    assertEquals(1, ran.size)
    val alternative = order("#insto plan: activar no molestar si falla bajar el volumen; poner el volumen al 20 por ciento")
    push(alternative)
    val second = planOf(alternative)
    assertEquals(PlanState.DONE, second.state)
    assertEquals(percent(20), volume())
    assertTrue(journal.planSteps(second.id).any { it.position == "1.1" && it.status == "fallida" })
    assertTrue(journal.planSteps(second.id).any { it.position == "1.1a" && it.status == "ok" })
  }

  @Test fun `an uncertain step is never repeated - the plan is suspended and resumes past it`() {
    InstinctPlanEngine.execute = { _, command ->
      ran += command
      if (command is PhoneCommand.Media) PhoneActionResult("Android no confirmó.", outcome = PhoneActionOutcome.UNCERTAIN)
      else PhoneActionExecutor(app).execute(command)
    }
    val incoming = order("#insto plan: pausar la música; poner el volumen al 20 por ciento")
    push(incoming)
    var plan = planOf(incoming)
    assertEquals(PlanState.SUSPENDED, plan.state)
    assertEquals(10, volume())
    InstinctPlanEngine.recover(app)
    assertEquals(1, ran.size)
    assertTrue(InstinctPlanEngine.resume(app, plan.shortId).startsWith("Se reanudó"))
    plan = planOf(incoming)
    assertEquals(PlanState.DONE, plan.state)
    assertEquals(percent(20), volume())
    assertEquals(1, ran.count { it is PhoneCommand.Media })
    assertTrue(InstinctPlanEngine.resume(app, plan.shortId).contains("no está suspendido"))
  }

  @Test fun `after a process death a dispatched setting is read back and the plan goes on without repeating it`() {
    val incoming = order("#insto plan: esperar 5 minutos; poner el volumen al 20 por ciento; pausar la música")
    push(incoming)
    var plan = planOf(incoming)
    assertEquals(PlanState.WAITING, plan.state)
    // Another process woke up, wrote «despachando», changed the volume and died.
    InstinctExecutionJournal.owner = "proceso-muerto"
    plan = requireNotNull(journal.updatePlan(plan.id, setOf(PlanState.WAITING)) {
      it.copy(state = PlanState.RUNNING, block = 1, sub = 0, wakeAt = null)
    })
    val message = PlanIds.step(plan.id, 1, 0, false)
    val row = (journal.admit(message, ExecutionTransport.PLAN, listOf("poner el volumen al 20 por ciento"), plan.message,
      occurrence = plan.id, claim = false) as InstinctExecutionJournal.Admission.Admitted).records.single()
    assertTrue(journal.beginDispatch(row.executionId, PhoneCommandCodec.encode(PhoneCommand.Volume(VolumeOp.SET, 20)).toString()))
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, percent(20), 0)
    InstinctExecutionJournal.resetAll()
    InstinctPlanEngine.reset()
    InstinctExecutionJournal.owner = live
    wall += 6 * 60_000
    InstinctExecutionRecoveryManager.recover(app)
    plan = planOf(incoming)
    assertEquals(PlanState.DONE, plan.state)
    assertEquals(live, plan.owner)
    val recovered = journal.get(row.executionId)!!
    assertEquals(1, recovered.attempts)
    assertEquals(ExecutionState.VERIFIED, recovered.state)
    assertEquals(ExecutionAttribution.SATISFIED, recovered.attribution)
    assertTrue(ran.none { it is PhoneCommand.Volume })
    assertEquals(1, ran.count { it is PhoneCommand.Media })
  }

  @Test fun `an interrupted action that cannot be read back suspends the plan as uncertain`() {
    val incoming = order("#insto plan: esperar 5 minutos; pausar la música; bajar el volumen")
    push(incoming)
    var plan = planOf(incoming)
    InstinctExecutionJournal.owner = "proceso-muerto"
    plan = requireNotNull(journal.updatePlan(plan.id) { it.copy(state = PlanState.RUNNING, block = 1, sub = 0, wakeAt = null) })
    val row = (journal.admit(PlanIds.step(plan.id, 1, 0, false), ExecutionTransport.PLAN, listOf("pausar la música"), plan.message,
      occurrence = plan.id, claim = false) as InstinctExecutionJournal.Admission.Admitted).records.single()
    assertTrue(journal.beginDispatch(row.executionId, PhoneCommandCodec.encode(PhoneCommand.Media(MediaOp.PAUSE)).toString()))
    InstinctExecutionJournal.resetAll()
    InstinctPlanEngine.reset()
    InstinctExecutionJournal.owner = live
    InstinctExecutionRecoveryManager.recover(app)
    plan = planOf(incoming)
    assertEquals(PlanState.SUSPENDED, plan.state)
    assertEquals(ExecutionState.UNCERTAIN, journal.get(row.executionId)?.state)
    assertTrue(ran.isEmpty())
    assertEquals(10, volume())
  }

  @Test fun `cancelling restores what the plan saved, unless the person changed it afterwards`() {
    val incoming = order("#insto plan: guardar volumen y no molestar; poner el volumen al 20 por ciento; activar no molestar; esperar 30 minutos; restaurar")
    push(incoming)
    var plan = planOf(incoming)
    assertEquals(PlanState.WAITING, plan.state)
    assertTrue(dnd())
    assertEquals(percent(20), volume())
    // The person turns Do not disturb off by hand; the volume stays as the plan left it.
    notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
    assertTrue(InstinctPlanEngine.cancel(app, plan.shortId).startsWith("Se canceló"))
    plan = planOf(incoming)
    assertEquals(PlanState.CANCELLED, plan.state)
    assertTrue(plan.closed)
    assertEquals(10, volume())
    assertTrue(InstinctPlanEngine.cancel(app, plan.shortId).contains("ya terminó"))
    wall += 31 * 60_000
    InstinctPlanEngine.recover(app)
    assertEquals(PlanState.CANCELLED, planOf(incoming).state)
    assertEquals(2, ran.size)
  }

  @Test fun `a restoration leaves a setting the person changed after the plan`() {
    val incoming = order("#insto plan: guardar volumen; poner el volumen al 20 por ciento; esperar 2 minutos; restaurar volumen")
    push(incoming)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0)
    wall += 3 * 60_000
    InstinctPlanEngine.recover(app)
    val plan = planOf(incoming)
    assertEquals(PlanState.DONE, plan.state)
    assertEquals(12, volume())
    assertTrue(journal.planSteps(plan.id).any { it.status == "restaurado" && it.detail.contains("no toqué") })
  }

  @Test fun `when the next event starts Do not disturb goes on and the previous state comes back when it ends`() {
    events = listOf(CalendarInstance(instanceId = 7, eventId = 70, begin = wall + 10 * 60_000, end = wall + 70 * 60_000))
    val incoming = order("#insto ${InstinctPlanSpec.EXAMPLES[1]}")
    push(incoming)
    var plan = planOf(incoming)
    assertEquals(PlanState.WAITING, plan.state)
    assertEquals(wall + 10 * 60_000, plan.wakeAt)
    assertFalse(dnd())
    wall += 10 * 60_000
    InstinctPlanEngine.recover(app)
    plan = planOf(incoming)
    assertTrue(dnd())
    assertEquals(PlanState.WAITING, plan.state)
    assertEquals(wall + 60 * 60_000, plan.wakeAt)
    assertEquals(70L, plan.event?.eventId)
    // The event moves: the plan waits for its new end.
    events = listOf(events.single().copy(end = wall + 90 * 60_000))
    wall += 60 * 60_000
    InstinctPlanEngine.recover(app)
    assertEquals(PlanState.WAITING, planOf(incoming).state)
    assertTrue(dnd())
    wall += 30 * 60_000
    InstinctPlanEngine.recover(app)
    plan = planOf(incoming)
    assertEquals(PlanState.DONE, plan.state)
    assertFalse(dnd())
  }

  @Test fun `a decision suspends the plan, shows the existing buttons and runs the chosen sequence`() {
    InstinctAutonomySettings(app).setShowQuestionsOnLockScreen(false)
    val incoming = order("#insto ${InstinctPlanSpec.EXAMPLES[2]}")
    push(incoming)
    var plan = planOf(incoming)
    assertEquals(PlanState.DECIDING, plan.state)
    assertTrue(ran.isEmpty())
    val notification = question(plan)
    val asked = requireNotNull(plan.question)
    assertEquals(2, notification.actions.size)
    // The process dies and comes back: the same question, not a second one.
    InstinctPlanEngine.reset()
    InstinctPlanEngine.recover(app)
    InstinctExecutionRecoveryManager.recover(app)
    assertEquals(1, notifications.activeNotifications.count { it.tag == plan.question })
    tap(notification, 0)
    tap(notification, 0)
    plan = planOf(incoming)
    assertEquals(PlanState.DONE, plan.state)
    assertTrue(dnd())
    assertEquals(listOf(PhoneCommand.Platform(PlatformAction.DND_ON), PhoneCommand.Media(MediaOp.PAUSE)), ran)
    assertEquals(0, notifications.activeNotifications.count { it.tag == asked })
    assertEquals(ExecutionState.ANSWERED, journal.get(asked)?.state)
  }

  @Test fun `a decision a dead process recorded but never showed is shown once, never as a loose question`() {
    val incoming = order("#insto plan: esperar 2 minutos; ${InstinctPlanSpec.EXAMPLES[2].removePrefix("plan: ")}")
    push(incoming)
    var plan = planOf(incoming)
    InstinctExecutionJournal.owner = "proceso-muerto"
    plan = requireNotNull(journal.updatePlan(plan.id) { it.copy(state = PlanState.RUNNING, block = 1, sub = 0, wakeAt = null) })
    journal.admit(PlanIds.question(plan.id, 1, 0, "d"), ExecutionTransport.PLAN, listOf("decidir ¿Preferís silencio o música? | Silencio | Música"),
      plan.message, deadline = wall + 3_600_000, occurrence = plan.id, claim = false)
    InstinctExecutionJournal.resetAll()
    InstinctPlanEngine.reset()
    InstinctExecutionJournal.owner = live
    InstinctExecutionRecoveryManager.recover(app)
    plan = planOf(incoming)
    assertEquals(PlanState.DECIDING, plan.state)
    assertEquals(1, journal.byOccurrence(plan.id).count { InstinctQuestion.isRelated(it.line) })
    assertEquals(ExecutionState.ASKING, journal.get(plan.question!!)?.state)
    assertEquals(1, notifications.activeNotifications.count { it.tag == plan.question })
    assertTrue(InstinctProposalCenter.book.pending().isEmpty())
    tap(question(plan), 1)
    assertEquals(PlanState.DONE, planOf(incoming).state)
    assertEquals(percent(40), volume())
  }

  @Test fun `a discarded decision cancels the plan without running anything`() {
    val incoming = order("#insto ${InstinctPlanSpec.EXAMPLES[2]}")
    push(incoming)
    val plan = planOf(incoming)
    InstinctQuestionNotifications.handle(app, shadowOf(question(plan).deleteIntent).savedIntent)
    assertEquals(PlanState.CANCELLED, planOf(incoming).state)
    assertTrue(ran.isEmpty())
  }

  @Test fun `Confirmar asks once for the whole plan and Nunca refuses it`() {
    InstinctAutonomySettings(app).set(InstinctCategory.VOLUMEN, InstinctAutonomy.CONFIRMAR)
    val incoming = order("#insto plan: bajar el volumen; poner el volumen al 20 por ciento")
    push(incoming)
    var plan = planOf(incoming)
    assertEquals(PlanState.CONFIRM, plan.state)
    assertTrue(ran.isEmpty())
    tap(question(plan), 0)
    plan = planOf(incoming)
    assertEquals(PlanState.DONE, plan.state)
    assertEquals(PlanApproval.PERSON, plan.approval)
    assertEquals(2, ran.size)

    InstinctAutonomySettings(app).set(InstinctCategory.VOLUMEN, InstinctAutonomy.NUNCA)
    val refused = order("#insto plan: activar no molestar; bajar el volumen")
    push(refused)
    val root = journal.forMessage(refused.id).single()
    assertEquals(ExecutionState.FAILED, root.state)
    assertEquals(InstinctAutonomyPolicy.REFUSED, root.reason)
    assertEquals(1, journal.plans().size)
    assertEquals(2, ran.size)
  }

  @Test fun `a category moved to Nunca while the plan waits stops it before that action`() {
    val incoming = order("#insto plan: esperar 2 minutos; bajar el volumen")
    push(incoming)
    InstinctAutonomySettings(app).set(InstinctCategory.VOLUMEN, InstinctAutonomy.NUNCA)
    wall += 3 * 60_000
    InstinctPlanEngine.recover(app)
    assertEquals(PlanState.FAILED, planOf(incoming).state)
    assertTrue(ran.isEmpty())
  }

  @Test fun `turning receiving orders off ends every plan without touching the phone again`() {
    val incoming = order("#insto plan: guardar volumen; poner el volumen al 20 por ciento; esperar 30 minutos; restaurar")
    push(incoming)
    InstinctProposalSettings(app).setEnabled(false)
    val plan = planOf(incoming)
    assertEquals(PlanState.CANCELLED, plan.state)
    assertEquals(percent(20), volume())
  }

  @Test fun `probar and the journal migration keep plans read-only and v3 rows intact`() {
    val probe = order("#insto probar ${InstinctPlanSpec.EXAMPLES[0]}")
    push(probe)
    val row = journal.forMessage(probe.id).single()
    assertTrue(row.result.orEmpty().contains("la haría sola"))
    assertTrue(journal.plans().isEmpty())
    assertTrue(ran.isEmpty())
    InstinctExecutionJournal.resetAll()
    android.database.sqlite.SQLiteDatabase.openDatabase(File(app.noBackupFilesDir, InstinctExecutionJournal.FILE).path,
      null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
      db.execSQL("DROP TABLE plans"); db.execSQL("DROP TABLE plan_steps"); db.version = 3
    }
    assertEquals(row, journal.get(row.executionId))
    assertTrue(journal.plans().isEmpty())
  }

  @Test fun `a plan that did not start travels in the backup and is started again only on reactivation`() {
    events = listOf(CalendarInstance(1, 10, wall + 60 * 60_000, wall + 120 * 60_000))
    val incoming = order("#insto ${InstinctPlanSpec.EXAMPLES[1]}")
    push(incoming)
    val data = InstinctBackupStore(app).read()
    assertEquals(listOf(InstinctPlanSpec.EXAMPLES[1]), data.plans.map { it.line })
    val json = com.example.backup.InstinctSection.encode(data)
    assertEquals(data.plans, com.example.backup.InstinctSection.decode(json).plans)
    // On another phone: nothing runs until «Reactivar».
    InstinctPlanEngine.cancel(app, planOf(incoming).shortId)
    InstinctBackupStore(app).requestReactivation(data.copy(proposalsEnabled = false, mailboxEnabled = false, doorbellEnabled = false, recurrences = emptyList()))
    assertEquals(1, journal.plans().size)
    InstinctReactivation.logTest = { "La bitácora respondió bien." }
    val steps = InstinctReactivation.run(app)
    assertTrue(steps.single { it.name == "Planes" }.ok)
    assertEquals(2, journal.plans().size)
    assertEquals(PlanState.WAITING, journal.plans(active = true).single().state)
  }
}
