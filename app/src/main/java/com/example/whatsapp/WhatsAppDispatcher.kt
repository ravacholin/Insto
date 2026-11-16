package com.example.whatsapp

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.assist.ArmedActionMode
import com.example.assist.AutoSendCoordinator
import com.example.assist.PingAutoSendAccessibilityService
import com.example.data.WhatsAppFlavor
import java.io.File

sealed class DispatchResult {
  data class OpenedChat(val usedPackage: String?, val usedFallbackBrowser: Boolean) :
    DispatchResult()

  data class NativePttArmed(val usedPackage: String) : DispatchResult()

  data class AudioDispatched(
    val usedPackage: String?,
    val autoSendArmed: Boolean,
    val usedChooserFallback: Boolean,
  ) : DispatchResult()

  data class TextDispatched(val autoSendArmed: Boolean) : DispatchResult()

  data class Error(val message: String) : DispatchResult()
}

data class InstalledWhatsAppInfo(
  val isWhatsAppInstalled: Boolean,
  val isBusinessInstalled: Boolean,
) {
  val hasAnyWhatsApp: Boolean
    get() = isWhatsAppInstalled || isBusinessInstalled
}

object WhatsAppDispatcher {

  const val PACKAGE_WHATSAPP = "com.whatsapp"
  const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

  private fun scheduleManualSendHint(context: Context, pkg: String) {
    val request = AutoSendCoordinator.requestId
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      if (AutoSendCoordinator.requestId == request && AutoSendCoordinator.isArmedFor(pkg)) {
        AutoSendCoordinator.disarm()
        android.widget.Toast.makeText(context,
          AutoSendCoordinator.textWaitReason + " No se ha confirmado el envío.",
          android.widget.Toast.LENGTH_LONG).show()
      }
    }, 9_000L)
  }

  fun detectInstalledFlavors(context: Context): InstalledWhatsAppInfo {
    val pm = context.packageManager
    return InstalledWhatsAppInfo(
      isWhatsAppInstalled = isPackageInstalled(pm, PACKAGE_WHATSAPP),
      isBusinessInstalled = isPackageInstalled(pm, PACKAGE_WHATSAPP_BUSINESS),
    )
  }

  fun resolveTargetPackage(context: Context, preferredFlavor: WhatsAppFlavor): String? {
    val info = detectInstalledFlavors(context)
    return when (preferredFlavor) {
      WhatsAppFlavor.WHATSAPP ->
        when {
          info.isWhatsAppInstalled -> PACKAGE_WHATSAPP
          info.isBusinessInstalled -> PACKAGE_WHATSAPP_BUSINESS
          else -> null
        }
      WhatsAppFlavor.WHATSAPP_BUSINESS ->
        when {
          info.isBusinessInstalled -> PACKAGE_WHATSAPP_BUSINESS
          info.isWhatsAppInstalled -> PACKAGE_WHATSAPP
          else -> null
        }
      WhatsAppFlavor.AUTO ->
        when {
          info.isWhatsAppInstalled -> PACKAGE_WHATSAPP
          info.isBusinessInstalled -> PACKAGE_WHATSAPP_BUSINESS
          else -> null
        }
    }
  }

  fun buildOpenChatIntent(
    context: Context,
    phoneNumber: String,
    preferredFlavor: WhatsAppFlavor,
  ): Intent? {
    val targetPkg = resolveTargetPackage(context, preferredFlavor)
    val uriString =
      if (targetPkg != null) {
        PhoneNumberUtils.toWhatsAppDirectSchemeUrl(phoneNumber)
      } else {
        PhoneNumberUtils.toWhatsAppChatUrl(phoneNumber)
      } ?: return null

    return Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
      if (targetPkg != null) {
        setPackage(targetPkg)
      }
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
  }

  fun buildSendTextIntent(
    context: Context,
    phoneNumber: String,
    text: String,
    preferredFlavor: WhatsAppFlavor,
  ): Intent? {
    if (!PhoneNumberUtils.isValidPhoneNumber(phoneNumber) || text.isBlank()) return null
    val uri = Uri.Builder().scheme("whatsapp").authority("send")
      .appendQueryParameter("phone", PhoneNumberUtils.normalizeDigits(phoneNumber))
      .appendQueryParameter("text", text).build()
    return Intent(Intent.ACTION_VIEW, uri).apply {
      resolveTargetPackage(context, preferredFlavor)?.let { setPackage(it) }
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
  }

  fun sendWhatsAppText(
    context: Context,
    phoneNumber: String,
    text: String,
    preferredFlavor: WhatsAppFlavor,
    isAutoSendServiceEnabled: Boolean,
    recipientName: String,
    returnPackage: String? = null,
  ): DispatchResult {
    val intent = buildSendTextIntent(context, phoneNumber, text, preferredFlavor)
      ?: return DispatchResult.Error("El mensaje está vacío o el número no es válido.")
    val pkg = intent.`package`
      ?: return DispatchResult.Error("Instalá WhatsApp para enviar el mensaje. El borrador se conserva.")
    if (!isAutoSendServiceEnabled || !PingAutoSendAccessibilityService.isConnected()) {
      return DispatchResult.Error(
        if (!isAutoSendServiceEnabled) "Activá Accesibilidad de PingAssist para enviar con un solo toque. El texto se conserva."
        else "Android indica que Accesibilidad está habilitada, pero el servicio de PingAssist no está conectado. Desactivalo y activalo una vez en Ajustes. El texto se conserva.")
    }
    AutoSendCoordinator.arm(pkg, boomerangReturn = true, recipientName = recipientName,
      recipientPhone = phoneNumber, expectedText = text, returnPackage = returnPackage)
    val request = AutoSendCoordinator.requestId
    return runCatching {
      context.startActivity(intent)
      AutoSendCoordinator.markTextLaunchCommitted(request)
      // Start polling ourselves. No dependency on WhatsApp Activity/class/text-change events.
      PingAutoSendAccessibilityService.requestTextSend(pkg, request)
      scheduleManualSendHint(context, pkg)
      DispatchResult.TextDispatched(true)
    }.getOrElse {
      AutoSendCoordinator.disarm()
      DispatchResult.Error(it.localizedMessage ?: "No se pudo abrir WhatsApp. El borrador se conserva.")
    }
  }

  fun buildSendAudioIntent(
    context: Context,
    phoneNumber: String,
    audioFile: File,
    preferredFlavor: WhatsAppFlavor,
    mimeType: String = "audio/mp4",
  ): Intent? {
    val jid = PhoneNumberUtils.toWhatsAppJid(phoneNumber) ?: return null
    if (!audioFile.exists() || audioFile.length() == 0L) return null

    val contentUri =
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", audioFile)
    val targetPkg = resolveTargetPackage(context, preferredFlavor)

    return Intent(Intent.ACTION_SEND).apply {
      type = mimeType
      putExtra(Intent.EXTRA_STREAM, contentUri)
      clipData = ClipData.newUri(context.contentResolver, audioFile.name, contentUri)
      putExtra("jid", jid)
      if (targetPkg != null) {
        setPackage(targetPkg)
      }
      addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
          Intent.FLAG_ACTIVITY_NEW_TASK or
          Intent.FLAG_ACTIVITY_CLEAR_TOP
      )
    }
  }

  fun openWhatsAppChat(
    context: Context,
    phoneNumber: String,
    preferredFlavor: WhatsAppFlavor,
  ): DispatchResult {
    val intent =
      buildOpenChatIntent(context, phoneNumber, preferredFlavor)
        ?: return DispatchResult.Error("Invalid phone number. Include country code digits.")

    val targetPkg = intent.`package`
    return runCatching {
        context.startActivity(intent)
        DispatchResult.OpenedChat(usedPackage = targetPkg, usedFallbackBrowser = targetPkg == null)
      }
      .getOrElse { firstError ->
        val webUrl = PhoneNumberUtils.toWhatsAppChatUrl(phoneNumber)
        if (webUrl != null) {
          val fallback =
            Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
              if (targetPkg != null) setPackage(targetPkg)
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
          runCatching {
              context.startActivity(fallback)
              DispatchResult.OpenedChat(usedPackage = targetPkg, usedFallbackBrowser = targetPkg == null)
            }
            .getOrElse {
              val browserFallback =
                Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
              runCatching {
                  context.startActivity(browserFallback)
                  DispatchResult.OpenedChat(usedPackage = null, usedFallbackBrowser = true)
                }
                .getOrElse {
                  DispatchResult.Error(firstError.localizedMessage ?: "Unable to open WhatsApp chat.")
                }
            }
        } else {
          DispatchResult.Error(firstError.localizedMessage ?: "No app available to open chat.")
        }
      }
  }

  /**
   * Opens WhatsApp straight to the target contact and arms PingAutoSendAccessibilityService to
   * press-and-lock WhatsApp's native voice-note mic button for genuine PTT recording.
   */
  fun launchNativeWhatsAppPtt(
    context: Context,
    phoneNumber: String,
    preferredFlavor: WhatsAppFlavor,
    boomerangReturn: Boolean,
  ): DispatchResult {
    val intent =
      buildOpenChatIntent(context, phoneNumber, preferredFlavor)
        ?: return DispatchResult.Error("Invalid phone number.")
    val targetPkg =
      intent.`package`
        ?: return DispatchResult.Error("WhatsApp is not installed for native PTT.")

    AutoSendCoordinator.arm(
      targetPackage = targetPkg,
      mode = ArmedActionMode.NATIVE_PTT_LOCK,
      boomerangReturn = boomerangReturn,
    )

    return runCatching {
        context.startActivity(intent)
        DispatchResult.NativePttArmed(targetPkg)
      }
      .getOrElse { err ->
        AutoSendCoordinator.disarm()
        DispatchResult.Error(err.localizedMessage ?: "Failed to launch native WhatsApp PTT.")
      }
  }

  fun sendWhatsAppAudio(
    context: Context,
    phoneNumber: String,
    audioFile: File,
    preferredFlavor: WhatsAppFlavor,
    isAutoSendServiceEnabled: Boolean,
    boomerangReturn: Boolean = false,
    mimeType: String = "audio/mp4",
    recipientName: String? = null,
    returnPackage: String? = null,
  ): DispatchResult {
    val sendIntent =
      buildSendAudioIntent(context, phoneNumber, audioFile, preferredFlavor, mimeType)
        ?: return DispatchResult.Error("Audio file is empty or phone number is invalid.")

    val targetPkg = sendIntent.`package`
    val shouldArm = targetPkg != null && isAutoSendServiceEnabled && PingAutoSendAccessibilityService.isConnected()
    if (recipientName != null && !shouldArm) {
      return DispatchResult.Error("Activá y conectá Accesibilidad de PingAssist para enviar el audio automáticamente. La grabación se conserva.")
    }

    if (shouldArm && targetPkg != null) {
      AutoSendCoordinator.arm(
        targetPackage = targetPkg,
        mode = ArmedActionMode.CLICK_SEND,
        boomerangReturn = boomerangReturn,
        recipientName = recipientName,
        recipientPhone = if (recipientName != null) phoneNumber else null,
        expectedAudioFileName = if (recipientName != null) audioFile.name else null,
        returnPackage = returnPackage,
      )
      scheduleManualSendHint(context, targetPkg)
    }

    val request = AutoSendCoordinator.requestId
    return runCatching {
        if (targetPkg != null) {
          if (shouldArm && recipientName != null) PingAutoSendAccessibilityService.prepareShareSend(targetPkg, request)
          context.startActivity(sendIntent)
          if (shouldArm && recipientName != null) {
            AutoSendCoordinator.markShareLaunchCommitted(request)
            PingAutoSendAccessibilityService.requestShareSend(targetPkg, request)
          }
          DispatchResult.AudioDispatched(
            usedPackage = targetPkg,
            autoSendArmed = shouldArm,
            usedChooserFallback = false,
          )
        } else {
          val chooser =
            Intent.createChooser(sendIntent, "Send voice note").apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
          context.startActivity(chooser)
          DispatchResult.AudioDispatched(
            usedPackage = null,
            autoSendArmed = false,
            usedChooserFallback = true,
          )
        }
      }
      .getOrElse { err ->
        AutoSendCoordinator.disarm()
        DispatchResult.Error(err.localizedMessage ?: "Failed to launch WhatsApp audio share.")
      }
  }

  fun sendWhatsAppAttachment(
    context: Context,
    phoneNumber: String,
    uri: Uri,
    name: String,
    mimeType: String,
    text: String,
    preferredFlavor: WhatsAppFlavor,
    recipientName: String,
    returnPackage: String?,
  ): DispatchResult {
    val jid = PhoneNumberUtils.toWhatsAppJid(phoneNumber)
      ?: return DispatchResult.Error("El número de contacto no es válido.")
    val pkg = resolveTargetPackage(context, preferredFlavor)
      ?: return DispatchResult.Error("Instalá WhatsApp para enviar. El borrador se conserva.")
    if (uri.scheme != "content") return DispatchResult.Error("Elegí un archivo válido.")
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = mimeType
      setPackage(pkg)
      putExtra("jid", jid)
      putExtra(Intent.EXTRA_STREAM, uri)
      if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
      clipData = ClipData.newUri(context.contentResolver, name, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    val autoSend = PingAutoSendAccessibilityService.isEnabled(context) &&
      PingAutoSendAccessibilityService.isConnected()
    if (autoSend) {
      AutoSendCoordinator.arm(pkg, boomerangReturn = true, recipientName = recipientName,
        recipientPhone = phoneNumber, expectedAudioFileName = name, returnPackage = returnPackage,
        expectedAttachmentCaption = text.takeIf { it.isNotBlank() })
    }
    val request = AutoSendCoordinator.requestId
    return runCatching {
      if (autoSend) PingAutoSendAccessibilityService.prepareShareSend(pkg, request)
      context.startActivity(intent)
      if (autoSend) {
        AutoSendCoordinator.markShareLaunchCommitted(request)
        PingAutoSendAccessibilityService.requestShareSend(pkg, request)
        scheduleManualSendHint(context, pkg)
      }
      DispatchResult.AudioDispatched(pkg, autoSend, false)
    }.getOrElse {
      if (autoSend) AutoSendCoordinator.disarm()
      DispatchResult.Error("No se pudo compartir el archivo. El borrador se conserva.")
    }
  }

  private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
          @Suppress("DEPRECATION") pm.getPackageInfo(packageName, 0)
        }
        true
      }
      .getOrDefault(false)
  }
}
