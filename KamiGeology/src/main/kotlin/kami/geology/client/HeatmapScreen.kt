package kami.geology.client

import com.mojang.blaze3d.platform.NativeImage
import kami.geology.KamiGeology
import kami.geology.command.GeoText
import kami.geology.client.HeatmapDraw.blend
import kami.geology.client.HeatmapDraw.short
import kami.geology.map.Heatmap
import kami.geology.map.Sparse
import kami.geology.net.MapDone
import kami.geology.net.MapLayer
import kami.geology.net.MapRequest
import kami.geology.net.OpenMap
import kami.geology.net.ProbeRequest
import kami.geology.net.ProbeResponse
import kami.libs.ui.KamiScreen
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.map.Viewport
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.SMALL_H
import kami.libs.ui.widget.card
import kami.libs.ui.widget.checkbox
import kami.libs.ui.widget.gradientLegend
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.progress
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.segmented
import kami.libs.ui.widget.slider
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val PANEL = 160
private const val TOP = 22
private const val ROW = 11
private const val LAYER_H = 68
private const val FILTER_H = 66
private const val LEGEND_H = 29
private const val MAX_CELLS = 150_000
private const val DEBOUNCE_MS = 120L
private const val REBUILD_MS = 90L
private const val DEFAULT_THRESHOLD = 80
private const val LOCK_FILL = 0.92
private val MARKS = intArrayOf(1, 10, 100, 1000, 10000)

class HeatmapScreen(private val info: OpenMap) : KamiScreen(Component.translatable("kami_geology.map.title")) {
    private class Tile(val x0: Int, val z0: Int, val cell: Int, val w: Int, val h: Int, ores: Int) {
        var province: ByteArray? = null
        val deposits = arrayOfNulls<FloatArray>(ores)
    }

    private enum class Mode {
        ORES, PROVINCES;

        val label get() = tr("kami_geology.map.mode.${name.lowercase()}")
    }

    override val dimColor = 0

    private val locked = info.lockX0 != null
    private val texId = ResourceLocation.fromNamespaceAndPath(KamiGeology.ID, "heatmap")
    private val enabled = BooleanArray(info.ores.size) { true }
    private val oreColors = IntArray(info.ores.size) { info.ores[it].color and 0xFFFFFF }
    private val provinceColors = IntArray(info.provinces.size) { info.provinces[it].color and 0xFFFFFF }
    private val totals = FloatArray(info.ores.size)
    private val homes: Array<BooleanArray?> = Array(info.ores.size) { o ->
        info.ores[o].scatterHomes?.let { list ->
            BooleanArray(info.provinces.size + 1).also { mask -> list.forEach { if (it in info.provinces.indices) mask[it + 1] = true } }
        }
    }
    private val layersExpected = info.ores.size + 1
    private val view = Viewport(4.0, 0.5, 12.0).apply {
        cx = info.x.toDouble()
        cz = info.z.toDouble()
        locked = this@HeatmapScreen.locked
    }

    private var yMin = info.minY
    private var yMax = info.maxY
    private var threshold = DEFAULT_THRESHOLD
    private var deposits = true
    private var scatter = true
    private var grid = true
    private var tint = true
    private var mode = Mode.ORES

    private var tile: Tile? = null
    private var incoming: Tile? = null
    private var texture: DynamicTexture? = null
    private var seq = 0
    private var dirty = true
    private var changedAt = 0L
    private var computing = false
    private var layersDone = 0
    private var finished: MapDone? = null
    private var rebuildDue = false
    private var lastRebuild = 0L
    private var hoverOre = -1

    private var mouseX = 0
    private var mouseY = 0
    private var rest = 0
    private var probeSeq = 0
    private var probeLines: List<String>? = null

    override fun removed() {
        Minecraft.getInstance().textureManager.release(texId)
        texture = null
        super.removed()
    }

    private fun setAll(value: Boolean) {
        enabled.fill(value)
        recompose()
    }

    private fun changed() {
        dirty = true
        changedAt = System.currentTimeMillis()
    }

    private fun recompose() {
        rebuildDue = true
        lastRebuild = 0L
    }

    private fun zoom(factor: Double) {
        if (view.zoomAt(factor)) changed()
    }

    private fun recenter() {
        val player = Minecraft.getInstance().player ?: return
        view.cx = player.x
        view.cz = player.z
        changed()
    }

    override fun tick() {
        val now = System.currentTimeMillis()
        if (dirty && now - changedAt >= DEBOUNCE_MS && (locked || !view.view.isEmpty)) {
            dirty = false
            sendRequest()
        }
        if (rebuildDue && now - lastRebuild >= REBUILD_MS) rebuild()
        rest++
        if (rest == 8 && !locked && view.contains(mouseX.toDouble(), mouseY.toDouble()) && !view.dragging) {
            PacketDistributor.sendToServer(
                ProbeRequest(++probeSeq, floor(view.worldX(mouseX.toDouble())).toInt(), floor(view.worldZ(mouseY.toDouble())).toInt(), yMin, yMax)
            )
        }
    }

    private fun sendRequest() {
        if (locked) {
            val x0 = info.lockX0!!
            val z0 = info.lockZ0!!
            val w = info.lockW!!
            val h = info.lockH!!
            incoming = Tile(x0, z0, 1, w, h, info.ores.size)
            layersDone = 0
            finished = null
            computing = true
            PacketDistributor.sendToServer(MapRequest(++seq, x0, z0, 1, w, h, yMin, yMax))
            return
        }
        val bpp = view.unitsPerPx
        val vw = view.view.w.toDouble()
        val vh = view.view.h.toDouble()
        var cell = 2
        while (cell < bpp) cell *= 2
        while (cell < 256 && (vw * bpp / cell + 5) * (vh * bpp / cell + 5) > MAX_CELLS) cell *= 2
        val halfW = vw * bpp / 2 + cell * 2
        val halfH = vh * bpp / 2 + cell * 2
        val x0 = Math.floorDiv(floor(view.cx - halfW).toInt(), cell) * cell
        val z0 = Math.floorDiv(floor(view.cz - halfH).toInt(), cell) * cell
        val w = (ceil(view.cx + halfW).toInt() - x0) / cell + 1
        val h = (ceil(view.cz + halfH).toInt() - z0) / cell + 1
        incoming = Tile(x0, z0, cell, w, h, info.ores.size)
        layersDone = 0
        finished = null
        computing = true
        PacketDistributor.sendToServer(MapRequest(++seq, x0, z0, cell, w, h, yMin, yMax))
    }

    fun onLayer(p: MapLayer) {
        if (p.seq != seq) return
        val target = incoming ?: return
        try {
            if (p.ore < 0) target.province = p.data
            else if (p.ore in info.ores.indices) target.deposits[p.ore] = Sparse.unpack(p.data, target.w * target.h)
        } catch (e: Exception) {
            KamiGeology.LOG.warn("Bad map layer {}", p.ore, e)
            return
        }
        layersDone++
        if (tile == null || layersDone * 2 >= layersExpected) {
            tile = target
            rebuildDue = true
        }
    }

    fun onDone(p: MapDone) {
        if (p.seq != seq) return
        computing = false
        finished = p
        tile = incoming ?: tile
        rebuildDue = true
    }

    fun onProbe(p: ProbeResponse) {
        if (p.seq == probeSeq) probeLines = p.lines
    }

    private fun rebuild() {
        rebuildDue = false
        lastRebuild = System.currentTimeMillis()
        val t = tile ?: return
        val n = t.w * t.h
        val province = t.province
        val sum = FloatArray(n)
        val best = FloatArray(n)
        val dominant = ByteArray(n)

        for (o in info.ores.indices) {
            val ore = info.ores[o]
            val layer = if (deposits) t.deposits[o] else null
            val blanket = if (scatter && ore.scatterColumn > 0f)
                (ore.scatterColumn * Heatmap.scatterShare(ore.scatterLow, ore.scatterHigh, ore.scatterTriangle, yMin, yMax)).toFloat() else 0f
            if (layer == null && blanket <= 0f) {
                totals[o] = 0f
                continue
            }
            val allowed = homes[o]
            val on = enabled[o]
            var total = 0.0
            val tag = (o + 1).toByte()
            for (k in 0 until n) {
                var v = if (layer != null) layer[k] else 0f
                if (blanket > 0f && (allowed == null || (province != null && allowed[province[k].toInt() and 0xFF]))) v += blanket
                total += v
                if (on && v > 0f) {
                    sum[k] += v
                    if (v > best[k]) {
                        best[k] = v
                        dominant[k] = tag
                    }
                }
            }
            totals[o] = (total * t.cell * t.cell).toFloat()
        }

        val floor = max(1, threshold)
        val level = IntArray(n)
        if (mode == Mode.ORES) {
            for (k in 0 until n) if (dominant[k].toInt() != 0) {
                val v = Heatmap.encode(sum[k] * 256.0)
                if (v >= floor) level[k] = v
            }
        }

        val existing = texture?.pixels
        val image = if (existing != null && existing.width == t.w && existing.height == t.h) existing else NativeImage(NativeImage.Format.RGBA, t.w, t.h, false)
        val heat = enabled.count { it } == 1
        val span = max(1, 255 - floor).toDouble()
        val background = Palette.sunken
        for (j in 0 until t.h) for (i in 0 until t.w) {
            val k = j * t.w + i
            var color = background
            val p = if (province != null) (province[k].toInt() and 0xFF) - 1 else -1
            if (p in provinceColors.indices) {
                color = if (mode == Mode.PROVINCES) 0xFF000000.toInt() or provinceColors[p] else if (tint) blend(color, provinceColors[p], 0.10) else color
            }
            val v = level[k]
            if (v > 0) {
                val o = dominant[k].toInt() - 1
                val t01 = ((v - floor) / span).coerceIn(0.0, 1.0)
                var alpha = 0.55 + 0.45 * t01
                if (hoverOre >= 0 && o != hoverOre) alpha *= 0.25
                var ore = if (heat) HeatmapDraw.heatColor(t01) else oreColors[o]
                val edge = (i == 0 || level[k - 1] == 0) || (i == t.w - 1 || level[k + 1] == 0) || (j == 0 || level[k - t.w] == 0) || (j == t.h - 1 || level[k + t.w] == 0)
                if (edge) ore = blend(0xFF000000.toInt() or ore, 0x000000, 0.45) and 0xFFFFFF
                color = blend(color, ore, alpha)
            }
            image.setPixelRGBA(i, j, HeatmapDraw.abgr(color))
        }
        if (image === existing) {
            texture!!.upload()
        } else {
            val created = DynamicTexture(image)
            Minecraft.getInstance().textureManager.register(texId, created)
            created.setFilter(false, false)
            texture = created
        }
    }

    override fun draw(ui: Ui, r: Rect) {
        if (ui.mouseX != mouseX || ui.mouseY != mouseY) {
            mouseX = ui.mouseX
            mouseY = ui.mouseY
            rest = 0
            probeLines = null
        }
        Draw.fill(ui.g, r, Palette.canvas)
        val body = r.dropTop(TOP)
        topBar(ui, r.top(TOP))
        mapArea(ui, body.dropRight(PANEL))
        panel(ui, body.right(PANEL))
        if (!ui.typing) {
            if (ui.input.takeKey(GLFW.GLFW_KEY_R) != null) changed()
            if (ui.input.takeKey(GLFW.GLFW_KEY_G) != null) grid = !grid
        }
    }

    private fun topBar(ui: Ui, r: Rect) {
        val g = ui.g
        Draw.fill(g, r, Palette.surface)
        Draw.hline(g, r.x, r.bottom - 1, r.w, Palette.border)
        val row = Row(r.inset(6, 3, 4, 3), 2)
        val size = SMALL_H
        val lockedReason = tr("kami_geology.map.locked")
        if (ui.iconButton(row.takeFromRight(size), Icons.PENDING, tr("kami_geology.map.redo.tooltip"), key = "redo")) changed()
        row.takeFromRight(4)
        if (ui.iconButton(row.takeFromRight(size), Icons.CROSS, tr("kami_geology.map.none.tooltip"), key = "none")) setAll(false)
        if (ui.iconButton(row.takeFromRight(size), Icons.EYE, tr("kami_geology.map.all.tooltip"), key = "all")) setAll(true)
        row.takeFromRight(4)
        if (ui.iconButton(row.takeFromRight(size), Icons.LOCATE, tr("kami_geology.map.me.tooltip"), !locked, key = "me", disabledReason = lockedReason)) recenter()
        if (ui.iconButton(row.takeFromRight(size), Icons.REMOVE, tr("kami_geology.map.zoom_out.tooltip"), !locked, key = "zoom-out", disabledReason = lockedReason)) zoom(1.25)
        if (ui.iconButton(row.takeFromRight(size), Icons.ADD, tr("kami_geology.map.zoom_in.tooltip"), !locked, key = "zoom-in", disabledReason = lockedReason)) zoom(0.8)
        row.takeFromRight(8)
        val area = row.remaining()
        val textY = area.y + (area.h - 8) / 2
        val titleW = Draw.text(g, title.string, area.x, textY, TextStyle.HEADING) - area.x
        val dimEnd = Draw.text(g, Draw.fit(info.dimension, area.w / 3), area.x + titleW + 8, textY, Palette.textMuted)
        val done = finished
        val t = tile
        if (computing || dirty) {
            val bar = Rect(area.right - 110, area.bottom - 4, 110, 4)
            ui.progress(bar, if (dirty) 0.0 else layersDone / layersExpected.toDouble(), label = tr("kami_geology.map.loading", if (dirty) 0 else layersDone, layersExpected))
        } else if (done != null && t != null) {
            val status = Draw.fit(tr("kami_geology.map.stats", t.cell, t.w, t.h, done.wallMs), area.right - dimEnd - 8)
            val w = Draw.width(status)
            Draw.textRight(g, status, area.right, textY, Palette.textMuted)
            ui.tooltip("stats", Rect(area.right - w, area.y, w, area.h), tr("kami_geology.map.stats.tooltip", done.provinceMs, done.sitesMs, done.paintMs))
        }
    }

    private fun mapArea(ui: Ui, r: Rect) {
        val g = ui.g
        val resized = view.view != r
        if (view.interact(ui, r) || (resized && tile != null)) changed()
        if (locked) view.fit(info.lockX0!!.toDouble(), info.lockZ0!!.toDouble(), info.lockW!!.toDouble(), info.lockH!!.toDouble(), LOCK_FILL)
        val t = tile
        ui.clip(r) {
            Draw.fill(g, r, Palette.sunken)
            if (t != null && texture != null) {
                val scale = (t.cell / view.unitsPerPx).toFloat()
                g.pose().pushPose()
                g.pose().translate(view.screenX(t.x0.toDouble()).toFloat(), view.screenY(t.z0.toDouble()).toFloat(), 0f)
                g.pose().scale(scale, scale, 1f)
                g.blit(texId, 0, 0, t.w, t.h, 0f, 0f, t.w, t.h, t.w, t.h)
                g.pose().popPose()
            }
            if (grid) HeatmapDraw.grid(g, view, r)
            val waiting = when {
                t == null && (computing || dirty) -> tr("kami_geology.map.waiting")
                t == null -> tr("kami_libs.chart.no_data")
                !computing && mode == Mode.ORES && totals.indices.none { enabled[it] && totals[it] > 0f } -> tr("kami_geology.map.empty")
                else -> null
            }
            waiting?.let { Draw.textCentered(g, it, r) }
            HeatmapDraw.scale(g, view, r)
            HeatmapDraw.marker(g, view)
        }
        if (ui.hovering(r) && !view.dragging) {
            val wx = floor(view.worldX(ui.mouseX.toDouble())).toInt()
            val wz = floor(view.worldZ(ui.mouseY.toDouble())).toInt()
            ui.tooltip("cursor", r, delay = 0) {
                val probe = probeLines?.takeIf { rest >= 8 }.orEmpty()
                Tip(tr("kami_geology.map.cursor", wx, wz), (cursorInfo(wx, wz) + probe).map { it to Palette.textSecondary })
            }
        }
    }

    private fun cursorInfo(wx: Int, wz: Int): List<String> {
        val t = tile ?: return emptyList()
        val i = Math.floorDiv(wx - t.x0, t.cell)
        val j = Math.floorDiv(wz - t.z0, t.cell)
        if (i !in 0 until t.w || j !in 0 until t.h) return emptyList()
        val k = j * t.w + i
        val parts = ArrayList<String>()
        val province = t.province?.let { (it[k].toInt() and 0xFF) - 1 } ?: -1
        if (province in info.provinces.indices) parts += GeoText.provinceLabel(info.provinces[province].name)
        info.ores.indices
            .map { o -> o to ((if (deposits) t.deposits[o]?.get(k) ?: 0f else 0f)) }
            .filter { it.second > 0f && enabled[it.first] }
            .sortedByDescending { it.second }
            .take(3)
            .forEach { parts += tr("kami_geology.map.per_chunk", GeoText.oreLabel(info.ores[it.first].id), short(it.second * 256.0)) }
        return parts
    }

    private fun panel(ui: Ui, r: Rect) {
        Draw.fill(ui.g, r, Palette.surface)
        Draw.vline(ui.g, r.x, r.y, r.h, Palette.border)
        val flow = Flow(r.inset(5, 4, 4, 4), 4)
        layerCard(ui, flow.take(LAYER_H))
        filterCard(ui, flow.take(FILTER_H))
        oresCard(ui, flow.remaining())
    }

    private fun layerCard(ui: Ui, r: Rect) {
        val c = ui.card(r, tr("kami_geology.map.layer"), Icons.LAYERS)
        val seg = c.top(14)
        ui.segmented(seg, Mode.entries.map { Option(it, it.label) }, mode, key = "mode")?.let {
            mode = it
            recompose()
        }
        ui.tooltip("mode-tip", seg, tr("kami_geology.map.layer.tooltip"))
        val cells = c.dropTop(17).grid(2, 12, 4, 2)
        fun check(i: Int, name: String, value: Boolean, recolor: Boolean = true, set: (Boolean) -> Unit) {
            ui.checkbox(cells[i], tr("kami_geology.map.$name"), value, tip = tr("kami_geology.map.$name.tooltip"), key = name)?.let {
                set(it)
                if (recolor) recompose()
            }
        }
        check(0, "deposits", deposits) { deposits = it }
        check(1, "scatter", scatter) { scatter = it }
        check(2, "tint", tint) { tint = it }
        check(3, "grid", grid, recolor = false) { grid = it }
    }

    private fun filterCard(ui: Ui, r: Rect) {
        val rows = ui.card(r, tr("kami_geology.map.filter"), Icons.FILTER).rows(3, 3)
        val labelW = listOf("y_min", "y_max", "threshold").maxOf { Draw.width(tr("kami_geology.map.$it")) }.coerceIn(30, 56)
        fun line(i: Int, name: String, value: Int, lo: Int, hi: Int, format: (Int) -> String): Int? {
            val row = Row(rows[i], 4)
            Draw.text(ui.g, Draw.fit(tr("kami_geology.map.$name"), labelW), row.take(labelW).x, rows[i].y + 2, Palette.textSecondary)
            Draw.textRight(ui.g, format(value), row.takeFromRight(30).right, rows[i].y + 2, Palette.text)
            return ui.slider(row.remaining(), value.toDouble(), lo.toDouble(), hi.toDouble(), 1.0, format = { format(it.roundToInt()) }, tip = tr("kami_geology.map.$name.tooltip"), key = name)?.roundToInt()
        }
        line(0, "y_min", yMin, info.minY, info.maxY, Int::toString)?.let {
            yMin = min(it, yMax)
            changed()
            recompose()
        }
        line(1, "y_max", yMax, info.minY, info.maxY, Int::toString)?.let {
            yMax = max(it, yMin)
            changed()
            recompose()
        }
        line(2, "threshold", threshold, 0, 255) { if (it == 0) tr("kami_geology.map.off") else short(Heatmap.decode(it)) }?.let {
            threshold = it
            recompose()
        }
    }

    private fun oresCard(ui: Ui, r: Rect) {
        val c = ui.card(r, tr("kami_geology.map.mode.ores"), Icons.PICKAXE, trailing = "${enabled.count { it }}/${enabled.size}")
        val list = if (mode == Mode.ORES) c.dropBottom(LEGEND_H, 4) else c
        if (mode == Mode.ORES) legend(ui, c.bottom(LEGEND_H))
        var hovered = -1
        ui.scroll("ores", list, info.ores.size * ROW) { content ->
            info.ores.forEachIndexed { i, ore ->
                val row = Rect(content.x, content.y + i * ROW, content.w, ROW)
                if (row.bottom < list.y || row.y > list.bottom) return@forEachIndexed
                val over = list.contains(ui.mouseX, ui.mouseY) && ui.hovering(row)
                if (over) {
                    Draw.fill(ui.g, row, Palette.hover)
                    if (enabled[i]) hovered = i
                    ui.tooltip("ore:$i", row, tr("kami_geology.map.ore.tooltip"))
                    if (ui.pressed(row) != null) toggleOre(i, ui.input.shift || ui.input.ctrl)
                }
                Draw.box(ui.g, Rect(row.x + 2, row.y + 1, 8, 8), if (enabled[i]) Palette.opaque(oreColors[i]) else Palette.raised, 0xFF000000.toInt())
                val total = if (enabled[i] && totals[i] > 0f) short(totals[i].toDouble()) else ""
                Draw.textRight(ui.g, total, row.right - 2, row.y + 1, Palette.textMuted)
                val name = Draw.fit(GeoText.oreLabel(ore.id), row.w - 16 - Draw.width(total) - 4)
                Draw.text(ui.g, name, row.x + 14, row.y + 1, if (enabled[i]) Palette.text else Palette.textMuted)
            }
        }
        if (hovered != hoverOre) {
            hoverOre = hovered
            recompose()
        }
    }

    private fun toggleOre(i: Int, solo: Boolean) {
        if (solo) {
            enabled.fill(false)
            enabled[i] = true
        } else {
            enabled[i] = !enabled[i]
        }
        recompose()
    }

    private fun legend(ui: Ui, r: Rect) {
        val single = enabled.count { it } == 1
        val floor = max(1, threshold)
        val colors = if (single) HeatmapDraw.ramp.map { Palette.opaque(it) }
        else listOf(0.55, 1.0).map { blend(Palette.sunken, HeatmapDraw.BRIGHT, it) }
        ui.gradientLegend(
            r, colors, floor.toDouble(), 255.0,
            format = { short(Heatmap.decode(it.roundToInt())) },
            ticks = MARKS.map { Heatmap.encode(it.toDouble()) }.filter { it >= floor }.map { it.toDouble() },
            title = tr(if (single) "kami_geology.map.legend" else "kami_geology.map.legend.brightness"),
            key = "legend"
        )
    }
}
