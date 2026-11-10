package kami.economy.villager

class Leg<T>(val stack: T, val count: Int)

sealed class Mapped<out T> {
    class Order<T>(val side: Side, val goods: Leg<T>, val emeralds: Int, val extra: Leg<T>?) : Mapped<T>()
    object Barter : Mapped<Nothing>()
}

object Offers {
    fun <T> map(a: Leg<T>, b: Leg<T>?, result: Leg<T>, emeralds: (Leg<T>) -> Int?): Mapped<T> {
        val ea = emeralds(a)
        val eb = b?.let(emeralds)
        val er = emeralds(result)
        return when {
            er != null -> if (ea == null && b == null) Mapped.Order(Side.BUY, a, er, null) else Mapped.Barter
            ea != null && eb != null -> Mapped.Order(Side.SELL, result, ea + eb, null)
            ea != null -> Mapped.Order(Side.SELL, result, ea, b)
            eb != null -> Mapped.Order(Side.SELL, result, eb, a)
            else -> Mapped.Barter
        }
    }
}
