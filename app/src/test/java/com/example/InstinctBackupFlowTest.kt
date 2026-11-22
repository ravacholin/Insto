package com.example

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.backup.AndroidBackup
import com.example.backup.BackupCategory
import com.example.backup.OpenResult
import com.example.backup.RestoreMode
import com.example.backup.RestoreOptions
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.20.0: Instinct's settings in the `.insto` copy over the real stores, the restore on a new phone and the reactivation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctBackupFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private val password = "una contraseña larga".toCharArray()
  private val url = "https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec"
  private val token = "token-largo-de-prueba-123456"
  private val key = "clave-del-buzon-de-prueba-0123456789-abcdef"
  private val wall = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 22, 9, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
  private val dir get() = app.noBackupFilesDir
  private val audio get() = app.getSystemService(AudioManager::class.java)

  @Before fun setUp() {
    InstinctScheduler.wallClock = { wall }
    InstinctMailbox.clock = { wall }
    InstinctScheduler.alarms = { _, _, _ -> }
    InstinctLog.schedule = {}
    InstinctMailbox.schedule = { _, _ -> }
    InstinctStatePublisher.heartbeat = { _, _ -> }
    InstinctStatePublisher.background = { }
    InstinctDoorbell.io = { }
    InstinctDoorbell.schedule = { _, _, _ -> }
    InstinctDoorbell.distributors = { listOf("io.heckel.ntfy") }
    InstinctReactivation.logTest = { "Bitácora: prueba enviada." }
    InstinctReactivation.mailboxTest = { "El buzón respondió bien: no hay órdenes pendientes." }
    org.robolectric.Shadows.shadowOf(app.packageManager).installPackage(android.content.pm.PackageInfo().apply {
      packageName = "com.whatsapp"; applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+54 9 11 2233-4455") }
    awaitCachedPreferences(app) { it.pinnedContact?.displayName == "Instinct" }
  }

  @After fun tearDown() {
    InstinctProposalSettings(app).setEnabled(false)
    InstinctScheduler.wallClock = System::currentTimeMillis
    InstinctMailbox.clock = { InstinctScheduler.wallClock() }
    InstinctReactivation.logTest = { InstinctLog.test(it) }
    InstinctReactivation.mailboxTest = { InstinctMailbox.test(it) }
  }

  private fun configureEverything() {
    InstinctProposalSettings(app).setEnabled(true)
    InstinctProposalSettings(app).setReplyMode(InstinctReplyMode.ALWAYS)
    InstinctProposalSettings(app).setChannel(InstinctAckChannel.BITACORA)
    assertTrue(InstinctLogSettings(app).save(url, token))
    assertTrue(InstinctMailboxSettings(app).saveKey(key))
    InstinctMailboxSettings(app).setEnabled(true)
    InstinctMailboxSettings(app).setAccept(InstinctMailboxAccept.SOLO_BUZON)
    InstinctMailboxSettings(app).setIntervalMinutes(2)
    InstinctAutonomySettings(app).set(InstinctCategory.VOLUMEN, InstinctAutonomy.CONFIRMAR)
    InstinctAutonomySettings(app).setQuiet(QuietHours(23 * 60, 7 * 60))
    // The doorbell of this phone: its preferences, plus what only belongs to it.
    File(dir, "instinct-doorbell-enabled").writeText("true")
    File(dir, "instinct-doorbell-with-order").writeText("true")
    File(dir, "instinct-doorbell-distributor").writeText("io.heckel.ntfy")
    File(dir, InstinctDoorbellSettings.ENDPOINT_FILE).writeText("https://ntfy.sh/upAbCdEf0123456789?up=1")
    File(dir, InstinctDoorbellSettings.SEALED_FILE).writeText("c2VsbGFkbw")
    val until = Calendar.getInstance().apply { timeInMillis = wall; add(Calendar.DAY_OF_MONTH, 10) }
    val line = "todos los días a las 07:00 hasta el %02d/%02d: poner el volumen al 30 por ciento".format(until.get(Calendar.DAY_OF_MONTH), until.get(Calendar.MONTH) + 1)
    val check = InstinctProposalValidator.check(line, PhoneCommandParser(), Calendar.getInstance().apply { timeInMillis = wall }) as ProposalCheck.Valid
    assertTrue(InstinctScheduler.schedule(app, check, InstinctProposalCenter.fingerprint(app)!!, "", ProposalSource.BUZON) is InstinctScheduler.Outcome.Scheduled)
  }

  /** A new phone: none of Instinct's files, the same contact (restored by its own category). */
  private fun wipeInstinct() {
    InstinctProposalSettings(app).setEnabled(false)
    dir.listFiles().orEmpty().filter { it.name.startsWith("instinct-") }.forEach { it.deleteRecursively() }
    InstinctExecutionJournal.resetAll()
  }

  private fun restore(bytes: ByteArray, mode: RestoreMode = RestoreMode.MERGE) = runBlocking {
    val manager = AndroidBackup.manager(app)
    val plan = (manager.open(bytes, password) as OpenResult.Ready).plan
    manager.restore(plan, mapOf(BackupCategory.INSTINCT to mode), RestoreOptions())
  }

  @Test fun `a full copy moves every Instinct setting to a new phone, off, without device-bound data, and runs nothing`() = runBlocking {
    configureEverything()
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
    val bytes = AndroidBackup.manager(app).export(BackupCategory.entries.toSet(), password)
    wipeInstinct()
    val report = restore(bytes)
    assertTrue(report.failure, report.success)
    assertEquals(url, InstinctLogSettings(app).url()); assertEquals(token, InstinctLogSettings(app).token())
    assertEquals(key, InstinctMailboxSettings(app).key())
    assertEquals(InstinctMailboxAccept.SOLO_BUZON, InstinctMailboxSettings(app).accept())
    assertEquals(2, InstinctMailboxSettings(app).intervalMinutes())
    assertEquals(InstinctAutonomy.CONFIRMAR, InstinctAutonomySettings(app).policy().level(InstinctCategory.VOLUMEN))
    assertEquals(QuietHours(23 * 60, 7 * 60), InstinctAutonomySettings(app).policy().quiet)
    assertEquals(InstinctReplyMode.ALWAYS, InstinctProposalSettings(app).replyMode())
    assertEquals(InstinctAckChannel.BITACORA, InstinctProposalSettings(app).channel())
    assertTrue(InstinctDoorbell.settings(app).withOrder()); assertEquals("io.heckel.ntfy", InstinctDoorbell.settings(app).chosen())
    // Off until the person reactivates; nothing of the old phone's registration.
    assertFalse(InstinctProposalSettings(app).isEnabled()); assertFalse(InstinctMailboxSettings(app).enabled())
    assertFalse(InstinctDoorbell.settings(app).enabled())
    assertNull(InstinctDoorbell.settings(app).endpoint()); assertNull(InstinctDoorbell.settings(app).sealed())
    assertTrue("no order scheduled by the import", InstinctScheduler.pending(app).isEmpty())
    assertTrue(InstinctProposalCenter.book.pending().isEmpty())
    assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue("old executions are never imported", InstinctExecutions.journal(app).recent(50).isEmpty())
    assertNotNull(InstinctReactivation.pending(app))
  }

  @Test fun `the reactivation turns on what the copy had on, registers the doorbell anew and schedules the recurrences once`() = runBlocking {
    configureEverything()
    val bytes = AndroidBackup.manager(app).export(BackupCategory.entries.toSet(), password)
    wipeInstinct()
    restore(bytes)
    val steps = InstinctReactivation.run(app)
    assertTrue(steps.toString(), steps.filter { it.name != "Permisos de Android" }.all { it.ok })
    assertTrue(InstinctProposalSettings(app).isEnabled()); assertTrue(InstinctMailboxSettings(app).enabled())
    assertTrue(InstinctDoorbell.settings(app).enabled())
    assertNull("the old endpoint is never reused", InstinctDoorbell.settings(app).endpoint())
    assertEquals(1, InstinctScheduler.pending(app).count { it.recurring })
    assertNull(InstinctReactivation.pending(app))
    // A second reactivation finds nothing and schedules nothing again.
    InstinctReactivation.run(app)
    assertEquals(1, InstinctScheduler.pending(app).count { it.recurring })
  }

  @Test fun `without a distributor the doorbell stays pending and says why, the rest is reactivated`() = runBlocking {
    configureEverything()
    val bytes = AndroidBackup.manager(app).export(BackupCategory.entries.toSet(), password)
    wipeInstinct()
    restore(bytes)
    InstinctDoorbell.distributors = { emptyList() }
    val steps = InstinctReactivation.run(app)
    assertFalse(steps.single { it.name == "Timbre" }.ok)
    assertTrue(InstinctMailboxSettings(app).enabled())
    assertTrue(InstinctReactivation.pending(app)!!.doorbell)
    assertTrue(InstinctReactivation.pending(app)!!.recurrences.isEmpty())
  }

  @Test fun `a copy without secrets restores the URL and the preferences but neither the token nor the key`() = runBlocking {
    configureEverything()
    val bytes = AndroidBackup.manager(app).export(BackupCategory.entries.toSet() - BackupCategory.CREDENTIALS, password, secrets = false)
    wipeInstinct()
    restore(bytes)
    assertEquals(url, InstinctLogSettings(app).url())
    assertNull(InstinctLogSettings(app).token()); assertNull(InstinctMailboxSettings(app).key())
    assertEquals(InstinctMailboxAccept.SOLO_BUZON, InstinctMailboxSettings(app).accept())
  }

  @Test fun `a restore on the same phone with everything on keeps it on and asks nothing`() = runBlocking {
    configureEverything()
    val bytes = AndroidBackup.manager(app).export(BackupCategory.entries.toSet(), password)
    val report = restore(bytes, RestoreMode.REPLACE)
    assertTrue(report.success)
    assertTrue(InstinctProposalSettings(app).isEnabled())
    assertNull(InstinctReactivation.pending(app))
    assertEquals(1, InstinctScheduler.pending(app).count { it.recurring })
  }
}
