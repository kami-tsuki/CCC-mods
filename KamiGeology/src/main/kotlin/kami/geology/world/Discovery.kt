package kami.geology.world

import kami.libs.progress.KamiProgress
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

object Discovery {
    private val posted = HashMap<UUID, MutableSet<String>>()

    fun report(player: ServerPlayer, tier: Int, sites: List<Site>, dim: ResourceLocation = player.level().dimension().location()) {
        if (player.hasDisconnected()) return
        KamiProgress.post(player, "prospect", "t$tier", 1)
        val seen = posted.getOrPut(player.uuid) { HashSet() }
        sites.forEach { if (seen.add("$dim:${it.id}")) KamiProgress.post(player, "deposit_found", it.ore.id, 1, "deposit:$dim:${it.id}") }
    }

    fun forget(player: ServerPlayer) = posted.remove(player.uuid)
}
