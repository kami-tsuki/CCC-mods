package kami.libs.economy

import kami.libs.LibConfig
import kotlin.math.abs

object Coins {
    private const val SPUR = ''
    private const val BEVEL = ''
    private const val SPROCKET = ''
    private const val COG = ''
    private const val CROWN = ''
    private const val SUN = ''

    private val denominations = listOf(4096L to SUN, 512L to CROWN, 64L to COG, 16L to SPROCKET, 8L to BEVEL, 1L to SPUR)

    val values: Map<String, Int> get() = LibConfig.s.coins

    fun glyph(spurValue: Long): Char = denominations.firstOrNull { spurValue == it.first }?.second ?: SPUR

    class Compact(val amount: String, val glyph: Char) {
        val text get() = "$amount$glyph"
    }

    fun compactParts(spurs: Long): Compact {
        val neg = spurs < 0
        val v = abs(spurs)
        val (value, glyph) = denominations.firstOrNull { v >= it.first } ?: (1L to SPUR)
        val amount = v.toDouble() / value
        val text = if (value == 1L || amount >= 100.0) amount.toLong().toString() else "%.1f".format(amount)
        return Compact((if (neg) "-" else "") + text, glyph)
    }

    fun compactParts(spurs: Int): Compact = compactParts(spurs.toLong())

    fun compact(spurs: Long): String = compactParts(spurs).text
    fun compact(spurs: Int): String = compactParts(spurs.toLong()).text
}
