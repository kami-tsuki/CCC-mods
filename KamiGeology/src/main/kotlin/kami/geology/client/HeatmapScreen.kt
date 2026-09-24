package kami.geology.client

import com.mojang.blaze3d.platform.NativeImage
import kami.geology.KamiGeology
import kami.geology.map.Heatmap
import kami.geology.map.Sparse
import kami.geology.net.MapDone
import kami.geology.net.MapLayer
import kami.geology.net.MapRequest
import kami.geology.net.OpenMap
import kami.geology.net.ProbeRequest
import kami.geology.net.ProbeResponse
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
import java.util.Optional
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val SCREEN_BG = 0xFF0B0E13.toInt()
private val PANEL_BG = 0xF00E1319.toInt()
private val BAR_BG = 0xFF10151C.toInt()
private val MAP_BG = 0xFF0F1318.toInt()
private val GRID = 0x30FFFFFF
private val AXIS = 0x70FFFFFF
private val TEXT = kami.libs.ui.Theme.TEXT
private val MUTED = kami.libs.ui.Theme.DIM
private val ACCENT = kami.libs.ui.Theme.ACCENT

private const val PANEL = 150
private const val TOP = 20
private const val BOTTOM = 46
private const val ROW = 11
private const val MAX_CELLS = 150_000
private const val MIN_BPP = 0.5
private const val MAX_BPP = 12.0
private const val DEBOUNCE_MS = 120L
private const val REBUILD_MS = 90L
private const val DEFAULT_THRESHOLD = 80

class HeatmapScreen(private val info: OpenMap) : Screen(Component.literal("Geology Map")) {
    private class Tile(val x0: Int, val z0: Int, val cell: Int, val w: Int, val h: Int, ores: Int) {
        var province: ByteArray? = null
        val deposits = arrayOfNulls<FloatArray>(ores)
    }

    private enum class Mode(val label: String) { ORES("Ores"), PROVINCES("Provinces") }

    private class Slider(
        x: Int, y: Int, w: Int, private val label: String, private val lo: Int, private val hi: Int, initial: Int,
        private val format: (Int) -> String, private val onChange: (Int) -> Unit
    ) : AbstractSliderButton(x, y, w, 14, Component.empty(), (initial - lo).toDouble() / max(1, hi - lo)) {
        init {
            updateMessage()
        }

        val current: Int get() = lo + (value * (hi - lo)).roundToInt()

        fun set(v: Int) {
            value = ((v - lo).toDouble() / max(1, hi - lo)).coerceIn(0.0, 1.0)
            updateMessage()
        }

        override fun updateMessage() {
            message = Component.literal("$label ${format(current)}")
        }

        override fun applyValue() = onChange(current)
    }

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

    private var centerX = info.x.toDouble()
    private var centerZ = info.z.toDouble()
    private var bpp = 4.0
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
    private var listScroll = 0
    private var dragging = false
    private var hoverOre = -1

    private var mouseX = 0
    private var mouseY = 0
    private var rest = 0
    private var probeSeq = 0
    private var probeLines: List<String>? = null

    private lateinit var minSlider: Slider
    private lateinit var maxSlider: Slider

    private val mapX0 get() = PANEL
    private val mapX1 get() = width
    private val mapY0 get() = TOP
    private val mapY1 get() = height - BOTTOM
    private val mapCx get() = (mapX0 + mapX1) / 2.0
    private val mapCy get() = (mapY0 + mapY1) / 2.0
    private val listTop get() = TOP + 24

    private fun worldX(sx: Double) = centerX + (sx - mapCx) * bpp
    private fun worldZ(sy: Double) = centerZ + (sy - mapCy) * bpp
    private fun screenX(wx: Double) = mapCx + (wx - centerX) / bpp
    private fun screenY(wz: Double) = mapCy + (wz - centerZ) / bpp
    private fun inMap(x: Double, y: Double) = x >= mapX0 && x < mapX1 && y >= mapY0 && y < mapY1

    override fun isPauseScreen() = false

    override fun renderBackground(g: GuiGraphics, mx: Int, my: Int, delta: Float) {}

    override fun init() {
        if (locked) {
            val w = info.lockW!!
            val h = info.lockH!!
            centerX = info.lockX0!! + w / 2.0
            centerZ = info.lockZ0!! + h / 2.0
            bpp = (max(w, h).toDouble() / min(mapX1 - mapX0, mapY1 - mapY0)).coerceIn(MIN_BPP, MAX_BPP)
        } else {
            addRenderableWidget(Button.builder(Component.literal("All")) { setAll(true) }.bounds(4, TOP + 4, 68, 14).build())
            addRenderableWidget(Button.builder(Component.literal("None")) { setAll(false) }.bounds(76, TOP + 4, 70, 14).build())
        }

        val by = height - BOTTOM + 5
        val sx = PANEL + 6
        val each = ((width - sx - 18) / 3).coerceIn(60, 150)
        minSlider = Slider(sx, by, each, "Y min", info.minY, info.maxY, yMin, { it.toString() }) {
            if (it > yMax) minSlider.set(yMax) else yMin = it
            changed()
            recompose()
        }
        maxSlider = Slider(sx + each + 6, by, each, "Y max", info.minY, info.maxY, yMax, { it.toString() }) {
            if (it < yMin) maxSlider.set(yMin) else yMax = it
            changed()
            recompose()
        }
        addRenderableWidget(minSlider)
        addRenderableWidget(maxSlider)
        addRenderableWidget(
            Slider(sx + (each + 6) * 2, by, each, "Min", 0, 255, threshold, { if (it == 0) "off" else short(Heatmap.decode(it).toFloat()) }) {
                threshold = it
                recompose()
            }
        )

        var bx = sx
        fun add(label: () -> String, w: Int, action: (Button) -> Unit) {
            addRenderableWidget(Button.builder(Component.literal(label())) { b ->
                action(b)
                b.message = Component.literal(label())
            }.bounds(bx, by + 20, w, 16).build())
            bx += w + 3
        }
        add({ "Layer: ${mode.label}" }, 84) {
            mode = if (mode == Mode.ORES) Mode.PROVINCES else Mode.ORES
            recompose()
        }
        add({ "Deposits ${if (deposits) "on" else "off"}" }, 66) {
            deposits = !deposits
            recompose()
        }
        add({ "Scatter ${if (scatter) "on" else "off"}" }, 64) {
            scatter = !scatter
            recompose()
        }
        add({ "Grid ${if (grid) "on" else "off"}" }, 50) { grid = !grid }
        add({ "Tint ${if (tint) "on" else "off"}" }, 50) {
            tint = !tint
            recompose()
        }
        if (!locked) {
            add({ "Me" }, 26) {
                Minecraft.getInstance().player?.let {
                    centerX = it.x
                    centerZ = it.z
                }
                changed()
            }
        }
        add({ "Redo" }, 34) { changed() }
    }

    override fun removed() {
        Minecraft.getInstance().textureManager.release(texId)
        texture = null
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

    override fun tick() {
        val now = System.currentTimeMillis()
        if (dirty && now - changedAt >= DEBOUNCE_MS) {
            dirty = false
            sendRequest()
        }
        if (rebuildDue && now - lastRebuild >= REBUILD_MS) rebuild()
        rest++
        if (rest == 8 && inMap(mouseX.toDouble(), mouseY.toDouble()) && !dragging) {
            PacketDistributor.sendToServer(
                ProbeRequest(++probeSeq, floor(worldX(mouseX.toDouble())).toInt(), floor(worldZ(mouseY.toDouble())).toInt(), yMin, yMax)
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
        val vw = (mapX1 - mapX0).toDouble()
        val vh = (mapY1 - mapY0).toDouble()
        var cell = 2
        while (cell < bpp) cell *= 2
        while (cell < 256 && (vw * bpp / cell + 5) * (vh * bpp / cell + 5) > MAX_CELLS) cell *= 2
        val halfW = vw * bpp / 2 + cell * 2
        val halfH = vh * bpp / 2 + cell * 2
        val x0 = Math.floorDiv(floor(centerX - halfW).toInt(), cell) * cell
        val z0 = Math.floorDiv(floor(centerZ - halfH).toInt(), cell) * cell
        val w = (ceil(centerX + halfW).toInt() - x0) / cell + 1
        val h = (ceil(centerZ + halfH).toInt() - z0) / cell + 1
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
        for (j in 0 until t.h) for (i in 0 until t.w) {
            val k = j * t.w + i
            var color = MAP_BG
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
                var ore = if (heat) heatColor(t01) else oreColors[o]
                val edge = (i == 0 || level[k - 1] == 0) || (i == t.w - 1 || level[k + 1] == 0) || (j == 0 || level[k - t.w] == 0) || (j == t.h - 1 || level[k + t.w] == 0)
                if (edge) ore = blend(0xFF000000.toInt() or ore, 0x000000, 0.45) and 0xFFFFFF
                color = blend(color, ore, alpha)
            }
            image.setPixelRGBA(i, j, abgr(color))
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

    private fun abgr(rgb: Int) = 0xFF shl 24 or ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)

    private fun blend(base: Int, top: Int, t: Double): Int {
        fun mix(shift: Int) = (((base shr shift) and 0xFF) * (1 - t) + ((top shr shift) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private val ramp = intArrayOf(0x440154, 0x3B528B, 0x21918C, 0x5EC962, 0xFDE725)

    private fun heatColor(t: Double): Int {
        val scaled = t.coerceIn(0.0, 1.0) * (ramp.size - 1)
        val i = min(scaled.toInt(), ramp.size - 2)
        return blend(0xFF000000.toInt() or ramp[i], ramp[i + 1], scaled - i) and 0xFFFFFF
    }

    override fun render(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
        if (mx != mouseX || my != mouseY) {
            mouseX = mx
            mouseY = my
            rest = 0
            probeLines = null
        }
        val row = if (mx < PANEL && my >= listTop) (my - listTop + listScroll) / ROW else -1
        val hovered = if (row in enabled.indices && enabled[row]) row else -1
        if (hovered != hoverOre) {
            hoverOre = hovered
            recompose()
        }
        g.fill(0, 0, width, height, SCREEN_BG)
        drawMap(g)
        drawPanel(g, mx, my)
        drawBars(g, mx, my)
        super.render(g, mx, my, delta)
        drawLegend(g)
        drawProgress(g)
        val lines = probeLines
        if (lines != null && rest >= 8 && !dragging && inMap(mx.toDouble(), my.toDouble())) {
            g.renderTooltip(font, lines.map { Component.literal(it) }, Optional.empty(), mx, my)
        }
    }

    private fun drawMap(g: GuiGraphics) {
        g.enableScissor(mapX0, mapY0, mapX1, mapY1)
        g.fill(mapX0, mapY0, mapX1, mapY1, MAP_BG)
        val t = tile
        if (t != null && texture != null) {
            val scale = (t.cell / bpp).toFloat()
            g.pose().pushPose()
            g.pose().translate(screenX(t.x0.toDouble()).toFloat(), screenY(t.z0.toDouble()).toFloat(), 0f)
            g.pose().scale(scale, scale, 1f)
            g.blit(texId, 0, 0, t.w, t.h, 0f, 0f, t.w, t.h, t.w, t.h)
            g.pose().popPose()
        }
        if (grid) drawGrid(g)
        val waiting = when {
            t == null && (computing || dirty) -> "Waiting for the server..."
            t == null -> "No data yet"
            !computing && mode == Mode.ORES && totals.indices.none { enabled[it] && totals[it] > 0f } -> "No ore in view for these filters"
            else -> null
        }
        if (waiting != null) g.drawCenteredString(font, waiting, (mapX0 + mapX1) / 2, (mapY0 + mapY1) / 2, TEXT)
        drawScale(g)
        drawMarker(g)
        g.disableScissor()
    }

    private fun gridStep(): Int = intArrayOf(16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384).firstOrNull { it / bpp >= 80 } ?: 16384

    private fun drawGrid(g: GuiGraphics) {
        val step = gridStep()
        var gx = Math.floorDiv(floor(worldX(mapX0.toDouble())).toInt(), step) * step
        while (gx <= worldX(mapX1.toDouble())) {
            val sx = screenX(gx.toDouble()).roundToInt()
            g.fill(sx, mapY0, sx + 1, mapY1, if (gx == 0) AXIS else GRID)
            g.drawString(font, gx.toString(), sx + 3, mapY0 + 3, MUTED, false)
            gx += step
        }
        var gz = Math.floorDiv(floor(worldZ(mapY0.toDouble())).toInt(), step) * step
        while (gz <= worldZ(mapY1.toDouble())) {
            val sy = screenY(gz.toDouble()).roundToInt()
            g.fill(mapX0, sy, mapX1, sy + 1, if (gz == 0) AXIS else GRID)
            g.drawString(font, gz.toString(), mapX0 + 3, sy + 3, MUTED, false)
            gz += step
        }
    }

    private fun drawScale(g: GuiGraphics) {
        val length = intArrayOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000).lastOrNull { it / bpp <= 140 } ?: 10
        val px = max(2, (length / bpp).roundToInt())
        val x = mapX0 + 10
        val y = mapY1 - 12
        g.fill(x - 1, y - 14, x + px + 1, y + 4, 0x80000000.toInt())
        g.fill(x, y, x + px, y + 1, TEXT)
        g.fill(x, y - 3, x + 1, y + 1, TEXT)
        g.fill(x + px - 1, y - 3, x + px, y + 1, TEXT)
        g.drawString(font, "$length blocks", x + 2, y - 12, TEXT, false)
        g.drawString(font, "N", mapX1 - 14, mapY0 + 14, TEXT, false)
        g.fill(mapX1 - 11, mapY0 + 24, mapX1 - 10, mapY0 + 40, TEXT)
    }

    private fun drawMarker(g: GuiGraphics) {
        val player = Minecraft.getInstance().player ?: return
        val sx = screenX(player.x).roundToInt()
        val sy = screenY(player.z).roundToInt()
        g.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFF000000.toInt())
        g.fill(sx - 2, sy - 2, sx + 3, sy + 3, 0xFFFFFFFF.toInt())
        g.drawString(font, "You", sx + 6, sy - 4, TEXT, true)
    }

    private fun drawPanel(g: GuiGraphics, mx: Int, my: Int) {
        g.fill(0, TOP, PANEL, height, PANEL_BG)
        g.fill(PANEL - 1, TOP, PANEL, height, GRID)
        g.enableScissor(0, listTop, PANEL, height - 4)
        info.ores.forEachIndexed { i, ore ->
            val y = listTop + i * ROW - listScroll
            if (y + ROW < listTop || y > height) return@forEachIndexed
            if (mx < PANEL && my >= y && my < y + ROW) g.fill(2, y, PANEL - 2, y + ROW, 0x20FFFFFF)
            g.fill(6, y + 1, 14, y + 9, 0xFF000000.toInt())
            g.fill(7, y + 2, 13, y + 8, if (enabled[i]) 0xFF000000.toInt() or oreColors[i] else 0xFF1E242D.toInt())
            g.drawString(font, ore.id, 19, y + 1, if (enabled[i]) TEXT else MUTED, false)
            if (enabled[i] && totals[i] > 0f) {
                val text = short(totals[i])
                g.drawString(font, text, PANEL - 5 - font.width(text), y + 1, MUTED, false)
            }
        }
        g.disableScissor()
    }

    private fun drawBars(g: GuiGraphics, mx: Int, my: Int) {
        g.fill(0, 0, width, TOP, BAR_BG)
        g.fill(0, TOP - 1, width, TOP, GRID)
        g.drawString(font, "Geology Map", 6, 6, ACCENT, false)
        g.drawString(font, info.dimension, 6 + font.width("Geology Map") + 8, 6, MUTED, false)
        val done = finished
        val status = when {
            computing || dirty -> "Loading $layersDone/$layersExpected"
            done != null && tile != null -> "cell ${tile!!.cell}b  ${tile!!.w}x${tile!!.h}  ${done.wallMs}ms  (cpu: biomes ${done.provinceMs} sites ${done.sitesMs} paint ${done.paintMs})"
            else -> ""
        }
        g.drawString(font, status, width - 6 - font.width(status), 6, MUTED, false)

        g.fill(PANEL, height - BOTTOM, width, height, BAR_BG)
        g.fill(PANEL, height - BOTTOM, width, height - BOTTOM + 1, GRID)
        if (inMap(mx.toDouble(), my.toDouble())) {
            val wx = floor(worldX(mx.toDouble())).toInt()
            val wz = floor(worldZ(my.toDouble())).toInt()
            g.drawString(font, "X $wx  Z $wz  ${cursorInfo(wx, wz)}", PANEL + 6, height - BOTTOM - 11, TEXT, true)
        }
    }

    private fun drawProgress(g: GuiGraphics) {
        if (!computing && !dirty) return
        val fraction = if (dirty) 0.0 else layersDone / layersExpected.toDouble()
        g.fill(0, TOP - 3, width, TOP - 1, 0xFF1E242D.toInt())
        g.fill(0, TOP - 3, (width * fraction).toInt(), TOP - 1, ACCENT)
        val dots = ".".repeat(((System.currentTimeMillis() / 300) % 4).toInt())
        val label = "Loading ${if (dirty) 0 else layersDone}/$layersExpected$dots"
        val w = font.width("Loading 00/00...") + 12
        val x = mapX0 + (mapX1 - mapX0 - w) / 2
        g.fill(x, mapY0 + 6, x + w, mapY0 + 20, 0xC0000000.toInt())
        g.drawString(font, label, x + 6, mapY0 + 9, TEXT, false)
    }

    private fun cursorInfo(wx: Int, wz: Int): String {
        val t = tile ?: return ""
        val i = Math.floorDiv(wx - t.x0, t.cell)
        val j = Math.floorDiv(wz - t.z0, t.cell)
        if (i !in 0 until t.w || j !in 0 until t.h) return ""
        val k = j * t.w + i
        val parts = ArrayList<String>()
        val province = t.province?.let { (it[k].toInt() and 0xFF) - 1 } ?: -1
        if (province in info.provinces.indices) parts += info.provinces[province].name
        val ranked = info.ores.indices
            .map { o -> o to ((if (deposits) t.deposits[o]?.get(k) ?: 0f else 0f)) }
            .filter { it.second > 0f && enabled[it.first] }
            .sortedByDescending { it.second }
            .take(3)
        if (ranked.isNotEmpty()) parts += ranked.joinToString(" ") { "${info.ores[it.first].id} ~${short(it.second * 256f)}/chunk" }
        return parts.joinToString("  ")
    }

    private fun drawLegend(g: GuiGraphics) {
        if (mode != Mode.ORES) return
        val single = enabled.count { it } == 1
        val floor = max(1, threshold)
        val w = 132
        val x = mapX1 - w - 10
        val y = mapY1 - 34
        g.fill(x - 6, y - 12, x + w + 6, y + 26, 0xB0000000.toInt())
        g.drawString(font, if (single) "ore blocks per chunk" else "brightness = ore blocks per chunk", x, y - 9, MUTED, false)
        for (px in 0 until w) {
            val v = px / (w - 1.0)
            g.fill(x + px, y + 2, x + px + 1, y + 10, 0xFF000000.toInt() or if (single) heatColor(v) else blend(MAP_BG, 0xC0C8D0, 0.55 + 0.45 * v))
        }
        for (mark in intArrayOf(1, 10, 100, 1000, 10000)) {
            val level = Heatmap.encode(mark.toDouble())
            if (level < floor) continue
            val px = ((level - floor) / max(1, 255 - floor).toDouble() * (w - 1)).roundToInt()
            g.fill(x + px, y + 10, x + px + 1, y + 13, TEXT)
            val label = if (mark >= 1000) "${mark / 1000}k" else "$mark"
            g.drawString(font, label, x + px - font.width(label) / 2, y + 14, MUTED, false)
        }
    }

    private fun short(v: Float) = when {
        v >= 1_000_000f -> "%.1fM".format(v / 1_000_000f)
        v >= 1_000f -> "%.1fk".format(v / 1_000f)
        else -> "%.0f".format(v)
    }

    override fun mouseClicked(mx: Double, my: Double, button: Int): Boolean {
        if (super.mouseClicked(mx, my, button)) return true
        if (button == 0 && mx < PANEL && my >= listTop) {
            val row = ((my - listTop + listScroll) / ROW).toInt()
            if (row in enabled.indices) {
                if (hasShiftDown() || hasControlDown()) {
                    enabled.fill(false)
                    enabled[row] = true
                } else {
                    enabled[row] = !enabled[row]
                }
                recompose()
                return true
            }
        }
        if (button == 0 && inMap(mx, my)) {
            dragging = true
            return true
        }
        return false
    }

    override fun mouseDragged(mx: Double, my: Double, button: Int, dx: Double, dy: Double): Boolean {
        if (dragging && !locked) {
            centerX -= dx * bpp
            centerZ -= dy * bpp
            changed()
            return true
        }
        return super.mouseDragged(mx, my, button, dx, dy)
    }

    override fun mouseReleased(mx: Double, my: Double, button: Int): Boolean {
        dragging = false
        return super.mouseReleased(mx, my, button)
    }

    override fun mouseScrolled(mx: Double, my: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mx < PANEL) {
            val limit = max(0, info.ores.size * ROW - (height - listTop - 6))
            listScroll = (listScroll - (scrollY * 22).roundToInt()).coerceIn(0, limit)
            return true
        }
        if (!inMap(mx, my)) return false
        zoom(if (scrollY > 0) 0.8 else 1.25, mx, my)
        return true
    }

    private fun zoom(factor: Double, mx: Double, my: Double) {
        val wx = worldX(mx)
        val wz = worldZ(my)
        bpp = (bpp * factor).coerceIn(MIN_BPP, MAX_BPP)
        centerX = wx - (mx - mapCx) * bpp
        centerZ = wz - (my - mapCy) * bpp
        changed()
    }

    override fun keyPressed(key: Int, scan: Int, mods: Int): Boolean {
        if (focused !is AbstractSliderButton) {
            val step = 60 * bpp
            when (key) {
                GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> if (!locked) centerX -= step
                GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> if (!locked) centerX += step
                GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> if (!locked) centerZ -= step
                GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> if (!locked) centerZ += step
                GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> zoom(0.8, mapCx, mapCy)
                GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> zoom(1.25, mapCx, mapCy)
                GLFW.GLFW_KEY_R -> {}
                GLFW.GLFW_KEY_G -> grid = !grid
                else -> return super.keyPressed(key, scan, mods)
            }
            changed()
            return true
        }
        return super.keyPressed(key, scan, mods)
    }
}
