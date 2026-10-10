package kami.claims.compat

import kami.claims.Key
import kami.claims.net.Sync
import kami.claims.world.Guard
import kami.claims.world.TempBlocks
import kami.libs.jade.KamiJade
import kami.libs.util.RateLimit
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.level.Level
import java.util.UUID

object JadeIds {
    val CLAIM: ResourceLocation = ResourceLocation.fromNamespaceAndPath("kami_claims", "claim")
    val TEMP: ResourceLocation = ResourceLocation.fromNamespaceAndPath("kami_claims", "temp")
    val OFFER: ResourceLocation = ResourceLocation.fromNamespaceAndPath("kami_claims", "offer")
    val RESEARCH: ResourceLocation = ResourceLocation.fromNamespaceAndPath("kami_claims", "research")
    val FRAME: ResourceLocation = ResourceLocation.fromNamespaceAndPath("kami_claims", "frame")
}

object JadeServer {
    @Volatile
    var unclaimed: (String, Int, Int) -> Boolean = { _, _, _ -> true }

    @Volatile
    var offered: (String, Int, Int) -> Boolean = { _, _, _ -> true }

    private val offerLimit = RateLimit<UUID>(4, 1000L)

    private fun wild(level: Level, pos: BlockPos) = unclaimed(level.dimension().location().toString(), pos.x shr 4, pos.z shr 4)

    fun register() {
        KamiJade.blockData(JadeIds.TEMP, { look -> TempBlocks.fits(look.state) && wild(look.level, look.pos) }) { player, pos ->
            TempBlocks.due(player.serverLevel(), pos)?.let { CompoundTag().apply { putLong("due", it) } }
        }
        KamiJade.blockData(JadeIds.OFFER, { look -> offered(look.level.dimension().location().toString(), look.pos.x shr 4, look.pos.z shr 4) }) { player, pos ->
            if (!offerLimit.allow(player.uuid)) return@blockData null
            Sync.offerPrice(player, Key(player.serverLevel().dimension().location().toString(), pos.x shr 4, pos.z shr 4))?.let { CompoundTag().apply { putInt("price", it.toInt()) } }
        }
        KamiJade.entityData(JadeIds.FRAME, ItemFrame::class.java, { look -> wild(look.entity.level(), look.entity.blockPosition()) }) { _, entity ->
            if (Guard.wildFrame(entity)) CompoundTag().apply { putBoolean("take", true) } else null
        }
    }
}
