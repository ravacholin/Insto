package com.example

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import com.example.assist.AssistActivity
import com.example.context.SharedTextContext
import com.example.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingContextActivityTest {
  @Test fun selectedTextSurvivesRecreationAndRepeatedHotkeyButNewShareReplacesIt() {
    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, "Primero")
    val controller = Robolectric.buildActivity(AssistActivity::class.java, intent).setup()
    fun vm() = ViewModelProvider(controller.get())[MainViewModel::class.java]
    try {
      assertEquals("Primero", vm().floatingAssistant.value.sourceContext?.text)
      controller.recreate()
      assertEquals("Primero", vm().floatingAssistant.value.sourceContext?.text)
      controller.newIntent(Intent(AssistActivity.ACTION_RECORD_AUDIO))
      assertEquals("Primero", vm().floatingAssistant.value.sourceContext?.text)
      controller.newIntent(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Segundo"))
      assertEquals("Segundo", vm().floatingAssistant.value.sourceContext?.text)
      vm().setFloatingContext(null)
      controller.recreate()
      assertNull(vm().floatingAssistant.value.sourceContext)
    } finally { vm().dismissFloatingAssistant(); controller.pause().stop().destroy() }
  }
  @Test fun invalidNewShareClearsOldReferenceAndShowsError() {
    val controller = Robolectric.buildActivity(AssistActivity::class.java,
      Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Anterior")).setup()
    val vm = ViewModelProvider(controller.get())[MainViewModel::class.java]
    try {
      controller.newIntent(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "x".repeat(10001)))
      assertNull(vm.floatingAssistant.value.sourceContext)
      assertNotNull(vm.floatingAssistant.value.error)
    } finally { vm.dismissFloatingAssistant(); controller.pause().stop().destroy() }
  }
}
