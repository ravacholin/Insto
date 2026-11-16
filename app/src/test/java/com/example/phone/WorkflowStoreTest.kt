package com.example.phone
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WorkflowStoreTest {
 @Test fun persistedInFlightStepIsRecoveredAsUncertain() {
  val app=ApplicationProvider.getApplicationContext<Application>()
  val store=WorkflowStore(app);store.write(null)
  try {
   val runner=WorkflowRunner(save=store::write)
   runner.prepare(listOf(PhoneCommand.Timer(60)));runner.confirm();runner.next()
   val recovered=WorkflowRunner(store.read(),store::write)
   assertEquals(StepStatus.UNCERTAIN,recovered.snapshot!!.steps.single().status)
   assertNull(recovered.next())
  }finally {store.write(null)}
 }
}
