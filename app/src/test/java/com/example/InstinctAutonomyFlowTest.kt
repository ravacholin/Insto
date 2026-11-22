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
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.example.data.AssistantPreferencesRepository
import com.example.phone.*
import com.example.testsupport.awaitCachedPreferences
import java.io.File
import java.util.Calendar
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

/** «Autonomía por acción» on Android: migration, the policy after the existing blockers, quiet hours and the ledger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctAutonomyFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private var fakeNow = 1_000_000L
  /** Noon, local time. */
  private var wall = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 21, 12, 0, 0) }.timeInMillis
  private val sent = mutableListOf<String>()
  private val audio get() = app.getSystemService(AudioManager::class.java)
  private val notifications get() = app.getSystemService(NotificationManager::class.java)
  private val autonomy get() = InstinctAutonomySettings(app)

  @Before fun setUp() {
    InstinctProposalCenter.clock = { fakeNow }
    InstinctScheduler.wallClock = { wall }
    InstinctScheduler.alarms = { _, _, _ -> }
    InstinctProposalCenter.budget.reset()
    InstinctProposalCenter.acks.clear()
    InstinctProposalCenter.undos.clear()
    InstinctProposalCenter.accessibilityConnected = { true }
    InstinctProposalCenter.keyguardLocked = { false }
    InstinctProposalCenter.replySender = { _, _, text -> sent += text; true }
    InstinctProposalIntake.log = {}
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"; applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+54 9 11 2233-4455") }
    awaitCachedPreferences(app) { it.pinnedContact?.displayName == "Instinct" && it.pinnedContact?.phoneNumber == "+54 9 11 2233-4455" }
    InstinctProposalSettings(app).setEnabled(true)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    shadowOf(notifications).setNotificationPolicyAccessGranted(true)
  }

  @After fun tearDown() {
    autonomy.setAll(InstinctAutonomy.SOLO)
    autonomy.setQuiet(null)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.NEVER)
    InstinctProposalSettings(app).setEnabled(false)
    InstinctProposalCenter.book.discardAll()
    InstinctProposalCenter.replySender = { context, handle, text -> handle.send(context, text) }
    InstinctProposalCenter.accessibilityConnected = { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
    InstinctProposalCenter.clock = android.os.SystemClock::elapsedRealtime
    InstinctScheduler.wallClock = System::currentTimeMillis
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

  private companion object { var nextId = 95_000 }
  private fun post(text: String) = Robolectric.buildService(NotificationAccessService::class.java).create().get()
    .onNotificationPosted(StatusBarNotification("com.whatsapp", "com.whatsapp", nextId++, "chat", 10_001, 0, 0, whatsApp(text),
      UserHandle.getUserHandleForUid(10_001), 0L))

  private fun posted() = shadowOf(notifications).allNotifications
  private fun titles() = posted().map { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "" }
  private fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

  @Test fun `askFirst on migrates to Confirmar everywhere and its file is deleted`() {
    File(app.noBackupFilesDir, InstinctAutonomySettings.FILE).delete()
    val legacy = File(app.noBackupFilesDir, InstinctAutonomySettings.LEGACY_FILE).apply { writeText("true") }
    val policy = autonomy.policy()
    for (c in InstinctCategory.entries) assertEquals(InstinctAutonomy.CONFIRMAR, policy.level(c))
    assertFalse(legacy.exists())
    assertTrue(File(app.noBackupFilesDir, InstinctAutonomySettings.FILE).exists())
  }

  @Test fun `without askFirst the defaults keep 1_15_0 behavior`() {
    File(app.noBackupFilesDir, InstinctAutonomySettings.FILE).delete()
    assertEquals(InstinctAutonomyPolicy(), autonomy.policy())
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto poner el volumen al 40 por ciento")
    assertTrue(volume() > 0)
    assertTrue(sent.single(), sent.single().startsWith("#insto-ok "))
  }

  @Test fun `Confirmar for a category waits with the concrete reason`() {
    autonomy.set(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR)
    post("#insto poner un temporizador de 5 minutos")
    assertEquals(listOf("#insto-espera poner un temporizador de 5 minutos → la persona pide confirmación para alarmas y temporizadores"), sent)
    assertTrue(titles().any { it.startsWith("Instinct propone: ") })
    assertEquals(1, InstinctProposalCenter.book.pending().size)
  }

  @Test fun `Nunca refuses at once and nothing runs`() {
    autonomy.set(InstinctCategory.VOLUMEN, InstinctAutonomy.NUNCA)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto poner el volumen al 40 por ciento")
    assertEquals(0, volume())
    assertEquals(listOf("#insto-error poner el volumen al 40 por ciento → la persona no permite esta acción"), sent)
    assertTrue(InstinctProposalCenter.book.pending().isEmpty())
  }

  @Test fun `Solo never loosens an existing blocker - a locked phone runs a setting and still waits for a screen`() {
    InstinctProposalCenter.keyguardLocked = { true }
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto poner el volumen al 40 por ciento")
    // 1.20.0: the volume changes with the phone locked, as Android allows.
    assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 40 / 100, volume())
    assertTrue(sent.single(), sent.single().startsWith("#insto-ok poner el volumen al 40 por ciento"))
    sent.clear()
    post("#insto poner un temporizador de 5 minutos")
    assertEquals(listOf("#insto-espera poner un temporizador de 5 minutos → ${InstinctGatePreview.LOCKED}"), sent)
    // And without Accessibility an app-opening order waits too, whatever the policy says.
    InstinctProposalCenter.keyguardLocked = { false }
    InstinctProposalCenter.accessibilityConnected = { false }
    sent.clear()
    post("#insto poner un temporizador de 5 minutos")
    assertTrue(sent.single(), sent.single().startsWith("#insto-espera poner un temporizador de 5 minutos → Para abrir otra app"))
  }

  @Test fun `quiet hours turn Solo into Confirmar, except silencing`() {
    autonomy.setQuiet(QuietHours(23 * 60, 7 * 60))
    wall = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 22, 3, 0, 0) }.timeInMillis
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto poner el volumen al 40 por ciento")
    assertEquals(0, volume())
    assertEquals("#insto-espera poner el volumen al 40 por ciento → horas de silencio", sent.single())
    sent.clear()
    audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
    post("#insto poner en vibración")
    assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
    assertTrue(sent.single(), sent.single().startsWith("#insto-ok "))
  }

  @Test fun `a deferred order is judged by the quiet hours when it fires`() {
    autonomy.setQuiet(QuietHours(23 * 60, 7 * 60))
    wall = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 21, 22, 30, 0) }.timeInMillis
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto en 60 minutos: poner el volumen al 40 por ciento")
    val order = InstinctScheduler.pending(app).single()
    wall = order.fireAt
    runBlocking { InstinctScheduler.fire(app, order.id, order.token) }
    assertEquals(0, volume())
    assertTrue(titles().any { it.startsWith("Instinct propone: ") })
  }

  @Test fun `three undos lower the category and Volver a automatico restores it`() {
    repeat(3) {
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
      fakeNow += 1_000
      post("#insto poner el volumen al ${40 + it} por ciento")
      val done = posted().last { n -> n.extras.getCharSequence(Notification.EXTRA_TITLE)?.startsWith("Instinct hizo") == true }
      val undo = shadowOf(done.actions.single().actionIntent).savedIntent
      InstinctProposalReceiver().onReceive(app, undo)
    }
    assertEquals(InstinctAutonomy.CONFIRMAR, autonomy.policy().level(InstinctCategory.VOLUMEN))
    assertNotNull(autonomy.policy().adjusted[InstinctCategory.VOLUMEN])
    val notice = posted().last { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.startsWith("Instinct va a pedirte confirmación") == true }
    assertEquals("Instinct va a pedirte confirmación para volumen: deshiciste 3 de 3", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    assertEquals(listOf("Volver a automático"), notice.actions.map { it.title.toString() })
    val restore = shadowOf(notice.actions.single().actionIntent).savedIntent
    InstinctProposalReceiver().onReceive(app, restore)
    assertEquals(InstinctAutonomy.SOLO, autonomy.policy().level(InstinctCategory.VOLUMEN))
    assertNull(autonomy.policy().adjusted[InstinctCategory.VOLUMEN])
    // Single use; and the counters started again, so one more undo does not lower it.
    assertFalse(InstinctTrust.answer(app, restore.action, restore.getStringExtra(InstinctTrust.EXTRA_TOKEN)))
    assertFalse(File(app.noBackupFilesDir, InstinctTrust.FILE).readText().contains("volumen al"))
  }

  @Test fun `the catalog includes the policy in force`() {
    autonomy.set(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR)
    assertTrue(InstinctProposalCenter.catalog(app).contains("- Espera confirmación (espera): alarmas y temporizadores."))
  }
}
