package com.example.backup

import com.example.phone.AndroidShortcutEngine
import com.example.phone.decodeShortcutPreferences
import com.example.phone.encodeShortcutPreferences
import org.json.JSONObject

internal class AppShortcutBackupSection(private val engine: AndroidShortcutEngine) : BackupSection {
  override val category = BackupCategory.SHORTCUTS

  override suspend fun export(): JSONObject? = engine.backupPreferences().takeIf { it.isNotEmpty() }
    ?.let(::encodeShortcutPreferences)

  override suspend fun describeCurrent(): String? = export()?.let { validate(it).description }

  override fun validate(json: JSONObject) = CategorySummary(decodeShortcutPreferences(json).size.toString() + " accesos directos con preferencias")

  override suspend fun prepare(json: JSONObject, mode: RestoreMode, options: RestoreOptions): PreparedChange {
    val values = decodeShortcutPreferences(json)
    val merge = mode == RestoreMode.MERGE
    val count = values.size
    return object : PreparedChange {
      override val changes = listOf("Se restauran alias, favoritos y habilitación de $count accesos directos.")
      override val warnings = if (merge) emptyList() else listOf("Se reemplazan las preferencias de accesos directos de este teléfono.")
      override val attention = listOf("Los accesos directos restaurados se vuelven a buscar en Android. Las autorizaciones de Instinct se dan en Configuración.")
      override suspend fun snapshot() = Restorable(engine.backupRollback())
      override suspend fun commit() = engine.restorePreferences(values, merge)
    }
  }
}
