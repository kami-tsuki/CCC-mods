package kami.libs.economy

import net.minecraft.world.item.ItemStack
import java.util.UUID

object CoinReturn {
    @Volatile
    private var keeper: ((UUID, ItemStack) -> Boolean)? = null

    fun register(keeper: (UUID, ItemStack) -> Boolean) {
        this.keeper = keeper
    }

    fun keep(owner: UUID, stack: ItemStack): Boolean = !stack.isEmpty && keeper?.invoke(owner, stack) == true
}
