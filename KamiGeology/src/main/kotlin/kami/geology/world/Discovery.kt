package kami.geology.world

import kami.libs.progress.KamiProgress
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Discovery {
    private val seen = ConcurrentHashMap<UUID, MutableSet<String>>()

    fun forget(player: ServerPlayer) {
        seen.remove(player.uuid)
    }

    fun report(player: ServerPlayer, tier: Int, sites: List<Site>): Boolean {
        KamiProgress.post(player, "prospect", "t$tier", 1)
        val dim = player.level().dimension().location()
        val known = seen.getOrPut(player.uuid) { ConcurrentHashMap.newKeySet() }
        var fresh = false
        for (site in sites) {
            val once = "deposit:$dim:${site.id}"
            KamiProgress.post(player, "deposit_found", site.ore.id, 1, once)
            if (known.add(once)) fresh = true
        }
        return fresh
    }
}
