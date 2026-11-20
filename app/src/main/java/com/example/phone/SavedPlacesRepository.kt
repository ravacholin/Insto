package com.example.phone

import android.content.Context

class SavedPlacesRepository(context: Context) {
  private val prefs = context.getSharedPreferences("saved_places", Context.MODE_PRIVATE)
  private val supportedAliases = listOf("casa", "trabajo")
  fun aliases(): List<String> = supportedAliases.filter { prefs.contains(it) }
  fun address(alias: String): String? = prefs.getString(alias, null)
  fun save(alias: String, address: String) {
    require(alias in supportedAliases && address.isNotBlank() && address.length <= 500)
    check(prefs.edit().putString(alias, address).commit()) { "No se pudo guardar la dirección" }
  }
  /** Removes one saved address. Used only by a confirmed «replace» restore. */
  fun remove(alias: String) {
    require(alias in supportedAliases)
    check(prefs.edit().remove(alias).commit()) { "No se pudo borrar la dirección" }
  }
  companion object {
    val ALIASES = listOf("casa", "trabajo")
    fun valid(alias: String, address: String): Boolean = alias in ALIASES && address.isNotBlank() && address.length <= 500
  }
}
