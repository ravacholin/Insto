package com.example.backup

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.example.assist.PingAutoSendAccessibilityService
import com.example.data.AssistantPreferencesRepository
import com.example.data.FloatingPillMode
import com.example.data.LearnedPhraseStore
import com.example.data.VolumeKeyTrigger
import com.example.data.WhatsAppFlavor
import com.example.phone.ConfiguredRoutineStore
import com.example.phone.GeminiSettings
import com.example.phone.PhoneCommand
import com.example.phone.SavedPlacesRepository
import com.example.speech.GroqKeyStore
import com.example.whatsapp.WhatsAppDispatcher
import java.io.File
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Learned phrases and structures, validated with the store's own export rules. */
class PhrasesSection(private val store: LearnedPhraseStore) : BackupSection {
  override val category = BackupCategory.PHRASES

  override suspend fun export(): JSONObject? {
    val data = store.backupData()
    if (data.entries.isEmpty() && data.patterns.isEmpty() && data.enabled == null) return null
    return LearnedPhraseStore.encodeBackup(data)
  }

  override suspend fun describeCurrent(): String? {
    val data = store.backupData()
    if (data.entries.isEmpty() && data.patterns.isEmpty()) return null
    return describe(data)
  }

  private fun describe(data: LearnedPhraseStore.BackupData): String {
    val manual = data.entries.count { it.source == LearnedPhraseStore.Source.MANUAL }
    val patterns = if (data.patterns.isEmpty()) "" else " · ${data.patterns.size} estructuras"
    return "${data.entries.size} frases ($manual agregadas por vos)$patterns"
  }

  override fun validate(json: JSONObject) = CategorySummary(describe(LearnedPhraseStore.decodeBackup(json)))

  override suspend fun prepare(json: JSONObject, mode: RestoreMode, options: RestoreOptions): PreparedChange {
    val incoming = LearnedPhraseStore.decodeBackup(json)
    val current = store.backupData()
    val changes = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val attention = mutableListOf<String>()
    if (mode == RestoreMode.MERGE) {
      val local = current.entries.associateBy { it.phrase }
      val added = incoming.entries.count { it.phrase !in local }
      val keptManual = incoming.entries.count { local[it.phrase]?.source == LearnedPhraseStore.Source.MANUAL && local[it.phrase] != it }
      val upgraded = incoming.entries.count {
        it.source == LearnedPhraseStore.Source.MANUAL && local[it.phrase]?.source == LearnedPhraseStore.Source.AUTOMATIC
      }
      if (added > 0) changes += "Se agregan $added frases."
      if (upgraded > 0) changes += "$upgraded frases tuyas del respaldo reemplazan a las aprendidas solas con el mismo texto."
      if (keptManual > 0) attention += "Se conservaron $keptManual frases que agregaste en este teléfono con el mismo texto."
      val newPatterns = incoming.patterns.count { p -> current.patterns.none { it.template == p.template } }
      if (newPatterns > 0) changes += "Se agregan $newPatterns estructuras."
      if (current.entries.size + added > LearnedPhraseStore.LIMIT)
        attention += "Se superó el límite de ${LearnedPhraseStore.LIMIT} frases: se descartaron primero las aprendidas solas menos recientes."
    } else {
      changes += "Se restauran ${incoming.entries.size} frases y ${incoming.patterns.size} estructuras."
      val manual = current.entries.count { it.source == LearnedPhraseStore.Source.MANUAL }
      if (current.entries.isNotEmpty()) warnings += "Se reemplazan tus ${current.entries.size} frases actuales ($manual agregadas por vos)."
    }
    return object : PreparedChange {
      override val changes = changes.ifEmpty { listOf("Sin cambios.") }
      override val warnings = warnings
      override val attention = attention
      override suspend fun snapshot(): Restorable { val before = store.backupData(); return Restorable { store.replaceBackupData(before) } }
      override suspend fun commit() = if (mode == RestoreMode.MERGE) store.mergeBackupData(incoming) else store.replaceBackupData(incoming)
    }
  }
}

/** Keys through the existing secure stores. The Gemini key is saved without turning Decisions on by itself. */
class StoreCredentialAccess(context: Context) : CredentialAccess {
  private val groq = GroqKeyStore(context, "groq")
  private val geminiKeys = GroqKeyStore(context, "gemini")
  private val gemini = GeminiSettings(context)
  override fun groqKey() = groq.readValue()
  override fun saveGroqKey(value: String) = groq.save(value)
  override fun clearGroqKey() = groq.clear()
  override fun geminiKey() = gemini.readKey()
  override fun saveGeminiKey(value: String) = geminiKeys.save(value)
  override fun clearGeminiKey() = gemini.clear()
  override fun geminiEnabled() = gemini.enabledFlag()
  override fun setGeminiEnabled(value: Boolean) = gemini.setEnabled(value)
  override fun localChoice() = gemini.hasLocalChoice()
  override fun setLocalChoice(value: Boolean) = gemini.setLocalChoice(value)
}

class RepositoryAssistantAccess(private val repository: AssistantPreferencesRepository) : AssistantAccess {
  override suspend fun values() = repository.backupValues()
  override suspend fun write(set: Map<String, Any>, remove: Set<String>) = repository.restoreBackupValues(set, remove)
}

class RepositoryPlacesAccess(private val repository: SavedPlacesRepository) : PlacesAccess {
  override fun address(alias: String) = repository.address(alias)
  override fun save(alias: String, address: String) = repository.save(alias, address)
  override fun remove(alias: String) = repository.remove(alias)
}

object AndroidBackup {
  const val MARKER = "restore-in-progress.json"

  fun manager(context: Context, phrases: LearnedPhraseStore = LearnedPhraseStore(context)): ConfigurationBackupManager {
    val app = context.applicationContext
    val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
    @Suppress("DEPRECATION")
    val code = info?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong() } ?: 0
    return ConfigurationBackupManager(
      sections = listOf(
        CredentialsSection(StoreCredentialAccess(app)),
        AssistantSection(RepositoryAssistantAccess(AssistantPreferencesRepository(app))),
        PhrasesSection(phrases),
        RoutinesSection(ConfiguredRoutineStore(File(app.noBackupFilesDir, "configured-routines.json"))),
        PlacesSection(RepositoryPlacesAccess(SavedPlacesRepository(app))),
      ),
      appVersionName = info?.versionName ?: "",
      appVersionCode = code,
      marker = FileRestoreMarker(File(app.noBackupFilesDir, MARKER)),
    )
  }

  fun fileName(millis: Long): String = "insto-backup-${BackupFormat.timestamp(millis).take(10)}.insto"

  /**
   * Permissions and system settings Android requires the user to grant by hand on this phone. A backup never grants
   * them; this only lists what the restored configuration needs and is not active yet.
   */
  suspend fun pendingAttention(context: Context, phrases: LearnedPhraseStore): List<String> {
    val app = context.applicationContext
    val prefs = AssistantPreferencesRepository(app).getCurrentPreferences()
    val items = mutableListOf<String>()
    val accessibility = PingAutoSendAccessibilityService.isEnabled(app)
    if (!accessibility && (prefs.floatingPillMode != FloatingPillMode.OFF || prefs.volumeKeyTrigger != VolumeKeyTrigger.DISABLED ||
        prefs.pinnedContact != null)) {
      items += "Activá Accesibilidad de Insto para el auto-envío, el botón flotante y el botón de volumen."
    }
    if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
      items += "Dale permiso de micrófono a Insto la próxima vez que grabes."
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val roles = app.getSystemService(android.app.role.RoleManager::class.java)
      if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true &&
        !roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT))
        items += "Si querés abrir Insto con el gesto del asistente, elegilo como asistente digital en Ajustes."
    }
    val listeners = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners").orEmpty()
    if (!listeners.contains(ComponentName(app, com.example.phone.MediaAccessService::class.java).flattenToString()))
      items += "Para controlar la música de otras apps, activá el acceso a notificaciones de Insto."
    val notifications = app.getSystemService(NotificationManager::class.java)
    if (notifications?.isNotificationPolicyAccessGranted == false)
      items += "Para No molestar, concedé a Insto el acceso a No molestar."
    if (!Settings.System.canWrite(app)) items += "Para el brillo, permití que Insto modifique los ajustes del sistema."
    if (prefs.preferredFlavor != WhatsAppFlavor.AUTO && WhatsAppDispatcher.resolveTargetPackage(app, prefs.preferredFlavor) != prefs.preferredFlavor.packageName)
      items += "${prefs.preferredFlavor.label} no está instalado en este teléfono; se usará la otra versión si está."
    val missingApps = phrases.backupData().entries.mapNotNull { (it.command as? PhoneCommand.OpenApp)?.packageName }
      .distinct().count { runCatching { app.packageManager.getPackageInfo(it, 0) }.isFailure }
    if (missingApps > 0) items += "$missingApps frases abren apps que no están instaladas; se usan cuando las instales."
    return items
  }
}
