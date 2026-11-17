package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.example.testsupport.resetApplicationViewModelFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityViewModelFactoryTest {
  @Test fun resettingTheSharedFactoryDropsThePreviousApplication() {
    resetApplicationViewModelFactory()
    try {
      val current = ApplicationProvider.getApplicationContext<Application>()
      val previous = Application()
      ViewModelProvider.AndroidViewModelFactory.getInstance(previous)
      val stale = ViewModelProvider.AndroidViewModelFactory.getInstance(current)
        .create(ApplicationIdentityViewModel::class.java)
      assertSame("Reproduce AndroidX retaining the first Application", previous, stale.getApplication<Application>())

      resetApplicationViewModelFactory()
      val fresh = ViewModelProvider.AndroidViewModelFactory.getInstance(current)
        .create(ApplicationIdentityViewModel::class.java)
      assertSame(current, fresh.getApplication<Application>())
    } finally { resetApplicationViewModelFactory() }
  }
}

class ApplicationIdentityViewModel(application: Application) : AndroidViewModel(application)
