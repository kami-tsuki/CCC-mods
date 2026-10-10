package kami.geology.client.compat.jade

import kami.geology.KamiGeology
import kami.geology.client.compat.xaero.ScanStore
import kami.geology.command.GeoText
import kami.libs.jade.KamiJade
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.common.Tags

object SignalJade {
    fun init() {
        KamiJade.block(ResourceLocation.fromNamespaceAndPath(KamiGeology.ID, "signal")) { look ->
            if (!look.state.`is`(Tags.Blocks.ORES)) return@block emptyList()
            val dim = look.level.dimension().location().toString()
            val cell = ScanStore.data.cell(dim, look.pos.x shr 4, look.pos.z shr 4) ?: return@block emptyList()
            val level = when {
                cell.intensity >= 2f / 3f -> "strong"
                cell.intensity >= 1f / 3f -> "medium"
                else -> "weak"
            }
            listOf(Component.translatable("kami_geology.jade.signal", GeoText.oreLabel(cell.ore), Component.translatable("kami_geology.jade.signal.$level")))
        }
    }
}
