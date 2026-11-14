package com.example.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.assist.ArmedActionMode
import com.example.assist.AutoSendCoordinator
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
  ): DispatchResult {
    val sendIntent =
      buildSendAudioIntent(context, phoneNumber, audioFile, preferredFlavor, mimeType)
        ?: return DispatchResult.Error("Audio file is empty or phone number is invalid.")

    val targetPkg = sendIntent.`package`
    val shouldArm = targetPkg != null && isAutoSendServiceEnabled

    if (shouldArm && targetPkg != null) {
      AutoSendCoordinator.arm(
        targetPackage = targetPkg,
        mode = ArmedActionMode.CLICK_SEND,
        boomerangReturn = boomerangReturn,
      )
    }

    return runCatching {
        if (targetPkg != null) {
          context.startActivity(sendIntent)
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
