package com.example

import android.app.Application
import com.example.phone.TemporaryModeManager

class InstoApplication:Application() {
 override fun onCreate() {
  super.onCreate()
  // A gap between processes may contain manual changes that no observer could see.
  runCatching {TemporaryModeManager(this).loseOwnership()}
 }
}
