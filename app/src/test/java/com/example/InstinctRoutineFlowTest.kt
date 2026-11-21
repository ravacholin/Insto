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

/** Routines Instinct may run, in the background, and the catalog query, on Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctRoutineFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private var fakeNow = 1_000_000L
  private val sent = mutableListOf<String>()
  private val audio get() = app.getSystemService(AudioManager::class.java)
  private val max get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
  private val estudio = ConfiguredRoutine("Estudio", listOf(PhoneCommand.Volume(VolumeOp.SET, 20), PhoneCommand.Volume(VolumeOp.SET, 40),
    PhoneCommand.Volume(VolumeOp.SET, 60)))
  private val reloj = ConfiguredRoutine("Reloj", listOf(PhoneCommand.Volume(VolumeOp.SET, 10), PhoneCommand.Timer(60)))

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
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    ConfiguredRoutineStore(File(app.noBackupFilesDir, "configured-routines.json")).save(listOf(estudio, reloj))
  }

  @After fun tearDown() {
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.NEVER)
    InstinctProposalSettings(app).setEnabled(false)
    InstinctProposalCenter.book.discardAll()
    InstinctProposalCenter.replySender = { context, handle, text -> handle.send(context, text) }
    InstinctProposalCenter.accessibilityConnected = { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
    InstinctProposalCenter.clock = android.os.SystemClock::elapsedRealtime
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

  private companion object { var nextId = 50_000 }
  private fun post(text: String) = Robolectric.buildService(NotificationAccessService::class.java).create().get()
    .onNotificationPosted(StatusBarNotification("com.whatsapp", "com.whatsapp", nextId++, "chat", 10_001, 0, 0, whatsApp(text),
      UserHandle.getUserHandleForUid(10_001), 0L))

  private fun titles() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
    .map { it.extras.getString(Notification.EXTRA_TITLE) ?: "" }

  @Test fun `a routine not enabled for Instinct is refused`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto ejecutar la rutina Estudio")
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals("#insto-error ejecutar la rutina Estudio → esa acción no está permitida para Instinct.", sent.single())
  }

  @Test fun `an enabled routine runs every step in the background and reports it`() {
    assertTrue(InstinctRoutineGrants(app).set(estudio, true))
    post("#insto ejecutar la rutina Estudio")
    assertEquals(max * 60 / 100, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue(titles().toString(), "Instinct hizo: rutina Estudio" in titles())
    assertEquals("#insto-ok ejecutar la rutina Estudio → Instinct ejecutó la rutina Estudio: 3 de 3 pasos.", sent.single())
    assertFalse("its workflow record is gone", File(app.noBackupFilesDir, InstinctProposalCenter.ROUTINE_WORKFLOW_FILE).exists())
  }

  @Test fun `a routine changed after it was enabled stays off`() {
    assertTrue(InstinctRoutineGrants(app).set(estudio, true))
    val changed = estudio.copy(commands = estudio.commands.dropLast(1))
    ConfiguredRoutineStore(File(app.noBackupFilesDir, "configured-routines.json")).save(listOf(changed, reloj))
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto ejecutar la rutina Estudio")
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue(sent.single().startsWith("#insto-error"))
  }

  @Test fun `a step that opens another app without accessibility leaves the whole routine for Confirmar before it starts`() {
    assertTrue(InstinctRoutineGrants(app).set(reloj, true))
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto ejecutar la rutina Reloj")
    assertEquals("not even the first step ran", 0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(1, InstinctProposalCenter.book.pending().size)
    assertTrue(sent.single(), sent.single().startsWith("#insto-espera ejecutar la rutina Reloj → Para abrir otra app"))
  }

  @Test fun `a routine counts as one execution for the anti-loop brake`() {
    assertTrue(InstinctRoutineGrants(app).set(estudio, true))
    repeat(5) { post("#insto poner el volumen al ${it + 1} por ciento") }
    post("#insto ejecutar la rutina Estudio")
    assertTrue(sent.last(), sent.last().startsWith("#insto-ok ejecutar la rutina Estudio → Instinct ejecutó la rutina Estudio: 3 de 3"))
    post("#insto ejecutar la rutina Estudio")
    assertTrue(sent.last(), sent.last().startsWith("#insto-espera ejecutar la rutina Estudio → Instinct pidió muchas acciones"))
  }

  @Test fun `ver catálogo changes nothing and answers with the version and enabled routines`() {
    assertTrue(InstinctRoutineGrants(app).set(estudio, true))
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
    val before = titles().size
    post("#insto ver catálogo")
    assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(before, titles().size)
    assertTrue(InstinctProposalCenter.book.pending().isEmpty())
    assertEquals("#insto-ok ver catálogo → Protocolo Insto v3 · rutinas habilitadas: Estudio.", sent.single())
    // Read-only answers go out under «Solo errores y esperas» too, and not under «Nunca».
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.PROBLEMS)
    post("#insto ver catalogo")
    assertEquals(2, sent.size)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.NEVER)
    post("#insto ver catálogo ")
    assertEquals(2, sent.size)
  }

  @Test fun `the catalog is sent marked, through the newest valid reply action`() {
    post("#insto ver catálogo")
    assertTrue(InstinctProposalCenter.sendCatalog(app))
    val catalog = sent.last()
    assertTrue(catalog.contains("› #insto poner una alarma a las 07:00"))
    assertEquals(emptyList<String>(), InstinctProposalProtocol.orders(catalog))
    fakeNow += ProposalBook.TTL_MILLIS + 1
    assertFalse(InstinctProposalCenter.sendCatalog(app))
  }

  @Test fun `routine grants live outside backups and an ineligible routine cannot be enabled`() {
    val call = ConfiguredRoutine("Llamar", listOf(PhoneCommand.Dial("+541112345678")))
    assertFalse(InstinctRoutineGrants(app).set(call, true))
    assertTrue(InstinctRoutineGrants(app).set(estudio, true))
    assertTrue(File(app.noBackupFilesDir, "instinct-routines.json").exists())
    assertEquals(listOf("Estudio"), InstinctRoutineGrants(app).access().enabledNames())
    assertTrue(InstinctRoutineGrants(app).set(estudio, false))
    assertEquals(emptyList<String>(), InstinctRoutineGrants(app).access().enabledNames())
  }
}
