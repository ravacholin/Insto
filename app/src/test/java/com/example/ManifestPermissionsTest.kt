package com.example

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.20.0: every permission «Funcionar con el teléfono bloqueado» offers must be declared, or Android shows its switch
 * in Settings disabled and the person cannot grant it (it happened with «Alarmas y recordatorios»).
 */
class ManifestPermissionsTest {
  private val manifest: String = generateSequence(File("").absoluteFile) { it.parentFile }
    .map { listOf(File(it, "app/src/main/AndroidManifest.xml"), File(it, "src/main/AndroidManifest.xml")) }
    .flatten().first { it.isFile }.readText()

  @Test fun `the permissions the readiness screen offers are declared`() {
    for (permission in listOf("SCHEDULE_EXACT_ALARM", "POST_NOTIFICATIONS", "ACCESS_NOTIFICATION_POLICY", "CAMERA"))
      assertTrue(permission, manifest.contains("android:name=\"android.permission.$permission\""))
  }
}
