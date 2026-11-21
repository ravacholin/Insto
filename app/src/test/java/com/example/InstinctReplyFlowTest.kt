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
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.example.data.AssistantPreferencesRepository
import com.example.data.emptyLearnedPhrasesForTest
import com.example.phone.*
import com.example.testsupport.awaitCachedPreferences
import com.example.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Replies to Instinct on Android: the «Responder» action of the same notification, modes, follow-ups and the guard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctReplyFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private var fakeNow = 1_000_000L
  private val sent = mutableListOf<String>()

  @Before fun setUp() {
    InstinctProposalCenter.clock = { fakeNow }
    InstinctProposalCenter.budget.reset()
    InstinctProposalCenter.acks.clear()
    InstinctProposalCenter.accessibilityConnected = { false }
    InstinctProposalCenter.keyguardLocked = { false }
    InstinctProposalCenter.replySender = { _, _, text -> sent += text; true }
    InstinctProposalIntake.log = {}
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"; applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+54 9 11 2233-4455") }
    awaitCachedPreferences(app) { it.pinnedContact?.displayName == "Instinct" }
    InstinctProposalSettings(app).setEnabled(true)
  }

  @After fun tearDown() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.NEVER)
    InstinctProposalSettings(app).setEnabled(false)
    InstinctProposalCenter.book.discardAll()
    InstinctProposalCenter.replySender = { context, handle, text -> handle.send(context, text) }
    InstinctProposalCenter.accessibilityConnected = { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
    InstinctProposalCenter.clock = android.os.SystemClock::elapsedRealtime
  }

  private fun replyAction(): Notification.Action {
    val intent = PendingIntent.getBroadcast(app, 1, Intent("com.whatsapp.REPLY").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE)
    return Notification.Action.Builder(null, "Responder", intent)
      .addRemoteInput(RemoteInput.Builder("android.intent.extra.text").setLabel("Responder").build()).build()
  }

  private fun whatsApp(text: String, fromMe: Boolean = false, reply: Boolean = true): Notification {
    val me = Person.Builder().setName("Yo").build()
    val style = Notification.MessagingStyle(me)
      .addMessage(text, System.currentTimeMillis(), if (fromMe) null else Person.Builder().setName("Instinct").build())
    return Notification.Builder(app, "whatsapp").setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Instinct").setContentText(text).setStyle(style)
      .apply { if (reply) addAction(replyAction()) }.build()
  }

  private companion object { var nextId = 1 } // The intake's dedupe is process-wide: every post gets its own key.
  private fun post(notification: Notification) = Robolectric.buildService(NotificationAccessService::class.java).create().get()
    .onNotificationPosted(StatusBarNotification("com.whatsapp", "com.whatsapp", nextId++, "chat", 10_001, 0, 0, notification,
      UserHandle.getUserHandleForUid(10_001), 0L))

  @Test fun `with replies off nothing is answered`() {
    post(whatsApp("#insto poner el volumen al 30 por ciento"))
    assertTrue(sent.isEmpty())
    assertNull(InstinctProposalCenter.acks.latestHandle())
  }

  @Test fun `Siempre answers each order on its own line, in order, and diagnoses it`() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    post(whatsApp("Te ajusto el teléfono.\n#insto poner el volumen al 30 por ciento\n#insto llamar a Ana\n#insto bajar el volumen"))
    val reply = sent.single().lines()
    assertEquals(3, reply.size)
    assertTrue(reply[0], reply[0].startsWith("#insto-ok poner el volumen al 30 por ciento → "))
    assertEquals("#insto-error llamar a Ana → esa acción no está permitida para Instinct.", reply[1])
    assertTrue(reply[2], reply[2].startsWith("#insto-ok bajar el volumen → "))
    assertEquals(InstinctProposalIntake.Diagnosis.REPLIED, InstinctProposalIntake.lastReply)
  }

  @Test fun `Solo errores y esperas skips a message where everything went well`() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.PROBLEMS)
    post(whatsApp("#insto poner el volumen al 20 por ciento"))
    assertTrue(sent.isEmpty())
    post(whatsApp("#insto poner el volumen al 25 por ciento\n#insto mandar un mensaje a Ana"))
    assertEquals(listOf("#insto-error mandar un mensaje a Ana → no la entendí: usá una de las formas del catálogo."), sent.single().lines())
  }

  @Test fun `without a reply action the reply is dropped silently and diagnosed`() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    post(whatsApp("#insto poner el volumen al 40 por ciento", reply = false))
    assertTrue(sent.isEmpty())
    assertEquals(InstinctProposalIntake.Diagnosis.REPLY_FAILED, InstinctProposalIntake.lastReply)
    assertEquals("WhatsApp no ofreció Responder.", InstinctProposalIntake.lastReplyDetail)
    assertNull(shadowOf(app).nextStartedActivity) // WhatsApp is never opened instead.
  }

  @Test fun `a cancelled reply action is diagnosed and never retried`() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    var attempts = 0
    InstinctProposalCenter.replySender = { _, _, _ -> attempts++; false }
    post(whatsApp("#insto poner el volumen al 45 por ciento"))
    assertEquals(1, attempts)
    assertEquals(InstinctProposalIntake.Diagnosis.REPLY_FAILED, InstinctProposalIntake.lastReply)
    assertNull(shadowOf(app).nextStartedActivity)
  }

  @Test fun `the real handle fills WhatsApp's remote input and fires its own intent`() {
    val handle = InstinctReplyHandle.from(whatsApp("x"))!!
    assertTrue(handle.send(app, "#insto-ok a → b"))
    val broadcast = shadowOf(app).broadcastIntents.last { it.action == "com.whatsapp.REPLY" }
    assertEquals("#insto-ok a → b", RemoteInput.getResultsFromIntent(broadcast).getCharSequence("android.intent.extra.text").toString())
    assertNull(InstinctReplyHandle.from(whatsApp("x", reply = false)))
  }

  @Test fun `our own reply re-posted by WhatsApp is not processed as an order`() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    val audio = app.getSystemService(android.media.AudioManager::class.java)
    post(whatsApp("#insto poner el volumen al 30 por ciento"))
    val reply = sent.single()
    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
    post(whatsApp(reply, fromMe = true))
    assertEquals(InstinctProposalIntake.Diagnosis.CONTACT, InstinctProposalIntake.lastDiagnosis)
    assertEquals(0, audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
    assertEquals(1, sent.size)
  }

  @Test fun `a waiting order is followed up when confirmed`() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    InstinctProposalCenter.keyguardLocked = { true }
    post(whatsApp("#insto poner el volumen al 35 por ciento"))
    assertEquals("#insto-espera poner el volumen al 35 por ciento → El teléfono estaba bloqueado.", sent.single())
    InstinctProposalCenter.keyguardLocked = { false }
    val proposal = InstinctProposalCenter.book.pending().single()
    MainViewModel(app, learnedPhrases = emptyLearnedPhrasesForTest()).confirmInstinctProposalFromNotification(proposal.id, proposal.token)
    assertEquals(2, sent.size)
    assertEquals("#insto-ok poner el volumen al 35 por ciento → Volumen multimedia ajustado.", sent[1])
  }

  @Test fun `a waiting order is followed up when discarded`() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.PROBLEMS)
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.CONFIRMAR)
    post(whatsApp("#insto prender la linterna"))
    val proposal = InstinctProposalCenter.book.pending().single()
    InstinctProposalReceiver().onReceive(app, Intent(InstinctProposalCenter.ACTION_DISCARD)
      .putExtra(InstinctProposalCenter.EXTRA_PROPOSAL_ID, proposal.id))
    assertEquals("#insto-descartada prender la linterna → la persona la descartó; no se ejecutó.", sent.last())
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.SOLO)
  }

  @Test fun `a waiting order is followed up when it expires, while the handle is still valid`() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.CONFIRMAR)
    post(whatsApp("#insto pausar la música"))
    val proposal = InstinctProposalCenter.book.pending().single()
    fakeNow += ProposalBook.TTL_MILLIS - 1_000
    InstinctProposalCenter.sweepReplies(app)
    assertEquals("#insto-vencida pausar la música → venció sin que se confirmara; no se ejecutó.", sent.last())
    assertFalse(InstinctProposalCenter.book.isPending(proposal.id))
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.SOLO)
  }

  @Test fun `after the handle lapsed nothing is sent`() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.CONFIRMAR)
    post(whatsApp("#insto pausar la música"))
    val proposal = InstinctProposalCenter.book.pending().single()
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.SOLO)
    fakeNow += ProposalBook.TTL_MILLIS + 1
    InstinctProposalCenter.discard(app, proposal.id)
    assertEquals(1, sent.size)
  }

  @Test fun `turning Recibir órdenes off cuts pending replies`() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.CONFIRMAR)
    post(whatsApp("#insto pausar la música"))
    assertEquals(1, sent.size)
    InstinctProposalSettings(app).setEnabled(false)
    assertEquals("discarding on switch-off answers nothing", 1, sent.size)
    assertNull(InstinctProposalCenter.acks.latestHandle())
    InstinctAutonomySettings(app).setAll(InstinctAutonomy.SOLO)
  }

  @Test fun `the reply mode lives outside backups and defaults to Nunca`() {
    assertEquals(InstinctReplyMode.NEVER, InstinctProposalSettings(app).replyMode())
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.PROBLEMS)
    assertTrue(java.io.File(app.noBackupFilesDir, "instinct-proposals-reply-mode").exists())
    assertEquals(InstinctReplyMode.PROBLEMS, InstinctProposalSettings(app).replyMode())
  }
}
