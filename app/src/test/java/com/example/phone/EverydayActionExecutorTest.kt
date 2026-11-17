package com.example.phone

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EverydayActionExecutorTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()
  private fun installHandler(pkg: String, action: String, scheme: String? = null) {
    val info = ActivityInfo().apply {
      name = "$pkg.Handler"; packageName = pkg; exported = true; enabled = true
      applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true }
    }
    val pm = shadowOf(app.packageManager)
    pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = info.applicationInfo })
    pm.addOrUpdateActivity(info)
    pm.addIntentFilterForActivity(ComponentName(pkg, info.name), IntentFilter(action).apply {
      addCategory(Intent.CATEGORY_DEFAULT)
      scheme?.let { addDataScheme(it) }
    })
  }
  @Test fun platformSearchPrefersTheRequestedAppOverABrowser() {
    com.example.testsupport.installNativeHandler(app, Intent.ACTION_VIEW, "https")
    for ((command, pkg, url) in listOf(
      Triple(PhoneCommand.YouTubeSearch("rebord"), "com.google.android.youtube", "https://www.youtube.com/results?search_query=rebord"),
      Triple(PhoneCommand.SpotifySearch("rebord"), "com.spotify.music", "https://open.spotify.com/search/rebord"))) {
      installHandler(pkg, Intent.ACTION_VIEW, "https")
      val result = PhoneActionExecutor(app).execute(command)
      assertEquals(PhoneActionOutcome.HANDOFF, result.outcome)
      val launched = shadowOf(app).nextStartedActivity
      assertEquals(pkg, launched.`package`)
      assertEquals(url, launched.dataString)
      assertNull(shadowOf(app).nextStartedActivity)
    }
  }
  @Test fun playbackIntentGoesToTheNamedAppWithTheOriginalQuery() {
    val action = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
    installHandler("com.spotify.music", action)
    installHandler("com.google.android.youtube", action)
    val command = (PhoneCommandParser().parse("Reproducir Björk en YouTube") as PhoneRoute.Local).command
    val result = PhoneActionExecutor(app).execute(command)
    assertEquals(PhoneActionOutcome.HANDOFF, result.outcome)
    val launched = shadowOf(app).nextStartedActivity
    assertEquals("com.google.android.youtube", launched.`package`)
    assertEquals(action, launched.action)
    assertEquals("Björk", launched.getStringExtra("query"))
    assertNull(shadowOf(app).nextStartedActivity)
  }
  @Test fun missingPlaybackHandlerOpensTheNamedAppsSearchInsteadOfAnotherPlayer() {
    installHandler("other.player", "android.media.action.MEDIA_PLAY_FROM_SEARCH")
    installHandler("com.spotify.music", Intent.ACTION_VIEW, "https")
    val command = (PhoneCommandParser().parse("Reproducir Miles Davis en Spotify") as PhoneRoute.Local).command
    val result = PhoneActionExecutor(app).execute(command)
    assertEquals(PhoneActionOutcome.HANDOFF, result.outcome)
    assertTrue(result.message, result.message.contains("búsqueda"))
    val launched = shadowOf(app).nextStartedActivity
    assertEquals("com.spotify.music", launched.`package`)
    assertEquals("https://open.spotify.com/search/Miles%20Davis", launched.dataString)
    assertNull(shadowOf(app).nextStartedActivity)
  }
  @Test fun spotifyWithoutAnyHandlerKeepsTheRequestLocal() {
    val result = PhoneActionExecutor(app).execute(PhoneCommand.SpotifySearch("rebord"))
    assertEquals(PhoneActionOutcome.REJECTED, result.outcome)
    assertTrue(result.retainRequest)
    assertNull(shadowOf(app).nextStartedActivity)
  }
  @Test fun explicitNumberOpensDialerWithoutContactsPermissionOrAutomaticCall() {
    shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
    installHandler("phone", Intent.ACTION_DIAL, "tel")
    val result = PhoneActionExecutor(app).execute(PhoneCommand.Dial("+54 (11) 5555-0100"))
    assertEquals(PhoneActionOutcome.HANDOFF, result.outcome)
    val launched = shadowOf(app).nextStartedActivity
    assertEquals(Intent.ACTION_DIAL, launched.action)
    assertEquals("tel:+541155550100", launched.dataString)
    assertNull(shadowOf(app).nextStartedActivity)
  }
  @Test fun namedContactRequestsPermissionAndRetainsTheRequest() {
    shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
    val result = PhoneActionExecutor(app).execute(PhoneCommand.Dial("Ana"))
    assertEquals(PhoneActionOutcome.NEEDS_INPUT, result.outcome)
    assertTrue(result.needsContacts)
    assertTrue(result.retainRequest)
    assertNull(shadowOf(app).nextStartedActivity)
  }
  @Test fun missingHandlerCannotBeReportedAsCompleted() {
    val result = PhoneActionExecutor(app).execute(PhoneCommand.Camera())
    assertEquals(PhoneActionOutcome.REJECTED, result.outcome)
    assertTrue(result.retainRequest)
    assertNull(shadowOf(app).nextStartedActivity)
  }
  @Test fun missingHomeAddressIsNotGuessedFromGoogleHistory() {
    app.getSharedPreferences("saved_places", 0).edit().clear().commit()
    val result = PhoneActionExecutor(app).execute(PhoneCommand.Navigate("casa"))
    assertEquals(PhoneActionOutcome.REJECTED, result.outcome)
    assertTrue(result.retainRequest)
    assertNull(shadowOf(app).nextStartedActivity)
    assertEquals(PhoneActionOutcome.COMPLETED, PhoneActionExecutor(app).execute(PhoneCommand.SavePlace("casa", "Calle Mitre 42")).outcome)
    assertEquals("Calle Mitre 42", SavedPlacesRepository(app).address("casa"))
  }
}
