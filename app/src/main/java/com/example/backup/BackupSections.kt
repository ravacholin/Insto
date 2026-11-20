package com.example.backup

import com.example.data.AssistantActivationMode
import com.example.data.FloatingPillMode
import com.example.data.OrbHoldBehavior
import com.example.data.VoiceRecordingMode
import com.example.data.VolumeKeyTrigger
import com.example.data.WhatsAppFlavor
import com.example.phone.ConfiguredRoutine
import com.example.phone.ConfiguredRoutineStore
import com.example.phone.PhoneCommandParser
import com.example.whatsapp.PhoneNumberUtils
import org.json.JSONArray
import org.json.JSONObject

private fun change(changes: List<String>, warnings: List<String>, attention: List<String>,
  snapshot: suspend () -> Restorable, commit: suspend () -> Unit) = object : PreparedChange {
  override val changes = changes.ifEmpty { listOf("Sin cambios.") }
  override val warnings = warnings
  override val attention = attention
  override suspend fun snapshot() = snapshot()
  override suspend fun commit() = commit()
}

// ---------------------------------------------------------------------------------------------------------------
// Credentials
// ---------------------------------------------------------------------------------------------------------------

/** The app's own secure storage of provider keys (Android Keystore through GroqKeyStore and GeminiSettings). */
interface CredentialAccess {
  fun groqKey(): String?
  fun saveGroqKey(value: String)
  fun clearGroqKey()
  fun geminiKey(): String?
  /** Saves the key without changing the Decisions switch. */
  fun saveGeminiKey(value: String)
  /** Removes the key and the switch. */
  fun clearGeminiKey()
  fun geminiEnabled(): Boolean
  fun setGeminiEnabled(value: Boolean)
  fun localChoice(): Boolean
  fun setLocalChoice(value: Boolean)
}

/**
 * API keys travel only inside the encrypted payload: read here through the app's own storage and written back with
 * the same methods, so the destination phone encrypts them with its own Keystore key. A key missing from the backup
 * never deletes the local one; an existing different key is replaced only with explicit consent.
 */
class CredentialsSection(private val access: CredentialAccess) : BackupSection {
  override val category = BackupCategory.CREDENTIALS

  override suspend fun export(): JSONObject? {
    val groq = access.groqKey()
    val gemini = access.geminiKey()
    if (groq == null && gemini == null && !access.localChoice()) return null
    return JSONObject().apply {
      groq?.let { put("groqKey", it) }
      gemini?.let { put("geminiKey", it); put("geminiEnabled", access.geminiEnabled()) }
      put("geminiLocalChoice", access.localChoice())
    }
  }

  override suspend fun describeCurrent(): String? = listOfNotNull(
    "Groq".takeIf { access.groqKey() != null }, "Gemini".takeIf { access.geminiKey() != null },
  ).takeIf { it.isNotEmpty() }?.joinToString(" y ", prefix = "Claves de ")

  private class Keys(val groq: String?, val gemini: String?, val enabled: Boolean?, val localChoice: Boolean?)

  private fun keys(json: JSONObject): Keys {
    fun key(name: String): String? {
      if (!json.has(name)) return null
      val value = json.get(name)
      require(value is String && value.isNotEmpty() && value.length <= 512 && value.none(Char::isWhitespace)) {
        "Una clave de API del archivo no tiene un formato válido."
      }
      return value
    }
    fun flag(name: String): Boolean? = if (!json.has(name)) null else (json.get(name) as? Boolean
      ?: throw IllegalArgumentException("Un ajuste de proveedores no es válido."))
    return Keys(key("groqKey"), key("geminiKey"), flag("geminiEnabled"), flag("geminiLocalChoice"))
  }

  override fun validate(json: JSONObject): CategorySummary {
    val keys = keys(json)
    val parts = listOfNotNull("clave de Groq".takeIf { keys.groq != null }, "clave de Gemini".takeIf { keys.gemini != null })
    val decisions = keys.enabled?.let { if (it) " · Gemini Decisions activado" else " · Gemini Decisions desactivado" }.orEmpty()
    return CategorySummary((if (parts.isEmpty()) "Sin claves" else "Incluye " + parts.joinToString(" y ")) + decisions)
  }

  override suspend fun prepare(json: JSONObject, mode: RestoreMode, options: RestoreOptions): PreparedChange {
    val incoming = keys(json)
    val groqNow = access.groqKey()
    val geminiNow = access.geminiKey()
    val changes = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val attention = mutableListOf<String>()
    fun decide(name: String, now: String?, backup: String?, consent: Boolean): Boolean = when {
      backup == null || backup == now -> false
      now == null -> { changes += "Se restaura la clave de $name."; true }
      mode == RestoreMode.REPLACE || consent -> {
        changes += "Se reemplaza la clave de $name."
        warnings += "La clave de $name actual se reemplaza por la del respaldo."
        true
      }
      else -> { attention += "Se conservó tu clave de $name actual: la del respaldo es distinta."; false }
    }
    val writeGroq = decide("Groq", groqNow, incoming.groq, options.replaceGroqKey)
    val writeGemini = decide("Gemini", geminiNow, incoming.gemini, options.replaceGeminiKey)
    val geminiAfter = if (writeGemini) incoming.gemini else geminiNow
    // The switch follows the backup when the key came from it, or always in replace mode if a key will exist.
    val enabled = incoming.enabled?.takeIf { geminiAfter != null && (writeGemini || mode == RestoreMode.REPLACE) }
    if (enabled != null && enabled != access.geminiEnabled()) changes += if (enabled) "Se activa Gemini Decisions." else "Se desactiva Gemini Decisions."
    val choice = incoming.localChoice?.takeIf { it != access.localChoice() && (it || mode == RestoreMode.REPLACE) }
    return change(changes, warnings, attention,
      snapshot = {
        val groq = access.groqKey(); val gemini = access.geminiKey()
        val flag = access.geminiEnabled(); val local = access.localChoice()
        Restorable {
          if (groq == null) access.clearGroqKey() else access.saveGroqKey(groq)
          if (gemini == null) access.clearGeminiKey() else { access.saveGeminiKey(gemini); access.setGeminiEnabled(flag) }
          access.setLocalChoice(local)
        }
      },
      commit = {
        if (writeGroq) access.saveGroqKey(incoming.groq!!)
        if (writeGemini) access.saveGeminiKey(incoming.gemini!!)
        if (enabled != null) access.setGeminiEnabled(enabled)
        if (choice != null) access.setLocalChoice(choice)
      })
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Assistant preferences
// ---------------------------------------------------------------------------------------------------------------

/** AssistantPreferencesRepository's backup methods, behind an interface for tests. */
interface AssistantAccess {
  suspend fun values(): Map<String, Any>
  suspend fun write(set: Map<String, Any>, remove: Set<String>)
}

/**
 * Assistant settings by stable name. Content: `{"values":{name: value}, "cleared":[names]}`. A name in «cleared» was
 * unset (default) when exported: it is removed only by a replace, after a warning. A name in neither list is unknown
 * and is never touched.
 */
class AssistantSection(private val access: AssistantAccess) : BackupSection {
  override val category = BackupCategory.ASSISTANT

  private sealed interface Type {
    data class Choice(val names: Set<String>) : Type
    data object Flag : Type
    data object Hour : Type
    data object Name : Type
    data object Phone : Type
  }

  private fun choices(values: Array<out Enum<*>>) = Type.Choice(values.map { it.name }.toSet())

  /** Must match AssistantPreferencesRepository.BACKUP_KEYS; a test checks it. */
  private val schema: Map<String, Type> = linkedMapOf(
    "contact_name" to Type.Name, "contact_phone" to Type.Phone,
    "driving_contact_name" to Type.Name, "driving_contact_phone" to Type.Phone,
    "activation_mode" to choices(AssistantActivationMode.entries.toTypedArray()),
    "orb_hold_behavior" to choices(OrbHoldBehavior.entries.toTypedArray()),
    "whatsapp_flavor" to choices(WhatsAppFlavor.entries.toTypedArray()),
    "voice_recording_mode" to choices(VoiceRecordingMode.entries.toTypedArray()),
    "volume_key_trigger" to choices(VolumeKeyTrigger.entries.toTypedArray()),
    "floating_pill_mode" to choices(FloatingPillMode.entries.toTypedArray()),
    "eyes_free_cues" to Type.Flag, "boomerang_return" to Type.Flag, "car_bt_auto_voice" to Type.Flag,
    "night_window_enabled" to Type.Flag, "night_start_hour" to Type.Hour, "night_end_hour" to Type.Hour,
    "groq_vocabulary" to Type.Flag, "groq_pinned_contact_hints" to Type.Flag,
  )
  val names: Set<String> get() = schema.keys

  private val contacts = listOf("contact_name" to "contact_phone", "driving_contact_name" to "driving_contact_phone")
  private val labels = mapOf("contact_phone" to "contacto de Instinct", "driving_contact_phone" to "contacto de conducción")

  override suspend fun export(): JSONObject? {
    val values = access.values().filterKeys { it in schema }
    if (values.isEmpty()) return null
    return JSONObject().put("values", JSONObject(values)).put("cleared", JSONArray(schema.keys.filter { it !in values }))
  }

  override suspend fun describeCurrent(): String? {
    val values = access.values()
    if (values.isEmpty()) return null
    val people = listOfNotNull("contacto de Instinct".takeIf { "contact_phone" in values },
      "contacto de conducción".takeIf { "driving_contact_phone" in values })
    return "${values.size} ajustes" + if (people.isEmpty()) "" else " · " + people.joinToString(" y ")
  }

  private class Parsed(val values: Map<String, Any>, val cleared: Set<String>)

  private fun parse(json: JSONObject): Parsed {
    val rawValues = json.optJSONObject("values") ?: JSONObject()
    val values = linkedMapOf<String, Any>()
    for (name in rawValues.keys()) {
      val type = schema[name] ?: continue // A setting from a newer Insto: skipped, never guessed.
      val value = rawValues.get(name)
      val valid = when (type) {
        is Type.Choice -> value is String && value in type.names
        Type.Flag -> value is Boolean
        Type.Hour -> value is Int && value in 0..23
        Type.Name -> value is String && value.isNotBlank() && value.length <= 100
        Type.Phone -> value is String && value.length <= 40 && PhoneNumberUtils.isValidPhoneNumber(value)
      }
      require(valid) { "El ajuste «$name» del archivo no es válido." }
      values[name] = value
    }
    val clearedJson = json.optJSONArray("cleared") ?: JSONArray()
    val cleared = (0 until clearedJson.length()).map { clearedJson.getString(it) }.filter { it in schema && it !in values }.toSet()
    for ((nameKey, phoneKey) in contacts) {
      require(nameKey !in values || phoneKey in values) { "Un contacto del archivo no tiene número." }
    }
    return Parsed(values, cleared)
  }

  override fun validate(json: JSONObject): CategorySummary {
    val parsed = parse(json)
    val people = contacts.mapNotNull { (_, phone) -> labels[phone]?.takeIf { phone in parsed.values } }
    return CategorySummary("${parsed.values.size} ajustes" + if (people.isEmpty()) "" else " · " + people.joinToString(" y "))
  }

  override suspend fun prepare(json: JSONObject, mode: RestoreMode, options: RestoreOptions): PreparedChange {
    val incoming = parse(json)
    val current = access.values()
    val set = linkedMapOf<String, Any>()
    val remove = mutableSetOf<String>()
    val changes = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val attention = mutableListOf<String>()
    val contactKeys = contacts.flatMap { it.toList() }.toSet()
    for ((nameKey, phoneKey) in contacts) {
      val label = labels.getValue(phoneKey)
      val phone = incoming.values[phoneKey] as String?
      when {
        phone == null && phoneKey in incoming.cleared && mode == RestoreMode.REPLACE && phoneKey in current -> {
          remove += listOf(nameKey, phoneKey); warnings += "Se borra el $label actual."
        }
        phone == null -> Unit
        current[phoneKey] == phone && current[nameKey] == incoming.values[nameKey] -> Unit
        phoneKey !in current || mode == RestoreMode.REPLACE -> {
          set[phoneKey] = phone
          incoming.values[nameKey]?.let { set[nameKey] = it } ?: remove.add(nameKey)
          changes += "Se restaura el $label."
          if (phoneKey in current) warnings += "Se reemplaza el $label actual."
        }
        else -> attention += "Se conservó tu $label actual: el del respaldo es distinto."
      }
    }
    var restored = 0
    var replaced = 0
    for ((name, value) in incoming.values) {
      if (name in contactKeys) continue
      when {
        current[name] == value -> Unit
        name !in current -> { set[name] = value; restored++ }
        mode == RestoreMode.REPLACE -> { set[name] = value; replaced++ }
      }
    }
    if (mode == RestoreMode.REPLACE) {
      val reset = incoming.cleared.filter { it !in contactKeys && it in current }
      remove += reset
      if (reset.isNotEmpty()) warnings += "${reset.size} ajustes vuelven a su valor predeterminado."
    }
    if (restored > 0) changes += "Se restauran $restored ajustes."
    if (replaced > 0) { changes += "Se reemplazan $replaced ajustes."; warnings += "Se sobrescriben $replaced ajustes actuales." }
    return change(changes, warnings, attention,
      snapshot = {
        val before = access.values().filterKeys { it in schema }
        Restorable { access.write(before, schema.keys - before.keys) }
      },
      commit = { if (set.isNotEmpty() || remove.isNotEmpty()) access.write(set, remove) })
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Routines
// ---------------------------------------------------------------------------------------------------------------

/** Configured routines, validated by [ConfiguredRoutineStore.decode]. Restoring only stores them; nothing runs. */
class RoutinesSection(private val store: ConfiguredRoutineStore) : BackupSection {
  override val category = BackupCategory.ROUTINES

  override suspend fun export(): JSONObject? {
    val routines = store.read()
    if (routines.isEmpty()) return null
    return JSONObject().put("routines", JSONArray(ConfiguredRoutineStore.encode(routines)))
  }

  override suspend fun describeCurrent(): String? = runCatching { store.read() }.getOrDefault(emptyList())
    .takeIf { it.isNotEmpty() }?.let { "${it.size} rutinas" }

  private fun parse(json: JSONObject): List<ConfiguredRoutine> {
    val array = json.optJSONArray("routines") ?: JSONArray()
    return try { ConfiguredRoutineStore.decode(array.toString()) }
      catch (error: IllegalArgumentException) { throw IllegalArgumentException(error.message?.takeIf { it.length < 120 } ?: "Hay rutinas no válidas.") }
      catch (error: org.json.JSONException) { throw IllegalArgumentException("Hay rutinas dañadas.") }
  }

  override fun validate(json: JSONObject) = CategorySummary("${parse(json).size} rutinas")

  override suspend fun prepare(json: JSONObject, mode: RestoreMode, options: RestoreOptions): PreparedChange {
    val incoming = parse(json)
    val current = store.read()
    val final: List<ConfiguredRoutine>
    val changes = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val attention = mutableListOf<String>()
    if (mode == RestoreMode.MERGE) {
      val names = current.map { PhoneCommandParser.normalize(it.name) }.toSet()
      val added = incoming.filter { PhoneCommandParser.normalize(it.name) !in names }
      val kept = incoming.size - added.size
      final = current + added
      require(final.size <= 50) { "Al fusionar habría más de 50 rutinas. Elegí reemplazar o borrá alguna." }
      if (added.isNotEmpty()) changes += "Se agregan ${added.size} rutinas."
      if (kept > 0) attention += "$kept rutinas del respaldo tienen el mismo nombre que una tuya; se conservaron las tuyas."
    } else {
      final = incoming
      changes += "Se restauran ${incoming.size} rutinas."
      if (current.isNotEmpty()) warnings += "Se reemplazan tus ${current.size} rutinas actuales."
    }
    ConfiguredRoutineStore.decode(ConfiguredRoutineStore.encode(final)) // The final list must be valid as a whole.
    return change(changes, warnings, attention,
      snapshot = { val before = store.read(); Restorable { store.save(before) } },
      commit = { if (final != current) store.save(final) })
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Saved places
// ---------------------------------------------------------------------------------------------------------------

interface PlacesAccess {
  fun address(alias: String): String?
  fun save(alias: String, address: String)
  fun remove(alias: String)
}

/** «casa» and «trabajo». Content: `{"casa": "…" | null, "trabajo": "…" | null}`; an absent alias is not touched. */
class PlacesSection(private val access: PlacesAccess) : BackupSection {
  override val category = BackupCategory.PLACES
  private val aliases = listOf("casa", "trabajo")

  override suspend fun export(): JSONObject? {
    val values = aliases.associateWith { access.address(it) }
    if (values.values.all { it == null }) return null
    return JSONObject().apply { values.forEach { (alias, address) -> put(alias, address ?: JSONObject.NULL) } }
  }

  override suspend fun describeCurrent(): String? = aliases.filter { access.address(it) != null }
    .takeIf { it.isNotEmpty() }?.joinToString(" y ")

  private fun parse(json: JSONObject): Map<String, String?> = aliases.filter { json.has(it) }.associateWith { alias ->
    if (json.isNull(alias)) null else (json.get(alias) as? String)?.takeIf { it.isNotBlank() && it.length <= 500 }
      ?: throw IllegalArgumentException("La dirección de «$alias» no es válida.")
  }

  override fun validate(json: JSONObject): CategorySummary {
    val present = parse(json).filterValues { it != null }.keys
    return CategorySummary(if (present.isEmpty()) "Sin lugares" else present.joinToString(" y "))
  }

  override suspend fun prepare(json: JSONObject, mode: RestoreMode, options: RestoreOptions): PreparedChange {
    val incoming = parse(json)
    val save = linkedMapOf<String, String>()
    val remove = mutableListOf<String>()
    val changes = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val attention = mutableListOf<String>()
    for ((alias, address) in incoming) {
      val now = access.address(alias)
      when {
        address == null -> if (mode == RestoreMode.REPLACE && now != null) { remove += alias; warnings += "Se borra la dirección de «$alias»." }
        address == now -> Unit
        now == null -> { save[alias] = address; changes += "Se restaura «$alias»." }
        mode == RestoreMode.REPLACE -> { save[alias] = address; changes += "Se reemplaza «$alias»."; warnings += "Se reemplaza la dirección actual de «$alias»." }
        else -> attention += "Se conservó tu dirección de «$alias»: la del respaldo es distinta."
      }
    }
    return change(changes, warnings, attention,
      snapshot = {
        val before = aliases.associateWith { access.address(it) }
        Restorable { before.forEach { (alias, address) -> if (address == null) access.remove(alias) else access.save(alias, address) } }
      },
      commit = { save.forEach { (alias, address) -> access.save(alias, address) }; remove.forEach(access::remove) })
  }
}
