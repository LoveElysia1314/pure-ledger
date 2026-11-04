package io.github.localledger.core

import kotlin.test.*

class PageRepeatGuardTest {
    private val window = PageRepeatGuard.Window("com.tencent.mm", 10)
    private fun key(no: String = "synthetic-1", time: Long = 1000) =
        PageRepeatGuard.Record.of(window.app, no, 1, "合成商户", 1234L, time)
    private fun ready() = PageRepeatGuard().also { it.observe(window) }
    private fun show(g: PageRepeatGuard, k: PageRepeatGuard.Record = key()) = g.claim(g.ticket()!!, k, false, true)

    @Test fun repeatedEventsOnOnePageDoNotReopenDismissedPopup() {
        val g = ready(); assertTrue(show(g))
        repeat(20) { g.observe(window); assertFalse(show(g)) }
    }
    @Test fun leaveAndReenterSameRecordImmediatelyAllowsItAgain() {
        val g = ready(); assertTrue(show(g)); assertTrue(g.leaveIfUnchanged(g.ticket()!!))
        g.observe(window); assertTrue(show(g))
    }
    @Test fun anotherWindowAndReturningDoesNotNeedATimeout() {
        val g = ready(); assertTrue(show(g)); g.observe(window.copy(id = 11)); g.observe(window)
        assertTrue(show(g))
    }
    @Test fun similarTransactionsWithDifferentNumbersRemainSeparate() {
        val g = ready(); assertTrue(show(g, key("n1"))); assertTrue(show(g, key("n2")))
        assertTrue(show(g, key("n1")))
    }
    @Test fun fallbackNeverDropsTimeWithinOneMinute() {
        val g = ready(); assertTrue(show(g, key("", 1000))); assertTrue(show(g, key("", 2000)))
        assertFalse(show(g, key("", 2000)))
    }
    @Test fun knownNumberIgnoresIncidentalDescriptionChanges() {
        assertEquals(key(), PageRepeatGuard.Record.of(window.app, "synthetic-1", 2, "页面描述更新", 9900L, 6000))
    }
    @Test fun savedRecordRespectsExistingRepeatPopupOption() {
        val g = ready(); assertFalse(g.claim(g.ticket()!!, key(), true, false))
        g.leave(); g.observe(window); assertTrue(g.claim(g.ticket()!!, key(), true, true))
    }
    @Test fun delayedResultFromPreviousPageCannotShow() {
        val g = ready(); val stale = g.ticket()!!; g.observe(window.copy(id = 11))
        assertFalse(g.claim(stale, key(), false, true)); assertTrue(show(g, key("new")))
    }
    @Test fun laterPageEventCancelsProvisionalExit() {
        val g = ready(); assertTrue(show(g)); val partial = g.ticket()!!; g.observe(window)
        assertFalse(g.leaveIfUnchanged(partial)); assertFalse(show(g))
    }
    @Test fun contentChangesInSameWindowInvalidateDelayedResultAndFreshObservationWorks() {
        val g = ready(); val pending = g.ticket()!!; g.observe(window)
        assertFalse(g.claim(pending, key(), false, true))
        assertTrue(show(g))
    }
    @Test fun failedOverlayCanRetryWithoutWaiting() {
        val g = ready(); assertTrue(show(g)); g.failedDisplay(); assertTrue(show(g))
    }
    @Test fun serviceUnbindInvalidatesPendingAndFutureResults() {
        val g = ready(); val pending = g.ticket()!!; g.stop(); g.observe(window)
        assertFalse(g.claim(pending, key(), false, true)); assertNull(g.ticket())
    }
    @Test fun otherApplicationsNeverShareTransactionIdentity() {
        val g = ready(); assertFalse(show(g, key().copy(app = "other.app")))
    }
}
