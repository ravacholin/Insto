package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.example.data.AssistantPreferencesRepository
import com.example.data.emptyLearnedPhrasesForTest
import com.example.phone.*
import com.example.testsupport.awaitCachedPreferences
import com.example.testsupport.installNativeHandler
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * «Instinct con manos» on Android: the opt-in switch, reading of WhatsApp notifications, notifications with
 * Confirmar/Descartar and proposals inside the floating assistant. Nothing runs without the user's confirmation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctProposalFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private val scheduler = TestCoroutineScheduler()
  private var fakeNow = 1_000_000L
  private val phone = "+54 9 11 2233-4455"
  private val logs = mutableListOf<String>()

  @Before fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher(scheduler))
    ShadowLog.clear()
    InstinctProposalCenter.clock = { fakeNow }
    InstinctProposalCenter.budget.reset()
    InstinctProposalCenter.accessibilityConnected = { false }
    InstinctProposalCenter.keyguardLocked = { false }
    InstinctProposalIntake.log = { logs += it }
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"; applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    contact(phone)
  }

  @After fun tearDown() {
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.SOLO)
    InstinctProposalSettings(app).setEnabled(false)
    InstinctProposalCenter.book.discardAll()
    InstinctProposalCenter.accessibilityConnected = { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
    InstinctProposalCenter.clock = android.os.SystemClock::elapsedRealtime
    Dispatchers.resetMain()
  }

  private fun contact(number: String, name: String = "Instinct") {
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact(name, number) }
    awaitCachedPreferences(app) { it.pinnedContact?.phoneNumber == number && it.pinnedContact?.displayName == name }
  }

  private fun enable(asksFirst: Boolean = false) {
    InstinctProposalSettings(app).setEnabled(true)
    InstinctAutonomySettings(app).setAll(if (asksFirst) InstinctAutonomy.CONFIRMAR else InstinctAutonomy.SOLO)
  }

  private fun whatsApp(text: String, sender: String = "Instinct", group: Boolean = false): Notification {
    val style = Notification.MessagingStyle(Person.Builder().setName("Yo").build())
      .addMessage(text, System.currentTimeMillis(), Person.Builder().setName(sender).build())
      .setGroupConversation(group)
    return Notification.Builder(app, "whatsapp").setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(sender).setContentText(text).setStyle(style).build()
  }

  /** Counts every access to the notification or its package: zero means nothing was read. */
  private class CountingSbn(pkg: String, notification: Notification) :
    StatusBarNotification(pkg, pkg, 7, "chat", 10_001, 0, 0, notification, UserHandle.getUserHandleForUid(10_001), 0L) {
    var reads = 0
    override fun getNotification(): Notification { reads++; return super.getNotification() }
    override fun getPackageName(): String { reads++; return super.getPackageName() }
  }

  private fun post(sbn: StatusBarNotification) =
    Robolectric.buildService(NotificationAccessService::class.java).create().get().onNotificationPosted(sbn)

  private fun posted(): List<Notification> = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

  private val phrases = emptyLearnedPhrasesForTest()

  private fun vm(context: OperationalContext = OperationalContext()) =
    MainViewModel(app, learnedPhrases = phrases, operationalContext = context)

  /** Delivers a validated proposal as the intake does: to the open panel if attached, otherwise in the background. */
  private fun deliver(command: PhoneCommand, order: String = "orden") =
    InstinctProposalCenter.deliver(app, listOf(ProposalCheck.Valid(order, command)), InstinctProposalCenter.fingerprint(app)!!)

  /** A pending proposal waiting for Confirmar, as the notification leaves it. */
  private fun pending(command: PhoneCommand): ProposalBook.Proposal =
    InstinctProposalCenter.book.offer("orden", command, InstinctProposalCenter.fingerprint(app)!!)!!

  private fun mediaVolume(): Int = app.getSystemService(android.media.AudioManager::class.java)
    .getStreamVolume(android.media.AudioManager.STREAM_MUSIC)

  private fun titles() = posted().map { it.extras.getString(Notification.EXTRA_TITLE) ?: "" }

  @Test fun `with the switch off a posted notification is not read at all`() {
    assertFalse(InstinctProposalSettings(app).isEnabled())
    // The framework constructor reads its own fields to build the key; only what happens afterwards counts.
    val sbn = CountingSbn("com.whatsapp", whatsApp("#insto poner el volumen al 10 por ciento")).also { it.reads = 0 }
    val before = mediaVolume()
    post(sbn)
    assertEquals(0, sbn.reads)
    assertEquals(before, mediaVolume())
    assertTrue(posted().isEmpty())
    assertTrue(logs.isEmpty())
  }

  @Test fun `Instinct on its own initiative changes the volume and Insto reports it afterwards`() {
    enable()
    val audio = app.getSystemService(android.media.AudioManager::class.java)
    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
    post(CountingSbn("com.whatsapp", whatsApp("Te bajo un poco la música.\n#insto poner el volumen al 30 por ciento")))
    val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    assertEquals(max * 30 / 100, mediaVolume())
    val done = posted().single()
    assertEquals(InstinctProposalCenter.ACTIONS_CHANNEL_ID, done.channelId)
    assertTrue(titles().single().startsWith("Instinct hizo: "))
    // Its only action is «Deshacer» (1.14.0): the volume is a reversible state.
    assertEquals(listOf("Deshacer"), done.actions.map { it.title.toString() })
    assertEquals(InstinctProposalIntake.Diagnosis.EXECUTED, InstinctProposalIntake.lastDiagnosis)
    // The same notification again (WhatsApp re-posting it) runs nothing more.
    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
    post(CountingSbn("com.whatsapp", whatsApp("Te bajo un poco la música.\n#insto poner el volumen al 30 por ciento")))
    assertEquals(0, mediaVolume())
    assertEquals(1, posted().size)
  }

  @Test fun `opening another app from the background needs accessibility, otherwise it waits for Confirmar`() {
    enable()
    installNativeHandler(app, android.provider.AlarmClock.ACTION_SET_TIMER)
    post(CountingSbn("com.whatsapp", whatsApp("#insto poner un temporizador de 3 minutos\nmi clave es secreto123\n#insto llamar a Ana")))
    assertNull(shadowOf(app).nextStartedActivity)
    val confirm = posted().single { it.channelId == InstinctProposalCenter.CHANNEL_ID }
    assertEquals(listOf("Confirmar", "Descartar"), confirm.actions.map { it.title.toString() })
    assertTrue(titles().toString(), InstinctProposalCenter.NOT_UNDERSTOOD in titles())
    assertEquals(InstinctProposalIntake.Diagnosis.CONFIRM, InstinctProposalIntake.lastDiagnosis)
    assertTrue(InstinctProposalIntake.lastDetail!!.contains("Accesibilidad"))
    // Logcat and the debug log get counters only.
    // Android's own SQLite warnings (a connection another test leaked, collected at random) name that test's temporary
    // database, which may contain «Instinct»: they are not Insto's logs.
    val everything = logs + ShadowLog.getLogs().filterNot { it.tag.orEmpty().startsWith("SQLite") }.map { it.msg ?: "" }
    assertTrue(logs.isNotEmpty())
    for (secret in listOf("secreto123", "temporizador", "Ana", "Instinct", "#insto"))
      assertTrue("$secret leaked: $everything", everything.none { secret in it })
  }

  @Test fun `with accessibility connected a timer is created without a touch`() {
    enable()
    InstinctProposalCenter.accessibilityConnected = { true }
    installNativeHandler(app, android.provider.AlarmClock.ACTION_SET_TIMER)
    post(CountingSbn("com.whatsapp", whatsApp("#insto poner un temporizador de 4 minutos")))
    val intent = shadowOf(app).nextStartedActivity
    assertEquals(android.provider.AlarmClock.ACTION_SET_TIMER, intent.action)
    assertEquals(240, intent.getIntExtra(android.provider.AlarmClock.EXTRA_LENGTH, 0))
    assertTrue(titles().single().startsWith("Instinct hizo: "))
  }

  @Test fun `a locked phone, the confirmation switch and the anti-loop brake leave it for Confirmar`() {
    enable()
    // 1.20.0: locked, what opens a screen waits (the volume would run).
    InstinctProposalCenter.keyguardLocked = { true }
    InstinctProposalCenter.accessibilityConnected = { true }
    deliver(PhoneCommand.Timer(60))
    assertEquals(InstinctProposalCenter.CHANNEL_ID, posted().last().channelId)
    InstinctProposalCenter.keyguardLocked = { false }
    InstinctProposalCenter.accessibilityConnected = { false }

    InstinctAutonomySettings(app).setAll(InstinctAutonomy.CONFIRMAR)
    deliver(PhoneCommand.Volume(VolumeOp.SET, 12))
    assertEquals(InstinctProposalCenter.CHANNEL_ID, posted().last().channelId)
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.SOLO)

    (13..18).forEach { deliver(PhoneCommand.Volume(VolumeOp.SET, it)) }
    deliver(PhoneCommand.Volume(VolumeOp.SET, 19))
    assertEquals(3, InstinctProposalCenter.book.pending().size)
    assertEquals(6, posted().count { it.channelId == InstinctProposalCenter.ACTIONS_CHANNEL_ID })
    assertEquals(InstinctProposalIntake.Diagnosis.CONFIRM, InstinctProposalIntake.lastDiagnosis)
  }

  @Test fun `other apps, groups and other contacts are ignored`() {
    enable()
    val before = mediaVolume()
    post(CountingSbn("com.example.chat", whatsApp("#insto poner el volumen al 20 por ciento")))
    post(CountingSbn("com.whatsapp", whatsApp("#insto poner el volumen al 21 por ciento", group = true)))
    assertEquals(InstinctProposalIntake.Diagnosis.GROUP, InstinctProposalIntake.lastDiagnosis)
    post(CountingSbn("com.whatsapp", whatsApp("#insto poner el volumen al 22 por ciento", sender = "Instinct IA")))
    assertEquals(InstinctProposalIntake.Diagnosis.CONTACT, InstinctProposalIntake.lastDiagnosis)
    post(CountingSbn("com.example.chat", whatsApp("#insto poner el volumen al 23 por ciento")))
    assertEquals("other apps never change it", InstinctProposalIntake.Diagnosis.CONTACT, InstinctProposalIntake.lastDiagnosis)
    assertEquals(before, mediaVolume())
    assertTrue(posted().isEmpty())
  }

  @Test fun `Descartar from the notification discards for good`() {
    enable()
    val proposal = pending(PhoneCommand.Torch(true))
    InstinctProposalReceiver().onReceive(app, Intent(InstinctProposalCenter.ACTION_DISCARD)
      .putExtra(InstinctProposalCenter.EXTRA_PROPOSAL_ID, proposal.id))
    val vm = vm()
    vm.confirmInstinctProposalFromNotification(proposal.id, proposal.token)
    assertEquals("Esa propuesta ya se ejecutó o se descartó. No se repite.", vm.floatingAssistant.value.error)
  }

  @Test fun `inside the open assistant an order waits while a draft is being written, then runs`() = runTest(scheduler) {
    enable()
    val vm = vm()
    vm.attachInstinctProposals()
    vm.enterFloatingTextMode(); vm.updateFloatingText("recordame comprar pan")
    deliver(PhoneCommand.Volume(VolumeOp.SET, 30))
    runCurrent()
    assertEquals(1, vm.floatingAssistant.value.proposalsWaiting)
    assertEquals("recordame comprar pan", vm.floatingAssistant.value.text)
    assertEquals(1, InstinctProposalCenter.book.pending().size)
    vm.updateFloatingText("")
    runCurrent()
    assertEquals(0, vm.floatingAssistant.value.proposalsWaiting)
    assertFalse(vm.floatingAssistant.value.needsConfirmation)
    assertTrue(InstinctProposalCenter.book.pending().isEmpty())
    assertEquals("Volumen multimedia ajustado.", vm.floatingAssistant.value.status)
    vm.detachInstinctProposals()
  }

  @Test fun `with confirmation on, dale runs once and never feeds context or learning`() = runTest(scheduler) {
    enable(asksFirst = true)
    installNativeHandler(app, Intent.ACTION_VIEW, scheme = "https")
    val context = OperationalContext()
    val vm = vm(context)
    vm.attachInstinctProposals()
    deliver(PhoneCommand.YouTubeSearch("jazz"))
    val proposal = InstinctProposalCenter.book.pending().single()
    runCurrent()
    assertTrue(vm.floatingAssistant.value.needsConfirmation)
    vm.enterFloatingTextMode(); vm.updateFloatingText("dale"); vm.sendFloatingText { fail("A confirmation never goes to Instinct") }
    runCurrent()
    assertNotNull(vm.consumePhoneActionCompletion())
    advanceUntilIdle()
    assertNull(context.snapshot())
    val learned = runBlocking { phrases.backupData() }
    assertTrue(learned.entries.isEmpty() && learned.patterns.isEmpty())
    vm.confirmInstinctProposalFromNotification(proposal.id, proposal.token)
    assertEquals("Esa propuesta ya se ejecutó o se descartó. No se repite.", vm.floatingAssistant.value.error)
    assertNull(vm.consumePhoneActionCompletion())
    vm.detachInstinctProposals()
  }

  @Test fun `an automatic order in the open panel does not feed the operational context`() = runTest(scheduler) {
    enable()
    installNativeHandler(app, Intent.ACTION_VIEW, scheme = "https")
    val context = OperationalContext()
    val vm = vm(context)
    vm.attachInstinctProposals()
    deliver(PhoneCommand.YouTubeSearch("tango"))
    runCurrent()
    assertNotNull(vm.consumePhoneActionCompletion())
    assertNull(context.snapshot())
    vm.detachInstinctProposals()
  }

  @Test fun `cancelling a shown proposal discards it`() = runTest(scheduler) {
    enable(asksFirst = true)
    val vm = vm()
    vm.attachInstinctProposals()
    deliver(PhoneCommand.Volume(VolumeOp.SET, 40))
    val proposal = InstinctProposalCenter.book.pending().single()
    runCurrent()
    vm.confirmFloatingAction(false)
    assertFalse(InstinctProposalCenter.book.isPending(proposal.id))
    assertEquals("Propuesta de Instinct descartada. No se ejecutó nada.", vm.floatingAssistant.value.status)
    vm.detachInstinctProposals()
  }

  @Test fun `an expired proposal does not run`() {
    enable()
    val proposal = pending(PhoneCommand.Volume(VolumeOp.SET, 50))
    fakeNow += ProposalBook.TTL_MILLIS + 1
    val vm = vm()
    vm.confirmInstinctProposalFromNotification(proposal.id, proposal.token)
    assertEquals("La propuesta de Instinct venció. No se ejecutó nada.", vm.floatingAssistant.value.error)
    assertNull(vm.consumePhoneActionCompletion())
  }

  @Test fun `a proposal confirmed after the contact changed does not run`() {
    enable()
    val proposal = pending(PhoneCommand.Volume(VolumeOp.SET, 60))
    contact("+54 9 11 9999-0000")
    val vm = vm()
    vm.confirmInstinctProposalFromNotification(proposal.id, proposal.token)
    assertEquals("Cambió la configuración desde que llegó la propuesta. No se ejecutó nada.", vm.floatingAssistant.value.error)
  }

  @Test fun `turning the switch off discards pending proposals`() {
    enable()
    val proposal = pending(PhoneCommand.Volume(VolumeOp.SET, 70))
    InstinctProposalSettings(app).setEnabled(false)
    assertFalse(InstinctProposalCenter.book.isPending(proposal.id))
    assertNull(InstinctProposalCenter.fingerprint(app))
  }

  @Test fun `a forged confirmation without the token runs nothing`() {
    enable()
    val proposal = pending(PhoneCommand.Volume(VolumeOp.SET, 80))
    val vm = vm()
    vm.confirmInstinctProposalFromNotification(proposal.id, "adivinado")
    assertEquals("Esa propuesta ya no está disponible. No se ejecutó nada.", vm.floatingAssistant.value.error)
    assertTrue(InstinctProposalCenter.book.isPending(proposal.id))
  }
}
