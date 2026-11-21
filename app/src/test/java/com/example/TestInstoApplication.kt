package com.example

import kotlinx.coroutines.runBlocking

/**
 * Robolectric uses `Test` + the manifest application automatically. Production code never waits for preferences on
 * the main thread; tests drive the view model synchronously, so each test starts with the cache already loaded, as it
 * is on a phone a moment after the process starts.
 */
class TestInstoApplication : InstoApplication() {
  override fun onCreate() {
    super.onCreate()
    runBlocking { preferencesCache.awaitCurrent() }
  }
}
