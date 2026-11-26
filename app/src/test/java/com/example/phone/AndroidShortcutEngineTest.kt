package com.example.phone

import android.app.Activity
import android.app.Application
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.shadows.ShadowRoleManager
import org.robolectric.shadows.ShadowUserManager.UserState
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], shadows = [RecordingLauncherApps::class])
class AndroidShortcutEngineTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private val launcher get() = Shadow.extract<RecordingLauncherApps>(app.getSystemService(LauncherApps::class.java))

  @Test fun permissionIsReconciledAndRevocationPreventsDispatch() = runBlocking {
    val engine = AndroidShortcutEngine.of(app)
    engine.refresh()
    assertEquals(ShortcutAccess.DENIED, engine.state.value.access)
    assertNull(launcher.queryFlags)
    installShortcut()
    engine.refresh()
    assertEquals(ShortcutAccess.GRANTED, engine.state.value.access)
    assertFalse("Android shortcut query or profile unavailable", engine.state.value.partial)
    val entry = engine.state.value.entries.single()
    assertTrue(entry.available)
    assertEquals("Nuevo documento", entry.name)
    val flags = checkNotNull(launcher.queryFlags)
    assertTrue(flags and LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC != 0)
    assertTrue(flags and LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST != 0)
    assertTrue(flags and LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER != 0)

    val command = withContext(Dispatchers.IO) { PhoneActionExecutor(app).execute(entry.command()).confirmation!! }
    launcher.setHasShortcutHostPermission(false)
    assertEquals(PhoneActionOutcome.REJECTED, withContext(Dispatchers.IO) {
      PhoneActionExecutor(app).execute(command, confirmed = true)
    }.outcome)
    assertNull(launcher.launch)
  }

  @Test fun aReviewedShortcutNeedsForegroundAndOnlyReportsHandoff() = runBlocking {
    installShortcut()
    val engine = AndroidShortcutEngine.of(app)
    engine.refresh()
    assertEquals(ShortcutAccess.GRANTED, engine.state.value.access)
    assertFalse("Android shortcut query or profile unavailable", engine.state.value.partial)
    val entry = engine.state.value.entries.single()
    val executor = PhoneActionExecutor(app)
    val preview = withContext(Dispatchers.IO) { executor.execute(entry.command(), confirmed = true) }
    val command = checkNotNull(preview.confirmation)
    assertEquals(PhoneActionOutcome.NEEDS_INPUT, preview.outcome)
    assertNull(launcher.launch)
    assertEquals(PhoneActionOutcome.REJECTED, withContext(Dispatchers.IO) { executor.execute(command, confirmed = true) }.outcome)

    val controller = Robolectric.buildActivity(ShortcutTestActivity::class.java).setup()
    val activity = controller.get()
    engine.visible(activity)
    try {
      val result = withContext(Dispatchers.IO) { executor.execute(command, confirmed = true) }
      assertEquals(PhoneActionOutcome.HANDOFF, result.outcome)
      assertFalse(result.verifiedLocally)
      assertEquals(Triple(app.packageName, "new-doc", Process.myUserHandle()), launcher.launch)
      launcher.refuseLaunch = true
      assertEquals(PhoneActionOutcome.REJECTED, withContext(Dispatchers.IO) { executor.execute(command, confirmed = true) }.outcome)
      assertNull(shadowOf(app).nextStartedActivity)
    } finally {
      engine.hidden(activity)
      controller.pause().stop().destroy()
    }
  }

  @Test fun disablingShortcutRevokesRemoteAuthorizationAndPreventsDispatch() = runBlocking {
    installShortcut()
    val engine = AndroidShortcutEngine.of(app)
    engine.refresh()
    assertEquals(ShortcutAccess.GRANTED, engine.state.value.access)
    assertFalse("Android shortcut query or profile unavailable", engine.state.value.partial)
    val entry = engine.state.value.entries.single()
    engine.setGrant(entry, true)
    val token = engine.remoteTargets().single()
    engine.setPreferences(entry) { it.copy(enabled = false) }
    assertNull(engine.remoteTarget(token))
    assertTrue(engine.remoteTargets().isEmpty())
    assertEquals(PhoneActionOutcome.REJECTED, withContext(Dispatchers.IO) {
      engine.execute(token, confirmed = true, remote = true)
    }.outcome)
    assertNull(launcher.launch)
  }

  @Test fun packageCallbackReconcilesOnlyThatPackageAndRetiresItsGrant() = runBlocking {
    installShortcut()
    val engine = AndroidShortcutEngine.of(app)
    engine.refresh()
    assertEquals(ShortcutAccess.GRANTED, engine.state.value.access)
    assertFalse("Android shortcut query or profile unavailable", engine.state.value.partial)
    val entry = engine.state.value.entries.single()
    engine.setGrant(entry, true)
    launcher.removed = true
    launcher.notifyPackageRemoved(app.packageName)
    shadowOf(Looper.getMainLooper()).idle()
    withTimeout(5_000) { engine.state.first { it.entries.none(AppShortcut::available) } }
    assertEquals(app.packageName, launcher.queryPackage)
    assertTrue(engine.remoteTargets().isEmpty())
  }

  private fun installShortcut() {
    val user = Process.myUserHandle()
    ShadowRoleManager.addRoleHolder(RoleManager.ROLE_ASSISTANT, app.packageName, user)
    shadowOf(app.getSystemService(UserManager::class.java)).apply {
      setUserUnlocked(true)
      setUserState(user, UserState.STATE_RUNNING_UNLOCKED)
      setSerialNumberForUser(user, 0L)
    }
    launcher.setHasShortcutHostPermission(true)
    launcher.addApplicationInfo(Process.myUserHandle(), app.packageName, app.applicationInfo)
    launcher.addDynamicShortcut(ShortcutInfo.Builder(app, "new-doc").setShortLabel("Nuevo documento").build())
  }
}

class ShortcutTestActivity : Activity() {
  override fun hasWindowFocus(): Boolean = true
}

@Implements(LauncherApps::class)
class RecordingLauncherApps : ShadowLauncherApps() {
  var queryFlags: Int? = null
  var queryPackage: String? = null
  var removed = false
  var launch: Triple<String, String, UserHandle>? = null
  var refuseLaunch: Boolean = false

  @Implementation
  override fun getShortcuts(query: LauncherApps.ShortcutQuery, user: UserHandle): MutableList<ShortcutInfo> {
    queryFlags = ReflectionHelpers.getField(query, "mQueryFlags")
    queryPackage = ReflectionHelpers.getField(query, "mPackage")
    if (removed) return mutableListOf()
    return checkNotNull(super.getShortcuts(query, user))
  }

  @Implementation
  override fun startShortcut(packageName: String, shortcutId: String, sourceBounds: Rect?,
    startActivityOptions: Bundle?, user: UserHandle) {
    if (refuseLaunch) throw ActivityNotFoundException()
    launch = Triple(packageName, shortcutId, user)
  }
}
