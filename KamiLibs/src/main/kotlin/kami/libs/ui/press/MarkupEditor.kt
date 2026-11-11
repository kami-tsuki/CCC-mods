package kami.libs.ui.press

import kami.libs.press.Doc
import kami.libs.press.Dye
import kami.libs.press.Markup
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Key
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.SMALL_H
import kami.libs.ui.widget.TabItem
import kami.libs.ui.widget.button
import kami.libs.ui.widget.clickable
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.popover
import kami.libs.ui.widget.PopoverAlign
import kami.libs.ui.widget.subTabs
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

enum class EditorTab { EDIT, MARKDOWN, PREVIEW }

class Inserter(val label: String, val icon: Icon? = null, val tip: String? = null, val action: ((String) -> Unit) -> Unit)

class EditorState(initial: String = "", limit: Int = 5000) {
    val area = TextAreaState(initial, limit)
    var tab = EditorTab.EDIT
    var dye: Dye? = null
    internal var locked = false
    internal var menu = Menu.NONE
    internal var anchor = Rect.ZERO
    val text get() = area.text
    val limit get() = area.buffer.limit

    fun set(value: String) = area.set(value)

    fun insertToken(token: String) {
        if (!locked && area.buffer.insert(token, paste = true)) area.follow = true
    }
}

internal enum class Menu { NONE, COLOR, INSERT, HELP }

class EditorResult(val changed: Boolean)

private class Item(val label: String, val tip: String, val width: Int, val group: Int, val active: Boolean, val click: (Rect) -> Unit)

private const val TAB_H = 18
private const val GAP = 2
private const val SEPARATOR = 6
private const val SWATCH = 15
private const val SWATCH_STEP = 17
private const val INSERT_ROW = 13
private const val COUNTER_MIN_WIDTH = 260
private const val NEAR_LIMIT = 0.9

private val HELP_ROWS = listOf(
    "**text**" to "bold", "_text_" to "italic", "# Title" to "heading", "> text" to "quote", "- text" to "list", "^ text" to "center",
    "---" to "rule", "{c:red}text{/c}" to "color", "{price:minecraft:iron_ingot}" to "price", "{flag:id}" to "flag",
    "{head:uuid}" to "head", "{item:minecraft:stone}" to "item", "{icon:star}" to "icon"
)

fun Ui.markupEditor(
    r: Rect, state: EditorState, key: Any = "markup", enabled: Boolean = true, readOnly: Boolean = false,
    inserts: List<Inserter> = emptyList(), colorHint: (Dye) -> String? = { null }, article: (Doc) -> Article = { Article(doc = it) },
    data: NewsData = NoNewsData, statusHeight: Int = 0, status: Ui.(Rect) -> Unit = {}
): EditorResult {
    val b = state.area.buffer
    state.locked = readOnly
    val start = b.version
    val usable = enabled && !readOnly && state.tab != EditorTab.PREVIEW
    val areaKey = "$key:${state.tab.name}"
    val now = remember("active:$key") { Memo() }.of(b.version, b.cursor) { MarkupStyler.active(b.text, b.cursor) }
    fun act(change: () -> Boolean) {
        if (change()) {
            state.area.follow = true
            focus = id(areaKey)
        }
    }
    fun toggle(menu: Menu, anchor: Rect) {
        state.menu = if (state.menu == menu) Menu.NONE else menu
        state.anchor = anchor
    }
    var colorIndex = -1
    val items = buildList {
        fun tool(group: Int, label: String, tip: String, shortcut: String?, active: Boolean, change: () -> Boolean) =
            add(Item(label, if (shortcut == null) tip else "$tip ($shortcut)", maxOf(18, Draw.width(label) + 10), group, active) { act(change) })
        tool(0, tr("kami_libs.press.tool.bold"), tr("kami_libs.press.tool.bold.tooltip"), "Ctrl+B", now.bold) { b.toggleWrap("**", "**") }
        tool(0, tr("kami_libs.press.tool.italic"), tr("kami_libs.press.tool.italic.tooltip"), "Ctrl+I", now.italic) { b.toggleWrap("_", "_") }
        tool(1, tr("kami_libs.press.tool.h1"), tr("kami_libs.press.tool.h1.tooltip"), "Ctrl+Alt+1", now.heading == 1) { b.toggleLinePrefix("# ") }
        tool(1, tr("kami_libs.press.tool.h2"), tr("kami_libs.press.tool.h2.tooltip"), "Ctrl+Alt+2", now.heading > 1) { b.toggleLinePrefix("## ") }
        tool(2, tr("kami_libs.press.tool.quote"), tr("kami_libs.press.tool.quote.tooltip"), null, now.quote) { b.toggleLinePrefix("> ") }
        tool(2, tr("kami_libs.press.tool.list"), tr("kami_libs.press.tool.list.tooltip"), null, now.list) { b.toggleLinePrefix("- ") }
        tool(2, tr("kami_libs.press.tool.center"), tr("kami_libs.press.tool.center.tooltip"), null, now.center) { b.toggleLinePrefix("^ ") }
        tool(2, tr("kami_libs.press.tool.rule"), tr("kami_libs.press.tool.rule.tooltip"), null, false) { b.insertBlock("---") }
        val color = tr("kami_libs.press.tool.color")
        colorIndex = size
        add(Item(color, "${tr("kami_libs.press.tool.color.tooltip")} (Ctrl+Shift+C)", Draw.width(color) + 22, 3, now.dye != null) { toggle(Menu.COLOR, it) })
        if (inserts.isNotEmpty()) {
            val insert = tr("kami_libs.press.tool.insert")
            add(Item(insert, tr("kami_libs.press.tool.insert.tooltip"), Draw.width(insert) + 14, 4, false) { toggle(Menu.INSERT, it) })
        }
    }
    var x = 0
    var row = 0
    var prev = -1
    val breaks = ArrayList<Pair<Int, Int>>()
    val cells = items.map { item ->
        var lead = if (x > 0 && item.group != prev) SEPARATOR else 0
        if (x > 0 && x + lead + item.width > r.w) {
            x = 0
            row++
            lead = 0
        }
        x += lead
        val cell = Rect(r.x + x, r.y + row * (SMALL_H + GAP), item.width, SMALL_H)
        if (lead > 0) breaks += cell.x - (GAP + lead) / 2 - 1 to cell.y
        x += item.width + GAP
        prev = item.group
        cell
    }
    val toolbarH = (row + 1) * (SMALL_H + GAP)
    for ((bx, by) in breaks) Draw.fill(g, Rect(bx, by + 2, 1, SMALL_H - 4), Palette.borderSubtle)
    items.forEachIndexed { i, item ->
        val cell = cells[i]
        if (button(cell, item.label, style = if (item.active) ButtonStyle.SECONDARY else ButtonStyle.GHOST, enabled = usable, tip = item.tip, key = "$key:tool:$i")) item.click(cell)
        if (item.active && usable) Draw.hline(g, cell.x + 2, cell.bottom - 1, cell.w - 4, Palette.brass)
        if (i == colorIndex) {
            val swatch = (now.dye ?: state.dye)?.let { PressColors.argb(it) } ?: Palette.textMuted
            Draw.fill(g, Rect(cell.right - 11, cell.centerY - 3, 6, 6), swatch)
            Draw.outline(g, Rect(cell.right - 11, cell.centerY - 3, 6, 6), Palette.borderSubtle)
        }
    }
    val tabs = listOf(tr("kami_libs.press.tab.edit"), tr("kami_libs.press.tab.markdown"), tr("kami_libs.press.tab.preview")).map { TabItem(it) }
    val tabRect = Rect(r.x, r.y + toolbarH, r.w, TAB_H)
    subTabs(tabRect, tabs, state.tab.ordinal, "$key:tabs")?.let {
        state.tab = EditorTab.entries[it]
        state.menu = Menu.NONE
    }
    val helpRect = Rect(r.right - TAB_H, tabRect.y, TAB_H, TAB_H)
    if (iconButton(helpRect, Icons.HELP, tr("kami_libs.press.help.tooltip"), enabled = enabled, selected = state.menu == Menu.HELP, key = "$key:help")) toggle(Menu.HELP, helpRect)
    if (r.w >= COUNTER_MIN_WIDTH) {
        val used = state.text.length
        val color = when {
            used >= state.limit -> Palette.danger
            used >= state.limit * NEAR_LIMIT -> Palette.warning
            else -> Palette.textMuted
        }
        Draw.textRight(g, "$used / ${state.limit}", helpRect.x - 4, Draw.textY(tabRect), color)
    }
    val body = Rect(r.x, tabRect.bottom + 2, r.w, (r.bottom - statusHeight - tabRect.bottom - 4).coerceAtLeast(20))
    val shortcut = { k: Key ->
        val plain = !k.shift && !k.alt
        when {
            !usable || !k.ctrl -> false
            k.code == GLFW.GLFW_KEY_B && plain -> { act { b.toggleWrap("**", "**") }; true }
            k.code == GLFW.GLFW_KEY_I && plain -> { act { b.toggleWrap("_", "_") }; true }
            k.code == GLFW.GLFW_KEY_C && k.shift && !k.alt -> { toggle(Menu.COLOR, cells[colorIndex]); true }
            k.code == GLFW.GLFW_KEY_1 && k.alt && !k.shift -> { act { b.toggleLinePrefix("# ") }; true }
            k.code == GLFW.GLFW_KEY_2 && k.alt && !k.shift -> { act { b.toggleLinePrefix("## ") }; true }
            else -> false
        }
    }
    when (state.tab) {
        EditorTab.EDIT -> textArea(body, state.area, tr("kami_libs.press.area.placeholder"), enabled, readOnly, areaKey, styled = true, paste = Paste::smart, data = data, shortcut = shortcut)
        EditorTab.MARKDOWN -> textArea(body, state.area, tr("kami_libs.press.area.placeholder"), enabled, readOnly, areaKey, paste = { Markup.clean(it, state.limit) }, shortcut = shortcut)
        EditorTab.PREVIEW -> {
            val doc = remember("doc:$key") { Memo() }.of(state.text) { Markup.parse(state.text) }
            val made = article(doc)
            val model = if (made.data === NoNewsData && data !== NoNewsData) made.copy(data = data) else made
            if (doc.blocks.isEmpty() && model.title.isBlank()) {
                Draw.fill(g, body, Newsprint.PAPER)
                Draw.text(g, tr("kami_libs.press.preview.empty"), body.centerX - Draw.width(tr("kami_libs.press.preview.empty")) / 2, body.centerY - 4, Newsprint.MUTED)
            } else newsprintView(body, model, "$key:preview")
        }
    }
    if (statusHeight > 0) status(Rect(r.x, r.bottom - statusHeight, r.w, statusHeight))
    if (state.menu != Menu.NONE && (if (state.menu == Menu.HELP) enabled else usable)) {
        onEscape(10) { state.menu = Menu.NONE }
        when (state.menu) {
            Menu.COLOR -> overlay { colorMenu(state, key, colorHint) { act(it) } }
            Menu.INSERT -> overlay { insertMenu(state, key, inserts) }
            Menu.HELP -> overlay { helpMenu(state) }
            Menu.NONE -> Unit
        }
    } else if (state.menu != Menu.NONE) state.menu = Menu.NONE
    return EditorResult(b.version != start)
}

private fun Ui.helpMenu(state: EditorState) {
    val code = HELP_ROWS.maxOf { Draw.width(it.first) } + 10
    val texts = HELP_ROWS.map { tr("kami_libs.press.help.${it.second}") }
    val w = (code + texts.maxOf { Draw.width(it) } + 12).coerceAtMost(screen.w - 8)
    popover(state.anchor, w, HELP_ROWS.size * INSERT_ROW + 6, align = PopoverAlign.END, onOutside = { state.menu = Menu.NONE }) { box ->
        HELP_ROWS.forEachIndexed { i, (syntax, _) ->
            val y = box.y + 3 + i * INSERT_ROW
            Draw.text(g, Draw.fit(syntax, code - 6), box.x + 6, Draw.textY(y, INSERT_ROW), Palette.brass)
            Draw.text(g, Draw.fit(texts[i], box.w - code - 8), box.x + code + 4, Draw.textY(y, INSERT_ROW), Palette.text)
        }
    }
}

private fun Ui.colorMenu(state: EditorState, key: Any, hint: (Dye) -> String?, apply: (() -> Boolean) -> Unit) {
    val size = 4 * SWATCH_STEP + 6
    popover(state.anchor, size, size, onOutside = { state.menu = Menu.NONE }) { box ->
        Dye.entries.forEachIndexed { i, dye ->
            val cell = Rect(box.x + 4 + (i % 4) * SWATCH_STEP, box.y + 4 + (i / 4) * SWATCH_STEP, SWATCH, SWATCH)
            val k = "$key:dye:$i"
            if (clickable(k, cell)) {
                state.dye = dye
                state.menu = Menu.NONE
                apply { state.area.buffer.toggleWrap("{c:${dye.id}}", "{/c}") }
            }
            Draw.fill(g, cell, PressColors.argb(dye))
            Draw.outline(g, cell, if (state.dye == dye) Palette.text else Palette.borderSubtle)
            val name = Component.translatable("color.minecraft.${dye.id}").string
            val extra = hint(dye) ?: if (dye.free) tr("kami_libs.press.color.free") else ""
            tooltip(k, cell) { Tip.text(extra.ifEmpty { name }, name.takeIf { extra.isNotEmpty() }) }
        }
    }
}

private fun Ui.insertMenu(state: EditorState, key: Any, inserts: List<Inserter>) {
    val w = maxOf(96, (inserts.maxOfOrNull { Draw.width(it.label) } ?: 0) + 30)
    popover(state.anchor, w, inserts.size * INSERT_ROW + 4, onOutside = { state.menu = Menu.NONE }) { box ->
        inserts.forEachIndexed { i, entry ->
            val row = Rect(box.x + 2, box.y + 2 + i * INSERT_ROW, box.w - 4, INSERT_ROW)
            val k = "$key:insert:$i"
            if (hovering(row)) {
                cursor = Cursor.HAND
                Draw.fill(g, row, Palette.hover)
            }
            Draw.leadIcon(g, entry.icon ?: Icons.ADD, row.x + 2, row.centerY, Palette.textMuted)
            Draw.text(g, Draw.fit(entry.label, row.w - 22), row.x + 18, Draw.textY(row), Palette.text)
            entry.tip?.let { tip -> tooltip(k, row, tip) }
            if (clickable(k, row)) {
                state.menu = Menu.NONE
                entry.action { token -> state.insertToken(token) }
            }
        }
    }
}
