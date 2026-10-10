package kami.economy.client.compat.jei

import kami.economy.client.ClientHooks
import kami.economy.client.PriceCache
import kami.economy.client.TooltipHook
import kami.economy.net.InfoQuest
import kami.economy.net.InfoVendor
import kami.economy.net.JeiPayload
import kami.economy.net.PriceInfoPayload
import kami.libs.mc.ItemSpec
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object JeiStore {
    private const val REFRESH = 1200
    private const val START = 100

    var quests = emptyList<InfoQuest>()
        private set
    var vendors = emptyList<InfoVendor>()
        private set
    private val listeners = mutableListOf<() -> Unit>()
    private val stacks = HashMap<String, ItemStack>()
    private var ticks = 0
    private var priced = 0

    fun init() {
        FORGE_BUS.addListener<ClientTickEvent.Post> {
            if (Minecraft.getInstance().player != null && ticks++ % REFRESH == START) ClientHooks.request("jei")
        }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> {
            ticks = 0
            priced = 0
            put(JeiPayload(emptyList(), emptyList()))
        }
    }

    fun put(p: JeiPayload) {
        if (quests == p.quests && vendors == p.vendors) return
        quests = p.quests
        vendors = p.vendors
        changed()
    }

    fun prices() {
        if (PriceCache.items.size != priced) {
            priced = PriceCache.items.size
            changed()
        }
    }

    fun listen(cb: () -> Unit): () -> Unit {
        listeners += cb
        return { listeners -= cb }
    }

    fun stack(id: String): ItemStack = stacks.getOrPut(id) { runCatching { ItemSpec.stack(id) }.getOrDefault(ItemStack.EMPTY) }

    fun info(id: String): PriceInfoPayload? {
        if (TooltipHook.info.request(id, System.currentTimeMillis())) ClientHooks.request("price_info", id)
        return TooltipHook.info.get(id)
    }

    private fun changed() = listeners.toList().forEach { it() }
}
