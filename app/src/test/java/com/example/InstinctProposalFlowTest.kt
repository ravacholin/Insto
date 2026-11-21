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
    InstinctProposalIntake.log = { logs += it }
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"; applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    contact(phone)
  }

  @After fun tearDown() {
    InstinctProposalSettings(app).setEnabled(false)
    InstinctProposalCenter.book.discardAll()
    InstinctProposalCenter.clock = android.os.SystemClock::elapsedRealtime
    Dispatchers.resetMain()
  }

  private fun contact(number: String, name: String = "Instinct") {
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact(name, number) }
    awaitCachedPreferences(app) { it.pinnedContact?.phoneNumber == number && it.pinnedContact?.displayName == name }
  }

  private fun enable() {
    InstinctProposalSettings(app).setEnabled(true)
    InstinctSendCorrelation(app).record(com.example.whatsapp.PhoneNumberUtils.normalizeDigits(phone))
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

  private fun offer(command: PhoneCommand, order: String = "orden"): ProposalBook.Proposal {
    val fingerprint = InstinctProposalCenter.fingerprint(app)!!
    InstinctProposalCenter.deliver(app, listOf(ProposalCheck.Valid(order, command)), fingerprint)
    return InstinctProposalCenter.book.pending().single { it.command == command }
  }

  @Test fun `with the switch off a posted notification is not read at all`() {
    assertFalse(InstinctProposalSettings(app).isEnabled())
    // The framework constructor reads its own fields to build the key; only what happens afterwards counts.
    val sbn = CountingSbn("com.whatsapp", whatsApp("#insto poner una alarma a las 07:00")).also { it.reads = 0 }
    post(sbn)
    assertEquals(0, sbn.reads)
    assertTrue(InstinctProposalCenter.book.pending().isEmpty())
    assertTrue(posted().isEmpty())
    assertTrue(logs.isEmpty())
  }

  @Test fun `a proposal from Instinct becomes a notification to confirm and no text is logged`() {
    enable()
    post(CountingSbn("com.whatsapp", whatsApp(
      "Te propongo esto:\n#insto poner una alarma a las 07:00\nmi clave es secreto123\n#insto llamar a Ana")))
    val pending = InstinctProposalCenter.book.pending()
    assertEquals(listOf(PhoneCommand.Alarm(7, 0)), pending.map { it.command })
    val titles = posted().map { it.extras.getString(Notification.EXTRA_TITLE) }
    assertTrue(titles.toString(), titles.any { it!!.startsWith("Instinct propone: ") })
    assertTrue(titles.toString(), InstinctProposalCenter.NOT_UNDERSTOOD in titles)
    val proposal = posted().single { it.extras.getString(Notification.EXTRA_TITLE)!!.startsWith("Instinct propone") }
    assertEquals(InstinctProposalCenter.CHANNEL_ID, proposal.channelId)
    assertEquals(listOf("Confirmar", "Descartar"), proposal.actions.map { it.title.toString() })
    // Logcat and the debug log get counters only.
    val everything = logs + ShadowLog.getLogs().map { it.msg ?: "" }
    assertTrue(logs.isNotEmpty())
    for (secret in listOf("secreto123", "alarma", "Ana", "Instinct", "07:00", "#insto"))
      assertTrue("$secret leaked: $everything", everything.none { secret in it })
  }

  @Test fun `other apps, groups, other contacts and messages outside the window are ignored`() {
    enable()
    post(CountingSbn("com.example.chat", whatsApp("#insto prender la linterna")))
    post(CountingSbn("com.whatsapp", whatsApp("#insto prender la linterna", group = true)))
    post(CountingSbn("com.whatsapp", whatsApp("#insto prender la linterna", sender = "Instinct bis")))
    InstinctSendCorrelation(app).clear()
    post(CountingSbn("com.whatsapp", whatsApp("#insto prender la linterna")))
    assertTrue(InstinctProposalCenter.book.pending().isEmpty())
    assertTrue(posted().isEmpty())
  }

  @Test fun `the diagnosis says why the last WhatsApp message was not offered, without content`() {
    enable()
    post(CountingSbn("com.whatsapp", whatsApp("#insto mostrar las alarmas", sender = "Instinct IA")))
    assertEquals(InstinctProposalIntake.Diagnosis.CONTACT, InstinctProposalIntake.lastDiagnosis)
    post(CountingSbn("com.example.chat", whatsApp("#insto mostrar las alarmas")))
    assertEquals("other apps never change it", InstinctProposalIntake.Diagnosis.CONTACT, InstinctProposalIntake.lastDiagnosis)
    InstinctSendCorrelation(app).clear()
    post(CountingSbn("com.whatsapp", whatsApp("#insto mostrar los temporizadores")))
    assertEquals(InstinctProposalIntake.Diagnosis.WINDOW, InstinctProposalIntake.lastDiagnosis)
    enable()
    post(CountingSbn("com.whatsapp", whatsApp("#insto llamar a Ana")))
    assertEquals(InstinctProposalIntake.Diagnosis.NOT_UNDERSTOOD, InstinctProposalIntake.lastDiagnosis)
    post(CountingSbn("com.whatsapp", whatsApp("#insto mostrar los temporizadores")))
    assertEquals(InstinctProposalIntake.Diagnosis.OFFERED, InstinctProposalIntake.lastDiagnosis)
  }

  @Test fun `a send to another number does not open the window`() {
    InstinctProposalSettings(app).setEnabled(true)
    InstinctSendCorrelation(app).record("5491100000000")
    assertNull(InstinctSendCorrelation(app).lastSendTo(phone))
    // Nothing is recorded with the switch off.
    InstinctProposalSettings(app).setEnabled(false)
    InstinctSendCorrelation(app).record(com.example.whatsapp.PhoneNumberUtils.normalizeDigits(phone))
    assertNull(InstinctSendCorrelation(app).lastSendTo(phone))
  }

  @Test fun `Descartar from the notification discards for good`() {
    enable()
    val proposal = offer(PhoneCommand.Torch(true))
    InstinctProposalReceiver().onReceive(app, Intent(InstinctProposalCenter.ACTION_DISCARD)
      .putExtra(InstinctProposalCenter.EXTRA_PROPOSAL_ID, proposal.id))
    val vm = vm()
    vm.confirmInstinctProposalFromNotification(proposal.id, proposal.token)
    assertEquals("Esa propuesta ya se ejecutó o se descartó. No se repite.", vm.floatingAssistant.value.error)
  }

  @Test fun `inside the open assistant a proposal waits while a draft is being written`() = runTest(scheduler) {
    enable()
    val vm = vm()
    vm.attachInstinctProposals()
    vm.enterFloatingTextMode(); vm.updateFloatingText("recordame comprar pan")
    offer(PhoneCommand.Volume(VolumeOp.SET, 30))
    runCurrent()
    assertEquals(1, vm.floatingAssistant.value.proposalsWaiting)
    assertFalse(vm.floatingAssistant.value.needsConfirmation)
    assertEquals("recordame comprar pan", vm.floatingAssistant.value.text)
    vm.updateFloatingText("")
    runCurrent()
    assertEquals(0, vm.floatingAssistant.value.proposalsWaiting)
    assertTrue(vm.floatingAssistant.value.needsConfirmation)
    assertTrue(vm.floatingAssistant.value.status!!.startsWith("Instinct propone: "))
    vm.detachInstinctProposals()
  }

  @Test fun `confirming runs once and never feeds the operational context`() = runTest(scheduler) {
    enable()
    installNativeHandler(app, Intent.ACTION_VIEW, scheme = "https")
    val context = OperationalContext()
    val vm = vm(context)
    vm.attachInstinctProposals()
    val proposal = offer(PhoneCommand.YouTubeSearch("jazz"))
    runCurrent()
    assertTrue(vm.floatingAssistant.value.needsConfirmation)
    vm.enterFloatingTextMode(); vm.updateFloatingText("dale"); vm.sendFloatingText { fail("A confirmation never goes to Instinct") }
    runCurrent()
    assertNotNull(vm.consumePhoneActionCompletion())
    advanceUntilIdle()
    assertNull(context.snapshot())
    // Nor learning: no phrase and no structure comes from a proposal.
    val learned = runBlocking { phrases.backupData() }
    assertTrue(learned.entries.isEmpty() && learned.patterns.isEmpty())
    // A second Confirmar (notification action delivered again) does not repeat it.
    vm.confirmInstinctProposalFromNotification(proposal.id, proposal.token)
    assertEquals("Esa propuesta ya se ejecutó o se descartó. No se repite.", vm.floatingAssistant.value.error)
    assertNull(vm.consumePhoneActionCompletion())
    vm.detachInstinctProposals()
  }

  @Test fun `cancelling a shown proposal discards it`() = runTest(scheduler) {
    enable()
    val vm = vm()
    vm.attachInstinctProposals()
    val proposal = offer(PhoneCommand.Volume(VolumeOp.SET, 40))
    runCurrent()
    vm.confirmFloatingAction(false)
    assertFalse(InstinctProposalCenter.book.isPending(proposal.id))
    assertEquals("Propuesta de Instinct descartada. No se ejecutó nada.", vm.floatingAssistant.value.status)
    vm.detachInstinctProposals()
  }

  @Test fun `an expired proposal does not run`() {
    enable()
    val proposal = offer(PhoneCommand.Volume(VolumeOp.SET, 50))
    fakeNow += ProposalBook.TTL_MILLIS + 1
    val vm = vm()
    vm.confirmInstinctProposalFromNotification(proposal.id, proposal.token)
    assertEquals("La propuesta de Instinct venció. No se ejecutó nada.", vm.floatingAssistant.value.error)
    assertNull(vm.consumePhoneActionCompletion())
  }

  @Test fun `a proposal confirmed after the contact changed does not run`() {
    enable()
    val proposal = offer(PhoneCommand.Volume(VolumeOp.SET, 60))
    contact("+54 9 11 9999-0000")
    val vm = vm()
    vm.confirmInstinctProposalFromNotification(proposal.id, proposal.token)
    assertEquals("Cambió la configuración desde que llegó la propuesta. No se ejecutó nada.", vm.floatingAssistant.value.error)
  }

  @Test fun `turning the switch off discards pending proposals`() {
    enable()
    val proposal = offer(PhoneCommand.Volume(VolumeOp.SET, 70))
    InstinctProposalSettings(app).setEnabled(false)
    assertFalse(InstinctProposalCenter.book.isPending(proposal.id))
    assertNull(InstinctProposalCenter.fingerprint(app))
  }

  @Test fun `a forged confirmation without the token runs nothing`() {
    enable()
    val proposal = offer(PhoneCommand.Volume(VolumeOp.SET, 80))
    val vm = vm()
    vm.confirmInstinctProposalFromNotification(proposal.id, "adivinado")
    assertEquals("Esa propuesta ya no está disponible. No se ejecutó nada.", vm.floatingAssistant.value.error)
    assertTrue(InstinctProposalCenter.book.isPending(proposal.id))
  }
}
