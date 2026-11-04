package io.github.localledger.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionUiTest {
    @org.junit.Before fun useAllTimeForHistoricalFixtures() {
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
        compose.runOnIdle { graph.setQuery(io.github.localledger.core.LedgerQuery()) }
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.ledgerGraph
    private val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(1800), "合成咖啡店", "synthetic-repeat-ui", null, "v1", 1793419200000)
    @After fun close() { compose.runOnUiThread { graph.enable(false); graph.close() } }
    private fun ready() = compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }
    private fun seed(block: (LedgerStore) -> Unit) {
        ready(); val context = compose.activity.applicationContext
        runBlocking(Dispatchers.IO) { LedgerStore(context).use(block) }
        compose.runOnIdle { graph.refresh() }; ready()
    }
    @Test fun repeatedPopupHasOnlyAMarkerAndTheNormalSaveCreatesAnotherEntry() {
        seed { store ->
            val account = store.saveAccount("现金", BigInteger.valueOf(10000))
            val category = store.saveCategory( TransactionType.EXPENSE, "餐饮")
            store.saveCandidate(ConfirmedEntry(source, accountId = account, categoryId = category), "request-ui-1")
        }
        compose.runOnIdle { graph.enable(true); graph.present(source) }
        compose.waitUntil(10_000) { graph.pending.value?.preparation != null }
        compose.onNodeWithText("待确认", substring = true).performClick()
        compose.onNodeWithText("重复").assertIsDisplayed()
        compose.onNodeWithText("现金").assertExists(); compose.onNodeWithText("餐饮").assertExists()
        compose.onAllNodesWithText("另记一笔").assertCountEquals(0)
        compose.onAllNodesWithText("修改原账").assertCountEquals(0)
        compose.onAllNodesWithText("确认重复").assertCountEquals(0)
        capture("repeat-popup")
        click("保存")
        compose.waitUntil(10_000) { graph.pending.value == null && graph.snapshot.value.count == 2 }
        assertEquals(BigInteger.valueOf(6400), graph.snapshot.value.catalog.accounts.single().balanceMinor)
    }
    @Test fun bookAndTypeSwitchesPreserveSelectionsWithinTheFormAndAcrossRecreation() {
        var traffic = ""
        seed { store ->
            val cash = store.saveAccount("现金", null)
            val bank = store.saveAccount("工资卡", null)
            val expense = store.saveCategory( TransactionType.EXPENSE, "餐饮")
            val income = store.saveCategory( TransactionType.INCOME, "工资")
            traffic = store.saveCategory(TransactionType.EXPENSE, "交通")
            store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(1), "", 1, categoryId = expense, accountId = cash))
            store.createEntry(EntryDraft(TransactionType.INCOME, Money(1), "", 2, categoryId = income, accountId = bank))
            val book = store.saveBook("旅行")
            val travel = store.saveAccount("旅费", null)
            val sightseeing = store.saveCategory( TransactionType.EXPENSE, "游玩")
            store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(1), "", 3, bookId = book, categoryId = sightseeing, accountId = travel))
        }
        compose.runOnIdle { graph.setQuery(LedgerQuery(bookId = DEFAULT_BOOK)) }; ready()
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNode(hasText("餐饮") and isSelected()).assertExists(); compose.onNodeWithContentDescription("账户").assert(hasText("旅费"))
        click("交通"); click("收入"); compose.onNode(hasText("工资") and isSelected()).assertExists()
        click("支出"); compose.onNode(hasText("交通") and isSelected()).assertExists()
        compose.onNodeWithContentDescription("账户").performClick()
        compose.onNode(hasText("现金") and hasAnyAncestor(isDialog())).performClick()
        compose.activityRule.scenario.recreate(); ready()
        compose.onNode(hasText("交通") and isSelected()).assertExists(); compose.onNodeWithContentDescription("账户").assert(hasText("现金"))
        compose.onNodeWithContentDescription("账本").performClick(); compose.onNode(hasText("旅行") and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText("交通") and isSelected()).assertExists(); compose.onNodeWithContentDescription("账户").assert(hasText("现金"))
        compose.onNodeWithContentDescription("账本").performClick(); compose.onNode(hasText("默认账本") and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText("交通") and isSelected()).assertExists(); compose.onNodeWithContentDescription("账户").assert(hasText("现金"))
        assertEquals(traffic, graph.snapshot.value.catalog.categories.single { it.name == "交通" }.id)
        assertEquals(2, graph.snapshot.value.count)
        capture("remembered-selections")
    }
    @Test fun automaticChoiceLearningHasNoExtraSettingsEntry() {
        seed { it.saveCandidate(ConfirmedEntry(source), "request-ui-1") }
        click("设置")
        compose.onAllNodesWithText("选择记忆").assertCountEquals(0)
        assertEquals(1, graph.snapshot.value.count)
        assertEquals(1, graph.choices.value.size)
        capture("settings-without-selection-memory")
    }
    private fun capture(name: String) {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val manager = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
            val view = ReflectionHelpers.getField<List<android.view.View>>(manager, "mViews").last()
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        }
        File("build/ui-previews", "$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
