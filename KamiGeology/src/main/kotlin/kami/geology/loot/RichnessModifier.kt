package kami.geology.loot

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import kami.geology.config.ConfigStore
import kami.geology.world.Worlds
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
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
        val tool = context.getParamOrNull(LootContextParams.TOOL)
        if (tool != null && EnchantmentHelper.getItemEnchantmentLevel(context.level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SILK_TOUCH), tool) > 0) return loot
        val grade = Worlds.of(context.level)?.gradeAt(ore, BlockPos.containing(origin)) ?: return loot
        if (grade.drops == 1.0) return loot

        val block = state.block.asItem()
        val out = ObjectArrayList<ItemStack>()
        for (stack in loot) {
            if (stack.`is`(block)) {
                out.add(stack)
                continue
            }
            var left = scaled(stack.count, grade.drops, context.random)
            while (left > 0) {
                val part = minOf(left, stack.maxStackSize)
                out.add(stack.copyWithCount(part))
                left -= part
            }
        }
        return out
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
