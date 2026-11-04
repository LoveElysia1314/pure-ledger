package io.github.localledger.app

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.performClick

/** Switch from the initially focused note to the amount's own keypad. */
internal fun AndroidComposeTestRule<*,MainActivity>.showAmountKeypad(english: Boolean=false) {
    onNodeWithText(if(english)"Amount (CNY)" else "金额（元）").performClick()
    waitUntil(10_000){activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true &&
        onNodeWithContentDescription(if(english)"Amount keypad" else "金额键盘").isDisplayed()}
    android.os.SystemClock.sleep(500)
    waitUntil(10_000){activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true &&
        onNodeWithContentDescription(if(english)"Amount keypad" else "金额键盘").isDisplayed()}
}
