package io.github.localledger.core

import java.math.BigInteger

/** Statement totals are independent of account balance posting. Zero-value entries still count. */
data class CashFlow(
    val expense: BigInteger = BigInteger.ZERO, val income: BigInteger = BigInteger.ZERO,
    val transfer: BigInteger = BigInteger.ZERO, val transferCost: BigInteger = BigInteger.ZERO,
    val expenseCount: Int = 0, val incomeCount: Int = 0, val transferCount: Int = 0,
) {
    val count get() = expenseCount + incomeCount + transferCount
    val net get() = income - expense - transferCost
    val received get() = transfer - transferCost
    companion object {
        fun summarize(entries: Sequence<EntryDraft>): CashFlow {
            var expense = BigInteger.ZERO; var income = BigInteger.ZERO
            var transfer = BigInteger.ZERO; var cost = BigInteger.ZERO
            var expenses = 0; var incomes = 0; var transfers = 0
            entries.forEach { row ->
                val value = BigInteger.valueOf(row.amount.minor)
                when (row.type) {
                    TransactionType.EXPENSE -> { expense += value; expenses++ }
                    TransactionType.INCOME -> { income += value; incomes++ }
                    TransactionType.TRANSFER -> {
                        transfer += value; transfers++
                        cost += BigInteger.valueOf(row.fee.minor) - BigInteger.valueOf(row.discount.minor)
                    }
                }
            }
            return CashFlow(expense, income, transfer, cost, expenses, incomes, transfers)
        }
    }
}
