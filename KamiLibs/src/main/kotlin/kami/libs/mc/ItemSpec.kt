package kami.libs.mc

import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

object ItemSpec {
    fun base(spec: String): String = ItemSpecs.base(spec)

    fun stack(spec: String): ItemStack {
        val base = ItemSpecs.base(spec)
        val item = ResourceLocation.tryParse(base)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) } ?: return ItemStack.EMPTY
        val stack = ItemStack(item)
        val pack = ItemSpecs.pack(spec)
        val key = ItemSpecs.packKeys[base]
        if (pack.isNotEmpty() && key != null) stack.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putString(key, pack) }))
        return stack
    }

    fun id(stack: ItemStack): String = BuiltInRegistries.ITEM.getKey(stack.item).toString()

    fun spec(stack: ItemStack): String {
        val base = id(stack)
        val key = ItemSpecs.packKeys[base] ?: return base
        val pack = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getString(key).orEmpty()
        return ItemSpecs.join(base, pack)
    }
}
