package com.example.testsupport

import android.app.Application
import android.content.Intent
import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.robolectric.Shadows.shadowOf

/** Models only Clock's external Android boundary, not its stopwatch internals. */
fun installGoogleClock(
  app: Application,
  actions: List<String> = emptyList(),
  exported: Boolean = true,
  permission: String? = null,
) {
  val appInfo = ApplicationInfo().apply {
    packageName = "com.google.android.deskclock"
    enabled = true
  }
  val handler = ActivityInfo().apply {
    packageName = appInfo.packageName
    name = "com.android.deskclock.HandleDeskClockApiCalls"
    applicationInfo = appInfo
    enabled = true
    this.exported = exported
    this.permission = permission
  }
  val launcher = ActivityInfo().apply {
    packageName = appInfo.packageName
    name = "com.android.deskclock.DeskClock"
    applicationInfo = appInfo
    enabled = true
    this.exported = true
  }
  val pm = shadowOf(app.packageManager)
  pm.installPackage(PackageInfo().apply {
    packageName = appInfo.packageName
    applicationInfo = appInfo
  })
  // Register components through the shadow API so their intent-filter entries exist too.
  // Passing activities to installPackage alone only populates PackageInfo in Robolectric.
  pm.addOrUpdateActivity(handler)
  pm.addOrUpdateActivity(launcher)
  val handlerName = ComponentName(handler.packageName, handler.name)
  pm.clearIntentFilterForActivity(handlerName)
  actions.forEach { action ->
    pm.addIntentFilterForActivity(handlerName, IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
  }
  val launcherName = ComponentName(launcher.packageName, launcher.name)
  pm.clearIntentFilterForActivity(launcherName)
  pm.addIntentFilterForActivity(launcherName, IntentFilter(Intent.ACTION_MAIN).apply {
    addCategory(Intent.CATEGORY_LAUNCHER)
    addCategory(Intent.CATEGORY_DEFAULT)
  })
}
