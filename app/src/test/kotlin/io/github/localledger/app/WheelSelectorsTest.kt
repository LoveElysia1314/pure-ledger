package io.github.localledger.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.localledger.app.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h800dp-xxhdpi")
class WheelSelectorsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @After fun close(){compose.runOnUiThread{compose.activity.ledgerGraph.close()}}
    private fun choose(tag:String,value:Int)=compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress){it(value.toFloat())}

    @Test fun leapDayAndMonthEndClampWithoutWritingUntilDone() {
        val original=LocalDateTime.of(2024,2,29,8,9,17,123000000).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var saved:Long?=null
        compose.runOnIdle{compose.activity.setContent{LedgerTheme{EntryTimeEditor(original,{saved=it},{})}}}
        choose("date-year",2023)
        compose.onNodeWithTag("date-day").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"28"))
        choose("date-month",4);choose("date-day",31)
        assertNull(saved)
        compose.onNodeWithText("完成").performClick()
        val result=Instant.ofEpochMilli(saved!!).atZone(ZoneId.systemDefault())
        assertEquals(LocalDate.of(2023,4,30),result.toLocalDate())
        assertEquals(8,result.hour);assertEquals(9,result.minute);assertEquals(17,result.second);assertEquals(123000000,result.nano)
    }

    @Test fun monthSelectionCommitsOnlyOnConfirmationAndCancelKeepsCaller() {
        var shown by mutableStateOf(true)
        var result=YearMonth.of(2026,10)
        compose.runOnIdle{compose.activity.setContent{LedgerTheme{if(shown)MonthOnlyPicker(result,{result=it;shown=false},{shown=false})}}}
        choose("month-number",3)
        compose.onNodeWithText("取消").performClick()
        assertEquals(YearMonth.of(2026,10),result)
        compose.runOnIdle{shown=true}
        choose("month-year",2025);choose("month-number",12)
        compose.onNodeWithText("确定").performClick()
        assertEquals(YearMonth.of(2025,12),result)
    }
}
