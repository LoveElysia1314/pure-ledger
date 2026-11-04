package io.github.localledger.app

import android.app.Application
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=EnglishTestApplication::class, qualifiers="en-rUS-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val graph get()=compose.activity.ledgerGraph
    private fun ready()=compose.waitUntil(15_000) { !graph.loading.value && !graph.busy.value }
    private fun click(text:String) { val node=compose.onNodeWithText(text);if(!node.isDisplayed())node.performScrollTo();node.performClick() }
    @After fun close() { compose.runOnIdle { graph.enable(false);graph.close() } }
    @Test fun englishNavigationAndTheFourLanguagePickerAreAvailable() {
        ready();click("Settings")
        for (label in listOf("Automatic entries","Ledger","Account","Category","Appearance","Language","Import ledger","Export data","About")) compose.onNodeWithText(label).assertExists()
        click("Language")
        for(label in listOf("System default","简体中文","繁體中文","English","日本語"))
            compose.onNode(hasText(label) and hasAnyAncestor(isDialog()) and hasClickAction()).assertExists()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        click("Cancel")
    }
    @Test fun localizedLabelsDoNotResetAnUnsavedAmountOrNoteAndSavingRemainsExact() {
        ready();compose.onNodeWithContentDescription("Add entry").performClick()
        "12.34".forEach { compose.onNodeWithContentDescription("Enter $it").performClick() }
        compose.onNodeWithContentDescription("Note").performTextReplacement("早餐 note")
        val app=compose.activity.application
        compose.runOnIdle { AppStrings.initialize(app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })) }
        compose.onNodeWithText("保存").assertIsDisplayed();compose.onNodeWithText("12.34").assertIsDisplayed()
        assertEquals(0,graph.snapshot.value.count)
        compose.runOnIdle { AppStrings.initialize(app) }
        click("Save");ready();compose.waitUntil(10_000){graph.snapshot.value.count==1}
        assertEquals(1234L,graph.snapshot.value.rows.single().amount.minor)
        assertEquals("早餐 note",graph.snapshot.value.rows.single().note)
    }
}
