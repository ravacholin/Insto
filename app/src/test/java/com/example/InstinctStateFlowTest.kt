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
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.AssistantPreferencesRepository
import com.example.phone.*
import com.example.testsupport.awaitCachedPreferences
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** «Estado del teléfono» on Android: when it is published, through the log only, and «#insto ver estado». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctStateFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private var fakeNow = 1_000_000L
  private var wall = 1_790_000_000_000L
  private val sent = mutableListOf<String>()
  private var heartbeat: Boolean? = null
  private val url = "https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec"
  private val token = "token-largo-de-prueba"

  @Before fun setUp() {
    InstinctProposalCenter.clock = { fakeNow }
    InstinctScheduler.wallClock = { wall }
    InstinctProposalCenter.budget.reset()
    InstinctProposalCenter.acks.clear()
    InstinctProposalCenter.accessibilityConnected = { false }
    InstinctProposalCenter.keyguardLocked = { false }
    InstinctProposalCenter.replySender = { _, _, text -> sent += text; true }
    InstinctProposalIntake.log = {}
    InstinctLog.schedule = {}
    InstinctStatePublisher.background = { it.run() }
    InstinctStatePublisher.heartbeat = { _, on -> heartbeat = on }
    InstinctLog.outbox(app).remove(InstinctLog.outbox(app).batch(500).map { it.id }.toSet())
    File(app.noBackupFilesDir, InstinctStatePublisher.LAST_FILE).delete()
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
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.NEVER)
    InstinctProposalSettings(app).setEnabled(false)
    InstinctLogSettings(app).clear()
    InstinctProposalCenter.book.discardAll()
    InstinctProposalCenter.replySender = { context, handle, text -> handle.send(context, text) }
    InstinctProposalCenter.accessibilityConnected = { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
    InstinctProposalCenter.clock = android.os.SystemClock::elapsedRealtime
    InstinctScheduler.wallClock = System::currentTimeMillis
    InstinctStatePublisher.reader = { context, now -> InstinctStateReader(context).read(now) }
  }

  private fun configure() { assertTrue(InstinctLogSettings(app).save(url, token)) }

  private fun whatsApp(text: String): Notification {
    val intent = PendingIntent.getBroadcast(app, 1, Intent("com.whatsapp.REPLY").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE)
    val reply = Notification.Action.Builder(null, "Responder", intent)
      .addRemoteInput(RemoteInput.Builder("android.intent.extra.text").setLabel("Responder").build()).build()
    val style = Notification.MessagingStyle(Person.Builder().setName("Yo").build())
      .addMessage(text, System.currentTimeMillis(), Person.Builder().setName("Instinct").build())
    return Notification.Builder(app, "whatsapp").setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Instinct").setContentText(text).setStyle(style).addAction(reply).build()
  }

  private companion object { var nextId = 130_000 }
  private fun post(text: String) {
    val notification = whatsApp(text)
    InstinctProposalIntake.onPosted(app, { ExtrasProposalNotification("com.whatsapp", "state-${nextId++}") { notification.extras } },
      { InstinctReplyHandle.from(notification) })
  }

  private fun states() = InstinctLog.outbox(app).batch(500).filter { it.kind == "estado" }

  @Test fun `without the log nothing is published and the diagnosis says why`() {
    assertEquals(InstinctStatePublisher.Outcome.NOT_CONFIGURED, InstinctStatePublisher.publish(app, InstinctState.Reason.MANUAL))
    assertTrue(states().isEmpty())
    assertEquals("Estado para Instinct: no se publica, porque la bitácora no está configurada.", InstinctStatePublisher.diagnosis(app))
  }

  @Test fun `the real reader fills every field and the calendar without permission is sin permiso`() {
    configure()
    shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
    app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
    assertEquals(InstinctStatePublisher.Outcome.PUBLISHED, InstinctStatePublisher.publish(app, InstinctState.Reason.MANUAL))
    val detail = states().single().detail
    assertTrue(detail, detail.startsWith("sonido=vibración; dnd="))
    assertTrue(detail, detail.contains("evento=sin permiso"))
    assertTrue(detail, detail.contains("programadas=ninguna"))
    assertFalse(detail.contains("volumen"))
    assertTrue(InstinctStatePublisher.diagnosis(app).startsWith("Estado para Instinct: último envío "))
    // The heartbeat is armed with the log and stops with the switch.
    assertEquals(true, heartbeat)
    InstinctProposalSettings(app).setEnabled(false)
    assertEquals(false, heartbeat)
  }

  @Test fun `a field that fails is sin datos and the snapshot is published anyway`() {
    configure()
    InstinctStatePublisher.reader = { _, _ -> error("roto") }
    assertEquals(InstinctStatePublisher.Outcome.PUBLISHED, InstinctStatePublisher.publish(app, InstinctState.Reason.MANUAL))
    assertTrue(states().single().detail.startsWith("sonido=sin datos; dnd=sin datos"))
  }

  @Test fun `an unchanged state is not published again on a change, but the heartbeat refreshes it`() {
    configure()
    assertEquals(InstinctStatePublisher.Outcome.PUBLISHED, InstinctStatePublisher.publish(app, InstinctState.Reason.CHANGE))
    assertEquals(InstinctStatePublisher.Outcome.UNCHANGED, InstinctStatePublisher.publish(app, InstinctState.Reason.CHANGE))
    wall += InstinctState.HEARTBEAT_MILLIS
    assertEquals(InstinctStatePublisher.Outcome.PUBLISHED, InstinctStatePublisher.publish(app, InstinctState.Reason.HEARTBEAT))
    assertEquals(2, states().size)
    assertTrue(File(app.noBackupFilesDir, InstinctStatePublisher.LAST_FILE).exists())
  }

  @Test fun `the state follows an order that ran`() {
    configure()
    post("#insto poner el volumen al 30 por ciento")
    assertTrue(states().isEmpty())
    ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
    assertEquals(1, states().size)
  }

  @Test fun `ver estado publishes even unchanged and never consumes the brake`() {
    configure()
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.BITACORA)
    InstinctStatePublisher.publish(app, InstinctState.Reason.MANUAL)
    repeat(6) { assertTrue(InstinctProposalCenter.budget.tryAcquire()) }
    post("#insto ver estado")
    assertEquals(2, states().size)
    val ack = InstinctLog.outbox(app).batch(500).last { it.kind == "ok" }
    assertEquals("ver estado", ack.order)
    assertEquals("estado publicado en la bitácora; leelo con action=estado.", ack.detail)
    assertFalse("the brake is still exhausted, untouched", InstinctProposalCenter.budget.tryAcquire())
  }

  @Test fun `with the channel on WhatsApp ver estado answers an error and never sends the state through the chat`() {
    configure()
    post("#insto ver estado")
    assertEquals(listOf("#insto-error ver estado → el estado solo se publica en la bitácora"), sent)
    assertFalse(sent.single().contains("sonido="))
  }
}
