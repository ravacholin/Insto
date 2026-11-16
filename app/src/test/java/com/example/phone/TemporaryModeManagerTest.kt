package com.example.phone
import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class TemporaryModeManagerTest {
 @Test fun deniedNotificationPermissionDoesNotApplyTemporaryMode() {
  val app=ApplicationProvider.getApplicationContext<Application>()
  shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
  val manager=TemporaryModeManager(app)
  assertNull(manager.read())
  val result=manager.execute(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME,60,20),true)
  assertTrue(result.needsNotifications)
  assertNull(manager.read())
 }
}
