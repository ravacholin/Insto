package com.example

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.example.assist.AutoSendCoordinator
import com.example.assist.AutoSendHealth
import com.example.assist.AutoSendMetrics
import com.example.assist.AutoSendMetricsStore
import com.example.data.AssistantPreferencesRepository
import com.example.testsupport.ConnectedAccessibility
import com.example.ui.MainViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The panel's indicator and the manual path. Neither arms automatic sending. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoSendHealthPanelTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()

  @Before fun configure() {
    AutoSendCoordinator.disarm()
    AutoSendMetricsStore(app).clear()
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+447700900123") }
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"; versionName = "2.26.20"; longVersionCode = 262000
      applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
  }

  @After fun clear() { AutoSendMetricsStore(app).clear() }

  private fun writeMetrics(outcome: AutoSendMetrics.Outcome) {
    val snapshot = AutoSendMetrics.record(AutoSendMetrics.Snapshot(), AutoSendMetrics.Flavor.WHATSAPP, 262000,
      AutoSendMetrics.Kind.TEXT, "send.id.send", outcome, 1)
    File(app.noBackupFilesDir, AutoSendMetricsStore.FILE_NAME).writeText(AutoSendMetrics.encode(snapshot))
  }

  @Test fun disabledReviewAndReadyFollowTheEvidence() {
    val vm = MainViewModel(app)
    try {
      vm.prepareFloatingAssistant()
      assertEquals(AutoSendHealth.State.DISABLED, vm.floatingAssistant.value.autoSendHealth?.state)
      val disconnect = ConnectedAccessibility.start(app)
      try {
        vm.prepareFloatingAssistant()
        assertEquals("Enabled is not a guarantee", AutoSendHealth.State.NEEDS_REVIEW, vm.floatingAssistant.value.autoSendHealth?.state)
        writeMetrics(AutoSendMetrics.Outcome.CONFIRMED)
        vm.prepareFloatingAssistant()
        assertEquals(AutoSendHealth.State.READY, vm.floatingAssistant.value.autoSendHealth?.state)
      } finally { disconnect() }
    } finally { vm.dismissFloatingAssistant() }
  }

  @Test fun manualPathOpensTheDraftWithoutArming() {
    val disconnect = ConnectedAccessibility.start(app)
    val vm = MainViewModel(app)
    try {
      writeMetrics(AutoSendMetrics.Outcome.NOT_FOUND)
      vm.prepareFloatingAssistant()
      vm.enterFloatingTextMode()
      vm.updateFloatingText("Hola")
      var opened = false
      vm.openFloatingInstinctManually { opened = true }
      assertTrue(opened)
      val intent = shadowOf(app).nextStartedActivity
      assertEquals("Hola", intent.data?.getQueryParameter("text"))
      assertFalse("Manual path never arms", AutoSendCoordinator.isArmedFor("com.whatsapp"))
    } finally { vm.dismissFloatingAssistant(); disconnect() }
  }

  @Test fun afterAnUncertainSendItOnlyOpensTheChatToCheck() {
    val disconnect = ConnectedAccessibility.start(app)
    val vm = MainViewModel(app)
    try {
      writeMetrics(AutoSendMetrics.Outcome.UNCERTAIN)
      vm.prepareFloatingAssistant()
      assertTrue(vm.floatingAssistant.value.autoSendHealth!!.lastUncertain)
      vm.enterFloatingTextMode()
      vm.updateFloatingText("Hola")
      vm.openFloatingInstinctManually {}
      val intent = shadowOf(app).nextStartedActivity
      assertNull("The text is not prepared again", intent.data?.getQueryParameter("text"))
      assertEquals("Hola", vm.floatingAssistant.value.text)
      assertFalse(AutoSendCoordinator.isArmedFor("com.whatsapp"))
    } finally { vm.dismissFloatingAssistant(); disconnect() }
  }
}
