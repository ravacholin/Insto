package com.example.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.InstoApplication
import com.example.testsupport.awaitCachedPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantPreferencesCacheTest {
  private val instinct = AssistantPreferences(pinnedContact = PinnedContact("Instinct", "+447700900123"))

  @Test fun currentIsNullUntilTheFirstValueAndAwaitSuspendsInsteadOfBlocking() = runTest {
    val source = MutableStateFlow<AssistantPreferences?>(null)
    val scope = TestScope(StandardTestDispatcher(testScheduler))
    val cache = AssistantPreferencesCache(source.filterNotNull(), scope)
    val waiting = async { cache.awaitCurrent() }
    runCurrent()
    assertNull(cache.current)
    assertFalse(waiting.isCompleted)
    source.value = instinct
    runCurrent()
    assertSame(instinct, waiting.await())
    assertSame(instinct, cache.current)
    scope.cancel()
  }

  @Test fun cachedValueFollowsEveryChangeInTheSource() = runTest {
    val source = MutableStateFlow(AssistantPreferences())
    val scope = TestScope(StandardTestDispatcher(testScheduler))
    val cache = AssistantPreferencesCache(source, scope)
    runCurrent()
    assertNull(cache.current!!.pinnedContact)
    source.value = instinct
    runCurrent()
    assertEquals("+447700900123", cache.current!!.pinnedContact!!.phoneNumber)
    source.value = instinct.copy(preferredFlavor = WhatsAppFlavor.WHATSAPP_BUSINESS)
    runCurrent()
    assertEquals(WhatsAppFlavor.WHATSAPP_BUSINESS, cache.current!!.preferredFlavor)
    assertEquals(WhatsAppFlavor.WHATSAPP_BUSINESS, cache.awaitCurrent().preferredFlavor)
    scope.cancel()
  }

  @Test fun unreadableStoreFallsBackToDefaultsInsteadOfCrashingOrHanging() = runTest {
    val scope = TestScope(StandardTestDispatcher(testScheduler))
    val cache = AssistantPreferencesCache(flow { throw IOException("corrupt") }, scope)
    runCurrent()
    assertEquals(AssistantPreferences(), cache.awaitCurrent())
    scope.cancel()
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistantPreferencesCacheRepositoryTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()

  @Test fun applicationOwnsTheCacheAndItIsLoadedAtStartup() {
    val cache = AssistantPreferencesCache.of(app)
    assertSame((app as InstoApplication).preferencesCache, cache)
    assertTrue(cache.current != null)
  }

  @Test fun repositoryWritesReachTheCacheWithoutReadingDataStoreAgain() {
    val repository = AssistantPreferencesRepository(app)
    runBlocking(Dispatchers.IO) { repository.savePinnedContact("Instinct", "+447700900123") }
    assertEquals("Instinct", awaitCachedPreferences(app) { it.pinnedContact != null }.pinnedContact!!.displayName)
    runBlocking(Dispatchers.IO) { repository.setPreferredFlavor(WhatsAppFlavor.WHATSAPP_BUSINESS) }
    assertEquals(WhatsAppFlavor.WHATSAPP_BUSINESS,
      awaitCachedPreferences(app) { it.preferredFlavor == WhatsAppFlavor.WHATSAPP_BUSINESS }.preferredFlavor)
    runBlocking(Dispatchers.IO) { repository.clearPinnedContact(); repository.setPreferredFlavor(WhatsAppFlavor.AUTO) }
    awaitCachedPreferences(app) { it.pinnedContact == null && it.preferredFlavor == WhatsAppFlavor.AUTO }
  }
}
