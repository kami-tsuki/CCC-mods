package kami.economy.client

import com.mojang.blaze3d.platform.InputConstants
import kami.economy.client.admin.MarketSection
import kami.economy.client.ui.signedPercent
import kami.economy.economy.ClientVariants
import kami.economy.net.WatchIdsPayload
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.pin.PinWindow
import kami.libs.ui.pin.Pins
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.fitText
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import kotlin.math.abs

object WatchKey {
    private const val PIN = "kami_economy:watch"
    private val key = KeyMapping("key.kami_economy.watch_item", KeyConflictContext.GUI, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_W, "key.categories.kami_economy")
    private val watched = HashSet<String>()
    private val base = HashMap<String, Int>()
    private val moves = HashMap<String, Pair<Int, Int>>()
    private var threshold = 10f

    private object Alerts : PinWindow(PIN, maxItems = 4, width = 150, height = 60) {
        override fun title(items: List<String>) = tr("kami_economy.watch.pin")

        override fun draw(ui: Ui, r: Rect, items: List<String>, editing: Boolean) {
            val stack = Stack(r.x, r.y + 1, r.w, 2)
            items.forEach { id ->
                val row = stack.take(Draw.LINE)
                val (from, to) = moves[id] ?: return@forEach
                val text = signedPercent((to - from) * 100.0 / from.coerceAtLeast(1), 1)
                Draw.textRight(ui.g, text, row.right, row.y, if (to >= from) Palette.success else Palette.danger)
                ui.fitText(row.x, row.y, row.w - Draw.width(text) - 4, MarketSection.itemName(id), Palette.text)
            }
        }
    }

    fun init() {
        Pins.register(Alerts)
        MOD_BUS.addListener<RegisterKeyMappingsEvent> { it.register(key) }
        FORGE_BUS.addListener<ScreenEvent.KeyPressed.Pre> {
            if (!key.isActiveAndMatches(InputConstants.getKey(it.keyCode, it.scanCode))) return@addListener
            val id = MarketLink.hoveredMarketItem(it.screen)?.let(ClientVariants::core) ?: return@addListener
            it.isCanceled = true
            val on = watched.add(id)
            if (!on) watched.remove(id)
            ClientHooks.request("watch", id, if (on) "on" else "off")
            Minecraft.getInstance().player?.displayClientMessage(Component.literal(tr(if (on) "kami_economy.watch.on" else "kami_economy.watch.off", MarketSection.itemName(id))), true)
        }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> { clear() }
    }

    fun clear() {
        watched.clear()
        base.clear()
        moves.clear()
        Pins.clear(PIN)
    }

    fun receive(p: WatchIdsPayload) {
        watched.clear()
        watched += p.ids
        threshold = p.thresholdPct
        base.keys.retainAll(watched)
    }

    fun moved(item: String, price: Int) {
        if (item !in watched) return
        val from = base[item]
        if (from == null || from <= 0) {
            base[item] = price
            return
        }
        if (abs(price - from) * 100f / from < threshold) return
        base[item] = price
        moves[item] = from to price
        Pins.pin(PIN, item)
    }
}
