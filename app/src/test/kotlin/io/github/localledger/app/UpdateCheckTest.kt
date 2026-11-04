package io.github.localledger.app

import io.github.localledger.app.update.*
import io.github.localledger.core.AppVersion
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckTest {
    private val gitee = ReleaseSource(ReleaseHost.GITEE, "synthetic/pure-ledger")
    private val github = ReleaseSource(ReleaseHost.GITHUB, "synthetic/pure-ledger")
    private val sources = listOf(gitee, github)
    private val current = requireNotNull(AppVersion.parse("0.6.1"))
    private fun release(tag: String) = """[{"tag_name":"$tag","draft":false,"prerelease":false}]"""
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("synthetic-updates", 0).apply { edit().clear().commit() }

    @Test fun publicVersionUpdatesRemainAvailableAfterInstallationCodeWasPreserved() = runTest {
        val publicVersion = requireNotNull(AppVersion.parse("0.1.0"))
        val result = checkUpdates(sources, publicVersion) { release("v0.1.1") }
        assertEquals("0.1.1", (result as UpdateResult.Available).releases.first().version.toString())
        assertEquals(UpdateResult.Current, checkUpdates(sources, publicVersion) { release("v0.1.0") })
    }

    @Test fun checksBothSourcesConcurrentlyAndChoosesHighestVersionRegardlessOfArrival() = runTest {
        val calls = mutableListOf<ReleaseSource>()
        val result = checkUpdates(sources, current) {
            calls += it
            if (it == gitee) { delay(200); release("v0.6.10") } else { delay(500); release("v0.6.9") }
        } as UpdateResult.Available
        assertEquals(sources.toSet(), calls.toSet())
        assertEquals(500, testScheduler.currentTime)
        assertEquals("0.6.10", result.releases.single().version.toString())
        assertEquals(gitee, result.releases.single().source)
    }
    @Test fun eitherSourceCanFailWithoutHidingASuccessfulResult() = runTest {
        for (failed in sources) {
            val result = checkUpdates(sources, current) { if (it == failed) error("offline") else release("0.7.0") }
            assertTrue(result is UpdateResult.Available)
            assertEquals(UpdateResult.Current, checkUpdates(sources, current) { if (it == failed) error("offline") else release("0.6.1") })
        }
    }
    @Test fun sameVersionKeepsBothDownloadPagesAndNeverOffersADowngrade() = runTest {
        val same = checkUpdates(sources, current) { release("v0.7.0") } as UpdateResult.Available
        assertEquals(sources, same.releases.map { it.source })
        assertTrue(same.releases.all(::isReleasePage))
        assertEquals(UpdateResult.Current, checkUpdates(sources, requireNotNull(AppVersion.parse("0.7.0"))) { release("v0.6.1") })
    }
    @Test fun malformedResponsesAndBothNetworkFailuresReportFailure() = runTest {
        assertEquals(UpdateResult.Failed, checkUpdates(sources, current) { error("timeout") })
        for (invalid in listOf("<html>blocked</html>", "{}", "[{\"name\":\"missing tag\"}]"))
            assertEquals(UpdateResult.Failed, checkUpdates(sources, current) { invalid })
        assertEquals(UpdateResult.Current, checkUpdates(sources, current) { "[]" })
        assertEquals(UpdateResult.Failed, checkUpdates(emptyList(), current) { "[]" })
    }
    @Test fun ignoresDraftsPrereleasesAndUntrustedRemoteLinks() = runTest {
        val result = checkUpdates(sources, current) { """[
            {"tag_name":"v9.0.0","draft":true},
            {"tag_name":"v8.0.0","prerelease":true},
            {"tag_name":"v7.0.0-beta"},
            {"tag_name":"v0.7.0","html_url":"https://evil.invalid/app.apk"}
        ]""" } as UpdateResult.Available
        assertEquals("0.7.0", result.releases.first().version.toString())
        assertTrue(result.releases.all(::isReleasePage))
        assertFalse(isReleasePage(result.releases.first().copy(page="https://gitee.com@evil.invalid/synthetic/pure-ledger/releases/tag/v0.7.0")))
        assertFalse(isReleasePage(result.releases.first().copy(page="http://gitee.com/synthetic/pure-ledger/releases/tag/v0.7.0")))
    }
    @Test fun backgroundChecksThrottleAcrossRecreationWhileManualChecksBypassIt() = runTest {
        val prefs = prefs(); var time = 100_000L; var calls = 0
        val fetch: suspend (ReleaseSource) -> String = { calls++; release("v0.6.1") }
        val controller = UpdateController(this, prefs, sources, current, { time }, fetch)
        controller.check(); controller.check(); advanceUntilIdle()
        assertEquals(2, calls); assertNull(controller.state.value.event)
        controller.check(); advanceUntilIdle(); assertEquals(2, calls)
        val recreated = UpdateController(this, prefs, sources, current, { time }, fetch)
        recreated.check(); advanceUntilIdle(); assertEquals(2, calls)
        recreated.check(manual = true); advanceUntilIdle(); assertEquals(4, calls)
        assertEquals(UpdateMessage.CURRENT, recreated.state.value.event!!.message)
        recreated.consumeEvent(recreated.state.value.event!!.id)
        assertNull(recreated.state.value.event)
        time += 86_400_000L; recreated.check(); advanceUntilIdle(); assertEquals(6, calls)
        time = 1; recreated.check(); advanceUntilIdle(); assertEquals(8, calls)
    }
    @Test fun disabledAutomaticChecksPersistAndManualChecksRemainAvailable() = runTest {
        val prefs = prefs(); var calls = 0
        val controller = UpdateController(this, prefs, sources, current, fetch = { calls++; release("v0.7.0") })
        controller.setAutomatic(false); controller.check(); advanceUntilIdle()
        assertEquals(0, calls)
        assertFalse(UpdateController(this, prefs, sources, current).state.value.automatic)
        controller.check(manual = true); advanceUntilIdle()
        assertEquals(2, calls); assertEquals(UpdateMessage.AVAILABLE, controller.state.value.event!!.message)
    }
    @Test fun cancellationDoesNotBecomeAFailureNoticeOrLeaveLoadingStuck() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = UpdateController(scope, prefs(), sources, current, fetch = { awaitCancellation() })
        controller.check(); runCurrent(); assertTrue(controller.state.value.checking)
        scope.cancel(); advanceUntilIdle()
        assertFalse(controller.state.value.checking); assertNull(controller.state.value.event)
    }
    @Test fun allSourcesFailProducesOneNoticeAndOneSuccessRemainsQuietWhenCurrent() = runTest {
        val failed = UpdateController(this, prefs(), sources, current, fetch = { error("offline") })
        failed.check(); advanceUntilIdle()
        assertEquals(UpdateMessage.FAILED, failed.state.value.event!!.message)
        val partial = UpdateController(this, prefs(), sources, current, fetch = { if (it == gitee) error("offline") else release("0.6.1") })
        partial.check(); advanceUntilIdle()
        assertNull(partial.state.value.event)
        assertEquals(UpdateMessage.CURRENT, partial.state.value.result)
    }

    @Test fun oneSourceTimeoutDoesNotCancelTheOtherSource() = runTest {
        val result = checkUpdates(sources, current) { if (it == gitee) awaitCancellation() else release("v0.7.0") }
        assertEquals(github, (result as UpdateResult.Available).releases.single().source)
        assertEquals(12_000, testScheduler.currentTime)
    }

    @Test fun transportBoundsResponsesRejectsRedirectsAndClosesConnections() = runTest {
        class Connection(private val status: Int, private val bytes: ByteArray) : HttpURLConnection(URL(github.endpoint)) {
            var disconnected = false
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected = true }
            override fun getResponseCode() = status
            override fun getContentLengthLong() = bytes.size.toLong()
            override fun getInputStream() = ByteArrayInputStream(bytes)
        }
        val valid = Connection(200, "[]".toByteArray())
        assertEquals("[]", fetchReleaseJsonUsing(github) { valid })
        assertFalse(valid.instanceFollowRedirects)
        assertEquals(5_000, valid.connectTimeout); assertEquals(5_000, valid.readTimeout)
        assertTrue(valid.disconnected)
        for (connection in listOf(Connection(302, ByteArray(0)), Connection(403, ByteArray(0)), Connection(200, ByteArray(1_048_577)))) {
            try { fetchReleaseJsonUsing(github) { connection }; fail("Expected rejected response") }
            catch (_: IllegalArgumentException) { assertTrue(connection.disconnected) }
        }
    }
}
