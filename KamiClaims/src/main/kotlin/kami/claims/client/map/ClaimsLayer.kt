package kami.claims.client.map

import kami.claims.client.ClientClaims
import kami.claims.client.app.Flags
import kami.claims.client.app.Vocabulary
import kami.claims.client.store.ClaimsStore
import kami.claims.service.View
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.map.ChunkMap
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kotlin.math.max

enum class MapMode(val key: String, val label: String, val icon: Icon, val description: String) {
    POLITICAL("political", "Political", Icons.FLAG, "Every country in its own colour."),
    RELATIONS("relations", "Relations", Icons.HANDSHAKE, "Your country, your family of provinces, allies, strangers and countries that banished you."),
    LANDUSE("landuse", "Land use", Icons.LAYERS, "What each chunk is used for: mining, farming, housing and more."),
    ECONOMY("economy", "Economy", Icons.COIN, "Daily upkeep of each chunk, from cheap (green) to expensive (red)."),
    RISK("risk", "Risk", Icons.WARNING, "Chunks in debt, reserved land and plots behind on tax."),
    PLOTS("plots", "Plots", Icons.HOUSE, "Residential plots: yours, free to rent and taken."),
    TERRAIN("terrain", "Terrain", Icons.MAP, "Only the land itself, with thin borders.");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: POLITICAL
    }
}

object ClaimsLayer {
    private var debtRevision = -1
    private val debt = HashMap<Long, Int>()
    private val lapse = HashMap<Long, Int>()

    private fun refresh() {
        if (debtRevision == ClaimsStore.revision) return
        debtRevision = ClaimsStore.revision
        debt.clear(); lapse.clear()
        ClaimsStore.info?.claimList?.forEach {
            if (it.debt > 0) debt[ClientClaims.key(it.x, it.z)] = it.debt
            if (it.lapse > 0) lapse[ClientClaims.key(it.x, it.z)] = it.lapse
        }
    }

    private fun price(e: View.Entry): Double {
        val name = ClientClaims.typeName(e) ?: return -1.0
        val t = ClaimsStore.snap?.types?.firstOrNull { it.name == name } ?: return -1.0
        return t.price.toDouble() / max(1, t.period)
    }

    fun fill(mode: MapMode, e: View.Entry): Int {
        val country = ClientClaims.country(e)
        val base = country?.color ?: 0x888888
        val own = e.flags and View.OWN != 0
        return when (mode) {
            MapMode.POLITICAL -> Palette.alpha(base, if (own) 0x90 else 0x68)
            MapMode.RELATIONS -> Palette.alpha(Vocabulary.relationColor(country?.relation ?: 0), 0x80)
            MapMode.LANDUSE -> ClientClaims.typeName(e)?.let { Palette.alpha(Vocabulary.type(it).color, 0xA0) } ?: Palette.alpha(Palette.geoNeutral, 0x40)
            MapMode.ECONOMY -> price(e).takeIf { it >= 0 }?.let { p ->
                val top = ClaimsStore.snap?.types?.maxOfOrNull { it.price.toDouble() / max(1, it.period) }?.takeIf { it > 0 } ?: 1.0
                Palette.alpha(Palette.mix(Palette.success, Palette.danger, (p / top).toFloat()), 0xA0)
            } ?: Palette.alpha(Palette.geoNeutral, 0x40)
            MapMode.RISK -> when {
                e.flags and View.DEBT != 0 -> Palette.alpha(Palette.danger, 0x70)
                own -> Palette.alpha(Palette.success, 0x30)
                else -> Palette.alpha(Palette.geoNeutral, 0x30)
            }
            MapMode.PLOTS -> when {
                e.flags and View.MINE != 0 -> Palette.alpha(Palette.money, 0xB0)
                e.flags and View.CLAIMABLE != 0 -> Palette.alpha(Palette.success, 0x90)
                e.flags and View.TAKEN != 0 -> Palette.alpha(Palette.geoNeutral, 0x80)
                else -> Palette.alpha(base, 0x28)
            }
            MapMode.TERRAIN -> 0
        }
    }

    fun draw(ui: Ui, map: ChunkMap, dim: String, mode: MapMode, layers: Set<String>, you: Pair<Int, Int>?) {
        refresh()
        val g = ui.g
        val xs = map.visibleX()
        val zs = map.visibleZ()
        val zoom = map.zoom
        val phase = if (ui.reduceMotion) 0 else (ui.now / 120 % 64).toInt()
        g.drawManaged {
            for (x in xs) for (z in zs) {
                val e = ClientClaims.at(dim, x, z)
                val r = map.cell(x, z)
                if (e == null) {
                    if (ClientClaims.reserved(dim, x, z) && (mode == MapMode.RISK || mode == MapMode.POLITICAL)) Draw.fill(g, r, Palette.alpha(Palette.geoReserved, 0x50))
                    continue
                }
                val color = fill(mode, e)
                if (color != 0) Draw.fill(g, r, color)
            }
        }
        if (mode == MapMode.RISK || mode == MapMode.POLITICAL) for (x in xs) for (z in zs) {
            val e = ClientClaims.at(dim, x, z) ?: continue
            if (e.flags and View.DEBT == 0 || zoom < 6) continue
            val level = debt[ClientClaims.key(x, z)] ?: 1
            Draw.hatch(g, map.cell(x, z), Palette.alpha(Palette.danger, 0x60 + level * 0x30), 5 - level.coerceAtMost(3), phase)
        }
        if (mode == MapMode.RISK) for (x in xs) for (z in zs) {
            val level = lapse[ClientClaims.key(x, z)] ?: continue
            Draw.outline(g, map.cell(x, z), Palette.alpha(Palette.warning, 0x60 + level * 0x30))
        }
        if ("borders" in layers || mode == MapMode.TERRAIN) borders(ui, map, dim, xs, zs)
        if ("markers" in layers && zoom >= 8) markers(ui, map, dim, xs, zs)
        if ("labels" in layers && zoom <= 16) labels(ui, map, dim, xs, zs)
        you?.let { (x, z) ->
            val r = map.cell(x, z)
            if (zoom >= 10) Draw.head(g, ClaimsStore.snap?.me ?: "", r.centerX - 4, r.centerY - 4, 8)
            Draw.outline(g, r, Palette.alpha(Palette.focus, (0x80 + 0x7F * ui.pulse()).toInt()))
        }
    }

    private fun borders(ui: Ui, map: ChunkMap, dim: String, xs: IntRange, zs: IntRange) {
        val g = ui.g
        val thick = if (map.zoom >= 12) 2 else 1
        g.drawManaged {
            for (x in xs) for (z in zs) {
                val e = ClientClaims.at(dim, x, z) ?: continue
                val r = map.cell(x, z)
                val color = Palette.opaque(ClientClaims.country(e)?.color ?: 0x888888)
                val dark = Palette.alpha(0x0C0E12, 0xB0)
                fun other(dx: Int, dz: Int) = ClientClaims.at(dim, x + dx, z + dz)?.country != e.country
                if (other(0, -1)) { Draw.fill(g, Rect(r.x, r.y, r.w, thick), color); Draw.fill(g, Rect(r.x, r.y - 1, r.w, 1), dark) }
                if (other(0, 1)) { Draw.fill(g, Rect(r.x, r.bottom - thick, r.w, thick), color); Draw.fill(g, Rect(r.x, r.bottom, r.w, 1), dark) }
                if (other(-1, 0)) { Draw.fill(g, Rect(r.x, r.y, thick, r.h), color); Draw.fill(g, Rect(r.x - 1, r.y, 1, r.h), dark) }
                if (other(1, 0)) { Draw.fill(g, Rect(r.right - thick, r.y, thick, r.h), color); Draw.fill(g, Rect(r.right, r.y, 1, r.h), dark) }
            }
        }
    }

    private fun markers(ui: Ui, map: ChunkMap, dim: String, xs: IntRange, zs: IntRange) {
        val size = if (map.zoom >= 20) 16 else 8
        for (x in xs) for (z in zs) {
            val e = ClientClaims.at(dim, x, z) ?: continue
            val r = map.cell(x, z)
            val icon = when {
                e.flags and View.CAPITAL != 0 -> Icons.CROWN
                e.flags and View.MINE != 0 -> Icons.HOUSE
                e.flags and View.CLAIMABLE != 0 && ClientClaims.prefs.claimable && map.zoom >= 16 -> Icons.ADD
                else -> null
            } ?: continue
            Draw.icon(ui.g, icon, r.x + (r.w - size) / 2, r.y + (r.h - size) / 2, size)
        }
    }

    private fun labels(ui: Ui, map: ChunkMap, dim: String, xs: IntRange, zs: IntRange) {
        val sums = HashMap<Int, IntArray>()
        for (x in xs) for (z in zs) {
            val e = ClientClaims.at(dim, x, z) ?: continue
            val acc = sums.getOrPut(e.country) { IntArray(3) }
            acc[0] += x; acc[1] += z; acc[2]++
        }
        val placed = ArrayList<Rect>()
        sums.entries.sortedByDescending { it.value[2] }.forEach { (idx, acc) ->
            if (acc[2] < 3) return@forEach
            val country = ClientClaims.countries.getOrNull(idx) ?: return@forEach
            val px = map.sx(acc[0].toDouble() / acc[2] + 0.5)
            val pz = map.sz(acc[1].toDouble() / acc[2] + 0.5)
            val w = Draw.width(country.name) + 18
            val box = Rect(px - w / 2, pz - 6, w, 13)
            if (placed.any { it.intersect(box).w > 0 && it.intersect(box).h > 0 } || !map.view.contains(box.centerX, box.centerY)) return@forEach
            placed += box
            Draw.fill(ui.g, box, Palette.alpha(0x0C0E12, 0xB0))
            Flags.draw(ui.g, Rect(box.x + 2, box.y + 2, 12, 9), country.color, country.pattern, country.emblem, country.secondary)
            Draw.text(ui.g, country.name, box.x + 16, box.y + 3, Palette.text)
        }
    }
}
