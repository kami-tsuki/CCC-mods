package kami.economy.client.admin

import kami.economy.BoostRange
import kami.economy.QuestSettings
import kami.economy.admin.QuestPreviewRes
import kami.economy.admin.QuestSpecReq
import kami.economy.client.PriceCache
import kami.economy.client.ui.ROW_H
import kami.economy.client.ui.questColor
import kami.economy.client.ui.questName
import kami.economy.quest.PickFactor
import kami.economy.quest.QuestBoost
import kami.libs.admin.Admin
import kami.libs.claims.Boost
import kami.libs.ui.app.leaveGuard
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.Remote
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.decode
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.message
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.app.remoteFailed
import kami.libs.ui.core.Rect
import kami.libs.ui.app.Route
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.app.AdminSections
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.NumberState
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.button
import kami.libs.ui.widget.buttonWidth
import kami.libs.ui.widget.checkbox
import kami.libs.ui.widget.edgeButton
import kami.libs.ui.widget.fieldHelp
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.numberField
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.segmented
import kami.libs.ui.widget.skeletonLines
import kami.libs.ui.widget.table
import kami.libs.ui.widget.textField
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json

private const val MAX_BOOSTS = 4

internal fun q(key: String, vararg args: Any) = tr("kami_economy.admin.quest.ui.$key", *args)

internal fun boostText(b: QuestBoost) = q("boost", b.pct, tr("kami_economy.quest.kind.${b.kind}"), b.hours)

internal fun NumberState.int() = (Format.parseAmount(text.text) ?: value).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

internal class BoostEdit(var kind: String, pct0: Long, hours0: Long) {
    val pct = NumberState(pct0)
    val hours = NumberState(hours0)
    fun value() = QuestBoost(kind, pct.int(), hours.int())
}

internal fun Ui.boostEditor(b: Rect, y0: Int, list: MutableList<BoostEdit>, key: String): Int {
    var y = y0
    var drop = -1
    val kinds = Boost.KINDS.map { Option(it, tr("kami_economy.quest.kind.$it")) }
    list.forEachIndexed { i, e ->
        val rest = (b.w - 90 - 18 - 12) / 2
        val cols = Rect(b.x, y, b.w, CONTROL_H).columnsFixed(90, rest, rest, 18, gap = 4)
        segmented(cols[0], kinds, e.kind, key = "$key:kind:$i")?.let { e.kind = it }
        numberField(cols[1], e.pct, min = 1, max = 1000, unit = "%", key = "$key:pct:$i")
        numberField(cols[2], e.hours, min = 1, max = 240, unit = "h", key = "$key:hours:$i")
        if (iconButton(cols[3], Icons.REMOVE, tr("kami_libs.common.remove"), key = "$key:remove:$i")) drop = i
        y += CONTROL_H + 4
    }
    if (drop >= 0) list.removeAt(drop)
    if (list.size < MAX_BOOSTS) {
        val label = q("boost.add")
        if (button(Rect(b.x, y, buttonWidth(label, Icons.ADD), CONTROL_H), label, Icons.ADD, key = "$key:add")) list += BoostEdit("research", 10, 6)
        y += CONTROL_H + 4
    }
    return y
}

internal object SpawnForm {
    val item = TextState()
    var rarity = 0
    val amount = NumberState(512)
    val hours = NumberState(24)
    var absolute = false
    val price = NumberState(100)
    val offset = TextState("25")
    var fixed = false
    val boosts = ArrayList<BoostEdit>()
    var announce = true

    fun fill(res: QuestPreviewRes) {
        item.set(res.item)
        rarity = res.rarity
        amount.commit(res.amount.toLong())
        absolute = true
        price.commit(res.lotPrice.toLong())
        fixed = res.boosts.isNotEmpty()
        boosts.clear()
        res.boosts.mapTo(boosts) { BoostEdit(it.kind, it.pct.toLong(), it.hours.toLong()) }
    }
}

internal class QuestSpawnPage(private val app: KamiApp) : Page() {
    override val title get() = q("nav.spawn")
    private var height = 420

    override fun draw(ui: Ui, r: Rect) {
        val f = SpawnForm
        val lock = lockUnless(adminManager)
        ui.scroll("quest-spawn", r, height) { c ->
            val x = c.x
            val w = c.w.coerceAtMost(420)
            var y = c.y
            val spec = f.item.text.trim()
            f.item.error = if (MarketSection.valid(spec)) null else q("invalid_item")
            ui.fieldLabel(Rect(x, y, w, 9), tr("kami_libs.common.item"))
            val itemRow = Row(Rect(x, y + 11, w, CONTROL_H), 6)
            if (ui.edgeButton(itemRow, tr("kami_libs.common.choose"), Icons.SEARCH, key = "quest-spawn:pick")) app.pickItem { f.item.set(it) }
            ui.textField(itemRow.remaining(), f.item, "minecraft:bread", maxLength = 128, key = "quest-spawn:item")
            y += 11 + CONTROL_H + 2
            ui.fieldHelp(Rect(x, y, w, 9), f.item, null)
            y += 12
            ui.fieldLabel(Rect(x, y, w, 9), tr("kami_economy.quest.ui.col.rarity"))
            ui.segmented(Rect(x, y + 11, w, CONTROL_H), (0..4).map { Option(it, questName(it), color = questColor(it)) }, f.rarity, key = "quest-spawn:rarity")?.let { f.rarity = it }
            y += 11 + CONTROL_H + 6
            val cols = Rect(x, y, w, 11 + CONTROL_H).columns(2, 8)
            ui.fieldLabel(cols[0].top(9), tr("kami_libs.common.amount"))
            ui.numberField(Rect(cols[0].x, y + 11, cols[0].w, CONTROL_H), f.amount, min = 1, max = 100_000_000, key = "quest-spawn:amount")
            ui.fieldLabel(cols[1].top(9), q("hours"))
            ui.numberField(Rect(cols[1].x, y + 11, cols[1].w, CONTROL_H), f.hours, min = 1, max = QuestSettings.MAX_HOURS.toLong(), key = "quest-spawn:hours")
            y += 11 + CONTROL_H + 6
            ui.fieldLabel(Rect(x, y, w, 9), q("price"))
            ui.segmented(Rect(x, y + 11, w, CONTROL_H), listOf(Option(false, q("price.offset")), Option(true, q("price.abs"))), f.absolute, key = "quest-spawn:mode")?.let { f.absolute = it }
            y += 11 + CONTROL_H + 4
            val pct = f.offset.text.trim().toDoubleOrNull()?.takeIf { it in -90.0..500.0 }
            f.offset.error = if (f.absolute || pct != null) null else q("invalid")
            if (f.absolute) ui.numberField(Rect(x, y, w, CONTROL_H), f.price, min = 1, max = 1_000_000_000, key = "quest-spawn:price")
            else ui.textField(Rect(x, y, w, CONTROL_H), f.offset, "25", maxLength = 6, allow = { it.isDigit() || it == '.' || it == '-' }, key = "quest-spawn:offset", suffix = "%")
            y += CONTROL_H + 2
            ui.fieldHelp(Rect(x, y, w, 9), f.offset, null)
            y += 12
            ui.fieldLabel(Rect(x, y, w, 9), q("boosts"))
            ui.segmented(Rect(x, y + 11, w, CONTROL_H), listOf(Option(false, q("boosts.rolled")), Option(true, q("boosts.fixed"))), f.fixed, key = "quest-spawn:boostmode")?.let { f.fixed = it }
            y += 11 + CONTROL_H + 4
            if (f.fixed) y = ui.boostEditor(Rect(x, 0, w, 0), y, f.boosts, "quest-spawn:boost")
            ui.checkbox(Rect(x, y, w, 12), q("announce"), f.announce, key = "quest-spawn:announce")?.let { f.announce = it }
            y += 18
            val lot = PriceCache.shownLot(spec)?.coerceAtLeast(1) ?: 1
            val course = PriceCache.shown(spec)?.price
            val lotPrice = if (f.absolute) f.price.int().toLong() else if (course != null && pct != null) Math.round(course * (1 + pct / 100)) else null
            val lots = f.amount.int() / lot
            val cost = lotPrice?.let { Format.money(it * lots) } ?: "-"
            val line = q("cost", Format.number(lots), lot, lotPrice?.let { Format.money(it) } ?: "-", cost)
            y += Draw.paragraph(ui.g, line, x, y, w, Palette.text) + 6
            val boostError = f.fixed && f.boosts.any { it.pct.text.error != null || it.hours.text.error != null }
            val error = f.item.error ?: f.offset.error ?: f.amount.text.error ?: f.hours.text.error ?: f.price.text.error.takeIf { f.absolute } ?: q("invalid").takeIf { boostError } ?: lock
            if (ui.edgeButton(Rect(x, y, w, CONTROL_H), q("spawn.go"), Icons.ADD, ButtonStyle.PRIMARY, error == null, error, key = "quest-spawn:go")) {
                val req = QuestSpecReq(
                    spec, f.rarity, f.amount.int(), f.hours.int(), if (f.absolute) f.price.int() else null,
                    if (f.absolute) null else pct?.div(100.0), if (f.fixed) f.boosts.map { it.value() } else null
                )
                reasonDialog({ app.open(it) }, q("spawn.go")) { reason, _, _ ->
                    app.adminAct("market.quest_spawn", listOf(Json.encodeToString(QuestSpecReq.serializer(), req), f.announce.toString()), reason)
                }
            }
            y += CONTROL_H + 8
            height = y - c.y
        }
    }
}

internal class QuestPreviewPage(private val app: KamiApp) : Page() {
    override val title get() = q("nav.preview")
    private var rarity = -1
    private var res: QuestPreviewRes? = null
    private var rolled = false
    private var rolling = false
    private val table = TableState<PickFactor>()

    private fun roll() {
        if (rolling) return
        rolling = true
        Admin.request("market.quest_preview", listOf(if (rarity < 0) "" else rarity.toString())) { r ->
            rolling = false
            if (!r.ok) {
                app.toast(Severity.DANGER, r.message())
                return@request
            }
            res = r.decode(QuestPreviewRes.serializer().nullable)
            rolled = true
        }
    }

    override fun draw(ui: Ui, r: Rect) {
        val lock = lockUnless(adminManager)
        val bar = Row(r.top(CONTROL_H), 6)
        if (ui.edgeButton(bar, q("roll"), Icons.UNDO, ButtonStyle.PRIMARY, lock == null, lock, pending = rolling, key = "quest-preview:roll")) roll()
        val options = listOf(Option(-1, q("rarity.any"))) + (0..4).map { Option(it, questName(it), color = questColor(it)) }
        ui.segmented(bar.remaining(), options, rarity, key = "quest-preview:rarity")?.let { rarity = it }
        var y = r.y + CONTROL_H + 8
        val p = res
        if (p != null) {
            val name = MarketSection.itemName(p.item)
            Draw.text(ui.g, "$name  (${questName(p.rarity)})", r.x, y, questColor(p.rarity))
            y += 12
            Draw.text(ui.g, q("preview.sum", Format.money(p.lotPrice.toLong()), Format.number(p.amount), Format.percent(p.offset, 1), Format.decimal(p.course, 1)), r.x, y, Palette.text)
            y += 12
            Draw.text(ui.g, p.boosts.joinToString(", ") { boostText(it) }.ifEmpty { q("boosts.none") }, r.x, y, Palette.textSecondary)
            y += 14
            val go = Rect(r.x, y, r.w, CONTROL_H)
            if (ui.edgeButton(go, q("preview.spawn"), Icons.ADD, enabled = lock == null, disabledReason = lock, left = true, key = "quest-preview:spawn")) {
                SpawnForm.fill(p)
                AdminSections.open(Route("market.quest_spawn"))
            }
            y += CONTROL_H + 6
        }
        val area = Rect(r.x, y, r.w, (r.bottom - y).coerceAtLeast(0))
        val rows = p?.pick?.top.orEmpty().take(10)
        val cols = listOf(
            itemColumn<PickFactor>(tr("kami_libs.common.item"), { it.item }),
            decimalColumn<PickFactor>(q("col.weight"), 46, 3) { it.weight },
            decimalColumn<PickFactor>(tr("kami_economy.col.age"), 40, 2) { it.age },
            decimalColumn<PickFactor>(q("col.volume"), 46, 2) { it.volume },
            decimalColumn<PickFactor>(q("col.value"), 40, 2) { it.value },
            decimalColumn<PickFactor>(tr("kami_economy.col.demand"), 46, 2) { it.demand },
            decimalColumn<PickFactor>(q("col.cooldown"), 54, 2) { it.cooldown }
        )
        ui.table(area, cols, rows, table, { it.item }, emptyText = if (rolled && p == null) q("preview.none") else q("preview.hint"), rowHeight = ROW_H, highlight = { it.item == p?.item }, key = "quest-preview", loading = rolling)
    }
}

private const val NARROW_W = 300

private class Spec(val name: String, val wide: Boolean, val get: (QuestSettings) -> String, val set: (QuestSettings, String) -> QuestSettings?)

private fun pair(t: String): List<Int>? = t.split('-').map { it.trim().toIntOrNull() ?: return null }.takeIf { it.size == 2 }

private fun ints(t: String, n: Int): List<Int>? = t.split(',').map { it.trim().toIntOrNull() ?: return null }.takeIf { it.size == n }

private fun pairs(t: String, n: Int): List<List<Int>>? = t.split(',').map { pair(it) ?: return null }.takeIf { it.size == n }

private fun ranges(t: String, n: Int): List<BoostRange>? = t.split(',').map { part ->
    val sides = part.split(':').takeIf { it.size == 2 } ?: return null
    val pct = pair(sides[0]) ?: return null
    val hours = pair(sides[1]) ?: return null
    BoostRange(pct[0], pct[1], hours[0], hours[1])
}.takeIf { it.size == n }

private fun showPairs(l: List<List<Int>>) = l.joinToString(", ") { "${it[0]}-${it[1]}" }

private fun showRanges(l: List<BoostRange>) = l.joinToString(", ") { "${it.minPct}-${it.maxPct}:${it.minHours}-${it.maxHours}" }

private val SPECS = listOf(
    Spec("minGapMinutes", false, { it.minGapMinutes.toString() }) { s, t -> t.toIntOrNull()?.let { s.copy(minGapMinutes = it) } },
    Spec("maxGapMinutes", false, { it.maxGapMinutes.toString() }) { s, t -> t.toIntOrNull()?.let { s.copy(maxGapMinutes = it) } },
    Spec("maxActive", false, { it.maxActive.toString() }) { s, t -> t.toIntOrNull()?.let { s.copy(maxActive = it) } },
    Spec("cooldownDays", false, { it.cooldownDays.toString() }) { s, t -> t.toDoubleOrNull()?.let { s.copy(cooldownDays = it) } },
    Spec("uncommonShare", false, { it.uncommonShare.toString() }) { s, t -> t.toDoubleOrNull()?.let { s.copy(uncommonShare = it) } },
    Spec("minusShare", false, { it.minusShare.toString() }) { s, t -> t.toDoubleOrNull()?.let { s.copy(minusShare = it) } },
    Spec("minusMax", false, { it.minusMax.toString() }) { s, t -> t.toDoubleOrNull()?.let { s.copy(minusMax = it) } },
    Spec("offsetMean", false, { it.offsetMean.toString() }) { s, t -> t.toDoubleOrNull()?.let { s.copy(offsetMean = it) } },
    Spec("offsetSpread", false, { it.offsetSpread.toString() }) { s, t -> t.toDoubleOrNull()?.let { s.copy(offsetSpread = it) } },
    Spec("offsetMin", false, { it.offsetMin.toString() }) { s, t -> t.toDoubleOrNull()?.let { s.copy(offsetMin = it) } },
    Spec("offsetMax", false, { it.offsetMax.toString() }) { s, t -> t.toDoubleOrNull()?.let { s.copy(offsetMax = it) } },
    Spec("bankFloor", false, { it.bankFloor.toString() }) { s, t -> t.toLongOrNull()?.let { s.copy(bankFloor = it) } },
    Spec("namespaces", true, { it.namespaces.joinToString(", ") }) { s, t ->
        t.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { s.copy(namespaces = it) }
    },
    Spec("hours", true, { it.hours.joinToString(", ") }) { s, t -> ints(t, 5)?.let { s.copy(hours = it) } },
    Spec("minTier", true, { it.minTier.joinToString(", ") }) { s, t -> ints(t, 5)?.let { s.copy(minTier = it) } },
    Spec("pityHours", true, { showPairs(it.pityHours) }) { s, t -> pairs(t, 3)?.let { s.copy(pityHours = it) } },
    Spec("amounts", true, { showPairs(it.amounts) }) { s, t -> pairs(t, 5)?.let { s.copy(amounts = it) } },
    Spec("research", true, { showRanges(it.research) }) { s, t -> ranges(t, 5)?.let { s.copy(research = it) } },
    Spec("xp", true, { showRanges(it.xp) }) { s, t -> ranges(t, 5)?.let { s.copy(xp = it) } }
)

internal class QuestSettingsPage(private val app: KamiApp) : Page() {
    override val title get() = q("nav.settings")
    private val remote = Remote("market.quest_settings", QuestSettings.serializer())
    private var seen: QuestSettings? = null
    private var enabled = true
    private val texts = LinkedHashMap<String, TextState>()
    private var height = 600

    private fun fill(s: QuestSettings) {
        enabled = s.enabled
        SPECS.forEach { texts.getOrPut(it.name) { TextState() }.set(it.get(s)) }
    }

    private fun build(): QuestSettings? {
        var s = seen ?: return null
        var ok = true
        SPECS.forEach { sp ->
            val t = texts[sp.name] ?: return null
            val next = sp.set(s, t.text.trim())
            t.error = if (next == null) q("invalid") else null
            if (next == null) ok = false else s = next
        }
        return if (ok) s.copy(enabled = enabled) else null
    }

    private fun dirty(): Boolean {
        val s = seen ?: return false
        if (texts.isEmpty()) return false
        return enabled != s.enabled || SPECS.any { texts[it.name]?.let { t -> t.text.trim() != it.get(s).trim() } ?: false }
    }

    private val guard = leaveGuard(app)

    override fun leaving(next: Route) = guard.allow(next, dirty()) { seen?.let(::fill) }

    override fun actionsWidth() = buttonWidth(tr("kami_libs.common.save"), Icons.CHECK)

    override fun actions(ui: Ui, r: Rect) {
        val lock = lockUnless(adminManager)
        val built = build()
        val error = lock ?: q("invalid").takeIf { built == null }
        if (ui.edgeButton(r, tr("kami_libs.common.save"), Icons.CHECK, ButtonStyle.PRIMARY, error == null, error, key = "quest-settings:save") && built != null)
            reasonDialog({ app.open(it) }, q("settings.title")) { reason, _, _ ->
                app.adminAct("market.quest_settings_save", listOf(Json.encodeToString(QuestSettings.serializer(), built)), reason)
            }
    }

    override fun draw(ui: Ui, r: Rect) {
        remote.poll()
        val s = remote.data
        if (s == null) {
            if (remote.error != null && !remote.loading) ui.remoteFailed(r, remote, "quest-settings") else ui.skeletonLines(r.top(80), 6)
            return
        }
        if (s !== seen) {
            seen = s
            fill(s)
        }
        val lock = lockUnless(adminManager)
        ui.scroll("quest-settings", r, height) { c ->
            var y = c.y
            ui.checkbox(Rect(c.x, y, c.w, 12), q("set.enabled"), enabled, key = "quest-set:enabled")?.let { enabled = it }
            y += 18
            val per = if (c.w < NARROW_W) 1 else 2
            SPECS.filter { !it.wide }.chunked(per).forEach { pair ->
                val cols = Rect(c.x, y, c.w, 11 + CONTROL_H).columns(per, 8)
                pair.forEachIndexed { i, sp ->
                    val t = texts[sp.name] ?: return@forEachIndexed
                    ui.fieldLabel(cols[i].top(9), q("set.${sp.name}"))
                    ui.textField(Rect(cols[i].x, y + 11, cols[i].w, CONTROL_H), t, "", maxLength = 24, allow = { ch -> ch.isDigit() || ch == '.' || ch == '-' }, key = "quest-set:${sp.name}")
                }
                y += 11 + CONTROL_H + 6
            }
            SPECS.filter { it.wide }.forEach { sp ->
                val t = texts[sp.name] ?: return@forEach
                ui.fieldLabel(Rect(c.x, y, c.w, 9), q("set.${sp.name}"))
                ui.textField(Rect(c.x, y + 11, c.w, CONTROL_H), t, "", maxLength = 200, key = "quest-set:${sp.name}")
                y += 11 + CONTROL_H + 6
            }
            y += 8
            ui.fieldLabel(Rect(c.x, y, c.w, 9), q("set.clock"))
            val cells = Rect(c.x, y + 11, c.w, CONTROL_H).columns(5, 4)
            (0..4).forEach { i ->
                if (ui.button(cells[i], questName(i), enabled = lock == null, disabledReason = lock, key = "quest-set:clock:$i"))
                    reasonDialog({ app.open(it) }, q("clock.title", questName(i))) { reason, _, _ ->
                        app.adminAct("market.quest_clock", listOf(i.toString()), reason)
                    }
            }
            y += 11 + CONTROL_H + 8
            height = y - c.y
        }
    }
}
