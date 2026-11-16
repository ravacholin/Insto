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
  private fun installDialer() {
    val info = ActivityInfo().apply {
      name = "phone.Dialer"; packageName = "phone"; exported = true; enabled = true
      applicationInfo = ApplicationInfo().apply { packageName = "phone"; enabled = true }
    }
    val pm = shadowOf(app.packageManager)
    pm.installPackage(PackageInfo().apply { packageName = "phone"; applicationInfo = info.applicationInfo; activities = arrayOf(info) })
    pm.addIntentFilterForActivity(ComponentName("phone", info.name), IntentFilter(Intent.ACTION_DIAL).apply {
      addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("tel")
    })
  }
  @Test fun explicitNumberOpensDialerWithoutContactsPermissionOrAutomaticCall() {
    shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
    installDialer()
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
