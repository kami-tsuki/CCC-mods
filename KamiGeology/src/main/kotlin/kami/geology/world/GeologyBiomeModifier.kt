package kami.geology.world

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kami.geology.config.ConfigStore
import kami.geology.config.Settings
import net.minecraft.core.Holder
import net.minecraft.core.HolderSet
import net.minecraft.tags.BiomeTags
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.neoforged.neoforge.common.world.BiomeModifier
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo

class GeologyBiomeModifier(private val features: HolderSet<PlacedFeature>) : BiomeModifier {
    override fun modify(biome: Holder<Biome>, phase: BiomeModifier.Phase, builder: ModifiableBiomeInfo.BiomeInfo.Builder) {
        val settings = ConfigStore.current ?: return
        val generation = builder.generationSettings
        when (phase) {
            BiomeModifier.Phase.REMOVE -> if (settings.general.removeOriginalOres || settings.removeIds.isNotEmpty()) {
                val overworld = biome.`is`(BiomeTags.IS_OVERWORLD)
                GenerationStep.Decoration.entries.forEach { step -> generation.getFeatures(step).removeIf { removable(it, settings, overworld) } }
            }
            BiomeModifier.Phase.ADD -> if (biome.`is`(BiomeTags.IS_OVERWORLD)) {
                features.forEach { generation.addFeature(GenerationStep.Decoration.UNDERGROUND_ORES, it) }
            }
            else -> Unit
        }
    }

    private fun removable(holder: Holder<PlacedFeature>, settings: Settings, overworld: Boolean): Boolean {
        val id = holder.unwrapKey().map { it.location() }.orElse(null)
        if (id != null) {
            if (id in settings.keepIds) return false
            if (id in settings.removeIds) return true
        }
        return overworld && settings.general.removeOriginalOres && holder.value().features.anyMatch { feature ->
            (feature.config() as? OreConfiguration)?.targetStates?.any { it.state.block in settings.managed } == true
        }
    }

    override fun codec(): MapCodec<out BiomeModifier> = CODEC

    companion object {
        val CODEC: MapCodec<GeologyBiomeModifier> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(PlacedFeature.LIST_CODEC.fieldOf("features").forGetter { it.features })
                .apply(instance) { GeologyBiomeModifier(it) }
        }
    }
}
