package kami.economy.client

import com.mojang.blaze3d.platform.InputConstants
import kami.economy.client.ui.MarketApp
import kami.economy.economy.Blacklist
import kami.libs.jei.KamiJei
import kami.libs.ui.app.AppScreen
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object MarketLink {
    private val key = KeyMapping("key.kami_economy.open_item", KeyConflictContext.GUI, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.kami_economy")

    fun market(stack: ItemStack): String? = Blacklist.itemId(stack).takeIf { !stack.isEmpty && PriceCache.shown(it) != null }

    fun hoveredMarketItem(screen: Screen): String? {
        if (KamiJei.typing() || screen.focused is EditBox || (screen as? AppScreen)?.app?.ui?.typing == true) return null
        val stack = KamiJei.hovered() ?: (screen as? AbstractContainerScreen<*>)?.slotUnderMouse?.item ?: return null
        return market(stack)
    }

    fun init() {
        MOD_BUS.addListener<RegisterKeyMappingsEvent> { it.register(key) }
        FORGE_BUS.addListener<ScreenEvent.KeyPressed.Pre> {
            if (!key.isActiveAndMatches(InputConstants.getKey(it.keyCode, it.scanCode))) return@addListener
            val item = hoveredMarketItem(it.screen) ?: return@addListener
            it.isCanceled = true
            MarketApp.instance.openItem(item)
            MarketApp.open()
        }
        KamiJei.drops { MarketApp.instance.drops() }
        KamiJei.price { stack -> market(stack)?.let { id -> PriceCache.shown(id)?.let { p -> PriceCache.shownLot(id)?.let { l -> p.price.toDouble() / l } } } }
    }
}
