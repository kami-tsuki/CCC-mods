package kami.libs.jei

import kami.libs.compat.Mods
import kami.libs.jei.impl.Hover
import net.minecraft.world.item.ItemStack
import java.util.concurrent.CopyOnWriteArrayList

object KamiJei {
    val present: Boolean get() = Mods.loaded(Mods.JEI)

    internal val dropSources = CopyOnWriteArrayList<() -> List<DropTarget>>()
    internal val priceSources = CopyOnWriteArrayList<(ItemStack) -> Double?>()
    internal val modules = CopyOnWriteArrayList<JeiModule>()

    fun hovered(): ItemStack? = if (present) Hover.item() else null

    fun typing(): Boolean = present && Hover.typing()

    fun drops(source: () -> List<DropTarget>) {
        if (present) dropSources += source
    }

    fun price(source: (ItemStack) -> Double?) {
        if (present) priceSources += source
    }

    fun module(m: JeiModule) {
        if (present) modules += m
    }
}
