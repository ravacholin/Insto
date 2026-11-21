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
import java.math.BigDecimal
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

/** Deferred orders, weather conditions and «Deshacer» on Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctDeferredFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private var fakeNow = 1_000_000L
  private var wall = 1_790_000_000_000L
  private val sent = mutableListOf<String>()
  private val armed = mutableListOf<Pair<String, Boolean>>()
  private val audio get() = app.getSystemService(AudioManager::class.java)
  private val notifications get() = app.getSystemService(NotificationManager::class.java)
  private val max get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
  private var temperature: BigDecimal? = null
  private var rain: PercentRange? = null
  private val torchCalls = mutableListOf<Boolean>()

  @Before fun setUp() {
    InstinctProposalCenter.clock = { fakeNow }
    InstinctScheduler.wallClock = { wall }
    InstinctScheduler.alarms = { _, order, arm -> armed += order.id to arm }
    InstinctScheduler.weather = object : WeatherReadings {
      override suspend fun currentTemperature() = temperature
      override suspend fun rainChance() = rain
    }
    InstinctUndoActions.torch = { _, on -> torchCalls += on; true }
    InstinctProposalCenter.budget.reset()
    InstinctProposalCenter.acks.clear()
    InstinctProposalCenter.undos.clear()
    InstinctProposalCenter.accessibilityConnected = { false }
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

  private companion object { var nextId = 90_000 }
  private fun post(text: String) = Robolectric.buildService(NotificationAccessService::class.java).create().get()
    .onNotificationPosted(StatusBarNotification("com.whatsapp", "com.whatsapp", nextId++, "chat", 10_001, 0, 0, whatsApp(text),
      UserHandle.getUserHandleForUid(10_001), 0L))

  private fun posted() = shadowOf(notifications).allNotifications
  private fun texts() = posted().map { (it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "") + " | " +
    (it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "") }

  private fun fireOnly(): ScheduledOrder {
    val order = InstinctScheduler.pending(app).single()
    wall = order.fireAt
    runBlocking { InstinctScheduler.fire(app, order.id, order.token) }
    return order
  }

  // Deferred

  @Test fun `a deferred order is stored, acknowledged and runs once when it fires`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto en 30 minutos: poner el volumen al 40 por ciento")
    assertEquals("nothing runs now", 0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    val order = InstinctScheduler.pending(app).single()
    assertEquals(wall + 30 * 60_000, order.fireAt)
    assertEquals(listOf(order.id to true), armed)
    assertTrue(sent.single(), sent.single().startsWith("#insto-programada en 30 minutos: poner el volumen al 40 por ciento → a las "))
    assertTrue(File(app.noBackupFilesDir, "instinct-scheduled.json").exists())
    fireOnly()
    assertEquals(max * 40 / 100, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue(InstinctScheduler.pending(app).isEmpty())
    assertTrue(texts().toString(), texts().any { it.startsWith("Instinct hizo: ") })
    // Delivered twice: nothing runs again.
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    runBlocking { InstinctScheduler.fire(app, order.id, order.token) }
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
  }

  @Test fun `an invalid inner order is never stored and answers an error`() {
    post("#insto a las 22:00: llamar a Ana")
    assertTrue(InstinctScheduler.pending(app).isEmpty())
    assertEquals("#insto-error a las 22:00: llamar a Ana → esa acción no está permitida para Instinct.", sent.single())
  }

  @Test fun `an eleventh scheduled order is refused`() {
    repeat(10) { post("#insto en ${it + 1} minutos: prender la linterna") }
    post("#insto en 20 minutos: apagar la linterna")
    assertEquals(10, InstinctScheduler.pending(app).size)
    assertTrue(sent.last(), sent.last().startsWith("#insto-error en 20 minutos: apagar la linterna → ya hay 10 órdenes programadas"))
  }

  @Test fun `a changed configuration drops the order when it fires`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto en 5 minutos: poner el volumen al 50 por ciento")
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+54 9 11 9999-0000") }
    awaitCachedPreferences(app) { it.pinnedContact?.phoneNumber == "+54 9 11 9999-0000" }
    fireOnly()
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue(texts().toString(), texts().any { it.contains("cambió la configuración") })
    assertTrue(InstinctScheduler.pending(app).isEmpty())
  }

  @Test fun `a late firing waits for Confirmar`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto en 5 minutos: poner el volumen al 50 por ciento")
    val order = InstinctScheduler.pending(app).single()
    wall = order.fireAt + 16 * 60_000
    runBlocking { InstinctScheduler.fire(app, order.id, order.token) }
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertEquals(1, InstinctProposalCenter.book.pending().size)
    assertTrue(posted().any { it.channelId == InstinctProposalCenter.CHANNEL_ID })
    assertTrue(InstinctProposalIntake.lastDetail!!.startsWith("Llegó tarde"))
  }

  @Test fun `a process death after consuming leaves it uncertain, reported and never run`() {
    post("#insto en 5 minutos: poner el volumen al 50 por ciento")
    val order = InstinctScheduler.pending(app).single()
    InstinctScheduler.store(app).consume(order.id, order.token) // ...and the process dies here.
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    InstinctScheduleReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
    assertTrue(texts().toString(), texts().any { it.contains("Pudo haberse ejecutado") })
    runBlocking { InstinctScheduler.fire(app, order.id, order.token) }
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
  }

  @Test fun `boot and clock changes arm every pending order again`() {
    post("#insto en 5 minutos: prender la linterna")
    post("#insto a las 06:30 si llueve: apagar la linterna")
    armed.clear()
    InstinctScheduleReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
    assertEquals(2, armed.count { it.second })
  }

  @Test fun `ver programadas and cancelar programadas`() {
    post("#insto en 5 minutos: prender la linterna")
    post("#insto ver programadas")
    assertTrue(sent.last(), sent.last().startsWith("#insto-ok ver programadas → ") && sent.last().endsWith("prender la linterna."))
    post("#insto cancelar programadas")
    assertEquals("#insto-ok cancelar programadas → Se canceló 1 orden programada.", sent.last())
    assertTrue(InstinctScheduler.pending(app).isEmpty())
    assertTrue(armed.any { !it.second })
  }

  @Test fun `turning the switch off cancels scheduled orders`() {
    post("#insto en 5 minutos: prender la linterna")
    InstinctProposalSettings(app).setEnabled(false)
    assertTrue(InstinctScheduler.pending(app).isEmpty())
  }

  // Conditions

  @Test fun `a met condition runs, an unmet one does not`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto a las 06:30 si llueve: poner el volumen al 30 por ciento")
    rain = PercentRange(10, 20)
    fireOnly()
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue(texts().toString(), texts().any { it.contains("no se cumplió «si llueve»") })
    post("#insto a las 06:30 si hace menos de 10 grados: poner el volumen al 30 por ciento")
    temperature = BigDecimal("8.5")
    fireOnly()
    assertEquals(max * 30 / 100, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
  }

  @Test fun `without weather data a condition never runs`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    post("#insto a las 06:30 si hace más de 30 grados: poner el volumen al 30 por ciento")
    temperature = null
    fireOnly()
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue(texts().toString(), texts().any { it.contains("no hay datos del clima") })
    post("#insto a las 06:30 si no llueve: poner el volumen al 30 por ciento")
    rain = PercentRange(40, 70) // straddles the threshold: ambiguous
    fireOnly()
    assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
  }

  // Undo

  private fun undoAction(): Pair<Long, String> {
    val done = posted().last { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.startsWith("Instinct hizo") == true }
    assertEquals(listOf("Deshacer"), done.actions.map { it.title.toString() })
    val entry = shadowOf(done.actions.single().actionIntent).savedIntent
    return entry.getLongExtra(InstinctProposalCenter.EXTRA_UNDO_ID, -1) to entry.getStringExtra(InstinctProposalCenter.EXTRA_UNDO_TOKEN)!!
  }

  private fun undo(id: Long, token: String) = InstinctProposalReceiver().onReceive(app,
    Intent(InstinctProposalCenter.ACTION_UNDO).putExtra(InstinctProposalCenter.EXTRA_UNDO_ID, id).putExtra(InstinctProposalCenter.EXTRA_UNDO_TOKEN, token))

  @Test fun `Deshacer restores the previous volume and tells Instinct`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
    post("#insto poner el volumen al 80 por ciento")
    val (id, token) = undoAction()
    undo(id, token)
    assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue(sent.last(), sent.last().startsWith("#insto-deshecho poner el volumen al 80 por ciento → Deshice: "))
  }

  @Test fun `Deshacer after a manual change does nothing and says so`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
    post("#insto poner el volumen al 80 por ciento")
    val (id, token) = undoAction()
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
    assertEquals("No toqué lo que cambiaste después: ${PhoneCommandCodec.summary(PhoneCommand.Volume(VolumeOp.SET, 80))}.",
      InstinctProposalCenter.undo(app, id, token))
    assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertFalse(sent.last().startsWith("#insto-deshecho"))
  }

  @Test fun `a wrong token or a second Deshacer does nothing`() {
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
    post("#insto poner el volumen al 80 por ciento")
    val (id, token) = undoAction()
    assertEquals("No hay nada para deshacer.", InstinctProposalCenter.undo(app, id, "adivinado"))
    assertEquals(max * 80 / 100, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    InstinctProposalCenter.undo(app, id, token)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
    assertEquals("Ya se deshizo.", InstinctProposalCenter.undo(app, id, token))
    assertEquals(7, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
  }

  @Test fun `Deshacer lapses after ten minutes`() {
    post("#insto poner el volumen al 80 por ciento")
    val (id, token) = undoAction()
    fakeNow += ProposalBook.TTL_MILLIS + 1
    assertEquals("Ya pasaron más de 10 minutos: no se puede deshacer.", InstinctProposalCenter.undo(app, id, token))
  }

  @Test fun `Deshacer of the torch sets the inverse state`() {
    shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
    InstinctProposalCenter.undos.offer(1, "prender la linterna", listOf(Reversal.Torch(PhoneCommand.Torch(true), restore = false)))!!.let {
      InstinctProposalCenter.undo(app, it.id, it.token)
    }
    assertEquals(listOf(false), torchCalls)
  }

  @Test fun `Deshacer of silence and Do not disturb restores the previous mode unless changed`() {
    audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
    post("#insto poner en vibración")
    assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
    val (id, token) = undoAction()
    undo(id, token)
    assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)

    notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    post("#insto activar no molestar")
    assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, notifications.currentInterruptionFilter)
    val (dnd, dndToken) = undoAction()
    notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
    InstinctProposalCenter.undo(app, dnd, dndToken)
    assertEquals("a manual change is kept", NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
  }

  @Test fun `Deshacer of a temporary mode ends its own lease, never one changed by hand`() {
    val manager = TemporaryModeManager(app)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
    manager.execute(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 3600, 50, approximate = true), confirmed = true)
    val lease = manager.read()!!
    val entry = InstinctProposalCenter.undos.offer(1, "orden", listOf(Reversal.Temporary(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 3600, 50), lease.id)))!!
    InstinctProposalCenter.undo(app, entry.id, entry.token)
    assertNull(manager.read())
    assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

    manager.execute(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 3600, 50, approximate = true), confirmed = true)
    val second = manager.read()!!
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0) // changed by hand
    val again = InstinctProposalCenter.undos.offer(2, "orden", listOf(Reversal.Temporary(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 3600, 50), second.id)))!!
    assertTrue(InstinctProposalCenter.undo(app, again.id, again.token).startsWith("No toqué"))
    assertNotNull("its lease is left as it was", manager.read())
    assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    manager.end(force = false)
  }

  @Test fun `alarms, timers and apps get no Deshacer`() {
    InstinctProposalCenter.accessibilityConnected = { true }
    com.example.testsupport.installNativeHandler(app, android.provider.AlarmClock.ACTION_SET_TIMER)
    post("#insto poner un temporizador de 2 minutos")
    val done = posted().last { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.startsWith("Instinct hizo") == true }
    assertTrue(done.actions.isNullOrEmpty())
  }

  @Test fun `Deshacer of a routine reverts its reversible steps in reverse order and lists the rest`() {
    val routine = ConfiguredRoutine("Noche", listOf(PhoneCommand.Volume(VolumeOp.SET, 20), PhoneCommand.Volume(VolumeOp.SET, 60)))
    ConfiguredRoutineStore(File(app.noBackupFilesDir, "configured-routines.json")).save(listOf(routine))
    assertTrue(InstinctRoutineGrants(app).set(routine, true))
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
    post("#insto ejecutar la rutina Noche")
    assertEquals(max * 60 / 100, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    val (id, token) = undoAction()
    undo(id, token)
    assertEquals("back to before the first step", 1, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
  }
}
