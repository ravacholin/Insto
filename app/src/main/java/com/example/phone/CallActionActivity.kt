package com.example.phone

import android.Manifest
import android.content.Intent
import android.net.Uri

class CallActionActivity : LocalActionActivity() {
  override val permissions=listOf(Manifest.permission.CALL_PHONE)
  override fun begin() {
    val number=PhoneNumbers.canonical(command.argument!!) ?: error("Número inválido")
    confirm("Iniciar una llamada a $number.") {handoff(Intent(Intent.ACTION_CALL,Uri.fromParts("tel",number,null)))}
  }
}
