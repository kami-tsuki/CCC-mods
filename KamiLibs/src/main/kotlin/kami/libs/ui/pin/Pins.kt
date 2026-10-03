package kami.libs.ui.pin

import kami.libs.config.Configs
import kami.libs.log.Log
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.iconButton
import kotlinx.serialization.Serializable
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import java.nio.file.Files

abstract class PinWindow(val id: String, val maxItems: Int = 1, val width: Int = 140, val height: Int = 70) {
    abstract fun title(items: List<String>): String

    abstract fun draw(ui: Ui, r: Rect, items: List<String>, editing: Boolean)

    open fun shown(items: List<String>) = items.isNotEmpty()
}

@Serializable
class PinGeo(var x: Int, var y: Int, var w: Int, var h: Int)

@Serializable
class PinData(val geo: MutableMap<String, PinGeo> = mutableMapOf(), val items: MutableMap<String, MutableList<String>> = mutableMapOf())

object Pins {
    private const val TITLE_H = 11
    private const val GRIP = 6
    private const val MIN_W = 80
    private const val MIN_H = 30
    private const val MARGIN = 4

    private class Drag(val id: String, val resize: Boolean, val dx: Int, val dy: Int)

    private val windows = LinkedHashMap<String, PinWindow>()
    private val json = Configs.json()
    private val file by lazy { Configs.dir("libs").resolve("pins.json") }
    private val data: PinData by lazy { runCatching { json.decodeFromString(PinData.serializer(), Files.readString(file)) }.getOrNull() ?: PinData() }
    private val hud = Ui()
    private var drag: Drag? = null

    fun register(window: PinWindow) { windows[window.id] = window }

    fun items(id: String): List<String> = data.items[id].orEmpty()

    fun pinned(id: String, item: String) = item in items(id)

    fun pin(id: String, item: String) {
        val list = data.items.getOrPut(id) { mutableListOf() }
        if (item in list) return
        list += item
        while (list.size > (windows[id]?.maxItems ?: 1)) list.removeAt(0)
        save()
    }

    fun unpin(id: String, item: String) { if (data.items[id]?.remove(item) == true) save() }

    fun toggle(id: String, item: String) = if (pinned(id, item)) unpin(id, item) else pin(id, item)

    fun clear(id: String) { if (data.items.remove(id) != null) save() }

    fun renderHud(g: GuiGraphics) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || mc.screen != null || mc.player == null || windows.isEmpty()) return
        hud.frame(g, -1, -1, g.guiWidth(), g.guiHeight()) { windows.values.forEach { window(hud, it, false) } }
    }

    fun renderEditable(ui: Ui) = windows.values.forEach { window(ui, it, true) }

    private fun window(ui: Ui, w: PinWindow, editing: Boolean) {
        val items = items(w.id)
        if (!w.shown(items)) return
        if (editing) follow(ui, w)
        val r = rect(w, ui.screen)
        val g = ui.g
        Draw.fill(g, r, Palette.alpha(Palette.surface, if (editing) 0xF0 else 0xB0))
        Draw.outline(g, r, if (editing && drag?.id == w.id) Palette.brass else Palette.alpha(Palette.border, 0xC0))
        val bar = r.top(TITLE_H)
        Draw.text(g, Draw.fit(w.title(items), bar.w - 6 - if (editing) TITLE_H else 0), r.x + 3, r.y + 2, Palette.textMuted)
        val body = r.dropTop(TITLE_H).inset(3, 0, 3, 3)
        ui.scope("pin:${w.id}") {
            ui.clip(body) { w.draw(ui, body, items, editing) }
            if (editing) handles(ui, w, r, bar)
        }
    }

    private fun handles(ui: Ui, w: PinWindow, r: Rect, bar: Rect) {
        ui.block(r)
        if (ui.iconButton(bar.right(TITLE_H), Icons.CLOSE, tr("kami_libs.pin.remove"), key = "close")) return clear(w.id)
        val grip = Rect(r.right - GRIP, r.bottom - GRIP, GRIP, GRIP)
        Draw.fill(ui.g, grip, Palette.alpha(Palette.border, 0xC0))
        val move = bar.dropRight(TITLE_H)
        if (ui.hovering(move) || ui.hovering(grip)) ui.cursor = Cursor.MOVE
        ui.pressed(grip)?.let { drag = Drag(w.id, true, r.right - it.x, r.bottom - it.y) }
            ?: ui.pressed(move)?.let { drag = Drag(w.id, false, it.x - r.x, it.y - r.y) }
    }

    private fun follow(ui: Ui, w: PinWindow) {
        val d = drag?.takeIf { it.id == w.id } ?: return
        if (!ui.isDown()) {
            drag = null
            return save()
        }
        val geo = data.geo.getOrPut(w.id) { rect(w, ui.screen).let { PinGeo(it.x, it.y, it.w, it.h) } }
        if (d.resize) {
            geo.w = ui.mouseX + d.dx - geo.x
            geo.h = ui.mouseY + d.dy - geo.y
        } else {
            geo.x = ui.mouseX - d.dx
            geo.y = ui.mouseY - d.dy
        }
    }

    private fun rect(w: PinWindow, screen: Rect): Rect {
        val geo = data.geo[w.id]
        val width = (geo?.w ?: w.width).coerceIn(MIN_W, maxOf(MIN_W, screen.w))
        val height = (geo?.h ?: w.height).coerceIn(MIN_H, maxOf(MIN_H, screen.h))
        val x = geo?.x ?: (screen.w - width - MARGIN)
        val y = geo?.y ?: (MARGIN + windows.values.takeWhile { it !== w }.filter { it.shown(items(it.id)) }.sumOf { (data.geo[it.id]?.h ?: it.height) + MARGIN })
        return Rect(x.coerceIn(0, maxOf(0, screen.w - width)), y.coerceIn(0, maxOf(0, screen.h - height)), width, height)
    }

    private fun save() {
        runCatching { Files.writeString(file, json.encodeToString(PinData.serializer(), data)) }
            .onFailure { Log.of("libs").warn("Could not save pins.json: {}", it.message) }
    }
}

fun Ui.pinButton(r: Rect, id: String, item: String, key: Any = "pin:$id:$item"): Boolean {
    val pinned = Pins.pinned(id, item)
    val fired = iconButton(r, Icons.PIN, tr(if (pinned) "kami_libs.pin.unpin" else "kami_libs.pin.pin"), selected = pinned, key = key)
    if (fired) Pins.toggle(id, item)
    return fired
}
