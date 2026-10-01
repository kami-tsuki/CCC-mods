package kami.libs.geology

import net.minecraft.server.level.ServerLevel

class DepositInfo(val ore: String, val siteId: String, val tier: Int, val grade: Double)

fun interface GeologyProvider {
    fun probe(level: ServerLevel, x: Int, z: Int, y0: Int, y1: Int, callback: (List<String>) -> Unit)
    fun deposits(level: ServerLevel, x: Int, z: Int): List<DepositInfo> = emptyList()
}

object GeologyApi {
    private var provider: GeologyProvider? = null

    fun register(p: GeologyProvider) {
        provider = p
    }

    val present get() = provider != null

    val probe: GeologyProvider? get() = provider

    fun deposits(level: ServerLevel, x: Int, z: Int): List<DepositInfo> = provider?.deposits(level, x, z).orEmpty()
}
