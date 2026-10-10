package kami.libs.xaero.impl

import net.minecraft.client.Minecraft
import kami.libs.compat.Refl
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.ChatFormatting
import net.neoforged.neoforge.client.event.ScreenEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

internal object MapScreenButtons {
    private const val GUI_MAP = "xaero.map.gui.GuiMap"
    private const val SIZE = 14
    private const val GAP = 2
    private const val MARGIN = 3
    private const val TOP = 34
    private const val BOTTOM_RESERVED = 124
    private const val MAX_COLUMNS = 3
    private const val CACHE_NANOS = 40_000_000L
    private val guard = Guard("world map buttons")
    private var cached: List<Slot> = emptyList()
    private var cachedScreen: Screen? = null
    private var cachedAt = 0L
    private var consumed = -1

    private class Slot(val x: Int, val y: Int, val glyph: String, val on: Boolean, val lines: List<Component>, val click: () -> Unit) {
        fun hit(mx: Double, my: Double) = mx >= x && mx < x + SIZE && my >= y && my < y + SIZE
    }

    fun register() {
        FORGE_BUS.addListener<ScreenEvent.Render.Post> { if (it.screen.javaClass.name == GUI_MAP) guard.run { draw(it.guiGraphics, it.screen, it.mouseX, it.mouseY) } }
        FORGE_BUS.addListener<ScreenEvent.MouseButtonPressed.Pre> {
            if (it.screen.javaClass.name == GUI_MAP) guard.run {
                slots(it.screen).firstOrNull { s -> s.hit(it.mouseX, it.mouseY) }?.let { s ->
                    consumed = it.button
                    if (it.button == 0) {
                        cachedAt = 0L
                        s.click()
                        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
                    }
                    it.isCanceled = true
                }
            }
        }
        FORGE_BUS.addListener<ScreenEvent.MouseButtonReleased.Pre> {
            if (consumed == it.button && it.screen.javaClass.name == GUI_MAP) {
                consumed = -1
                it.isCanceled = true
            }
        }
    }

    private fun flag(screen: Screen, name: String) = runCatching { Refl.field(screen, name) == true }.getOrDefault(false)

    private fun blocked(screen: Screen): Boolean {
        if (runCatching { Refl.staticField(GUI_MAP, "hiddenUI") == true }.getOrDefault(false)) return true
        if (runCatching { Refl.field(screen, "rightClickMenu") != null }.getOrDefault(false)) return true
        if (flag(screen, "hopMenu") || flag(screen, "waypointMenu") || flag(screen, "playersMenu")) return true
        return screen.focused is EditBox
    }

    private fun glyph(icon: String, label: Component) = icon.ifEmpty { label.string.take(1).uppercase() }.ifEmpty { "?" }

    private fun slots(screen: Screen): List<Slot> {
        val now = System.nanoTime()
        if (screen === cachedScreen && now - cachedAt < CACHE_NANOS) return cached
        cached = build(screen)
        cachedScreen = screen
        cachedAt = now
        return cached
    }

    private fun build(screen: Screen): List<Slot> {
        if (blocked(screen)) return emptyList()
        val perColumn = (screen.height - BOTTOM_RESERVED - TOP) / (SIZE + GAP)
        if (perColumn <= 0) return emptyList()
        val out = ArrayList<Slot>()
        fun add(glyph: String, on: Boolean, lines: List<Component>, click: () -> Unit) {
            val i = out.size
            if (i >= perColumn * MAX_COLUMNS) return
            out += Slot(MARGIN + i / perColumn * (SIZE + GAP), TOP + i % perColumn * (SIZE + GAP), glyph, on, lines, click)
        }
        Registry.toggleList.filter { it.world && it.visible() }.forEach { t ->
            val on = Registry.on(t.id)
            val label = Component.translatable(t.label)
            add(glyph(t.icon, label), on, listOf(label, Component.translatable(t.tip).withStyle(ChatFormatting.GRAY))) { Registry.setToggle(t.id, !on) }
        }
        Registry.choiceList.filter { it.options.isNotEmpty() }.forEach { c ->
            val current = Registry.choice(c.id)
            val label = Component.translatable(c.label)
            val shown = Component.translatable(c.options.firstOrNull { it.first == current }?.second ?: c.default)
            val following = c.options[(c.options.indexOfFirst { it.first == current } + 1) % c.options.size].first
            add(glyph(c.icon, label), true, listOf(label, shown.copy().withStyle(ChatFormatting.GRAY))) { Registry.setChoice(c.id, following) }
        }
        return out
    }

    private fun draw(g: GuiGraphics, screen: Screen, mx: Int, my: Int) {
        val font = Minecraft.getInstance().font
        var hovered: Slot? = null
        for (s in slots(screen)) {
            val over = s.hit(mx.toDouble(), my.toDouble())
            if (over) hovered = s
            g.fill(s.x, s.y, s.x + SIZE, s.y + SIZE, if (s.on) 0xE02E6B3A.toInt() else 0xE0202020.toInt())
            g.renderOutline(s.x, s.y, SIZE, SIZE, if (over) 0xFFFFFFFF.toInt() else 0xFF606060.toInt())
            g.drawString(font, s.glyph, s.x + (SIZE - font.width(s.glyph)) / 2 + 1, s.y + 3, if (s.on) 0xFFFFFFFF.toInt() else 0xFF909090.toInt(), false)
        }
        hovered?.let { g.renderComponentTooltip(font, it.lines, mx, my) }
    }
}
