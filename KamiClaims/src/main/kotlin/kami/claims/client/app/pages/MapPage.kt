package kami.claims.client.app.pages

import kami.claims.client.ClientClaims
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.libs.ui.app.Consequence
import kami.claims.client.app.Dialogs
import kami.libs.ui.widget.Flags
import kami.claims.client.app.Vocabulary
import kami.claims.client.map.ClaimsLayer
import kami.claims.client.map.MapMode
import kami.claims.client.map.MiniMap
import kami.claims.client.store.ClaimsStore
import kami.claims.net.PreviewLine
import kami.claims.service.View
import kami.libs.ui.app.CRUMBS_H
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.map.ChunkMap
import kami.libs.ui.map.ChunkPoint
import kami.libs.ui.map.TerrainCache
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.text.trJson
import kami.libs.ui.text.trn
import kami.libs.ui.widget.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.lwjgl.glfw.GLFW

class MapPage(app: ClaimsApp) : ClaimsPage(app) {
    enum class Tool(val key: Int) {
        SELECT(GLFW.GLFW_KEY_Q), AREA(GLFW.GLFW_KEY_E), BRUSH(GLFW.GLFW_KEY_B), MEASURE(GLFW.GLFW_KEY_R);

        val label get() = tr("kami_claims.map.tool.${name.lowercase()}")
        val tip get() = tr("kami_claims.map.tool.${name.lowercase()}.tooltip")
    }

    enum class Plan { CLAIM, RETYPE, UNCLAIM }

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

    override fun actionsWidth() = 2 * CRUMBS_H + 2

    override fun actions(ui: Ui, r: Rect) {
        if (r.w < actionsWidth()) return
        if (ui.iconButton(Rect(r.right - r.h, r.y, r.h, r.h), Icons.LOCATE, tr("kami_claims.map.center_me.tooltip"), key = "map-me")) map.center(snap.px, snap.pz)
        val capital = info?.claimList?.firstOrNull { it.capital }
        if (ui.iconButton(Rect(r.right - 2 * r.h - 2, r.y, r.h, r.h), Icons.CROWN, tr("kami_claims.map.center_capital.tooltip"), capital != null, key = "map-capital", disabledReason = tr("kami_claims.alert.capital.title"))) capital?.let { map.center(it.x, it.z) }
    }

    override fun draw(ui: Ui, r: Rect) {
        if (!centered) { map.center(snap.px, snap.pz); centered = true }
        if (planType.isEmpty()) planType = snap.types.firstOrNull()?.name ?: ""
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
        side(ui, panel)
        requestPreview()
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
                val nums = jump.text.split(' ', ',', ';').mapNotNull { it.trim().toIntOrNull() }
                if (nums.size >= 2) {
                    val (x, z) = if (abs(nums[0]) > 3000 || abs(nums[1]) > 3000 || jump.text.contains("b")) Math.floorDiv(nums[0], 16) to Math.floorDiv(nums[1], 16) else nums[0] to nums[1]
                    map.center(x, z); select(x, z, false)
                }
            }
            ui.tooltip("jump-tip", jumpRect, tr("kami_claims.map.jump.tooltip"))
        }
    }

    private fun layerPopover(ui: Ui, anchor: Rect) {
        ui.onEscape(55) { layersOpen = false }
        val items = listOf("borders", "labels", "markers", "grid").map { it to tr("kami_claims.map.layer.$it") }
        ui.popover(anchor, 170, items.size * 16 + 38, onOutside = { layersOpen = false }) { box ->
            var y = box.y + 5
            items.forEach { (k, label) ->
                toggle(Rect(box.x + 6, y, box.w - 12, 14), k in ClientClaims.prefs.mapLayers, label, key = "layer:$k")?.let { on ->
                    if (on) ClientClaims.prefs.mapLayers += k else ClientClaims.prefs.mapLayers -= k
                    ClientClaims.savePrefs()
                }
                y += 16
            }
            toggle(Rect(box.x + 6, y, box.w - 12, 14), ClientClaims.prefs.terrain, tr("kami_claims.map.mode.terrain"), tip = tr("kami_claims.map.layer.terrain.tooltip"), key = "layer:terrain")?.let {
                ClientClaims.prefs.terrain = it; TerrainCache.enabled = it; ClientClaims.savePrefs()
            }
            y += 16
            toggle(Rect(box.x + 6, y, box.w - 12, 14), ClientClaims.prefs.overlay, tr("kami_claims.map.layer.xaero"), key = "layer:xaero")?.let { ClientClaims.prefs.overlay = it; ClientClaims.savePrefs() }
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
        if (!ClientClaims.active(dim)) ui.banner(Rect(r.x + 6, r.y + 6, min(260, r.w - 12), 22), Severity.INFO, tr("kami_claims.block.dimension"))
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
                if (w * h > limit) app.toast(Severity.WARNING, tr("kami_claims.map.too_large"), tr("kami_claims.map.too_large.desc", Format.number(limit), Format.number(w * h)))
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

    private fun overlays(ui: Ui, dragStart: ChunkPoint?, dragging: ChunkPoint?) {
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
        val entries: List<Pair<Int, String>> = when (mode) {
            MapMode.POLITICAL -> ClientClaims.countries.take(6).map { Palette.opaque(it.color) to it.name } + (Palette.geoReserved to tr("kami_claims.help.term.reserved"))
            MapMode.RELATIONS -> (1..4).map { Vocabulary.relationColor(it) to Vocabulary.relationLabel(it) } + (Vocabulary.relationColor(0) to Vocabulary.relationLabel(0))
            MapMode.LANDUSE -> ClientClaims.types.map { Vocabulary.type(it).color to Vocabulary.type(it).label }
            MapMode.ECONOMY -> listOf(Palette.success to tr("kami_claims.map.legend.cheap"), Palette.warning to tr("kami_claims.map.legend.medium"), Palette.danger to tr("kami_claims.map.legend.expensive"))
            MapMode.RISK -> listOf(Palette.danger to tr("kami_claims.stats.in_debt"), Palette.warning to tr("kami_claims.map.legend.overdue"), Palette.geoReserved to tr("kami_claims.help.term.reserved"), Palette.success to tr("kami_claims.map.legend.healthy"))
            MapMode.PLOTS -> listOf(Palette.money to tr("kami_claims.map.legend.yours"), Palette.success to tr("kami_claims.plots.tab.free"), Palette.geoNeutral to tr("kami_claims.map.legend.taken"))
            MapMode.TERRAIN -> emptyList()
        }
        if (entries.isEmpty()) return
        val w = 130
        val h = if (legendOpen) entries.size * 11 + 18 else 14
        val box = Rect(r.x + 4, r.bottom - h - 4, w, h)
        Draw.fill(ui.g, box, Palette.alpha(0x0C0E12, 0xC8))
        Draw.text(ui.g, tr("kami_claims.map.legend", mode.label).uppercase(Format.locale), box.x + 4, box.y + 3, Palette.textMuted)
        if (ui.hovering(box.top(12))) ui.cursor = Cursor.HAND
        if (ui.pressed(box.top(12)) != null) legendOpen = !legendOpen
        if (!legendOpen) return
        entries.forEachIndexed { i, (color, label) -> ui.legendItem(box.x + 4, box.y + 15 + i * 11, color, Draw.fit(label, w - 20), color == Palette.danger && mode == MapMode.RISK) }
    }

    private fun hoverTip(ui: Ui, r: Rect, x: Int, z: Int) {
        ui.tooltip("chunk", r) {
            val e = ClientClaims.at(dim, x, z)
            val lines = ArrayList<Pair<String, Int>>()
            if (e == null) {
                lines += tr(if (ClientClaims.reserved(dim, x, z)) "kami_claims.map.chunk.reserved" else "kami_claims.world.nomansland") to Palette.textMuted
            } else {
                val c = ClientClaims.country(e)
                lines += Vocabulary.relationLabel(c?.relation ?: 0) to Vocabulary.relationColor(c?.relation ?: 0)
                ClientClaims.typeName(e)?.let { lines += (if (e.flags and View.CAPITAL != 0) tr("kami_claims.map.chunk.capital", Vocabulary.type(it).label) else Vocabulary.type(it).label) to Vocabulary.type(it).color }
                if (e.flags and View.MINE != 0) lines += tr("kami_claims.map.chunk.mine") to Palette.money
                if (e.flags and View.CLAIMABLE != 0) lines += tr("kami_claims.map.chunk.claimable") to Palette.success
                if (e.flags and View.DEBT != 0) lines += tr("kami_claims.stats.in_debt") to Palette.danger
            }
            previewFor()?.cells?.firstOrNull { it.x == x && it.z == z }?.let { cell ->
                val text = when (cell.outcome) {
                    "CLAIM" -> tr("kami_claims.map.preview.claim")
                    "FREE" -> tr("kami_claims.map.preview.free")
                    "RETYPE" -> tr("kami_claims.map.preview.retype", Vocabulary.type(planType).label)
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
            Tool.SELECT -> listOf(tr("kami_claims.map.key.lmb") to tr("kami_claims.map.hint.select"), tr("kami_claims.map.key.ctrl_lmb") to tr("kami_claims.map.hint.add"), tr("kami_claims.map.key.rmb") to tr("kami_claims.map.hint.pan"))
            Tool.AREA -> listOf(tr("kami_claims.map.key.drag") to tr("kami_claims.map.hint.area"), tr("kami_claims.map.key.ctrl") to tr("kami_claims.map.hint.add"), tr("kami_claims.map.key.rmb") to tr("kami_claims.map.hint.pan"))
            Tool.BRUSH -> listOf(tr("kami_claims.map.key.lmb") to tr("kami_claims.map.hint.paint"), tr("kami_claims.map.key.shift") to tr("kami_claims.map.hint.erase"), tr("kami_claims.map.key.rmb") to tr("kami_claims.map.hint.pan"))
            Tool.MEASURE -> listOf(tr("kami_claims.map.key.lmb") to tr("kami_claims.map.hint.points"), "Esc" to tr("kami_claims.map.hint.clear"))
        }
        val text = hover?.let { (x, z) -> "$x, $z" } ?: ""
        Draw.text(ui.g, text, r.x, r.y + 1, Palette.textMuted)
        if (!app.compact) ui.keyHints(r.right - 250, r.y - 2, hints)
    }

    private fun side(ui: Ui, r: Rect) {
        val inner = ui.sidePanel(r, inset = 6).rest
        when {
            selection.isEmpty() -> overview(ui, inner)
            selection.size == 1 && !planning -> single(ui, inner)
            else -> planner(ui, inner)
        }
    }

    private fun overview(ui: Ui, r: Rect) {
        val f = Flow(r, 4)
        Draw.text(ui.g, tr("kami_claims.map.howto").uppercase(Format.locale), f.take(10).x, r.y, TextStyle.LABEL)
        listOf(
            Icons.CURSOR to tr("kami_claims.map.howto.inspect"),
            Icons.AREA to tr("kami_claims.map.howto.plan"),
            Icons.PAN to tr("kami_claims.map.howto.navigate")
        ).forEachIndexed { i, (icon, text) ->
            val row = f.take(TABLE_ROW_H)
            val x = row.x + Draw.leadIcon(ui.g, icon, row.x, row.centerY) + 2
            val shown = Draw.fit(text, row.right - x)
            Draw.text(ui.g, shown, x, row.y + 3, Palette.textSecondary)
            if (shown != text) ui.tooltip("howto:$i", row, text)
        }
        f.skip(4)
        ui.section(f.take(14), tr("kami_claims.map.prices"), tr("kami_claims.map.prices.unit"))
        snap.types.forEach { t ->
            val row = f.take(TABLE_ROW_H)
            val look = Vocabulary.type(t.name)
            val x = row.x + Draw.leadIcon(ui.g, look.icon, row.x, row.centerY) + 2
            Draw.text(ui.g, look.label, x, row.y + 3, look.color)
            Draw.textRight(ui.g, if (t.period > 1) "${Format.money(t.price.toLong())} / ${Format.days(t.period.toLong())}" else Format.money(t.price.toLong()), row.right, row.y + 3, Palette.textSecondary)
            ui.tooltip("price:${t.name}", row, look.description)
        }
        info?.let { i ->
            f.skip(6)
            ui.section(f.take(14), tr("kami_claims.chunks.help.table"))
            ui.property(f.take(11), tr("kami_claims.nav.chunks"), Format.number(i.chunks))
            ui.property(f.take(11), tr("kami_claims.map.free_left"), Format.number((i.freeAllowed - i.chunks).coerceAtLeast(0)), Palette.success)
            ui.property(f.take(11), tr("kami_claims.ledger.upkeep"), Format.perDay(Format.money(i.upkeep)), Palette.danger)
        }
    }

    private fun single(ui: Ui, r: Rect) {
        val (x, z) = unkey(selection.first())
        val d = snap.detail?.takeIf { it.x == x && it.z == z }
        if (d == null) {
            ui.spinner(r.x, r.y)
            Draw.text(ui.g, tr("kami_claims.map.loading", x, z), r.x + 12, r.y, Palette.textMuted)
            return
        }
        val f = Flow(r, 3)
        val head = f.take(22)
        val e = ClientClaims.at(dim, x, z)
        val country = e?.let { ClientClaims.country(it) }
        if (country != null) Flags.draw(ui.g, Rect(head.x, head.y + 4, 16, 12), country.color, country.pattern, country.emblem, country.secondary)
        val textX = head.x + (if (country != null) 21 else 0)
        Draw.text(ui.g, Draw.fit(d.country.ifEmpty { tr("kami_claims.world.nomansland") }, head.right - textX), textX, head.y + 1, TextStyle.HEADING)
        Draw.text(ui.g, tr("kami_claims.map.chunk", x, z), textX, head.y + 11, Palette.textMuted)
        MiniMap.draw(ui, f.take(70), x, z, 3, "side", listOf(x to z))
        if (d.type.isNotEmpty()) {
            val look = Vocabulary.type(d.type)
            ui.property(f.take(11), tr("kami_claims.chunks.col.type"), if (d.capital) tr("kami_claims.map.chunk.capital", look.label) else look.label, look.color, tip = look.description)
            ui.property(f.take(11), tr("kami_claims.ledger.upkeep"), if (d.free) tr("kami_claims.chunks.status.free") else Vocabulary.rate(d.price, d.period))
            if (d.owner.isNotEmpty()) ui.property(f.take(11), tr("kami_claims.chunks.col.tenant"), d.owner)
            if (d.tax >= 0) ui.property(f.take(11), tr("kami_claims.ledger.plot_tax"), Format.perDay(Format.money(d.tax.toLong())))
            if (d.at > 0) ui.property(f.take(11), tr("kami_claims.chunks.col.claimed"), Format.ago(d.at))
            if (d.debt > 0) {
                val max = limits?.maxDebt ?: 3
                ui.property(f.take(11), tr("kami_claims.help.term.debt"), tr("kami_claims.map.debt", d.debt, max), Palette.danger)
                f.skip(2)
                ui.meter(f.take(5), d.debt, max, { if (it >= max) Severity.DANGER else Severity.WARNING })
            }
        } else if (d.note.isNotEmpty()) f.take(Draw.paragraph(ui.g, trJson(d.note), r.x, f.rest.y, r.w, Palette.textSecondary))
        if (d.reserved.isNotEmpty()) f.take(ui.callout(f.rest, Severity.WARNING, tr("kami_claims.map.reserved", d.reserved)))
        if (d.access.isNotEmpty()) {
            f.skip(4)
            ui.section(f.take(14), tr("kami_claims.map.access"))
            val cells = f.take(2 * TABLE_ROW_H).grid(2, TABLE_ROW_H, 4)
            listOf("break" to 0, "place" to 1, "interact" to 2, "container" to 3).forEachIndexed { i, (k, a) ->
                val allowed = d.access[k] == true
                val look = Vocabulary.actions[a]
                val tx = cells[i].x + Draw.leadIcon(ui.g, if (allowed) Icons.CHECK else Icons.CROSS, cells[i].x, cells[i].centerY) + 2
                Draw.text(ui.g, look.label, tx, cells[i].y + 3, if (allowed) Palette.textSecondary else Palette.textMuted)
                ui.tooltip("access:$k", cells[i], look.description)
            }
        }
        if (d.locked.isNotEmpty()) f.take(ui.callout(f.rest, Severity.INFO, tr("kami_claims.map.locked", trJson(d.locked))))
        chunkActions(ui, f, x, z, e)
    }

    private fun chunkActions(ui: Ui, f: Flow, x: Int, z: Int, e: View.Entry?) {
        val own = e != null && e.flags and View.OWN != 0
        f.skip(4)
        val rest = f.rest
        var y = rest.bottom - CONTROL_H
        fun place(label: String, icon: Icon, style: ButtonStyle, enabled: Boolean, reason: String?, key: String, action: () -> Unit) {
            val cell = Rect(rest.x, y, rest.w, CONTROL_H)
            if (ui.button(cell, label, icon, style, enabled, reason, pending = pending(key), key = "chunk-$key")) action()
            y -= CONTROL_H + 3
        }
        if (e == null && info != null) place(tr("kami_claims.map.action.plan"), Icons.ADD, ButtonStyle.PRIMARY, can("claim"), lock("claim"), "claim") { plan = Plan.CLAIM; planning = true; ClaimsStore.clearPreview() }
        if (e?.flags?.and(View.CLAIMABLE) != 0 && e != null) place(tr("kami_claims.plots.rent"), Icons.HOUSE, ButtonStyle.PRIMARY, can("plot"), lock("plot"), "plot_claim") {
            val d = snap.detail
            val tax = Format.perDay(Format.money((d?.tax?.takeIf { it >= 0 } ?: info?.tax ?: 0).toLong()))
            Dialogs.confirm(app, tr("kami_claims.plots.rent.confirm.title", x, z), tr("kami_claims.chunk_type.residential"), Icons.HOUSE, listOf(
                Consequence(tr("kami_claims.plots.rent.tax", tax)),
                Consequence(tr("kami_claims.plots.rent.lapse", Format.days((info?.shutdown ?: 0).toLong()), Format.days((info?.release ?: 0).toLong())), Severity.WARNING),
                Consequence(tr("kami_claims.plots.rent.access"), Severity.SUCCESS)
            ), tr("kami_claims.plots.rent.action", tax), "plot_claim", arrayOf(x.toString(), z.toString()))
        }
        if (e?.flags?.and(View.MINE) != 0 && e != null) place(tr("kami_claims.plots.release"), Icons.REMOVE, ButtonStyle.DANGER, true, null, "plot_release") {
            Dialogs.confirm(app, tr("kami_claims.plots.release.confirm.title", x, z), tr("kami_claims.plots.release.tax"), Icons.HOUSE, listOf(Consequence(tr("kami_claims.plots.release.access")), Consequence(tr("kami_claims.plots.release.open"), Severity.WARNING)), tr("kami_claims.plots.release.action"), "plot_release", arrayOf(x.toString(), z.toString()), danger = true, hold = true)
        }
        if (own) {
            if (e.flags and View.CAPITAL == 0) place(tr("kami_claims.map.action.capital"), Icons.CROWN, ButtonStyle.SECONDARY, can("capital"), lock("capital"), "capital") {
                Dialogs.confirm(app, tr("kami_claims.map.capital.confirm.title", x, z), null, Icons.CROWN, listOf(
                    Consequence(tr("kami_claims.map.capital.safe")),
                    Consequence(tr("kami_claims.map.capital.cooldown", trn("kami_claims.unit.day", limits?.capitalCooldownDays ?: 7)), Severity.WARNING)
                ), tr("kami_claims.cap.capital"), "capital", arrayOf(x.toString(), z.toString()))
            }
            place(tr("kami_claims.map.action.retype"), Icons.EDIT, ButtonStyle.SECONDARY, can("claim"), lock("claim"), "retype") { plan = Plan.RETYPE; planning = true; ClaimsStore.clearPreview() }
        }
    }

    private fun planner(ui: Ui, r: Rect) {
        val f = Flow(r, 4)
        val count = selection.size
        val head = f.take(12)
        Draw.text(ui.g, tr("kami_claims.chunks.selected", trn("kami_claims.unit.chunk", count)), head.x, head.y, TextStyle.HEADING)
        val clear = tr("kami_claims.map.clear")
        if (ui.link(head.right - Draw.width(clear), head.y, clear, key = "clear-selection")) { selection.clear(); return }
        val entries = selection.map { unkey(it) }.map { (x, z) -> ClientClaims.at(dim, x, z) }
        val free = entries.count { it == null }
        val own = entries.count { it != null && it.flags and View.OWN != 0 }
        val modes = listOf(
            Option(Plan.CLAIM, tr("kami_claims.map.plan.claim"), Icons.ADD, tr("kami_claims.map.plan.claim.tooltip"), disabledReason = if (free == 0) tr("kami_claims.map.plan.claim.disabled") else lock("claim")),
            Option(Plan.RETYPE, tr("kami_claims.chunks.col.type"), Icons.EDIT, tr("kami_claims.map.plan.retype.tooltip"), disabledReason = if (own == 0) tr("kami_claims.map.plan.own.disabled") else lock("claim")),
            Option(Plan.UNCLAIM, tr("kami_claims.chunks.release.action"), Icons.REMOVE, tr("kami_claims.map.plan.unclaim.tooltip"), disabledReason = if (own == 0) tr("kami_claims.map.plan.own.disabled") else lock("claim"))
        )
        ui.segmented(f.take(CONTROL_H), modes, plan, key = "plan")?.let { plan = it; ClaimsStore.clearPreview(); touchSelection() }
        if (plan != Plan.UNCLAIM) {
            ui.fieldLabel(f.take(9), tr(if (plan == Plan.CLAIM) "kami_claims.map.plan.claim_as" else "kami_claims.map.plan.new_type"))
            ui.select(f.take(CONTROL_H), snap.types.map { t ->
                val look = Vocabulary.type(t.name)
                Option(t.name, look.label, look.icon, "${Vocabulary.rate(t.price, t.period)} · ${look.description}", look.color)
            }, planType, key = "plan-type")?.let { planType = it; ClaimsStore.clearPreview(); touchSelection() }
        }
        val preview = previewFor()
        f.skip(2)
        if (preview == null) {
            val row = f.take(14)
            val reason = lock("claim")
            when {
                !ClientClaims.active(dim) -> f.take(ui.callout(f.rest, Severity.INFO, tr("kami_claims.block.dimension")))
                reason != null -> f.take(ui.callout(f.rest, Severity.WARNING, reason))
                System.currentTimeMillis() - previewWaitSince > 1400L -> {
                    f.take(ui.callout(f.rest, Severity.WARNING, tr("kami_claims.map.check.delayed")))
                    if (ui.button(f.take(CONTROL_H), tr("kami_claims.map.check.retry"), Icons.PENDING, ButtonStyle.SECONDARY, key = "preview-retry")) {
                        ClaimsStore.clearPreview()
                        previewWaitSince = System.currentTimeMillis()
                        lastPreviewSent = 0L
                    }
                }
                else -> {
                    ui.spinner(row.x, row.y + 1)
                    Draw.text(ui.g, tr("kami_claims.map.check.running"), row.x + 12, row.y + 1, Palette.textMuted)
                }
            }
            return
        }
        previewWaitSince = 0L
        val byOutcome = preview.cells.groupBy { it.outcome }
        val ready = (byOutcome["CLAIM"]?.size ?: 0) + (byOutcome["FREE"]?.size ?: 0) + (byOutcome["RETYPE"]?.size ?: 0) + (byOutcome["UNCLAIM"]?.size ?: 0)
        ui.section(f.take(14), tr("kami_claims.map.result"))
        byOutcome["CLAIM"]?.let { outcomeRow(ui, f.take(TABLE_ROW_H), Palette.success, tr("kami_claims.map.result.claim", Format.number(it.size)), Icons.CHECK) }
        byOutcome["FREE"]?.let { outcomeRow(ui, f.take(TABLE_ROW_H), Palette.success, tr("kami_claims.map.result.free", Format.number(it.size)), Icons.STAR) }
        byOutcome["RETYPE"]?.let { outcomeRow(ui, f.take(TABLE_ROW_H), Palette.warning, tr("kami_claims.map.result.retype", Format.number(it.size)), Icons.EDIT) }
        byOutcome["UNCLAIM"]?.let { outcomeRow(ui, f.take(TABLE_ROW_H), Palette.danger, tr("kami_claims.map.result.unclaim", Format.number(it.size)), Icons.REMOVE) }
        byOutcome["SKIP"]?.let { outcomeRow(ui, f.take(TABLE_ROW_H), Palette.textMuted, tr("kami_claims.map.result.skip", Format.number(it.size)), Icons.CHEVRON_RIGHT) }
        byOutcome["BLOCKED"]?.groupBy { trJson(it.reason) }?.forEach { (reason, cells) ->
            val row = f.take(TABLE_ROW_H)
            outcomeRow(ui, row, Palette.danger, tr("kami_claims.map.result.blocked", Format.number(cells.size), reason), Icons.CROSS)
            ui.tooltip("blocked:$reason", row, reason)
        }
        byOutcome["RETYPE"]?.mapNotNull { it.reason.ifEmpty { null } }?.distinct()?.forEach { f.take(ui.callout(f.rest, Severity.WARNING, trJson(it))) }
        val info = info ?: return
        val net = info.income - info.upkeep - info.jobs
        val after = net - preview.upkeepPerDay.toLong()
        f.skip(4)
        ui.section(f.take(14), tr("kami_claims.map.cost"))
        if (plan == Plan.CLAIM) ui.property(f.take(11), tr("kami_claims.map.cost.now"), Format.money(preview.cost), if (preview.cost > info.treasury) Palette.danger else Palette.money)
        ui.property(f.take(11), tr("kami_claims.map.cost.upkeep"), perDayDelta(preview.upkeepPerDay), if (preview.upkeepPerDay > 0) Palette.danger else Palette.success)
        ui.property(f.take(11), tr("kami_claims.kpi.runway"), "${app.runwayText(info.treasury, net)} → ${app.runwayText(info.treasury - preview.cost, after)}", app.runwayColor(info.treasury - preview.cost, after))
        val rest = f.rest
        val button = Rect(rest.x, rest.bottom - CONTROL_H, rest.w, CONTROL_H)
        val label = when (plan) {
            Plan.CLAIM -> tr("kami_claims.map.commit.claim", trn("kami_claims.unit.chunk", ready))
            Plan.RETYPE -> tr("kami_claims.map.commit.retype", trn("kami_claims.unit.chunk", ready))
            Plan.UNCLAIM -> tr("kami_claims.map.commit.unclaim", trn("kami_claims.unit.chunk", ready))
        }
        val keyName = when (plan) { Plan.CLAIM -> "claimcells"; Plan.RETYPE -> "typecells"; Plan.UNCLAIM -> "unclaimcells" }
        if (ui.button(button, label, if (plan == Plan.UNCLAIM) Icons.REMOVE else Icons.CHECK, if (plan == Plan.UNCLAIM) ButtonStyle.DANGER else ButtonStyle.PRIMARY,
                ready > 0 && lock("claim") == null, lock("claim") ?: tr("kami_claims.map.commit.disabled"), pending = pending(keyName), key = "plan-go")) commit(ready, preview, after)
    }

    private fun perDayDelta(v: Double) = Format.perDay(tr("kami_libs.unit.money", (if (v > 0) "+" else "") + Format.decimal(v)))

    private fun outcomeRow(ui: Ui, r: Rect, color: Int, text: String, icon: Icon) {
        val x = r.x + Draw.leadIcon(ui.g, icon, r.x, r.centerY) + 2
        Draw.text(ui.g, Draw.fit(text, r.right - x), x, r.y + 3, color)
    }

    private fun commit(ready: Int, preview: PreviewLine, after: Long) {
        val info = info ?: return
        val args = planArgs()
        val runwayAfter = if (after >= 0) Long.MAX_VALUE else (info.treasury - preview.cost) / -after
        when (plan) {
            Plan.CLAIM -> {
                val items = listOfNotNull(
                    if (runwayAfter < 7) Consequence(tr("kami_claims.map.commit.runway", trn("kami_claims.unit.day", runwayAfter)), Severity.DANGER) else null,
                    Consequence(tr("kami_claims.map.commit.cost", Format.money(preview.cost))),
                    Consequence(tr("kami_claims.chunks.retype.delta", perDayDelta(preview.upkeepPerDay)), if (preview.upkeepPerDay > 0) Severity.WARNING else Severity.NEUTRAL),
                    Consequence(tr("kami_claims.notice.release_lock"))
                )
                if (ClientClaims.prefs.skipClaimConfirm && runwayAfter >= 7) ClaimsStore.send("claimcells", *args, key = "claimcells")
                else Dialogs.confirm(app, tr("kami_claims.map.commit.claim.title", trn("kami_claims.unit.chunk", ready)), Vocabulary.type(planType).label, Icons.AREA, items, tr("kami_claims.map.plan.claim"), "claimcells", args)
            }
            Plan.RETYPE -> Dialogs.confirm(app, tr("kami_claims.chunks.retype.confirm.title", trn("kami_claims.unit.chunk", ready), Vocabulary.type(planType).label), null, Icons.EDIT, listOfNotNull(
                if (preview.cells.any { it.reason.isNotEmpty() && it.outcome == "RETYPE" }) Consequence(tr("kami_claims.map.commit.plots_lost"), Severity.DANGER) else null,
                Consequence(tr("kami_claims.chunks.retype.delta", perDayDelta(preview.upkeepPerDay))),
                Consequence(tr("kami_claims.map.commit.rules"))
            ), tr("kami_claims.chunks.retype.action"), "typecells", args)
            Plan.UNCLAIM -> Dialogs.confirm(app, tr("kami_claims.chunks.release.confirm.title", trn("kami_claims.unit.chunk", ready)), tr("kami_claims.chunks.release.confirm.subtitle"), Icons.REMOVE, listOf(
                Consequence(tr("kami_claims.chunks.release.open"), Severity.DANGER),
                Consequence(tr("kami_claims.map.commit.builds"), Severity.WARNING),
                Consequence(tr("kami_claims.chunks.release.saved", perDayDelta(-preview.upkeepPerDay)), Severity.SUCCESS)
            ), tr("kami_claims.chunks.release.action"), "unclaimcells", args, danger = true, hold = true)
        }
        ClaimsStore.clearPreview()
    }
}

private const val TOOL_W = 20
