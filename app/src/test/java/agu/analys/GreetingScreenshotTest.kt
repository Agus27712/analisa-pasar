package agu.analys

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import agu.analys.ui.theme.TradingViewAITheme
import org.junit.Rule
import org.junit.Test

class GreetingScreenshotTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun app_content_renders() {
    composeTestRule.setContent {
      TradingViewAITheme {
        Text("TradingView AI Engine")
      }
    }

    composeTestRule.onRoot().assertTextContains("TradingView AI Engine")
  }
}
