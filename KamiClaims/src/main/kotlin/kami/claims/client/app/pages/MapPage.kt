package kami.claims.client.app.pages

import kami.claims.client.ClientClaims
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Flags
import kami.claims.client.app.Vocabulary
import kami.claims.client.map.ClaimsLayer
import kami.claims.client.map.MapMode
import kami.claims.client.map.MiniMap
import kami.claims.client.store.ClaimsStore
import kami.claims.net.PreviewLine
import kami.claims.service.View
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.map.ChunkMap
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.*
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MapPage(app: ClaimsApp) : ClaimsPage(app) {
    enum class Tool(val label: String, val key: Int, val tip: String) {
        SELECT("Select", GLFW.GLFW_KEY_Q, "Click a chunk to see it. Ctrl+click adds more chunks."),
        AREA("Area", GLFW.GLFW_KEY_E, "Drag a rectangle to select many chunks."),
        BRUSH("Brush", GLFW.GLFW_KEY_B, "Paint chunks while holding the mouse. Shift erases."),
        MEASURE("Measure", GLFW.GLFW_KEY_R, "Click two points to measure the distance.")
    }

    enum class Plan(val label: String) { CLAIM("Claim"), RETYPE("Change type"), UNCLAIM("Release") }

    override val title = "Map"
    override val needsCountry = false
    override val subtitle: String? get() = if (ClientClaims.active(dim)) null else "claims are off in this dimension"
    override val help = listOf(
        Callout("map:tools", "Tools", "Select, area, brush, or measure. Keys Q/E/B/R."),
        Callout("map:mode", "Modes", "Political, relation, land use, economy, risk, plots. Keys 1-7."),
        Callout("map:view", "Map", "RMB drag to pan, wheel to zoom."),
        Callout("map:panel", "Planner", "Selection details, cost preview, and commit.")
    )

    private val map = ChunkMap(12f)
    private var tool = Tool.SELECT
    private var plan = Plan.CLAIM
    private var planType = ""
    private val selection = LinkedHashSet<Long>()
    private var planning = false
    private var measureA: Pair<Int, Int>? = null
    private var measureB: Pair<Int, Int>? = null
    private var centered = false
    private var lastPreviewSent = 0L
    private var previewWaitSince = 0L
    private var selectionTouchedAt = 0L
    private var legendOpen = true
    private var layersOpen = false
    private val jump = TextState()

    private val dim get() = MiniMap.dim()
    private val mode get() = MapMode.of(ClientClaims.prefs.mapMode)
    private fun previewKind() = when (plan) { Plan.CLAIM -> "claim"; Plan.RETYPE -> "type"; Plan.UNCLAIM -> "unclaim" }

    private fun key(x: Int, z: Int) = ClientClaims.key(x, z)
    private fun unkey(k: Long) = (k shr 32).toInt() to k.toInt()

    private fun touchSelection() {
        selectionTouchedAt = System.currentTimeMillis()
        previewWaitSince = 0L
    }

    override fun opened(route: Route) {
        val x = route.int("x")
        val z = route.int("z")
        if (x != null && z != null) {
            map.center(x, z)
            centered = true
            if (route.param("select") != "false") select(x, z, false)
        }
    }

    private fun select(x: Int, z: Int, add: Boolean) {
        if (!add) selection.clear()
        val k = key(x, z)
        val changed = if (add && k in selection) selection.remove(k) else selection.add(k)
        if (changed) touchSelection()
        if (selection.size == 1) ClaimsStore.quiet("chunk", x.toString(), z.toString())
        planning = selection.size > 1
        choosePlan()
    }

    private fun choosePlan() {
        val entries = selection.map { unkey(it) }.map { (x, z) -> ClientClaims.at(dim, x, z) }
        val own = entries.count { it != null && it.flags and View.OWN != 0 }
        plan = when {
            entries.any { it == null } -> Plan.CLAIM
            own > 0 && plan == Plan.CLAIM -> Plan.RETYPE
            else -> plan
        }
    }

    override fun actions(ui: Ui, r: Rect) {
        val w = r.w
        if (w < 60) return
        if (ui.iconButton(Rect(r.right - 20, r.y + 1, 18, 18), Icons.LOCATE, "Center on you [C]", key = "map-me")) map.center(snap.px, snap.pz)
        val capital = info?.claimList?.firstOrNull { it.capital }
        if (ui.iconButton(Rect(r.right - 40, r.y + 1, 18, 18), Icons.CROWN, "Center capital", capital != null, key = "map-capital", disabledReason = "No capital")) capital?.let { map.center(it.x, it.z) }
    }

    override fun draw(ui: Ui, r: Rect) {
        if (!centered) { map.center(snap.px, snap.pz); centered = true }
        if (planType.isEmpty()) planType = snap.types.firstOrNull()?.name ?: ""
        val bar = r.top(20)
        toolbar(ui, bar)
        val body = r.dropTop(20, 4)
        val panelW = if (app.compact) 150 else (body.w / 3).coerceIn(170, 230)
        val view = body.dropRight(panelW, 6).dropBottom(12, 2)
        val status = Rect(view.x, view.bottom + 3, view.w, 10)
        val panel = body.right(panelW)
        ui.anchor("map:view", view)
        ui.anchor("map:panel", panel)
        mapView(ui, view, status)
        side(ui, panel)
        requestPreview()
        keys(ui)
    }

    private fun toolbar(ui: Ui, r: Rect) {
        val tools = r.left(4 * 26)
        ui.anchor("map:tools", tools)
        Tool.entries.forEachIndexed { i, t ->
            val icon = when (t) { Tool.SELECT -> Icons.CURSOR; Tool.AREA -> Icons.AREA; Tool.BRUSH -> Icons.BRUSH; Tool.MEASURE -> Icons.RULER }
            val keyName = GLFW.glfwGetKeyName(t.key, 0)?.uppercase() ?: ""
            if (ui.iconButton(Rect(tools.x + i * 26, r.y, 24, 20), icon, "${t.label}  [$keyName]\n${t.tip}", selected = tool == t, key = "tool:${t.name}")) switchTool(t)
        }
        val modeRect = Rect(tools.right + 8, r.y, 130, 20)
        ui.anchor("map:mode", modeRect)
        ui.select(modeRect, MapMode.entries.mapIndexed { i, m -> Option(m, "${m.label}", m.icon, "${m.description}  [${i + 1}]") }, mode, key = "mode")?.let { setMode(it) }
        val layers = Rect(modeRect.right + 4, r.y, 22, 20)
        if (ui.iconButton(layers, Icons.LAYERS, "Map layers", selected = layersOpen, key = "layers")) layersOpen = !layersOpen
        if (layersOpen) layerPopover(ui, layers)
        val jumpRect = Rect(layers.right + 6, r.y, (r.right - layers.right - 6).coerceAtMost(130), 20)
        if (jumpRect.w >= 70) {
            val res = ui.textField(jumpRect, jump, "Go to x z", Icons.SEARCH, key = "jump", clearable = true)
            if (res.submitted) {
                val nums = jump.text.split(' ', ',', ';').mapNotNull { it.trim().toIntOrNull() }
                if (nums.size >= 2) {
                    val (x, z) = if (abs(nums[0]) > 3000 || abs(nums[1]) > 3000 || jump.text.contains("b")) Math.floorDiv(nums[0], 16) to Math.floorDiv(nums[1], 16) else nums[0] to nums[1]
                    map.center(x, z); select(x, z, false)
                }
            }
            ui.tooltip("jump-tip", jumpRect, "Use chunk coords like 12 -4. Large values are treated as block coords.")
        }
    }

    private fun layerPopover(ui: Ui, anchor: Rect) {
        ui.onEscape(55) { layersOpen = false }
        ui.overlay {
            val items = listOf("borders" to "Borders", "labels" to "Country names", "markers" to "Markers (capital, plots)", "grid" to "Chunk grid")
            val box = Rect(anchor.x, anchor.bottom + 2, 170, items.size * 16 + 38)
            ui.block(box)
            Draw.shadow(ui.g, box, 2)
            Draw.sprite(ui.g, Sprites.POPOVER, box)
            var y = box.y + 5
            items.forEach { (k, label) ->
                ui.toggle(Rect(box.x + 6, y, box.w - 12, 14), k in ClientClaims.prefs.mapLayers, label, key = "layer:$k")?.let { on ->
                    if (on) ClientClaims.prefs.mapLayers += k else ClientClaims.prefs.mapLayers -= k
                    ClientClaims.savePrefs()
                }
                y += 16
            }
            ui.toggle(Rect(box.x + 6, y, box.w - 12, 14), ClientClaims.prefs.terrain, "Terrain", tip = "Colours from the chunks you explored.", key = "layer:terrain")?.let {
                ClientClaims.prefs.terrain = it; kami.libs.ui.map.TerrainCache.enabled = it; ClientClaims.savePrefs()
            }
            y += 16
            ui.toggle(Rect(box.x + 6, y, box.w - 12, 14), ClientClaims.prefs.overlay, "Show on Xaero's maps", key = "layer:xaero")?.let { ClientClaims.prefs.overlay = it; ClientClaims.savePrefs() }
            if (ui.input.presses.any { !it.consumed && !box.contains(it.x, it.y) && !anchor.contains(it.x, it.y) }) layersOpen = false
        }
    }

    private fun setMode(m: MapMode) {
        ClientClaims.prefs.mapMode = m.key
        ClientClaims.savePrefs()
    }

    private fun switchTool(t: Tool) {
        tool = t
        if (t != Tool.MEASURE) { measureA = null; measureB = null }
    }

    private fun keys(ui: Ui) {
        if (ui.typing || app.dialogOpen) return
        Tool.entries.forEach { t -> if (ui.input.takeKey(t.key) { !it.ctrl } != null) switchTool(t) }
        MapMode.entries.forEachIndexed { i, m -> if (ui.input.takeKey(GLFW.GLFW_KEY_1 + i) { !it.ctrl } != null) setMode(m) }
        if (ui.input.takeKey(GLFW.GLFW_KEY_C) { !it.ctrl } != null) map.center(snap.px, snap.pz)
        if (selection.isNotEmpty()) ui.onEscape(10) { selection.clear(); measureA = null; measureB = null }
    }

    private fun previewKey(kind: String, args: Array<String>) = "$kind|${args.joinToString("|")}"

    private fun previewFor(): PreviewLine? {
        if (selection.isEmpty()) return null
        val kind = previewKind()
        val args = planArgs()
        val key = previewKey(kind, args)
        return ClaimsStore.previewLine()?.takeIf { it.kind == kind && it.key == key }
    }

    private fun planArgs(): Array<String> {
        val cells = selection.joinToString(",") { k -> unkey(k).let { "${it.first}:${it.second}" } }
        return if (plan == Plan.UNCLAIM) arrayOf("cells", cells) else arrayOf(planType, "cells", cells)
    }

    private fun requestPreview() {
        if (selection.size < 1 || info == null || !ClientClaims.active(dim)) return
        val now = System.currentTimeMillis()
        if (now - selectionTouchedAt < 80L) return
        if (previewFor() != null) { previewWaitSince = 0L; return }
        if (now - lastPreviewSent < 150L) return
        if (previewFor() == null && previewWaitSince == 0L) previewWaitSince = System.currentTimeMillis()
        lastPreviewSent = now
        ClaimsStore.preview(previewKind(), *planArgs())
    }

    private fun mapView(ui: Ui, r: Rect, status: Rect) {
        val input = map.interact(ui, r, tool == Tool.AREA || tool == Tool.BRUSH)
        ui.clip(r) {
            map.drawBase(ui, r, dim, ClientClaims.prefs.terrain, "grid" in ClientClaims.prefs.mapLayers)
            ClaimsLayer.draw(ui, map, dim, mode, ClientClaims.prefs.mapLayers, snap.px to snap.pz)
            overlays(ui, input.dragStart, input.dragging)
        }
        Draw.outline(ui.g, r, Palette.border)
        if (!ClientClaims.active(dim)) ui.banner(Rect(r.x + 6, r.y + 6, min(260, r.w - 12), 22), Severity.INFO, "Claims disabled in this dimension")
        compass(ui, r)
        legend(ui, r)
        zoomControls(ui, r)
        val hover = input.hover
        when (tool) {
            Tool.SELECT -> input.click?.let { select(it.x, it.z, ui.input.ctrl) }
            Tool.AREA -> input.dragEnd?.let { end ->
                val start = input.dragStart ?: return@let
                val w = abs(end.x - start.x) + 1
                val h = abs(end.z - start.z) + 1
                val limit = limits?.maxRect ?: 400
                if (w * h > limit) app.toast(Severity.WARNING, "Selection too large", "Max $limit chunks. Selected ${w * h}.")
                else {
                    val replaced = !ui.input.ctrl
                    if (replaced) selection.clear()
                    val before = selection.size
                    for (x in min(start.x, end.x)..max(start.x, end.x)) for (z in min(start.z, end.z)..max(start.z, end.z)) selection += key(x, z)
                    if (replaced || selection.size != before) touchSelection()
                    planning = true
                    choosePlan()
                }
            } ?: input.click?.let { select(it.x, it.z, ui.input.ctrl) }
            Tool.BRUSH -> if (hover != null && ui.isDown(0) && ui.hovering(r)) {
                val k = key(hover.x, hover.z)
                val changed = if (ui.input.shift) selection.remove(k) else if (selection.size < (limits?.maxRect ?: 400)) selection.add(k) else false
                if (changed) {
                    touchSelection()
                    planning = true
                    choosePlan()
                }
            }
            Tool.MEASURE -> input.click?.let { c -> if (measureA == null || measureB != null) { measureA = c.x to c.z; measureB = null } else measureB = c.x to c.z }
        }
        input.doubleClick?.let { map.center(it.x, it.z); map.zoomBy(2) }
        input.rightClick?.let { select(it.x, it.z, false) }
        hover?.let { h -> hoverTip(ui, r, h.x, h.z) }
        statusLine(ui, status, hover?.let { it.x to it.z })
    }

    private fun overlays(ui: Ui, dragStart: kami.libs.ui.map.ChunkPoint?, dragging: kami.libs.ui.map.ChunkPoint?) {
        val g = ui.g
        val preview = previewFor()
        val outcomes = preview?.cells?.associateBy { key(it.x, it.z) } ?: emptyMap()
        val phase = if (ui.reduceMotion) 0 else (ui.now / 90 % 1000).toInt()
        val typeColor = Vocabulary.type(planType).color
        selection.forEach { k ->
            val (x, z) = unkey(k)
            val c = map.cell(x, z)
            val o = outcomes[k]?.outcome
            when (o) {
                "CLAIM", "FREE" -> { Draw.fill(g, c, Palette.alpha(typeColor, 0x70)); Draw.outline(g, c, Palette.success) }
                "RETYPE" -> { Draw.fill(g, c, Palette.alpha(typeColor, 0x70)); Draw.outline(g, c, Palette.warning) }
                "UNCLAIM" -> { Draw.fill(g, c, Palette.alpha(Palette.danger, 0x50)); Draw.outline(g, c, Palette.danger) }
                "BLOCKED" -> { Draw.hatch(g, c, Palette.alpha(Palette.danger, 0xC0), 3); Draw.outline(g, c, Palette.alpha(Palette.danger, 0xA0)) }
                "SKIP" -> Draw.fill(g, c, Palette.alpha(0, 0x50))
                else -> Draw.fill(g, c, Palette.alpha(Palette.brass, 0x40))
            }
            if (o == "FREE" && map.zoom >= 12) Draw.text(g, "0", c.centerX - 2, c.centerY - 4, Palette.success)
        }
        if (selection.size == 1) {
            val (x, z) = unkey(selection.first())
            Draw.marching(g, map.cell(x, z).grow(1), Palette.brass, phase)
        } else if (selection.isNotEmpty()) {
            val xs = selection.map { unkey(it).first }
            val zs = selection.map { unkey(it).second }
            Draw.marching(g, map.area(xs.min(), zs.min(), xs.max(), zs.max()).grow(1), Palette.alpha(Palette.brass, 0xA0), phase)
        }
        if (dragStart != null && dragging != null) {
            val a = map.area(dragStart.x, dragStart.z, dragging.x, dragging.z)
            Draw.fill(g, a, Palette.alpha(Palette.brass, 0x30))
            Draw.marching(g, a, Palette.brass, phase)
            val count = (abs(dragging.x - dragStart.x) + 1) * (abs(dragging.z - dragStart.z) + 1)
            val label = "${abs(dragging.x - dragStart.x) + 1} × ${abs(dragging.z - dragStart.z) + 1} = $count"
            Draw.fill(g, Rect(a.right + 4, a.bottom + 2, Draw.width(label) + 6, 11), Palette.alpha(0, 0xC0))
            Draw.text(g, label, a.right + 7, a.bottom + 4, if (count > (limits?.maxRect ?: 400)) Palette.danger else Palette.text)
        }
        val a = measureA ?: return
        val b = measureB ?: map.chunkAt(ui.mouseX, ui.mouseY).let { it.x to it.z }
        val ax = map.sx(a.first + 0.5); val az = map.sz(a.second + 0.5)
        val bx = map.sx(b.first + 0.5); val bz = map.sz(b.second + 0.5)
        Draw.line(g, ax, az, bx, bz, Palette.link, 2)
        val chunks = sqrt(((b.first - a.first) * (b.first - a.first) + (b.second - a.second) * (b.second - a.second)).toDouble())
        val label = "%.1f chunks · %d blocks".format(chunks, (chunks * 16).toInt())
        Draw.fill(g, Rect(bx + 6, bz - 5, Draw.width(label) + 6, 11), Palette.alpha(0, 0xC0))
        Draw.text(g, label, bx + 9, bz - 3, Palette.text)
    }

    private fun compass(ui: Ui, r: Rect) {
        val c = Rect(r.right - 26, r.y + 4, 22, 22)
        Draw.fill(ui.g, c, Palette.alpha(0, 0x80))
        Draw.text(ui.g, "N", c.centerX - 2, c.y + 2, Palette.danger)
        Draw.text(ui.g, "S", c.centerX - 2, c.bottom - 9, Palette.textMuted)
        Draw.vline(ui.g, c.centerX, c.y + 10, 3, Palette.textMuted)
    }

    private fun zoomControls(ui: Ui, r: Rect) {
        val x = r.right - 22
        if (ui.button(Rect(x, r.y + 30, 18, 16), "+", key = "zoom-in")) map.zoomBy(1)
        if (ui.button(Rect(x, r.y + 48, 18, 16), "-", key = "zoom-out")) map.zoomBy(-1)
        ui.tooltip("zoom-tip", Rect(x, r.y + 30, 18, 34), "Zoom  [+] [-] or scroll")
    }

    private fun legend(ui: Ui, r: Rect) {
        val entries: List<Pair<Int, String>> = when (mode) {
            MapMode.POLITICAL -> ClientClaims.countries.take(6).map { Palette.opaque(it.color) to it.name } + (Palette.geoReserved to "Reserved")
            MapMode.RELATIONS -> (1..4).map { Vocabulary.relationColor(it) to Vocabulary.relationLabel(it) } + (Vocabulary.relationColor(0) to Vocabulary.relationLabel(0))
            MapMode.LANDUSE -> ClientClaims.types.map { Vocabulary.type(it).color to Vocabulary.type(it).label }
            MapMode.ECONOMY -> listOf(Palette.success to "Cheap", Palette.warning to "Medium", Palette.danger to "Expensive")
            MapMode.RISK -> listOf(Palette.danger to "In debt", Palette.warning to "Plot tax overdue", Palette.geoReserved to "Reserved", Palette.success to "Healthy")
            MapMode.PLOTS -> listOf(Palette.money to "Your plots", Palette.success to "Free to rent", Palette.geoNeutral to "Taken")
            MapMode.TERRAIN -> emptyList()
        }
        if (entries.isEmpty()) return
        val w = 130
        val h = if (legendOpen) entries.size * 11 + 18 else 14
        val box = Rect(r.x + 4, r.bottom - h - 4, w, h)
        Draw.fill(ui.g, box, Palette.alpha(0x0C0E12, 0xC8))
        Draw.text(ui.g, "LEGEND · ${mode.label.uppercase()}", box.x + 4, box.y + 3, Palette.textMuted)
        if (ui.hovering(box.top(12))) ui.cursor = Cursor.HAND
        if (ui.pressed(box.top(12)) != null) legendOpen = !legendOpen
        if (!legendOpen) return
        entries.forEachIndexed { i, (color, label) -> ui.legendItem(box.x + 4, box.y + 15 + i * 11, color, Draw.fit(label, w - 20), label == "In debt") }
    }

    private fun hoverTip(ui: Ui, r: Rect, x: Int, z: Int) {
        ui.tooltip("chunk", r) {
            val e = ClientClaims.at(dim, x, z)
            val lines = ArrayList<Pair<String, Int>>()
            if (e == null) {
                lines += (if (ClientClaims.reserved(dim, x, z)) "Reserved after a country lost it" else "Nobody owns this land") to Palette.textMuted
            } else {
                val c = ClientClaims.country(e)
                lines += Vocabulary.relationLabel(c?.relation ?: 0) to Vocabulary.relationColor(c?.relation ?: 0)
                ClientClaims.typeName(e)?.let { lines += Vocabulary.type(it).label + (if (e.flags and View.CAPITAL != 0) " · capital" else "") to Vocabulary.type(it).color }
                if (e.flags and View.MINE != 0) lines += "Your plot" to Palette.money
                if (e.flags and View.CLAIMABLE != 0) lines += "Free plot, you can rent it" to Palette.success
                if (e.flags and View.DEBT != 0) lines += "In debt" to Palette.danger
            }
            previewFor()?.cells?.firstOrNull { it.x == x && it.z == z }?.let { cell ->
                val text = when (cell.outcome) {
                    "CLAIM" -> "Will be claimed"
                    "FREE" -> "Will be claimed for free"
                    "RETYPE" -> "Changes to ${Vocabulary.type(planType).label}"
                    "UNCLAIM" -> "Will be released"
                    "BLOCKED" -> "Can't: ${cell.reason}"
                    else -> cell.reason.ifEmpty { "Skipped" }
                }
                lines += text to if (cell.outcome == "BLOCKED") Palette.danger else Palette.success
            }
            Tip(e?.let { ClientClaims.country(it)?.name } ?: "Chunk $x, $z", lines, keys = "Chunk $x, $z · blocks ${x * 16}..${x * 16 + 15}, ${z * 16}..${z * 16 + 15}")
        }
    }

    private fun statusLine(ui: Ui, r: Rect, hover: Pair<Int, Int>?) {
        val hints = when (tool) {
            Tool.SELECT -> listOf("LMB" to "select", "Ctrl+LMB" to "add", "RMB" to "pan")
            Tool.AREA -> listOf("drag" to "select area", "Ctrl" to "add", "RMB" to "pan")
            Tool.BRUSH -> listOf("LMB" to "paint", "Shift" to "erase", "RMB" to "pan")
            Tool.MEASURE -> listOf("LMB" to "set points", "Esc" to "clear")
        }
        val text = hover?.let { (x, z) -> "$x, $z" } ?: ""
        Draw.text(ui.g, text, r.x, r.y + 1, Palette.textMuted)
        if (!app.compact) ui.keyHints(r.right - 250, r.y - 2, hints)
    }

    private fun side(ui: Ui, r: Rect) {
        Draw.sprite(ui.g, Sprites.PANEL, r)
        val inner = r.inset(6)
        when {
            selection.isEmpty() -> overview(ui, inner)
            selection.size == 1 && !planning -> single(ui, inner)
            else -> planner(ui, inner)
        }
    }

    private fun overview(ui: Ui, r: Rect) {
        val f = Flow(r, 4)
        Draw.text(ui.g, "HOW TO", f.take(10).x, r.y, TextStyle.LABEL)
        listOf(
            Icons.CURSOR to "Click a chunk to see who owns it and what you may do there.",
            Icons.AREA to "Use Area (E) or Brush (B) to plan new land. You see the price before paying.",
            Icons.PAN to "Drag with the right mouse button to move the map. Scroll to zoom."
        ).forEach { (icon, text) ->
            val h = Draw.paragraphHeight(text, r.w - 20)
            val row = f.take(h)
            Draw.icon(ui.g, icon, row.x - 2, row.y - 3)
            Draw.paragraph(ui.g, text, row.x + 16, row.y, r.w - 18)
        }
        f.skip(4)
        ui.section(f.take(14), "Land prices", "per day")
        snap.types.forEach { t ->
            val row = f.take(12)
            val look = Vocabulary.type(t.name)
            Draw.icon(ui.g, look.icon, row.x, row.y - 2, 12)
            Draw.text(ui.g, look.label, row.x + 15, row.y, look.color)
            Draw.textRight(ui.g, if (t.period > 1) "${t.price} ◎ / ${t.period}d" else "${t.price} ◎", row.right, row.y, Palette.textSecondary)
            ui.tooltip("price:${t.name}", row, look.description)
        }
        info?.let { i ->
            f.skip(6)
            ui.section(f.take(14), "Your land")
            ui.property(f.take(11), "Chunks", "${i.chunks}")
            ui.property(f.take(11), "Free chunks left", "${(i.freeAllowed - i.chunks).coerceAtLeast(0)}", Palette.success)
            ui.property(f.take(11), "Upkeep", "${i.upkeep} ◎ / day", Palette.danger)
        }
    }

    private fun single(ui: Ui, r: Rect) {
        val (x, z) = unkey(selection.first())
        val d = snap.detail?.takeIf { it.x == x && it.z == z }
        if (d == null) {
            ui.spinner(r.x, r.y)
            Draw.text(ui.g, "Loading chunk $x, $z…", r.x + 12, r.y, Palette.textMuted)
            return
        }
        val f = Flow(r, 3)
        val head = f.take(24)
        val e = ClientClaims.at(dim, x, z)
        val country = e?.let { ClientClaims.country(it) }
        if (country != null) Flags.draw(ui.g, Rect(head.x, head.y + 3, 18, 13), country.color, country.pattern, country.emblem, country.secondary)
        Draw.text(ui.g, Draw.fit(d.country.ifEmpty { "Nomansland" }, head.w - 24), head.x + (if (country != null) 24 else 0), head.y + 2, TextStyle.HEADING)
        Draw.text(ui.g, "Chunk $x, $z", head.x + (if (country != null) 24 else 0), head.y + 13, Palette.textMuted)
        MiniMap.draw(ui, f.take(70), x, z, 3, "side", listOf(x to z))
        if (d.type.isNotEmpty()) {
            val look = Vocabulary.type(d.type)
            ui.property(f.take(11), "Type", look.label + if (d.capital) " · capital" else "", look.color, tip = look.description)
            ui.property(f.take(11), "Upkeep", if (d.free) "free" else "${d.price} ◎ / ${if (d.period > 1) "${d.period} days" else "day"}")
            if (d.owner.isNotEmpty()) ui.property(f.take(11), "Plot owner", d.owner)
            if (d.tax >= 0) ui.property(f.take(11), "Plot tax", "${d.tax} ◎ / day")
            if (d.at > 0) ui.property(f.take(11), "Claimed", Format.ago(d.at))
            if (d.debt > 0) {
                val max = limits?.maxDebt ?: 3
                ui.property(f.take(11), "Debt", "${d.debt} / $max unpaid days", Palette.danger)
                f.skip(2)
                ui.meter(f.take(5), d.debt, max, { if (it >= max) Severity.DANGER else Severity.WARNING })
            }
        } else if (d.note.isNotEmpty()) f.take(Draw.paragraph(ui.g, d.note, r.x, f.rest.y, r.w, Palette.textSecondary))
        if (d.reserved.isNotEmpty()) f.take(ui.callout(f.rest, Severity.WARNING, "Reserved for ${d.reserved}. Others cannot claim it until reservation ends."))
        if (d.access.isNotEmpty()) {
            f.skip(4)
            ui.section(f.take(14), "What you may do here")
            val cells = f.take(24).grid(2, 11, 4, 2)
            listOf("break" to 0, "place" to 1, "interact" to 2, "container" to 3).forEachIndexed { i, (k, a) ->
                val allowed = d.access[k] == true
                val look = Vocabulary.actions[a]
                Draw.icon(ui.g, if (allowed) Icons.CHECK else Icons.CROSS, cells[i].x - 1, cells[i].y - 2, 10)
                Draw.text(ui.g, look.label, cells[i].x + 11, cells[i].y, if (allowed) Palette.textSecondary else Palette.textMuted)
                ui.tooltip("access:$k", cells[i], look.description)
            }
        }
        if (d.locked.isNotEmpty()) f.take(ui.callout(f.rest, Severity.INFO, "Can't be released yet: ${d.locked}."))
        chunkActions(ui, f, x, z, e)
    }

    private fun chunkActions(ui: Ui, f: Flow, x: Int, z: Int, e: View.Entry?) {
        val own = e != null && e.flags and View.OWN != 0
        f.skip(4)
        val rest = f.rest
        var y = rest.bottom - CONTROL_H
        fun place(label: String, icon: kami.libs.ui.style.Icon, style: ButtonStyle, enabled: Boolean, reason: String?, key: String, action: () -> Unit) {
            val cell = Rect(rest.x, y, rest.w, CONTROL_H)
            if (ui.button(cell, label, icon, style, enabled, reason, pending = pending(key), key = "chunk-$key")) action()
            y -= CONTROL_H + 3
        }
        if (e == null && info != null) place("Plan a claim here", Icons.ADD, ButtonStyle.PRIMARY, can("claim"), lock("claim"), "claim") { plan = Plan.CLAIM; planning = true; ClaimsStore.clearPreview() }
        if (e?.flags?.and(View.CLAIMABLE) != 0 && e != null) place("Rent this plot", Icons.HOUSE, ButtonStyle.PRIMARY, can("plot"), lock("plot"), "plot_claim") {
            val d = snap.detail
            Dialogs.confirm(app, "Rent plot $x, $z", "Residential plot", Icons.HOUSE, listOf(
                Consequence("You pay ${d?.tax?.takeIf { it >= 0 } ?: info?.tax ?: 0} ◎ tax every day from your bank."),
                Consequence("If you can't pay: locked out after ${info?.shutdown ?: 0} days, lost ${info?.release ?: 0} days later.", Severity.WARNING),
                Consequence("You decide who may build on it.", Severity.SUCCESS)
            ), "Rent plot", "plot_claim", arrayOf(x.toString(), z.toString()))
        }
        if (e?.flags?.and(View.MINE) != 0 && e != null) place("Give up this plot", Icons.REMOVE, ButtonStyle.DANGER, true, null, "plot_release") {
            Dialogs.confirm(app, "Give up plot $x, $z", "You stop paying its tax", Icons.HOUSE, listOf(Consequence("Everyone you trusted loses access."), Consequence("Anyone may rent it afterwards.", Severity.WARNING)), "Give up plot", "plot_release", arrayOf(x.toString(), z.toString()), danger = true, hold = true)
        }
        if (own) {
            if (e.flags and View.CAPITAL == 0) place("Make capital", Icons.CROWN, ButtonStyle.SECONDARY, can("capital"), lock("capital"), "capital") {
                Dialogs.confirm(app, "Move the capital to $x, $z", null, Icons.CROWN, listOf(
                    Consequence("The capital is never lost to debt and gets the first free chunk."),
                    Consequence("You can move it again after ${limits?.capitalCooldownDays ?: 7} days.", Severity.WARNING)
                ), "Move capital", "capital", arrayOf(x.toString(), z.toString()))
            }
            place("Change type or release", Icons.EDIT, ButtonStyle.SECONDARY, can("claim"), lock("claim"), "retype") { plan = Plan.RETYPE; planning = true; ClaimsStore.clearPreview() }
        }
    }

    private fun planner(ui: Ui, r: Rect) {
        val f = Flow(r, 4)
        val count = selection.size
        val head = f.take(12)
        Draw.text(ui.g, "${Format.plural(count, "chunk")} selected", head.x, head.y, TextStyle.HEADING)
        if (ui.link(head.right - 30, head.y, "Clear", key = "clear-selection")) { selection.clear(); return }
        val entries = selection.map { unkey(it) }.map { (x, z) -> ClientClaims.at(dim, x, z) }
        val free = entries.count { it == null }
        val own = entries.count { it != null && it.flags and View.OWN != 0 }
        val modes = listOf(
            Option(Plan.CLAIM, "Claim", Icons.ADD, disabledReason = if (free == 0) "No unclaimed chunks selected" else lock("claim")),
            Option(Plan.RETYPE, "Type", Icons.EDIT, disabledReason = if (own == 0) "No owned chunks selected" else lock("claim")),
            Option(Plan.UNCLAIM, "Release", Icons.REMOVE, disabledReason = if (own == 0) "None of the selected chunks are yours" else lock("claim"))
        )
        ui.segmented(f.take(CONTROL_H), modes, plan, key = "plan")?.let { plan = it; ClaimsStore.clearPreview(); touchSelection() }
        if (plan != Plan.UNCLAIM) {
            ui.fieldLabel(f.take(9), if (plan == Plan.CLAIM) "Claim as" else "New type")
            ui.select(f.take(CONTROL_H), snap.types.map { t ->
                val look = Vocabulary.type(t.name)
                Option(t.name, look.label, look.icon, "${if (t.period > 1) "${t.price} ◎ every ${t.period} days" else "${t.price} ◎ per day"}. ${look.description}", look.color)
            }, planType, key = "plan-type")?.let { planType = it; ClaimsStore.clearPreview(); touchSelection() }
        }
        val preview = previewFor()
        f.skip(2)
        if (preview == null) {
            val row = f.take(14)
            val reason = lock("claim")
            when {
                !ClientClaims.active(dim) -> f.take(ui.callout(f.rest, Severity.INFO, "Claims are disabled in this dimension."))
                reason != null -> f.take(ui.callout(f.rest, Severity.WARNING, reason))
                System.currentTimeMillis() - previewWaitSince > 1400L -> {
                    f.take(ui.callout(f.rest, Severity.WARNING, "Server check delayed."))
                    if (ui.button(f.take(CONTROL_H), "Retry check", Icons.PENDING, ButtonStyle.SECONDARY, key = "preview-retry")) {
                        ClaimsStore.clearPreview()
                        previewWaitSince = System.currentTimeMillis()
                        lastPreviewSent = 0L
                    }
                }
                else -> {
                    ui.spinner(row.x, row.y + 1)
                    Draw.text(ui.g, "Checking server...", row.x + 12, row.y + 1, Palette.textMuted)
                }
            }
            return
        }
        previewWaitSince = 0L
        val byOutcome = preview.cells.groupBy { it.outcome }
        val ready = (byOutcome["CLAIM"]?.size ?: 0) + (byOutcome["FREE"]?.size ?: 0) + (byOutcome["RETYPE"]?.size ?: 0) + (byOutcome["UNCLAIM"]?.size ?: 0)
        ui.section(f.take(14), "Result")
        byOutcome["CLAIM"]?.let { outcomeRow(ui, f.take(11), Palette.success, "${it.size} claimed", Icons.CHECK) }
        byOutcome["FREE"]?.let { outcomeRow(ui, f.take(11), Palette.success, "${it.size} free", Icons.STAR) }
        byOutcome["RETYPE"]?.let { outcomeRow(ui, f.take(11), Palette.warning, "${it.size} retyped", Icons.EDIT) }
        byOutcome["UNCLAIM"]?.let { outcomeRow(ui, f.take(11), Palette.danger, "${it.size} released", Icons.REMOVE) }
        byOutcome["SKIP"]?.let { outcomeRow(ui, f.take(11), Palette.textMuted, "${it.size} skipped", Icons.CHEVRON_RIGHT) }
        byOutcome["BLOCKED"]?.groupBy { it.reason }?.forEach { (reason, cells) ->
            val row = f.take(11)
            outcomeRow(ui, row, Palette.danger, "${cells.size} can't: $reason", Icons.CROSS)
            ui.tooltip("blocked:$reason", row, reason)
        }
        byOutcome["RETYPE"]?.mapNotNull { it.reason.ifEmpty { null } }?.distinct()?.forEach { f.take(ui.callout(f.rest, Severity.WARNING, "$it.")) }
        val info = info ?: return
        val net = info.income - info.upkeep - info.jobs
        val after = net - preview.upkeepPerDay.toLong()
        f.skip(4)
        ui.section(f.take(14), "Cost")
        if (plan == Plan.CLAIM) ui.property(f.take(11), "Pay now", "${preview.cost} ◎", if (preview.cost > info.treasury) Palette.danger else Palette.money)
        ui.property(f.take(11), "Upkeep change", "${if (preview.upkeepPerDay >= 0) "+" else ""}${"%.1f".format(preview.upkeepPerDay)} ◎ / day", if (preview.upkeepPerDay > 0) Palette.danger else Palette.success)
        ui.property(f.take(11), "Runway", "${app.runwayText(info.treasury, net)} → ${app.runwayText(info.treasury - preview.cost, after)}", app.runwayColor(info.treasury - preview.cost, after))
        val rest = f.rest
        val button = Rect(rest.x, rest.bottom - CONTROL_H, rest.w, CONTROL_H)
        val label = when (plan) {
            Plan.CLAIM -> "Claim ${Format.plural(ready, "chunk")}"
            Plan.RETYPE -> "Retype ${Format.plural(ready, "chunk")}"
            Plan.UNCLAIM -> "Release ${Format.plural(ready, "chunk")}"
        }
        val keyName = when (plan) { Plan.CLAIM -> "claimcells"; Plan.RETYPE -> "typecells"; Plan.UNCLAIM -> "unclaimcells" }
        if (ui.button(button, label, if (plan == Plan.UNCLAIM) Icons.REMOVE else Icons.CHECK, if (plan == Plan.UNCLAIM) ButtonStyle.DANGER else ButtonStyle.PRIMARY,
                ready > 0 && lock("claim") == null, lock("claim") ?: "Nothing in the selection can be changed", pending = pending(keyName), key = "plan-go")) commit(ready, preview, after)
    }

    private fun outcomeRow(ui: Ui, r: Rect, color: Int, text: String, icon: kami.libs.ui.style.Icon) {
        Draw.icon(ui.g, icon, r.x - 2, r.y - 3, 12)
        Draw.text(ui.g, Draw.fit(text, r.w - 14), r.x + 12, r.y, color)
    }

    private fun commit(ready: Int, preview: PreviewLine, after: Long) {
        val info = info ?: return
        val args = planArgs()
        val runwayAfter = if (after >= 0) Long.MAX_VALUE else (info.treasury - preview.cost) / -after
        when (plan) {
            Plan.CLAIM -> {
                val items = listOf(
                    Consequence("Pay now: ${preview.cost} ◎."),
                    Consequence("Upkeep delta: ${"%.1f".format(preview.upkeepPerDay)} ◎/day.", if (preview.upkeepPerDay > 0) Severity.WARNING else Severity.NEUTRAL),
                    Consequence("Unclaim lock: 24h + 1 upkeep cycle.")
                ) + if (runwayAfter < 7) listOf(Consequence("Runway after claim: $runwayAfter days.", Severity.DANGER)) else emptyList()
                if (ClientClaims.prefs.skipClaimConfirm && runwayAfter >= 7) ClaimsStore.send("claimcells", *args, key = "claimcells")
                else Dialogs.confirm(app, "Claim ${Format.plural(ready, "chunk")}", "as ${Vocabulary.type(planType).label} land", Icons.AREA, items, "Claim ${Format.plural(ready, "chunk")}", "claimcells", args)
            }
            Plan.RETYPE -> Dialogs.confirm(app, "Change ${Format.plural(ready, "chunk")} to ${Vocabulary.type(planType).label}", null, Icons.EDIT, listOfNotNull(
                Consequence("Upkeep delta: ${"%.1f".format(preview.upkeepPerDay)} ◎/day."),
                Consequence("Land rules and jobs change with type."),
                if (preview.cells.any { it.reason.isNotEmpty() && it.outcome == "RETYPE" }) Consequence("Non-residential conversion removes affected plots.", Severity.DANGER) else null
            ), "Retype", "typecells", args)
            Plan.UNCLAIM -> Dialogs.confirm(app, "Release ${Format.plural(ready, "chunk")}", "They become nomansland", Icons.REMOVE, listOf(
                Consequence("Upkeep saved: ${"%.1f".format(-preview.upkeepPerDay)} ◎/day.", Severity.SUCCESS),
                Consequence("Result: nomansland. Builds stay, edits blocked.", Severity.WARNING),
                Consequence("Other countries can claim immediately.", Severity.DANGER)
            ), "Release land", "unclaimcells", args, danger = true, hold = true)
        }
        ClaimsStore.clearPreview()
    }
}
