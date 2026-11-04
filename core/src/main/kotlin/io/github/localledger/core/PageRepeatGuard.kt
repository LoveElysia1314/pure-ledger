package io.github.localledger.core

/** One visible page session, rather than a timed ban on a transaction. No Android APIs. */
class PageRepeatGuard {
    data class Window(val app: String, val id: Int)
    data class Ticket(val generation: Long, val observation: Long, val window: Window)
    data class Record(val app: String, val number: String?, val type: Int = 0,
                      val target: String = "", val amountMinor: Long = 0, val time: Long = 0) {
        companion object {
            fun of(app: String, number: String, type: Int, target: String, amountMinor: Long, time: Long): Record =
                if (number.isNotBlank()) Record(app, number)
                else Record(app, null, type, target, amountMinor, time)
        }
    }

    private var window: Window? = null
    private var generation = 0L
    private var observation = 0L
    private var handled: Record? = null
    private var active = true

    fun observe(next: Window): Boolean {
        if (!active) return false
        val changed = window != next
        if (changed) { generation++; handled = null; window = next }
        observation++
        return changed
    }

    fun ticket(): Ticket? = if (active) window?.let { Ticket(generation, observation, it) } else null
    fun current(ticket: Ticket): Boolean = active && ticket.generation == generation && ticket.window == window
    fun unchanged(ticket: Ticket): Boolean = current(ticket) && ticket.observation == observation

    fun claim(ticket: Ticket, record: Record, alreadySaved: Boolean, repeatSaved: Boolean): Boolean {
        if (!unchanged(ticket) || record.app != ticket.window.app || handled == record) return false
        handled = record
        return !alreadySaved || repeatSaved
    }

    /** A later candidate/event invalidates an earlier provisional page-exit observation. */
    fun leaveIfUnchanged(ticket: Ticket): Boolean {
        if (!unchanged(ticket)) return false
        generation++; observation++; handled = null
        return true
    }

    fun failedDisplay() { handled = null }
    fun leave() { generation++; observation++; window = null; handled = null }
    fun stop() { leave(); active = false }
}
