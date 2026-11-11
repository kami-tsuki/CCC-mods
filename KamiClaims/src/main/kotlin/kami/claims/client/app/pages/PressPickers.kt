package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.store.ClaimsStore
import kami.libs.mc.ItemSpec
import kami.libs.text.SearchQuery
import kami.libs.ui.app.formDialog
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.ItemSearch
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Flags
import kami.libs.ui.widget.PlayerNames
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.itemSlot
import kami.libs.ui.widget.searchField

object PressPickers {
    private const val SLOT = 20
    private const val GRID_ROWS = 5
    private const val ROW_H = 16
    private const val LIST_ROWS = 8
    private const val WIDTH = 300

    private val icons: List<Pair<String, Icon>> = listOf(
        "star" to Icons.STAR, "crown" to Icons.CROWN, "shield" to Icons.SHIELD, "scroll" to Icons.SCROLL, "globe" to Icons.GLOBE, "flag" to Icons.FLAG,
        "house" to Icons.HOUSE, "pickaxe" to Icons.PICKAXE, "wheat" to Icons.WHEAT, "tree" to Icons.TREE, "fire" to Icons.FIRE, "water" to Icons.WATER,
        "coin" to Icons.COIN, "scales" to Icons.SCALES, "handshake" to Icons.HANDSHAKE, "gear" to Icons.GEAR, "town" to Icons.TOWN, "tool" to Icons.TOOL,
        "chest" to Icons.CHEST, "treasury" to Icons.TREASURY, "person" to Icons.PERSON, "people" to Icons.PEOPLE, "check" to Icons.CHECK, "cross" to Icons.CROSS,
        "warning" to Icons.WARNING, "info" to Icons.INFO, "clock" to Icons.CLOCK
    )

    private fun Ui.pickRow(r: Rect, selected: Boolean, key: String, draw: (Rect) -> Unit): Boolean {
        val over = hovering(r)
        if (over) cursor = Cursor.HAND
        if (selected) Draw.fill(g, r, Palette.alpha(Palette.brass, 0x30)) else if (over) Draw.fill(g, r, Palette.hover)
        draw(r)
        return pressed(r) != null
    }

    fun item(app: ClaimsApp, title: String, onPick: (String) -> Unit) {
        val search = TextState()
        var picked = ""
        var hitsFor: String? = null
        var hits = emptyList<String>()
        formDialog({ app.open(it) }, title, null, Icons.SEARCH, tr("kami_claims.press.insert"), { picked.isNotEmpty() }, { tr("kami_claims.press.pick.item.none") }, { onPick(picked) }, WIDTH) { r ->
            searchField(Rect(r.x, r.y, r.w, CONTROL_H), search, key = "press-item-search")
            if (hitsFor != search.text) {
                hitsFor = search.text
                val q = SearchQuery.parse(search.text)
                hits = if (q.isEmpty) emptyList() else ItemSearch.ids(q).sorted().take(r.w / SLOT * GRID_ROWS)
            }
            val grid = Rect(r.x, r.y + CONTROL_H + 4, r.w, GRID_ROWS * SLOT)
            val cols = (grid.w / SLOT).coerceAtLeast(1)
            hits.take(cols * GRID_ROWS).forEachIndexed { i, spec ->
                if (itemSlot(Rect(grid.x + i % cols * SLOT, grid.y + i / cols * SLOT, SLOT - 2, SLOT - 2), ItemSpec.stack(spec), selected = spec == picked, key = "press-item:$spec")) picked = spec
            }
            if (hits.isEmpty()) Draw.text(g, tr("kami_claims.press.pick.item.hint"), grid.x, grid.y + 2, Palette.textMuted)
            if (picked.isNotEmpty()) Draw.text(g, Draw.fit(PressModel.itemName(picked), r.w), r.x, grid.bottom + 4, Palette.text)
            CONTROL_H + 4 + GRID_ROWS * SLOT + 16
        }
    }

    fun countries(app: ClaimsApp, title: String, multi: Boolean, initial: Set<String>, onDone: (Set<String>) -> Unit) {
        val search = TextState()
        val picked = LinkedHashSet(initial)
        formDialog(
            { app.open(it) }, title, null, Icons.FLAG, tr(if (multi) "kami_libs.common.apply" else "kami_claims.press.insert"), { multi || picked.isNotEmpty() },
            { tr("kami_claims.press.pick.country.none") }, { onDone(picked.toSet()) }, WIDTH
        ) { r ->
            searchField(Rect(r.x, r.y, r.w, CONTROL_H), search, key = "press-country-search")
            val q = SearchQuery.parse(search.text)
            val shown = PressModel.countries().filter { q.matches(it.name) }.take(LIST_ROWS)
            val top = r.y + CONTROL_H + 4
            if (shown.isEmpty()) Draw.text(g, tr("kami_claims.press.pick.empty"), r.x, top + 2, Palette.textMuted)
            shown.forEachIndexed { i, c ->
                val row = Rect(r.x, top + i * ROW_H, r.w, ROW_H)
                if (pickRow(row, c.id in picked, "press-country:${c.id}") {
                    Flags.draw(g, Rect(it.x + 3, it.y + 2, 16, 12), c.color, c.pattern, c.emblem, c.secondary)
                    Draw.text(g, Draw.fit(c.name, it.w - 28), it.x + 24, Draw.textY(it), Palette.text)
                }) {
                    if (multi) { if (!picked.remove(c.id)) picked += c.id } else { picked.clear(); picked += c.id }
                }
            }
            CONTROL_H + 4 + LIST_ROWS * ROW_H
        }
    }

    fun player(app: ClaimsApp, onPick: (String) -> Unit) {
        val search = TextState()
        var picked = ""
        formDialog({ app.open(it) }, tr("kami_claims.press.insert.head"), null, Icons.PERSON, tr("kami_claims.press.insert"), { picked.isNotEmpty() }, { tr("kami_claims.press.pick.player.none") }, { onPick(picked) }, WIDTH) { r ->
            searchField(Rect(r.x, r.y, r.w, CONTROL_H), search, key = "press-player-search")
            val q = SearchQuery.parse(search.text)
            val people = PlayerNames.onlineIds().mapValues { it.value.toString() } + ClaimsStore.info?.members.orEmpty().associate { it.name to it.id }
            val shown = people.entries.filter { q.matches(it.key) }.sortedBy { it.key.lowercase() }.take(LIST_ROWS)
            val top = r.y + CONTROL_H + 4
            if (shown.isEmpty()) Draw.text(g, tr("kami_claims.press.pick.empty"), r.x, top + 2, Palette.textMuted)
            shown.forEachIndexed { i, (name, id) ->
                if (pickRow(Rect(r.x, top + i * ROW_H, r.w, ROW_H), id == picked, "press-player:$id") {
                    Draw.leadIcon(g, Icons.PERSON, it.x + 4, it.centerY)
                    Draw.text(g, Draw.fit(name, it.w - 28), it.x + 24, Draw.textY(it), Palette.text)
                }) picked = id
            }
            CONTROL_H + 4 + LIST_ROWS * ROW_H
        }
    }

    fun icon(app: ClaimsApp, onPick: (String) -> Unit) {
        var picked = ""
        formDialog({ app.open(it) }, tr("kami_claims.press.insert.icon"), null, Icons.STAR, tr("kami_claims.press.insert"), { picked.isNotEmpty() }, { tr("kami_claims.press.pick.icon.none") }, { onPick(picked) }, WIDTH) { r ->
            val cols = (r.w / SLOT).coerceAtLeast(1)
            icons.forEachIndexed { i, (name, icon) ->
                val cell = Rect(r.x + i % cols * SLOT, r.y + i / cols * SLOT, SLOT - 2, SLOT - 2)
                if (pickRow(cell, name == picked, "press-icon:$name") { Draw.icon(g, icon, it) }) picked = name
                tooltip("press-icon-tip:$name", cell, name)
            }
            (icons.size + cols - 1) / cols * SLOT
        }
    }
}
