package agu.analys

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class ExampleRobolectricTest {
  @Test
  fun `read string from context`() {
    val context: Context = RuntimeEnvironment.getApplication()
    val appName = context.getString(R.string.app_name)
    assertEquals("TradingView AI", appName)
  }
}
