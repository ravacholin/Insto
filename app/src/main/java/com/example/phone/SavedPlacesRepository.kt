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
}
