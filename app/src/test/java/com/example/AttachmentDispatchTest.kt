package com.example

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.WhatsAppFlavor
import com.example.whatsapp.DispatchResult
import com.example.whatsapp.WhatsAppDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentDispatchTest {
  @Test fun `attachment and caption share one targeted intent with read permission`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"
      applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    val uri = Uri.parse("content://documents/report.pdf")
    val result = WhatsAppDispatcher.sendWhatsAppAttachment(app, "+447700900123", uri,
      "report.pdf", "application/pdf", "Hola Instinct", WhatsAppFlavor.WHATSAPP, "Instinct", null)
    assertFalse(result is DispatchResult.Error)
    val intent = shadowOf(app).nextStartedActivity
    assertEquals(Intent.ACTION_SEND, intent.action)
    assertEquals("com.whatsapp", intent.`package`)
    assertEquals("447700900123@s.whatsapp.net", intent.getStringExtra("jid"))
    assertEquals("Hola Instinct", intent.getStringExtra(Intent.EXTRA_TEXT))
    assertEquals("application/pdf", intent.type)
    assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
    assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
  }

  @Test fun `invalid recipient never opens a share screen`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val result = WhatsAppDispatcher.sendWhatsAppAttachment(app, "", Uri.parse("content://documents/image"),
      "photo.jpg", "image/jpeg", "Hola", WhatsAppFlavor.AUTO, "Instinct", null)
    assertTrue(result is DispatchResult.Error)
    assertNull(shadowOf(app).nextStartedActivity)
  }
}
