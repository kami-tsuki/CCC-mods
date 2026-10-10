package kami.claims.client.compat.xaero

import kami.claims.service.View

interface ClaimData {
    val rev: Int
    val enabled: Boolean
    fun at(dim: String, x: Int, z: Int): View.Entry?
    fun view(dim: String): Map<Long, View.Entry>?
    fun country(e: View.Entry): View.CountryView?
    fun typeName(e: View.Entry): String?
    fun reserved(dim: String, x: Int, z: Int): Boolean
    fun regionHash(dim: String, rx: Int, rz: Int): Int
}

fun chunkKey(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

const val SHOWN = View.SCOPE or View.ALLIANCE or View.MINE or View.OFFER

fun shown(e: View.Entry) = e.flags and SHOWN != 0

fun foreign(e: View.Entry) = e.flags and (View.SCOPE or View.ALLIANCE) == 0

fun argb(rgb: Int, alpha: Int) = (alpha shl 24) or (rgb and 0xFFFFFF)

const val WAR_RED = 0xFF3B3B
const val WAR_ORANGE = 0xFFA000
const val SAFE_GREEN = 0x55FF55
const val GOLD = 0xFFD54F
const val CLAIMABLE_GREEN = 0x5CFF7A
const val FALLBACK = 0x888888

class ClaimStyle(
    val mode: () -> String,
    val relationColor: (Int) -> Int,
    val typeLabel: (String) -> String
) {
    fun base(data: ClaimData, e: View.Entry): Int {
        val c = data.country(e) ?: return FALLBACK
        return if (mode() == "relations") relationColor(c.relation) else c.color
    }
}
