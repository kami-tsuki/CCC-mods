package kami.claims.client.app

import kami.claims.net.Info

fun runway(treasury: Long, net: Long): Long? = if (net >= 0) null else treasury.coerceAtLeast(0) / -net

class Finance(val tax: Long, val upkeep: Long, val wages: Long, val tributeIn: Long, val tributeOut: Long) {
    val income get() = tax + tributeIn
    val spending get() = upkeep + wages + tributeOut
    val net get() = income - spending

    companion object {
        fun of(info: Info) = Finance(
            info.income, info.upkeep, info.jobs,
            info.provinces.sumOf { Vocabulary.tributeEstimate(it.mode, it.amount, it.income) },
            if (info.parent.isEmpty()) 0L else Vocabulary.tributeEstimate(info.taxMode, info.taxAmount, info.income + info.pendingDeposits)
        )
    }
}
