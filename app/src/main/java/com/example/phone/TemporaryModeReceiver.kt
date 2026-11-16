package com.example.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TemporaryModeReceiver : BroadcastReceiver() {
 override fun onReceive(context:Context,intent:Intent) {
  runCatching {
   val manager=TemporaryModeManager(context)
   if(intent.action==Intent.ACTION_BOOT_COMPLETED) manager.reconcile(boot=true)
   else manager.reconcile()
  }
 }
}
