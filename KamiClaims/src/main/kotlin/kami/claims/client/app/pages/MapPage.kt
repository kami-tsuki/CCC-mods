package kami.claims.client.app.pages

import kami.claims.client.ClientClaims
import kami.claims.client.map.ChunkCells
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Vocabulary
import kami.claims.client.map.ClaimsLayer
import kami.claims.client.map.MapMode
import kami.claims.client.map.SectorShapes
import kami.claims.client.store.ClaimsStore
import kami.libs.xaero.XaeroMaps
import kami.claims.service.View
import kami.libs.ui.app.CRUMBS_H
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Dim
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.map.ChunkMap
import kami.libs.ui.map.ChunkPoint
import kami.libs.ui.map.TerrainCache
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.text.trJson
import kami.libs.ui.widget.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import org.lwjgl.glfw.GLFW

class MapPage(app: ClaimsApp) : ClaimsPage(app) {
    enum class Tool(val key: Int) {
        SELECT(GLFW.GLFW_KEY_Q), AREA(GLFW.GLFW_KEY_E), BRUSH(GLFW.GLFW_KEY_B), MEASURE(GLFW.GLFW_KEY_R);

        val label get() = tr("kami_claims.map.tool.${name.lowercase()}")
        val tip get() = tr("kami_claims.map.tool.${name.lowercase()}.tooltip")
    }

    override val title get() = tr("kami_claims.nav.map")
    override val needsCountry = false
    override val subtitle: String? get() = if (ClientClaims.active(dim)) null else tr("kami_claims.block.dimension")
    override val help get() = listOf(
        Callout("map:tools", tr("kami_claims.map.help.tools"), tr("kami_claims.map.help.tools.desc")),
        Callout("map:mode", tr("kami_claims.map.help.modes"), tr("kami_claims.map.help.modes.desc")),
        Callout("map:view", tr("kami_claims.nav.map"), tr("kami_claims.map.help.view.desc")),
        Callout("map:panel", tr("kami_claims.map.help.panel"), tr("kami_claims.map.help.panel.desc"))
    )

    private val map = ChunkMap(12f)
    private var tool = Tool.SELECT
    private var measureA: Pair<Int, Int>? = null
    private var measureB: Pair<Int, Int>? = null
    private var centered = false
    private var legendOpen = true
    private var layersOpen = false
    private val planner = MapPlanner(this)
    private val side = MapSide(this, planner)
    private val jump = TextState()
    private val outcomeIndex = Memo()

    private val dim get() = Dim.current()
    private val selection get() = planner.selection
    private val mode get() = MapMode.of(ClientClaims.prefs.mapMode)
    private fun key(x: Int, z: Int) = ClientClaims.key(x, z)
    private fun unkey(k: Long) = ChunkCells.cell(k)

    private val boundsMemo = Memo()

    override fun opened(route: Route) {
        val x = route.int("x")
        val z = route.int("z")
        if (x != null && z != null) {
            map.center(x, z)
            centered = true
            if (route.param("select") != "false") select(x, z, false)
            if (route.param("plan") == "claim") planner.begin(Plan.CLAIM)
        }
    }

    private fun select(x: Int, z: Int, add: Boolean) {
        val sectorEdit = planner.plan == Plan.SECTOR && planner.planning
        if (!add && !sectorEdit) {
            planner.clear()
            side.reset()
        }
        val k = key(x, z)
        val changed = if ((add || sectorEdit) && k in selection) selection.remove(k) else selection.add(k)
        if (changed) planner.touchSelection()
        if (selection.size == 1) ClaimsStore.quiet("chunk", x.toString(), z.toString())
        planner.planning = selection.size > 1 || sectorEdit
        planner.choosePlan()
    }

    override fun actionsWidth() = 2 * CRUMBS_H + 2

    override fun actions(ui: Ui, r: Rect) {
        if (r.w < actionsWidth()) return
        if (ui.iconButton(Rect(r.right - r.h, r.y, r.h, r.h), Icons.LOCATE, tr("kami_claims.map.center_me.tooltip"), key = "map-me")) map.center(snap.px, snap.pz)
        val capital = info?.claimList?.firstOrNull { it.capital }
        if (ui.iconButton(Rect(r.right - 2 * r.h - 2, r.y, r.h, r.h), Icons.CROWN, tr("kami_claims.map.center_capital.tooltip"), capital != null, key = "map-capital", disabledReason = tr("kami_claims.alert.capital.title"))) capital?.let { map.center(it.x, it.z) }
    }

    override fun draw(ui: Ui, r: Rect) {
        if (!centered) { map.center(snap.px, snap.pz); centered = true }
        planner.ensureType()
        val bar = r.top(CONTROL_H)
        toolbar(ui, bar)
        val body = r.dropTop(CONTROL_H, 6)
        val panelW = if (app.compact) 150 else (body.w / 3).coerceIn(170, 230)
        val view = body.dropRight(panelW, 6).dropBottom(12, 2)
        val status = Rect(view.x, view.bottom + 3, view.w, 10)
        val panel = body.right(panelW)
        ui.anchor("map:view", view)
        ui.anchor("map:panel", panel)
        mapView(ui, view, status)
        side.draw(ui, panel)
        planner.requestPreview()
        keys(ui)
    }

    private fun toolbar(ui: Ui, r: Rect) {
        val tools = r.left(Tool.entries.size * (TOOL_W + 2) - 2)
        ui.anchor("map:tools", tools)
        Tool.entries.forEachIndexed { i, t ->
            val icon = when (t) { Tool.SELECT -> Icons.CURSOR; Tool.AREA -> Icons.AREA; Tool.BRUSH -> Icons.BRUSH; Tool.MEASURE -> Icons.RULER }
            val keyName = GLFW.glfwGetKeyName(t.key, 0)?.uppercase() ?: ""
            if (ui.iconButton(Rect(tools.x + i * (TOOL_W + 2), r.y, TOOL_W, r.h), icon, "${t.label}  [$keyName]\n${t.tip}", selected = tool == t, key = "tool:${t.name}")) switchTool(t)
        }
        val modeRect = Rect(tools.right + 8, r.y, 124, r.h)
        ui.anchor("map:mode", modeRect)
        ui.select(modeRect, MapMode.entries.mapIndexed { i, m -> Option(m, m.label, m.icon, "${m.description}  [${i + 1}]") }, mode, key = "mode")?.let { setMode(it) }
        val layers = Rect(modeRect.right + 4, r.y, r.h, r.h)
        if (ui.iconButton(layers, Icons.LAYERS, tr("kami_claims.map.layers.tooltip"), selected = layersOpen, key = "layers")) layersOpen = !layersOpen
        if (layersOpen) layerPopover(ui, layers)
        val jumpRect = Rect(layers.right + 8, r.y, (r.right - layers.right - 8).coerceAtMost(130), r.h)
        if (jumpRect.w >= 70) {
            val res = ui.textField(jumpRect, jump, tr("kami_claims.map.jump"), Icons.SEARCH, key = "jump", clearable = true)
            if (res.submitted) {
                MapModel.jump(jump.text)?.let { (x, z) -> map.center(x, z); select(x, z, false) }
            }
            ui.tooltip("jump-tip", jumpRect, tr("kami_claims.map.jump.tooltip"))
        }
    }

    private fun layerPopover(ui: Ui, anchor: Rect) {
        ui.onEscape(55) { layersOpen = false }
        val items = listOf("borders", "labels", "markers", "grid", "work", "sectors").map { it to tr("kami_claims.map.layer.$it") }
        ui.popover(anchor, 170, items.size * 16 + 38, onOutside = { layersOpen = false }) { box ->
            var y = box.y + 5
            items.forEach { (k, label) ->
                toggle(Rect(box.x + 6, y, box.w - 12, 14), k in ClientClaims.prefs.mapLayers, label, key = "layer:$k")?.let { setLayer(k, it) }
                y += 16
            }
            toggle(Rect(box.x + 6, y, box.w - 12, 14), ClientClaims.prefs.terrain, tr("kami_claims.map.mode.terrain"), tip = tr("kami_claims.map.layer.terrain.tooltip"), key = "layer:terrain")?.let {
                ClientClaims.prefs.terrain = it; TerrainCache.enabled = it; ClientClaims.savePrefs()
            }
            y += 16
            toggle(Rect(box.x + 6, y, box.w - 12, 14), ClientClaims.prefs.overlay, tr("kami_claims.map.layer.xaero"), key = "layer:xaero")?.let { ClientClaims.prefs.overlay = it; ClientClaims.savePrefs(); XaeroMaps.refresh() }
        }
    }

    private fun setLayer(layer: String, on: Boolean) {
        if (on) ClientClaims.prefs.mapLayers += layer else ClientClaims.prefs.mapLayers -= layer
        ClientClaims.savePrefs()
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
        if (ui.input.takeKey(GLFW.GLFW_KEY_G) { !it.ctrl } != null) setLayer("sectors", "sectors" !in ClientClaims.prefs.mapLayers)
        if (selection.isNotEmpty() || measureA != null) ui.onEscape(10) { planner.clear(); measureA = null; measureB = null }
    }

    private fun mapView(ui: Ui, r: Rect, status: Rect) {
        val input = map.interact(ui, r, tool == Tool.AREA || tool == Tool.BRUSH)
        ui.clip(r) {
            map.drawBase(ui, r, dim, ClientClaims.prefs.terrain, "grid" in ClientClaims.prefs.mapLayers)
            ClaimsLayer.draw(ui, map, dim, mode, ClientClaims.prefs.mapLayers, snap.px to snap.pz)
            overlays(ui, input.dragStart, input.dragging)
        }
        Draw.outline(ui.g, r, Palette.border)
        if (!ClientClaims.active(dim)) ui.banner(Rect(r.x + 6, r.y + 6, min(260, r.w - 12), 22), Severity.INFO, tr("kami_claims.block.dimension"))
        compass(ui, r)
        legend(ui, r)
        zoomControls(ui, r)
        val hover = input.hover
        when (tool) {
            Tool.SELECT -> input.click?.let { select(it.x, it.z, ui.input.ctrl) }
            Tool.AREA -> input.dragEnd?.let { end ->
                val start = input.dragStart ?: return@let
                val count = MapModel.size(start.x, start.z, end.x, end.z)
                val limit = limits?.maxRect ?: 400
                if (count > limit) app.toast(Severity.WARNING, tr("kami_claims.map.too_large"), tr("kami_claims.map.too_large.desc", Format.number(limit), Format.number(count)))
                else {
                    val replaced = !ui.input.ctrl
                    if (replaced) selection.clear()
                    val before = selection.size
                    selection += MapModel.area(start.x, start.z, end.x, end.z, ::key)
                    if (replaced || selection.size != before) planner.touchSelection()
                    planner.planning = true
                    planner.choosePlan()
                }
            } ?: input.click?.let { select(it.x, it.z, ui.input.ctrl) }
            Tool.BRUSH -> if (hover != null && ui.isDown(0) && ui.hovering(r)) {
                val k = key(hover.x, hover.z)
                val changed = if (ui.input.shift) selection.remove(k) else if (selection.size < (limits?.maxRect ?: 400)) selection.add(k) else false
                if (changed) {
                    planner.touchSelection()
                    planner.planning = true
                    planner.choosePlan()
                }
            }
            Tool.MEASURE -> input.click?.let { c -> if (measureA == null || measureB != null) { measureA = c.x to c.z; measureB = null } else measureB = c.x to c.z }
        }
        input.doubleClick?.let { map.center(it.x, it.z); map.zoomBy(2) }
        input.rightClick?.let { select(it.x, it.z, false) }
        hover?.let { h -> hoverTip(ui, r, h.x, h.z) }
        statusLine(ui, status, hover?.let { it.x to it.z })
    }

    private fun overlays(ui: Ui, dragStart: ChunkPoint?, dragging: ChunkPoint?) {
        val g = ui.g
        val preview = planner.previewFor()
        val outcomes = outcomeIndex.of(preview) { preview?.cells?.associateBy { key(it.x, it.z) } ?: emptyMap() }
        val phase = if (ui.reduceMotion) 0 else (ui.wallMillis / 90 % 1000).toInt()
        val typeColor = Vocabulary.type(planner.planType).color
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
            val b = boundsMemo.of(planner.selectionRev) { MapModel.bounds(selection) }
            Draw.marching(g, map.area(b[0], b[1], b[2], b[3]).grow(1), Palette.alpha(Palette.brass, 0xA0), phase)
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
        val label = tr("kami_claims.map.measure", Format.decimal(chunks), Format.number((chunks * 16).toLong()))
        Draw.fill(g, Rect(bx + 6, bz - 5, Draw.width(label) + 6, 11), Palette.alpha(0, 0xC0))
        Draw.text(g, label, bx + 9, bz - 3, Palette.text)
    }

    private fun compass(ui: Ui, r: Rect) {
        val c = Rect(r.right - 26, r.y + 4, 22, 22)
        ui.keycap(c.centerX - 5, c.y, "N")
        ui.keycap(c.centerX - 5, c.bottom - 11, "S")
        Draw.vline(ui.g, c.centerX, c.y + 12, 3, Palette.textMuted)
    }

    private fun zoomControls(ui: Ui, r: Rect) {
        val x = r.right - SMALL_H - 7
        val tip = tr("kami_claims.map.zoom.tooltip")
        if (ui.iconButton(Rect(x, r.y + 30, SMALL_H, SMALL_H), Icons.ADD, tip, key = "zoom-in")) map.zoomBy(1)
        if (ui.iconButton(Rect(x, r.y + 32 + SMALL_H, SMALL_H, SMALL_H), Icons.REMOVE, tip, key = "zoom-out")) map.zoomBy(-1)
    }

    private fun legend(ui: Ui, r: Rect) {
        val modeEntries: List<Pair<Int, String>> = when (mode) {
            MapMode.POLITICAL -> ClientClaims.countries.take(6).map { Palette.opaque(it.color) to it.name } + (Palette.geoReserved to tr("kami_claims.help.term.reserved"))
            MapMode.RELATIONS -> (1..4).map { Vocabulary.relationColor(it) to Vocabulary.relationLabel(it) } + (Vocabulary.relationColor(0) to Vocabulary.relationLabel(0))
            MapMode.LANDUSE -> ClientClaims.types.map { Vocabulary.type(it).color to Vocabulary.type(it).label }
            MapMode.ECONOMY -> listOf(Palette.success to tr("kami_claims.map.legend.cheap"), Palette.warning to tr("kami_libs.common.medium"), Palette.danger to tr("kami_claims.map.legend.expensive"))
            MapMode.RISK -> listOf(Palette.danger to tr("kami_claims.stats.in_debt"), Palette.warning to tr("kami_claims.map.legend.overdue"), Palette.geoReserved to tr("kami_claims.help.term.reserved"), Palette.success to tr("kami_claims.map.legend.healthy"))
            MapMode.PLOTS -> listOf(Palette.money to tr("kami_claims.map.legend.yours"), Palette.success to tr("kami_claims.plots.tab.free"), Palette.geoNeutral to tr("kami_claims.map.legend.taken"))
            MapMode.TERRAIN -> emptyList()
        }
        val entries = modeEntries + sectorEntries()
        if (entries.isEmpty()) return
        val w = 130
        val h = if (legendOpen) entries.size * 11 + 18 else 14
        val box = Rect(r.x + 4, r.bottom - h - 4, w, h)
        Draw.fill(ui.g, box, Palette.alpha(Palette.surface, 0xE6))
        Draw.text(ui.g, tr("kami_claims.map.legend", mode.label).uppercase(Format.locale), box.x + 4, box.y + 3, Palette.textMuted)
        if (ui.hovering(box.top(12))) ui.cursor = Cursor.HAND
        if (ui.pressed(box.top(12)) != null) legendOpen = !legendOpen
        if (!legendOpen) return
        entries.forEachIndexed { i, (color, label) ->
            val y = box.y + 15 + i * 11
            val shown = Draw.fit(label, w - 20)
            ui.legendItem(box.x + 4, y, color, shown, color == Palette.danger && mode == MapMode.RISK)
            if (shown != label) ui.tooltip("legend:$i", Rect(box.x + 4, y, w - 8, 11), label)
        }
    }

    private fun sectorEntries(): List<Pair<Int, String>> {
        if ("sectors" !in ClientClaims.prefs.mapLayers) return emptyList()
        val shapes = SectorShapes.of(dim)
        val fragmented = if (shapes.any { it.line.fragmented }) listOf(Palette.warning to tr("kami_claims.map.legend.sector_split")) else emptyList()
        return shapes.take(MAX_LEGEND_SECTORS).map { Palette.opaque(it.line.color) to it.line.name } + fragmented
    }

    private fun sectorLines(x: Int, z: Int, lines: MutableList<Pair<String, Int>>) {
        val info = info ?: return
        SectorShapes.at(dim, x, z).forEach { s ->
            lines += s.line.name to Palette.opaque(s.line.color)
            info.members.firstOrNull { it.id == s.line.manager }?.let { lines += tr("kami_claims.map.sector.manager.line", it.name) to Palette.textSecondary }
            info.jobSpecs.filter { it.scope == "sector" && it.scopeRef == s.line.id }.forEach { lines += tr("kami_claims.map.sector.job", it.name) to Palette.textSecondary }
            if (s.line.fragmented) lines += tr("kami_claims.map.sector.fragmented") to Palette.warning
        }
    }

    private fun hoverTip(ui: Ui, r: Rect, x: Int, z: Int) {
        ui.tooltip("chunk", r) {
            val e = ClientClaims.at(dim, x, z)
            val lines = ArrayList<Pair<String, Int>>()
            if (e == null) {
                lines += tr(if (ClientClaims.reserved(dim, x, z)) "kami_claims.map.chunk.reserved" else "kami_claims.help.term.nomansland") to Palette.textMuted
            } else {
                val c = ClientClaims.country(e)
                lines += Vocabulary.relationLabel(c?.relation ?: 0) to Vocabulary.relationColor(c?.relation ?: 0)
                c?.let {
                    lines += tr("kami_claims.warpage.reputation.short", Format.number(it.reputation)) to WarPage.reputationColor(it.reputation)
                    lines += tr("kami_claims.warpage.fame.short", it.fame?.let(WarPage::stars) ?: "-") to Palette.brass
                }
                ClientClaims.typeName(e)?.let { lines += (if (e.flags and View.CAPITAL != 0) tr("kami_claims.map.chunk.capital", Vocabulary.type(it).label) else Vocabulary.type(it).label) to Vocabulary.type(it).color }
                if (e.flags and View.MINE != 0) lines += tr("kami_claims.map.chunk.mine") to Palette.money
                if (e.flags and View.CLAIMABLE != 0) lines += tr("kami_claims.map.chunk.claimable") to Palette.success
                if (e.flags and View.DEBT != 0) lines += tr("kami_claims.stats.in_debt") to Palette.danger
            }
            if ("sectors" in ClientClaims.prefs.mapLayers) sectorLines(x, z, lines)
            planner.previewFor()?.cells?.firstOrNull { it.x == x && it.z == z }?.let { cell ->
                val text = when (cell.outcome) {
                    "CLAIM" -> tr("kami_claims.map.preview.claim")
                    "FREE" -> tr("kami_claims.map.preview.free")
                    "RETYPE" -> tr("kami_claims.map.preview.retype", Vocabulary.type(planner.planType).label)
                    "UNCLAIM" -> tr("kami_claims.map.preview.unclaim")
                    "BLOCKED" -> tr("kami_claims.map.preview.blocked", trJson(cell.reason))
                    else -> trJson(cell.reason).ifEmpty { tr("kami_claims.map.preview.skip") }
                }
                lines += text to if (cell.outcome == "BLOCKED") Palette.danger else Palette.success
            }
            Tip(e?.let { ClientClaims.country(it)?.name } ?: tr("kami_claims.map.chunk", x, z), lines, keys = tr("kami_claims.map.chunk.blocks", tr("kami_claims.map.chunk", x, z), "${x * 16}..${x * 16 + 15}", "${z * 16}..${z * 16 + 15}"))
        }
    }

    private fun statusLine(ui: Ui, r: Rect, hover: Pair<Int, Int>?) {
        val hints = when (tool) {
            Tool.SELECT -> listOf(tr("kami_libs.key.lmb") to tr("kami_claims.map.hint.select"), tr("kami_libs.key.ctrl_lmb") to tr("kami_claims.map.hint.add"), tr("kami_libs.key.rmb") to tr("kami_claims.map.hint.pan"))
            Tool.AREA -> listOf(tr("kami_libs.key.drag") to tr("kami_claims.map.hint.area"), tr("kami_libs.key.ctrl") to tr("kami_claims.map.hint.add"), tr("kami_libs.key.rmb") to tr("kami_claims.map.hint.pan"))
            Tool.BRUSH -> listOf(tr("kami_libs.key.lmb") to tr("kami_claims.map.hint.paint"), tr("kami_libs.key.shift") to tr("kami_claims.map.hint.erase"), tr("kami_libs.key.rmb") to tr("kami_claims.map.hint.pan"))
            Tool.MEASURE -> listOf(tr("kami_libs.key.lmb") to tr("kami_claims.map.hint.points"), "Esc" to tr("kami_claims.map.hint.clear"))
        }
        val text = hover?.let { (x, z) -> "$x, $z" } ?: ""
        Draw.text(ui.g, text, r.x, r.y + 1, Palette.textMuted)
        if (!app.compact) ui.keyHints(r.right - 310, r.y - 2, hints + ("G" to tr("kami_claims.map.hint.sectors")))
    }

}

private const val TOOL_W = 20
private const val MAX_LEGEND_SECTORS = 4
