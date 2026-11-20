package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.assist.AutoSendCoordinator
import com.example.backup.*
import com.example.data.AssistantPreferencesRepository
import com.example.data.LearnedPhraseStore
import com.example.data.PhraseTestDataStore
import com.example.phone.*
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Export and import over the real stores of one installation: DataStore, SharedPreferences and the routines file.
 * Keys use an in-memory [CredentialAccess]: Robolectric has no Android Keystore. The physical check on two phones
 * remains pending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupRestoreFlowTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()
  private val password = "una contraseña larga".toCharArray()

  private class Keys : CredentialAccess {
    var groq: String? = null; var gemini: String? = null; var enabled = false; var local = false
    override fun groqKey() = groq
    override fun saveGroqKey(value: String) { groq = value }
    override fun clearGroqKey() { groq = null }
    override fun geminiKey() = gemini
    override fun saveGeminiKey(value: String) { gemini = value }
    override fun clearGeminiKey() { gemini = null; enabled = false }
    override fun geminiEnabled() = enabled
    override fun setGeminiEnabled(value: Boolean) { enabled = value }
    override fun localChoice() = local
    override fun setLocalChoice(value: Boolean) { local = value }
  }

  private fun manager(keys: CredentialAccess, phrases: LearnedPhraseStore) = ConfigurationBackupManager(
    listOf(
      CredentialsSection(keys),
      AssistantSection(RepositoryAssistantAccess(AssistantPreferencesRepository(app))),
      PhrasesSection(phrases),
      RoutinesSection(ConfiguredRoutineStore(File(app.noBackupFilesDir, "configured-routines.json"))),
      PlacesSection(RepositoryPlacesAccess(SavedPlacesRepository(app))),
    ), "1.12.0", 21, FileRestoreMarker(File(app.noBackupFilesDir, AndroidBackup.MARKER)), random = SecureRandom(),
    iterations = BackupCrypto.MIN_ITERATIONS)

  @Test fun backupSchemaMatchesThePreferencesRepository() {
    assertEquals(AssistantPreferencesRepository.BACKUP_KEYS.keys,
      AssistantSection(RepositoryAssistantAccess(AssistantPreferencesRepository(app))).names)
  }

  @Test fun exportWipeAndImportRestoresEverythingWithoutExecutingAnything() = runBlocking {
    val repository = AssistantPreferencesRepository(app)
    repository.savePinnedContact("Contacto Prueba", "+447700900123")
    repository.setPreferredFlavor(com.example.data.WhatsAppFlavor.WHATSAPP_BUSINESS)
    repository.setGroqVocabularyEnabled(false)
    SavedPlacesRepository(app).save("casa", "Calle Falsa 123")
    ConfiguredRoutineStore(File(app.noBackupFilesDir, "configured-routines.json"))
      .save(listOf(ConfiguredRoutine("Estudio", listOf(PhoneCommand.Torch(true), PhoneCommand.Volume(VolumeOp.SET, 10)))))
    val phrases = LearnedPhraseStore(PhraseTestDataStore(), { true })
    phrases.teach("modo cine", PhoneCommand.Volume(VolumeOp.SET, 20), display = "Modo cine")
    phrases.teach("baja la musica al 30", PhoneCommand.Volume(VolumeOp.SET, 30), display = "Bajá la música al 30", generalize = true)
    phrases.learn(InterpretationRequest("Iluminame el cuarto"), PhoneCommand.Torch(true), true, PhoneActionResult("ok"))
    val keys = Keys().apply { groq = "gsk_test_0000000000"; gemini = "AIzaTest00000000000"; enabled = true }
    val file = manager(keys, phrases).export(BackupCategory.entries.toSet(), password)

    // A clean installation: empty stores of every kind.
    repository.restoreBackupValues(emptyMap(), AssistantPreferencesRepository.BACKUP_KEYS.keys)
    SavedPlacesRepository(app).remove("casa")
    File(app.noBackupFilesDir, "configured-routines.json").delete()
    val freshPhrases = LearnedPhraseStore(PhraseTestDataStore(), { true })
    val freshKeys = Keys()
    val target = manager(freshKeys, freshPhrases)
    val plan = (target.open(file, password) as OpenResult.Ready).plan
    val report = target.restore(plan, BackupCategory.entries.associateWith { RestoreMode.MERGE }, RestoreOptions())
    assertTrue(report.failure, report.success)

    val prefs = repository.getCurrentPreferences()
    assertEquals("+447700900123", prefs.pinnedContact?.phoneNumber)
    assertEquals(com.example.data.WhatsAppFlavor.WHATSAPP_BUSINESS, prefs.preferredFlavor)
    assertFalse(prefs.groqVocabularyEnabled)
    assertEquals("Calle Falsa 123", SavedPlacesRepository(app).address("casa"))
    assertEquals(2, ConfiguredRoutineStore(File(app.noBackupFilesDir, "configured-routines.json")).read().single().commands.size)
    assertEquals("gsk_test_0000000000", freshKeys.groq)
    assertTrue(freshKeys.enabled)
    val restored = freshPhrases.uiSettings.first().entries
    assertEquals(3, restored.size)
    assertEquals(LearnedPhraseStore.Source.MANUAL, restored.first { it.phrase == "modo cine" }.source)
    // The parameterized phrase still generalizes after the restore.
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 50), freshPhrases.lookup(InterpretationRequest("Bajá la música al 50")))

    // Nothing ran: no activity started, nothing armed, no phrase use recorded.
    assertNull(shadowOf(app).nextStartedActivity)
    assertFalse(AutoSendCoordinator.isArmedFor("com.whatsapp") || AutoSendCoordinator.isArmedFor("com.whatsapp.w4b"))
    assertTrue(restored.all { it.uses <= 1L })
    assertNull(target.interruptedRestore())
  }

  @Test fun mergingPhrasesNeverReplacesOnesTaughtHere() = runBlocking {
    val source = LearnedPhraseStore(PhraseTestDataStore(), { true })
    source.teach("modo cine", PhoneCommand.Volume(VolumeOp.SET, 20))
    source.teach("luz del pasillo", PhoneCommand.Torch(true))
    val file = manager(Keys(), source).export(setOf(BackupCategory.PHRASES), password)
    val here = LearnedPhraseStore(PhraseTestDataStore(), { true })
    here.teach("modo cine", PhoneCommand.Volume(VolumeOp.SET, 5))
    here.learn(InterpretationRequest("Luz del pasillo"), PhoneCommand.Torch(false), true, PhoneActionResult("ok"))
    val target = manager(Keys(), here)
    val plan = (target.open(file, password) as OpenResult.Ready).plan
    val report = target.restore(plan, mapOf(BackupCategory.PHRASES to RestoreMode.MERGE), RestoreOptions())
    assertTrue(report.success)
    val entries = here.uiSettings.first().entries.associateBy { it.phrase }
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 5), entries.getValue("modo cine").command)
    assertEquals("Manual from the backup replaces automatic here", PhoneCommand.Torch(true), entries.getValue("luz del pasillo").command)
    assertTrue(report.attention.any { it.contains("conservaron") })
  }
}
