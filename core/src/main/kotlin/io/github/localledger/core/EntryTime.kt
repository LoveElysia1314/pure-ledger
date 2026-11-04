package io.github.localledger.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** DatePicker dates are UTC calendar dates, while the displayed clock belongs to the user zone. */
object EntryTime {
    fun change(originalMs: Long, dateUtcMs: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val original = Instant.ofEpochMilli(originalMs).atZone(zone)
        val date = Instant.ofEpochMilli(dateUtcMs).atZone(ZoneOffset.UTC).toLocalDate()
        val local = LocalDateTime.of(date, original.toLocalTime().withHour(hour).withMinute(minute))
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "所选时间在当前时区不存在，请调整时间" }
        val offset = original.offset.takeIf { it in offsets } ?: offsets.first()
        return local.toInstant(offset).toEpochMilli()
    }
}
