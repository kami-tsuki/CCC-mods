package kami.libs.ui.style

import kami.libs.KamiLibs
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation

@JvmInline
value class Icon(val sprite: ResourceLocation) {
    companion object {
        fun of(namespace: String, name: String) = Icon(ResourceLocation.fromNamespaceAndPath(namespace, "icon/$name"))
    }
}

object Icons {
    private fun lib(name: String) = Icon.of(KamiLibs.ID, name)

    val DASHBOARD = lib("dashboard")
    val MAP = lib("map")
    val STATS = lib("stats")
    val LEDGER = lib("ledger")
    val SETTINGS = lib("settings")
    val HELP = lib("help")
    val BELL = lib("bell")
    val CLOSE = lib("close")
    val BACK = lib("back")
    val FORWARD = lib("forward")
    val ADD = lib("add")
    val REMOVE = lib("remove")
    val EDIT = lib("edit")
    val SAVE = lib("save")
    val UNDO = lib("undo")
    val SEARCH = lib("search")
    val FILTER = lib("filter")
    val SORT_UP = lib("sort_up")
    val SORT_DOWN = lib("sort_down")
    val MORE = lib("more")
    val LOCATE = lib("locate")
    val COPY = lib("copy")
    val CHEVRON_DOWN = lib("chevron_down")
    val CHEVRON_RIGHT = lib("chevron_right")
    val CHECK = lib("check")
    val CROSS = lib("cross")
    val INFO = lib("info")
    val WARNING = lib("warning")
    val DANGER = lib("danger")
    val LOCK = lib("lock")
    val CLOCK = lib("clock")
    val TREND_UP = lib("trend_up")
    val TREND_DOWN = lib("trend_down")
    val TREND_FLAT = lib("trend_flat")
    val STAR = lib("star")
    val EYE = lib("eye")
    val PENDING = lib("pending")
    val COIN = lib("coin")
    val TREASURY = lib("treasury")
    val INCOME = lib("income")
    val EXPENSE = lib("expense")
    val DEPOSIT = lib("deposit")
    val WITHDRAW = lib("withdraw")
    val PERSON = lib("person")
    val PEOPLE = lib("people")
    val INVITE = lib("invite")
    val FLAG = lib("flag")
    val CROWN = lib("crown")
    val SHIELD = lib("shield")
    val SCROLL = lib("scroll")
    val HANDSHAKE = lib("handshake")
    val BAN = lib("ban")
    val GLOBE = lib("globe")
    val CHAIN = lib("chain")
    val BROKEN_CHAIN = lib("broken_chain")
    val HOUSE = lib("house")
    val PICKAXE = lib("pickaxe")
    val TOOL = lib("tool")
    val GEAR = lib("gear")
    val FIRE = lib("fire")
    val WATER = lib("water")
    val HAND = lib("hand")
    val CHEST = lib("chest")
    val BLOCK = lib("block")
    val BRUSH = lib("brush")
    val AREA = lib("area")
    val CURSOR = lib("cursor")
    val PAN = lib("pan")
    val RULER = lib("ruler")
    val LAYERS = lib("layers")
    val PERCENT = lib("percent")
    val TAX = lib("tax")
    val DEBT = lib("debt")
    val TOWN = lib("town")
    val TREE = lib("tree")
    val WHEAT = lib("wheat")
    val SCALES = lib("scales")
    val RAIL = lib("rail")
    val PINE = lib("pine")
    val GENERIC = lib("generic")
}

object Glyphs {
    val FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath(KamiLibs.ID, "glyphs")

    enum class Glyph(val char: Char, val fallback: String) {
        COIN('', "◎"), UP('', "▲"), DOWN('', "▼"), FLAT('', "▶"),
        CROWN('', "♛"), HOUSE('', "⌂"), FLAG('', "⚑"), SHIELD('', "[S]"),
        CHECK('', "✔"), CROSS('', "✖"), WARNING('', "⚠"), INFO('', "ⓘ"),
        LOCK('', "[L]"), CLOCK('', "(t)"), ARROW('', "→"), DOT('', "●"),
        MOUSE_LEFT('', "[LMB]"), MOUSE_RIGHT('', "[RMB]"), SHIFT('', "[Shift]"), ESC('', "[Esc]"),
        CHAIN('', "⛓"), PICKAXE('', "⛏"), PERSON('', "☺"), GLOBE('', "◍")
    }

    fun component(glyph: Glyph): MutableComponent = Component.literal(glyph.char.toString()).withStyle(Style.EMPTY.withFont(FONT))

    fun text(glyph: Glyph, modded: Boolean) = if (modded) glyph.char.toString() else glyph.fallback
}
