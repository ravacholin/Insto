package com.example

import android.app.Application
import com.example.data.AssistantPreferencesCache
import com.example.phone.TemporaryModeManager

open class InstoApplication:Application() {
 /** Loaded once per process and kept current; the main thread reads it without waiting for DataStore. */
 val preferencesCache: AssistantPreferencesCache by lazy { AssistantPreferencesCache.create(this) }

 override fun onCreate() {
  super.onCreate()
  preferencesCache // Starts loading now, before any screen, tile or widget asks for it.
  // A gap between processes may contain manual changes that no observer could see: a value that differs from the one
  // Insto applied is one, and is kept; an unchanged value is still Insto's to restore (1.20.0).
  runCatching {TemporaryModeManager(this).markUnobserved()}
  // The first start after an update publishes the catalog if its hash changed (1.18.0); the job decides, off this thread.
  runCatching {com.example.phone.InstinctCatalogPublisher.changed(this)}
 }
}
