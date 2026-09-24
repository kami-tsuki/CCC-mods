package kami.libs.geology

import net.minecraft.server.level.ServerLevel

fun interface GeologyProvider {
    fun probe(level: ServerLevel, x: Int, z: Int, y0: Int, y1: Int, callback: (List<String>) -> Unit)
}

object GeologyApi {
    private var provider: GeologyProvider? = null

    fun register(p: GeologyProvider) {
        provider = p
    }

    val present get() = provider != null

    fun probeAsync(level: ServerLevel, x: Int, z: Int, y0: Int, y1: Int, callback: (List<String>) -> Unit) {
        val p = provider
        if (p == null) callback(emptyList()) else p.probe(level, x, z, y0, y1, callback)
    }
}
