package io.github.localledger.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.math.BigInteger
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LedgerFilterUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val graph get()=compose.activity.ledgerGraph
    @After fun close() { compose.runOnUiThread { graph.close() }; RuntimeEnvironment.setFontScale(1f) }
    private fun ready()=compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    private fun click(text:String) { val node=compose.onNodeWithText(text); if(!node.isDisplayed())node.performScrollTo(); node.performClick() }
    private fun fill(label:String,value:String) { val node=compose.onNodeWithText(label); if(!node.isDisplayed())node.performScrollTo(); node.performTextReplacement(value) }
    private fun seed() {
        ready(); val context=compose.activity.applicationContext
        runBlocking(Dispatchers.IO) { LedgerStore(context).use { store ->
            val account=store.saveAccount("合成现金",BigInteger("10000"))
            val day=LedgerQuery().inDateRange(LocalDate.of(2026,2,28),LocalDate.of(2026,2,28)).fromMs!!
            listOf(0L,101L,200L).forEachIndexed { index,amount -> store.createEntry(EntryDraft(TransactionType.EXPENSE,Money(amount),"合成消费$index",day+index,accountId=account)) }
            store.createEntry(EntryDraft(TransactionType.INCOME,Money(150),"合成收入",day+100,accountId=account))
            store.createEntry(EntryDraft(TransactionType.EXPENSE,Money(300),"下个时段",day+86_400_000))
        } }
        compose.runOnIdle { graph.setQuery(LedgerQuery(bookId=DEFAULT_BOOK).inDateRange(LocalDate.of(2026,2,28),LocalDate.of(2026,2,28))) }; ready()
    }
    @Test fun boundsValidateBeforeApplyAndClearingConditionsKeepsBookAndDates() {
        seed(); val original=graph.query.value
        compose.onNodeWithContentDescription("筛选").performClick()
        fill("最低金额","2.01"); fill("最高金额","2.00"); click("应用")
        compose.onNodeWithText("最高金额需不小于最低金额").assertIsDisplayed(); assertEquals(original,graph.query.value)
        fill("最低金额","1.00"); click("支出"); click("应用"); ready()
        assertEquals(2,graph.snapshot.value.count); assertEquals(BigInteger("301"),graph.snapshot.value.expense)
        compose.onNodeWithText("支出 · ¥ 1.00–2.00").assertIsDisplayed(); capture("nightly-filters-active")
        click("统计"); assertEquals(2,graph.snapshot.value.count)
        compose.onNodeWithContentDescription("清除筛选条件").performClick(); ready()
        assertEquals(original,graph.query.value); assertEquals(4,graph.snapshot.value.count)
    }
    @Test fun draftSurvivesRecreationAndResetDoesNotSilentlyApplyOrLoseScope() {
        seed(); val original=graph.query.value
        compose.onNodeWithContentDescription("筛选").performClick(); fill("最低金额","1.01")
        compose.activityRule.scenario.recreate(); ready()
        compose.onNodeWithText("最低金额").assertTextContains("1.01")
        click("重置"); assertEquals(original,graph.query.value)
        click("应用"); ready(); assertEquals(original,graph.query.value)
        compose.onNodeWithContentDescription("下一时段").performClick(); ready()
        compose.onNodeWithText("下个时段").assertIsDisplayed(); assertEquals(1,graph.snapshot.value.count)
        compose.onNodeWithContentDescription("上一时段").performClick(); ready(); assertEquals(original,graph.query.value)
    }
    @Test fun calendarCancelPreservesRangeAndEmptyResultsOfferFilterRecovery() {
        seed(); val original=graph.query.value
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithContentDescription("选择时间范围").performScrollTo().performClick()
        compose.onNode(hasText("取消") and hasAnyAncestor(isDialog())).performClick()
        fill("最低金额","999.00"); click("应用"); ready()
        compose.onNodeWithText("没有符合条件的账单").assertIsDisplayed()
        compose.onAllNodesWithText("自动记账设置").assertCountEquals(0)
        click("清除筛选"); ready(); assertEquals(original,graph.query.value)
    }
    @Test @Config(sdk=[34],qualifiers="w320dp-h740dp-night-mdpi")
    fun largeTypeFiltersKeepErrorsAndApplyReachableWithLongBounds() {
        RuntimeEnvironment.setFontScale(2f); compose.activityRule.scenario.recreate(); seed()
        compose.onNodeWithContentDescription("筛选").performClick()
        fill("最低金额","92233720368547758.07"); fill("最高金额","1.00"); click("应用")
        compose.onNodeWithText("最高金额需不小于最低金额").assertIsDisplayed()
        compose.onNodeWithText("应用").assertIsDisplayed(); capture("nightly-filters-dark-font200")
    }
    @Test fun monthRangeIsDraftAcrossRecreationUntilTheFilterIsApplied() {
        seed();val original=graph.query.value
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithContentDescription("选择时间范围").performScrollTo().performClick()
        click("自定义")
        compose.onNodeWithContentDescription("选择结束月份").performClick();compose.onNodeWithTag("month-number").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(3f)};click("确定")
        compose.activityRule.scenario.recreate();ready()
        assertEquals(original,graph.query.value)
        compose.onNode(hasText("应用") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(original,graph.query.value)
        click("应用");ready()
        assertEquals(LocalDate.of(2026,2,1),graph.query.value.firstDate())
        assertEquals(LocalDate.of(2026,3,31),graph.query.value.lastDate());assertEquals(5,graph.snapshot.value.count)
    }
    private fun capture(name:String) {
        lateinit var bitmap:Bitmap
        compose.runOnIdle {
            val manager=ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"),"getInstance")
            val view=ReflectionHelpers.getField<List<android.view.View>>(manager,"mViews").last()
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        }
        File("build/ui-previews","$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
