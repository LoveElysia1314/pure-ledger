package io.github.localledger.app

internal enum class NoticeKind { SUCCESS, INFO, WARNING, ERROR }
internal data class UiNotice(val message: String, val kind: NoticeKind = NoticeKind.INFO, val id: Long = sequence.incrementAndGet()) {
    private companion object { val sequence = java.util.concurrent.atomic.AtomicLong() }
}
