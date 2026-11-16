package com.example.testsupport

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.robolectric.Shadows.shadowOf

fun installNativeHandler(app: Application, action: String, scheme: String? = null, mime: String? = null) {
  val activity = ActivityInfo().apply {
    packageName = "native.handler"; name = "native.handler.Main"; enabled = true; exported = true
    applicationInfo = ApplicationInfo().apply { packageName = "native.handler"; enabled = true }
  }
  val pm = shadowOf(app.packageManager)
  pm.installPackage(PackageInfo().apply { packageName = activity.packageName; applicationInfo = activity.applicationInfo; activities = arrayOf(activity) })
  pm.addIntentFilterForActivity(ComponentName(activity.packageName, activity.name), IntentFilter(action).apply {
    addCategory(Intent.CATEGORY_DEFAULT)
    scheme?.let { addDataScheme(it) }
    mime?.let { addDataType(it) }
  })
}
