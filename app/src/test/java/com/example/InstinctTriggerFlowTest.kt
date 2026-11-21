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

/** Triggers by state and recurring orders (1.17.0) on Android, from the WhatsApp line to the firing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctTriggerFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private var fakeNow = 1_000_000L
  private var wall = 1_790_000_000_000L
  private val sent = mutableListOf<String>()
  private val armed = mutableListOf<Pair<String, Boolean>>()
  /** What the calendar holds now; null: no permission. */
  private var events: List<CalendarInstance>? = emptyList()
  private val audio get() = app.getSystemService(AudioManager::class.java)
  private val notifications get() = app.getSystemService(NotificationManager::class.java)
  private val max get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

  @Before fun setUp() {
    InstinctProposalCenter.clock = { fakeNow }
    InstinctScheduler.wallClock = { wall }
    InstinctScheduler.alarms = { _, order, arm -> armed += order.id to arm }
    InstinctScheduler.calendar = { _, _ -> events }
    InstinctScheduler.launch = { block -> runBlocking { block() } }
    InstinctProposalCenter.budget.reset()
    InstinctProposalCenter.acks.clear()
    InstinctProposalCenter.accessibilityConnected = { false }
    InstinctProposalCenter.keyguardLocked = { false }
    InstinctProposalCenter.replySender = { _, _, text -> sent += text; true }
    InstinctProposalIntake.log = {}
    InstinctStatePublisher.background = { }
    InstinctLog.schedule = {}
    InstinctLog.outbox(app).remove(InstinctLog.outbox(app).batch(500).map { it.id }.toSet())
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"; applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+54 9 11 2233-4455") }
    awaitCachedPreferences(app) { it.pinnedContact?.displayName == "Instinct" && it.pinnedContact?.phoneNumber == "+54 9 11 2233-4455" }
    File(app.noBackupFilesDir, "instinct-scheduled.json").delete()
    InstinctProposalSettings(app).setEnabled(true)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
  }

  @After fun tearDown() {
    InstinctAutonomySettings(app).set(InstinctCategory.VOLUMEN, InstinctAutonomy.SOLO)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.NEVER)
    InstinctProposalSettings(app).setEnabled(false)
    InstinctProposalCenter.book.discardAll()
    InstinctProposalCenter.replySender = { context, handle, text -> handle.send(context, text) }
    InstinctProposalCenter.accessibilityConnected = { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
    InstinctProposalCenter.clock = android.os.SystemClock::elapsedRealtime
    InstinctScheduler.wallClock = System::currentTimeMillis
    InstinctScheduler.calendar = { context, now -> InstinctCalendarReader(context).instances(now) }
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

  private companion object { var nextId = 120_000 }
  private fun post(text: String) = Robolectric.buildService(NotificationAccessService::class.java).create().get()
    .onNotificationPosted(StatusBarNotification("com.whatsapp", "com.whatsapp", nextId++, "chat", 10_001, 0, 0, whatsApp(text),
      UserHandle.getUserHandleForUid(10_001), 0L))

  /** The log's events (not the phone's state): where what is new in 1.17.0 is told. */
  private fun logged() = InstinctLog.outbox(app).batch(500).filter { it.kind != "estado" }
  private fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
  private fun pending() = InstinctScheduler.pending(app)
  private fun fire(order: ScheduledOrder) = runBlocking { InstinctScheduler.fire(app, order.id, order.token) }
  private fun texts() = shadowOf(notifications).allNotifications.map { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() }
  /** «DD/MM» [days] after [wall], in the default zone. */
  private fun dayAfter(days: Int) = Calendar.getInstance().apply { timeInMillis = wall; add(Calendar.DAY_OF_MONTH, days) }
    .let { "%02d/%02d".format(it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.MONTH) + 1) }

  @Test fun `a headphones trigger fires once, only for its own event`() {
    post("#insto al conectar auriculares: poner el volumen al 40 por ciento")
    val order = pending().single()
    assertEquals(Trigger.HeadphonesOn, order.trigger)
    assertEquals(wall + InstinctTriggers.DEFAULT_EXPIRY_MILLIS, order.expiresAt)
    assertEquals("its alarm is the expiry", listOf(order.id to true), armed)
    assertTrue(sent.single(), sent.single().startsWith("#insto-programada al conectar auriculares: poner el volumen al 40 por ciento → al conectar auriculares, hasta las "))
    InstinctScheduler.onPhoneEvent(app, PhoneEvent.HEADPHONES_OFF)
    InstinctScheduler.onPhoneEvent(app, PhoneEvent.UNLOCK)
    assertEquals(0, volume())
    InstinctScheduler.onPhoneEvent(app, PhoneEvent.HEADPHONES_ON)
    assertEquals(max * 40 / 100, volume())
    assertTrue(pending().isEmpty())
    assertTrue("the expiry alarm is cancelled", armed.contains(order.id to false))
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    InstinctScheduler.onPhoneEvent(app, PhoneEvent.HEADPHONES_ON)
    runBlocking { InstinctScheduler.fire(app, order.id, order.token, InstinctScheduler.Cause.STATE) }
    assertEquals("used once", 0, volume())
  }

  @Test fun `an event that moved is rescheduled, then fires at its new time`() {
    val end = wall + 30 * 60_000L
    events = listOf(CalendarInstance(7, 70, wall - 30 * 60_000L, end))
    post("#insto al terminar el evento: poner el volumen al 40 por ciento")
    val order = pending().single()
    assertEquals(Trigger.EventEnd(70, 7, end), order.trigger)
    assertEquals(end, order.fireAt)
    assertTrue(sent.last(), sent.last().contains("→ al terminar el evento ("))
    // Extended by 20 minutes: the alarm at the old end only moves it.
    events = listOf(CalendarInstance(7, 70, wall - 30 * 60_000L, end + 20 * 60_000L))
    wall = end
    fire(order)
    assertEquals(0, volume())
    val moved = pending().single()
    assertEquals(end + 20 * 60_000L, moved.fireAt)
    assertEquals(order.id, moved.id)
    assertEquals(moved.id to true, armed.last())
    wall = moved.fireAt
    fire(moved)
    assertEquals(max * 40 / 100, volume())
    assertTrue(pending().isEmpty())
  }

  @Test fun `an event that was deleted is discarded and told`() {
    events = listOf(CalendarInstance(8, 80, wall + 60 * 60_000L, wall + 2 * 60 * 60_000L))
    post("#insto al empezar el próximo evento: poner el volumen al 40 por ciento")
    val order = pending().single()
    assertEquals(wall + 60 * 60_000L, order.fireAt)
    events = emptyList()
    wall = order.fireAt
    fire(order)
    assertEquals(0, volume())
    assertTrue(pending().isEmpty())
    assertTrue("nothing new in the chat", sent.last().startsWith("#insto-programada "))
    val event = logged().last()
    assertEquals("descartada", event.kind)
    assertEquals("el evento se borró; no se ejecutó.", event.detail)
    assertEquals("al empezar el próximo evento: poner el volumen al 40 por ciento", event.order)
  }

  @Test fun `an event trigger without an event or without permission is refused when it arrives`() {
    events = emptyList()
    post("#insto al terminar el evento: poner el volumen al 40 por ciento")
    assertEquals("#insto-error al terminar el evento: poner el volumen al 40 por ciento → no hay ningún evento en curso.", sent.last())
    events = null
    post("#insto al empezar el próximo evento: poner el volumen al 40 por ciento")
    assertTrue(sent.last(), sent.last().contains("no tiene permiso para leer el calendario"))
    assertTrue(pending().isEmpty())
  }

  @Test fun `a trigger that lapses is logged as vencida and never fires after`() {
    post("#insto al desbloquear: poner el volumen al 40 por ciento")
    val order = pending().single()
    wall = order.expiresAt!! + 1
    fire(order)
    assertTrue(pending().isEmpty())
    assertEquals("nothing new in the chat", 1, sent.size)
    assertEquals("vencida", logged().last().kind)
    assertEquals("venció sin dispararse; no se ejecutó.", logged().last().detail)
    assertTrue(texts().toString(), texts().any { it.startsWith("Instinct había programado") })
    InstinctScheduler.onPhoneEvent(app, PhoneEvent.UNLOCK)
    assertEquals(0, volume())
  }

  @Test fun `a late occurrence of a recurrence waits for Confirmar, and the next one is already armed`() {
    post("#insto todos los días a las 08:00 hasta el ${dayAfter(5)}: poner el volumen al 40 por ciento")
    val first = pending().single()
    assertTrue(first.recurring)
    assertTrue(sent.single(), sent.single().contains("→ todos los días 08:00 hasta el ${dayAfter(5)}, próxima "))
    wall = first.fireAt + 20 * 60_000L
    fire(first)
    assertEquals("late: it does not run alone", 0, volume())
    assertTrue(texts().toString(), texts().any { it.startsWith("Instinct propone: ") })
    assertEquals("a wait is not a follow-up line in the chat (as in 1.16.0)", 1, sent.size)
    assertTrue(InstinctProposalIntake.lastDetail.toString(), InstinctProposalIntake.lastDetail!!.startsWith("Llegó tarde"))
    val next = pending().single()
    assertEquals(first.series, next.series)
    assertEquals(first.fireAt + 24 * 60 * 60_000L, next.fireAt)
    assertEquals(next.id to true, armed.last())
    // The person discards the late one; the next occurrence fires on time and runs.
    InstinctProposalCenter.book.pending().forEach { InstinctProposalCenter.discard(app, it.id) }
    wall = next.fireAt
    fire(next)
    assertEquals(max * 40 / 100, volume())
    assertEquals(1, pending().size)
  }

  @Test fun `cancelar programada cancels one recurrence by its short id, without the brake`() {
    post("#insto los fines de semana a las 10:00 hasta el ${dayAfter(20)}: poner el volumen al 40 por ciento")
    post("#insto en 30 minutos: poner el volumen al 10 por ciento")
    val recurring = pending().first { it.recurring }
    sent.clear()
    post("#insto ver programadas")
    assertTrue(sent.single(), sent.single().contains("${recurring.shortId} · fines de semana 10:00 hasta el ${dayAfter(20)}, próxima "))
    post("#insto cancelar programada ${recurring.shortId}")
    assertTrue(sent.last(), sent.last().startsWith("#insto-ok cancelar programada ${recurring.shortId} → Se canceló ${recurring.shortId}: "))
    assertEquals(listOf(false), pending().map { it.recurring })
    assertTrue(armed.contains(recurring.id to false))
    post("#insto cancelar programada ${recurring.shortId}")
    assertTrue(sent.last(), sent.last().endsWith("No hay ninguna orden programada con el id ${recurring.shortId}."))
    post("#insto cancelar programadas")
    assertTrue(pending().isEmpty())
  }

  @Test fun `Nunca for its category cancels a recurrence whole`() {
    post("#insto los días hábiles a las 08:00 hasta el ${dayAfter(10)}: poner el volumen al 40 por ciento")
    assertEquals(1, pending().size)
    InstinctAutonomySettings(app).set(InstinctCategory.VOLUMEN, InstinctAutonomy.NUNCA)
    assertTrue(pending().isEmpty())
    assertEquals("nothing new in the chat", 1, sent.size)
    assertEquals("error", logged().last().kind)
    assertTrue(logged().last().detail, logged().last().detail.contains("se canceló la recurrente entera"))
  }
}
