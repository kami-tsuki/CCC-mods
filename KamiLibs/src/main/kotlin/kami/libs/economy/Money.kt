package kami.libs.economy

object Money {
    fun spurs(amount: Long): Int? = amount.takeIf { it in 1..Int.MAX_VALUE }?.toInt()

    fun canDebit(balance: Long, amount: Long): Boolean = amount in 1..balance

    fun canCredit(balance: Long, amount: Long): Boolean = amount >= 0 && balance + amount <= Int.MAX_VALUE
}
