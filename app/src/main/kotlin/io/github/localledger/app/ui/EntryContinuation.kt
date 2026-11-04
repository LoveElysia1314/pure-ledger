package io.github.localledger.app.ui

import androidx.compose.runtime.saveable.listSaver
import io.github.localledger.core.*

/** Only created after a successful write. A new form keeps choices, never the previous amount. */
internal fun EntryDraft.continuation() = copy(amount = Money(1), merchant = "", note = "", fee = Money(0), discount = Money(0))

internal val EntryContinuationSaver = listSaver<EntryDraft?, Any>(
    save = { draft -> if (draft == null) emptyList() else listOf(draft.type.name, draft.occurredAtMs,
        draft.bookId, draft.categoryId.orEmpty(), draft.accountId.orEmpty(), draft.toAccountId.orEmpty(), draft.affectsBalance) },
    restore = { values -> if (values.isEmpty()) null else EntryDraft(TransactionType.valueOf(values[0] as String), Money(1), "",
        values[1] as Long, bookId = values[2] as String, categoryId = (values[3] as String).ifEmpty { null },
        accountId = (values[4] as String).ifEmpty { null }, toAccountId = (values[5] as String).ifEmpty { null }, affectsBalance = values[6] as Boolean) },
)
