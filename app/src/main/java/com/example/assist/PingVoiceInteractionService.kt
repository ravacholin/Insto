package com.example.assist

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class PingVoiceInteractionService : VoiceInteractionService()

class PingVoiceInteractionSessionService : VoiceInteractionSessionService() {
  override fun onNewSession(args: Bundle?): VoiceInteractionSession {
    return PingVoiceInteractionSession(this)
  }
}

class PingVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    val intent =
      Intent(context, AssistActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(AssistActivity.EXTRA_FROM_SYSTEM_ASSIST, true)
      }
    context.startActivity(intent)
    finish()
  }
}

object AssistantRoleHelper {

  fun isDefaultAssistant(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val roleManager = context.getSystemService(RoleManager::class.java)
      if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
        return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
      }
    }
    val current =
      Settings.Secure.getString(context.contentResolver, "assistant") ?: return false
    val parsed = ComponentName.unflattenFromString(current)
    return parsed?.packageName == context.packageName
  }

  fun buildSetDefaultAssistantIntent(context: Context): Intent {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val roleManager = context.getSystemService(RoleManager::class.java)
      if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
      }
    }
    return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
  }
}
