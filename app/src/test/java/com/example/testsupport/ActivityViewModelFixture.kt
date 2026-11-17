package com.example.testsupport

import androidx.lifecycle.ViewModelProvider

/** AndroidX retains its first Application; Robolectric creates a fresh one for every test. */
fun resetApplicationViewModelFactory() {
  ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply {
    isAccessible = true
    set(null, null)
  }
}
