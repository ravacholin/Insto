package com.example.testsupport

import android.content.Context
import com.example.data.AssistantPreferences
import com.example.data.AssistantPreferencesCache
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Waits until the process-wide cache reflects a write the test just made through the repository. The cache follows
 * DataStore through a flow, so a value written a moment ago reaches it asynchronously.
 */
fun awaitCachedPreferences(context: Context, matches: (AssistantPreferences) -> Boolean): AssistantPreferences =
  runBlocking {
    withTimeout(5_000) { AssistantPreferencesCache.of(context).preferences.filterNotNull().first(matches) }
  }
