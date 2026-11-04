package io.github.localledger.app.update

import android.content.SharedPreferences
import io.github.localledger.core.AppVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class UpdateMessage { AVAILABLE, CURRENT, FAILED }
internal data class UpdateEvent(val id: Long, val message: UpdateMessage)
internal data class UpdateState(val automatic: Boolean = true, val checking: Boolean = false,
                                val releases: List<AppRelease> = emptyList(), val result: UpdateMessage? = null,
                                val event: UpdateEvent? = null)

/** Activity-owned: cancellation follows the ViewModel; checks never run from the accessibility service. */
internal class UpdateController(private val scope: CoroutineScope, private val prefs: SharedPreferences,
                                private val sources: List<ReleaseSource>, private val currentVersion: AppVersion,
                                private val now: () -> Long = System::currentTimeMillis,
                                private val fetch: suspend (ReleaseSource) -> String = ::fetchReleaseJson) {
    private val mutable = MutableStateFlow(UpdateState(automatic = prefs.getBoolean("automatic_updates", true)))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var eventId = 0L

    fun setAutomatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic_updates", enabled).apply()
        mutable.value = mutable.value.copy(automatic = enabled)
    }

    fun check(manual: Boolean = false) {
        if (job?.isActive == true || (!manual && !state.value.automatic)) return
        val time = now()
        val last = prefs.getLong("update_last_attempt", 0)
        if (!manual && last > 0 && time >= last && time - last < 24 * 60 * 60 * 1_000L) return
        prefs.edit().putLong("update_last_attempt", time).apply()
        mutable.value = mutable.value.copy(checking = true, result = null)
        job = scope.launch {
            try {
                val result = checkUpdates(sources, currentVersion, fetch)
                val message = when (result) {
                    is UpdateResult.Available -> UpdateMessage.AVAILABLE
                    UpdateResult.Current -> UpdateMessage.CURRENT
                    UpdateResult.Failed -> UpdateMessage.FAILED
                }
                val releases = when (result) {
                    is UpdateResult.Available -> result.releases
                    UpdateResult.Current -> emptyList()
                    UpdateResult.Failed -> state.value.releases
                }
                // A successful background check with no new version needs no feedback.
                val event = if (manual || message != UpdateMessage.CURRENT) UpdateEvent(++eventId, message) else null
                mutable.value = mutable.value.copy(releases = releases, result = message, event = event)
            } finally { mutable.value = mutable.value.copy(checking = false) }
        }
    }

    fun consumeEvent(id: Long) { if (state.value.event?.id == id) mutable.value = mutable.value.copy(event = null) }
}
