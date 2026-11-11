package kami.libs.sophisticated.impl

import kami.libs.KamiLibs
import kami.libs.compat.Refl
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import java.util.function.BiPredicate

object CoreAdapter {
    private const val WORLD_HELPER = "net.p3pp3rf1y.sophisticatedcore.util.WorldHelper"

    fun register(block: (Player, BlockPos) -> Boolean, entity: (Player, Entity) -> Boolean) {
        try {
            val helper = Refl.cls(WORLD_HELPER)
            Refl.staticCallOn(helper, "addAdditionalInteractionCheck", BiPredicate<Player, BlockPos> { p, pos -> check { block(p, pos) } })
            Refl.staticCallOn(helper, "addAdditionalEntityInteractionCheck", BiPredicate<Player, Entity> { p, e -> check { entity(p, e) } })
        } catch (t: Throwable) {
            KamiLibs.LOG.warn("Sophisticated pump claim checks unavailable: {}", t.toString())
        }
    }

    private fun check(test: () -> Boolean): Boolean = try {
        test()
    } catch (t: Throwable) {
        KamiLibs.LOG.warn("Sophisticated claim check failed, denying: {}", t.toString())
        false
    }
}
