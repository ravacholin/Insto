package com.example.assist

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.example.data.AssistantPreferences
import com.example.data.AssistantPreferencesRepository
import com.example.whatsapp.DispatchResult
import com.example.whatsapp.WhatsAppDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ShareToInstinctActivity : ComponentActivity() {
  private var handedOff = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (intent.action != Intent.ACTION_SEND || intent.type == null || savedInstanceState?.getBoolean(HANDED_OFF) == true) {
      finish()
      return
    }
    lifecycleScope.launch {
      val result = try {
        val prefs = AssistantPreferencesRepository(this@ShareToInstinctActivity).getCurrentPreferences()
        lifecycle.withResumed {
          handedOff = true
          forwardShare(prefs)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        DispatchResult.Error("No se pudo abrir el contenido compartido.")
      }
      if (result is DispatchResult.Error) Toast.makeText(this@ShareToInstinctActivity, result.message, Toast.LENGTH_LONG).show()
      finish()
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean(HANDED_OFF, handedOff)
  }

  private fun forwardShare(prefs: AssistantPreferences): DispatchResult {
    val contact = prefs.pinnedContact
      ?: return DispatchResult.Error("Configurá el contacto de Instinct en Insto y volvé a compartir.")
    val clipItem = intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)
    val text = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: clipItem?.text)?.toString().orEmpty()
    // Text shares may attach a thumbnail in ClipData for the Android Sharesheet preview.
    val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
      ?: clipItem?.uri?.takeUnless { text.isNotBlank() && intent.type?.startsWith("text/") == true }
    // External apps can start an exported share activity directly. Keep the send action in WhatsApp.
    if (stream == null) {
      return WhatsAppDispatcher.openWhatsAppTextDraft(this, contact.phoneNumber, text, prefs.preferredFlavor)
    }
    val readable = stream.scheme == "content" && checkUriPermission(stream, Process.myPid(), Process.myUid(),
      Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
    if (!readable) return DispatchResult.Error("La app de origen no dio permiso para compartir el archivo.")
    val mimeType = contentResolver.getType(stream) ?: intent.type ?: "application/octet-stream"
    return WhatsAppDispatcher.sendWhatsAppAttachment(
      this, contact.phoneNumber, stream, "Archivo compartido", mimeType, text,
      prefs.preferredFlavor, contact.displayName, returnPackage = null, allowAutoSend = false,
    )
  }

  private companion object {
    const val HANDED_OFF = "handed_off"
  }
}
