package kami.geology.loot

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import kami.geology.config.ConfigStore
import kami.geology.world.Worlds
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.neoforged.neoforge.common.loot.IGlobalLootModifier
import net.neoforged.neoforge.common.loot.LootModifier
import kotlin.math.floor

class RichnessModifier(private val checks: Array<LootItemCondition>) : LootModifier(checks) {
    override fun doApply(loot: ObjectArrayList<ItemStack>, context: LootContext): ObjectArrayList<ItemStack> {
        val settings = ConfigStore.current?.takeIf { it.general.richness.enabled } ?: return loot
        val state = context.getParamOrNull(LootContextParams.BLOCK_STATE) ?: return loot
        val origin = context.getParamOrNull(LootContextParams.ORIGIN) ?: return loot
        val ore = settings.oreOf(state.block) ?: return loot
        val grade = Worlds.of(context.level)?.gradeAt(ore, BlockPos.containing(origin)) ?: return loot
        if (grade.drops == 1.0) return loot

        val block = state.block.asItem()
        loot.replaceAll { stack -> if (stack.`is`(block)) stack else stack.copyWithCount(scaled(stack.count, grade.drops, context.random)) }
        loot.removeIf { it.isEmpty }
        return loot
    }

    private fun scaled(count: Int, factor: Double, random: RandomSource): Int {
        val exact = count * factor
        return floor(exact).toInt() + if (random.nextDouble() < exact - floor(exact)) 1 else 0
    }

    override fun codec(): MapCodec<out IGlobalLootModifier> = CODEC

    companion object {
        val CODEC: MapCodec<RichnessModifier> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(IGlobalLootModifier.LOOT_CONDITIONS_CODEC.fieldOf("conditions").forGetter { it.checks })
                .apply(instance) { RichnessModifier(it) }
        }
    }
}
