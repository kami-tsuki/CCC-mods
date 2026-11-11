package kami.claims.client.hud

import kami.libs.ui.text.trJson
import kami.libs.ui.text.tr
import kami.libs.ui.text.trn
import kami.claims.client.Prefs
import kami.claims.KamiClaims
import kami.claims.client.ClientClaims
import kami.claims.client.ClientHooks
import kami.claims.client.WarDisplay
import kami.libs.ui.widget.Flags
import kami.claims.client.app.Finance
import kami.claims.client.app.Vocabulary
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.ClientResearch
import kami.claims.client.world.AccessGuess
import kami.claims.client.world.BlockHint
import kami.claims.client.world.Borders
import kami.claims.client.world.Hint
import kami.claims.service.AlertLine
import kami.claims.service.View
import kami.libs.ui.core.Dim
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.hud.Anchor
import kami.libs.ui.hud.BarData
import kami.libs.ui.hud.BarPrefs
import kami.libs.ui.hud.HudBar
import kami.libs.ui.hud.HudBars
import kami.libs.ui.hud.HudElement
import kami.libs.ui.hud.HudLayout
import kami.libs.ui.hud.HudOption
import kami.libs.ui.hud.HudPos
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.widget.iconFor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.atan2
import kotlin.math.roundToInt

object Hud {
    private const val WAR = "kami_claims:war"
    private const val TERRITORY = "kami_claims:territory"
    private const val TOASTS = "kami_claims:toasts"
    private const val TAX = "kami_claims:tax"
    private const val XP_BAR = "kami_claims:xp"
    private const val TAX_REFRESH_MS = 60_000L
    private const val MAX_FAILURES = 3
    private const val RETRY_MS = 5000L

    fun register() {
        HudLayout.element(HudElement(WAR, HudPos(Anchor.TOP_LEFT), { tr("kami_claims.hud.sample_war", "${tr("kami_libs.unit.day.short", 2)} ${tr("kami_libs.unit.hour.short", 4)}") }, { Palette.danger }))
        HudLayout.element(HudElement(TERRITORY, HudPos(Anchor.TOP), { tr("kami_claims.hud.sample_territory") }))
        HudLayout.element(HudElement(TOASTS, HudPos(Anchor.TOP_RIGHT, 0, 18), { tr("kami_claims.hud.sample_toast") }))
        HudLayout.element(HudElement(TAX, HudPos(Anchor.BOTTOM_LEFT), { taxSample().first().first }, lines = ::taxSample))
        HudBars.register(HudBar(XP_BAR, { tr("kami_claims.hud.xp") }, HudPos(Anchor.TOP, 0, 18), BarPrefs(), ::countryBar))
        HudLayout.option(HudOption({ tr("kami_claims.hud.tax") }, listOf(
            TaxHudMode.OFF.name to { tr("kami_claims.hud.tax.off") },
            TaxHudMode.ALWAYS.name to { tr("kami_claims.hud.tax.always") },
            TaxHudMode.IMPORTANT.name to { tr("kami_claims.hud.tax.important") }
        ), { ClientClaims.prefs.taxHud.name }, { ClientClaims.prefs.taxHud = TaxHudMode.valueOf(it); ClientClaims.savePrefs() }))
        HudLayout.option(HudOption({ tr("kami_claims.hud.war_display") }, listOf(
            WarDisplay.NONE.name to { tr("kami_claims.hud.display.none") },
            WarDisplay.BOSS.name to { tr("kami_claims.hud.display.boss") },
            WarDisplay.HUD.name to { tr("kami_claims.hud.display.hud") },
            WarDisplay.BOTH.name to { tr("kami_claims.hud.display.both") }
        ), { ClientClaims.prefs.warDisplay.name }, { ClientClaims.prefs.warDisplay = WarDisplay.valueOf(it); ClientClaims.savePrefs() }))
    }

    private class Banner(val title: String, val body: String, val color: Int, val at: Long)
    private class HudToast(val alert: AlertLine, val at: Long)

    private var lastOwner = Int.MIN_VALUE
    private var lastDim = ""
    private var banner: Banner? = null
    private val seenAlerts = HashSet<String>()
    private val toasts = ArrayList<HudToast>()
    private class Failure(var count: Int, var at: Long)
    private val failures = HashMap<String, Failure>()
    private val openHint = Memo()
    private val warMemo = Memo()
    private val taxMemo = Memo()
    private var lastTaxRefresh = 0L
    private val territoryMemo = Memo()
    private class Territory(val country: View.CountryView?, val name: String, val extra: String, val typeColor: Int?, val distance: String?, val width: Int)
    private val arrows = listOf("→", "↘", "↓", "↙", "←", "↖", "↑", "↗")

    fun alertsChanged() {
        if (!ClientClaims.prefs.worldToasts) return
        val alerts = ClaimsStore.snap?.alerts ?: return
        alerts.filter { it.severity != "INFO" && it.id !in ClientClaims.prefs.dismissed && seenAlerts.add(it.id) }.forEach { toasts += HudToast(it, System.currentTimeMillis()) }
        while (toasts.size > 3) toasts.removeAt(0)
    }

    fun render(g: GuiGraphics) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.options.hideGui || mc.screen != null) return
        val dim = Dim.current()
        val now = System.currentTimeMillis()
        val prefs = ClientClaims.prefs
        val active = ClientClaims.active(dim)
        if (active && prefs.hud) guarded("territory", now) { territory(g, dim, player.blockX, player.blockZ, now) }
        if (active && prefs.taxHud != TaxHudMode.OFF) guarded("tax", now) { tax(g, dim, now) }
        if (active && prefs.blockHints) guarded("crosshair", now) { crosshair(g, dim) }
        if (toasts.isNotEmpty()) guarded("toasts", now) { toasts(g, now) }
        if (prefs.warDisplay.let { it == WarDisplay.HUD || it == WarDisplay.BOTH }) guarded("war", now) { war(g, now) }
    }

    private fun guarded(section: String, now: Long, block: () -> Unit) {
        val failure = failures[section]
        if (failure != null && failure.count >= MAX_FAILURES) {
            if (now - failure.at < RETRY_MS) return
            failure.count = 0
        }
        try {
            block()
            if (failure != null) failures.remove(section)
        } catch (e: RuntimeException) {
            val f = failure ?: Failure(0, now).also { failures[section] = it }
            f.count++
            f.at = now
            KamiClaims.LOG.error("HUD section {} failed ({}/{})", section, f.count, MAX_FAILURES, e)
        }
    }

    fun reset() {
        failures.clear()
        seenAlerts.clear()
        toasts.clear()
        banner = null
        lastOwner = Int.MIN_VALUE
        lastDim = ""
        lastTaxRefresh = 0L
    }

    private class TaxView(val lines: List<Pair<String, Int>>, val important: Boolean, val width: Int)

    private fun taxView(now: Long): TaxView? {
        val p = ClientClaims.prefs
        return taxMemo.of(ClaimsStore.revision, Format.locale, now / 60_000, p.taxCondDebt, p.taxCondPrepaid, p.taxPrepaidDays, p.taxCondDue, p.taxDueHours, p.taxCondLapse, p.taxLapseDays) {
            val snap = ClaimsStore.snap
            val info = ClaimsStore.info?.takeIf { it.details }
            val rows = snap?.let { TaxTimer.rows(TaxInputs(it.homes, ClaimsStore.receivedAt, now, info?.treasury ?: 0, info?.let { Finance.of(it).net } ?: 0, info != null)) }
            if (rows == null || rows.empty) null else {
                val lines = taxLines(rows, p)
                val cond = TaxConditions(p.taxCondDebt, p.taxCondPrepaid, p.taxPrepaidDays.toLong(), p.taxCondDue, p.taxDueHours.toLong(), p.taxCondLapse, p.taxLapseDays.toLong())
                TaxView(lines, TaxTimer.important(rows, cond), lines.maxOf { Draw.width(it.first) } + 10)
            }
        }
    }

    private fun taxLines(rows: TaxRows, p: Prefs): List<Pair<String, Int>> = buildList {
        if (rows.debt > 0) add(tr("kami_claims.hud.tax.debt", Format.money(rows.debt)) to Palette.danger)
        if (rows.hasHomes) {
            val days = rows.prepaidDays
            add(tr("kami_claims.hud.tax.prepaid", Format.money(rows.prepaid), days?.let { trn("kami_claims.unit.day", it) } ?: "-") to if (days != null && days <= p.taxPrepaidDays) Palette.warning else Palette.textSecondary)
        }
        rows.rentDueIn?.let { add(tr("kami_claims.hud.tax.rent_due", Format.duration(it)) to if (it <= p.taxDueHours * 3_600_000L) Palette.warning else Palette.textSecondary) }
        if (rows.hasLapse) {
            val days = rows.claimsDays
            add(tr("kami_claims.hud.tax.claims_last", days?.let { trn("kami_claims.unit.day", it) } ?: tr("kami_claims.hud.tax.stable")) to if (days != null && days <= p.taxLapseDays) Palette.warning else Palette.textSecondary)
        }
    }

    private fun taxSample() = listOf(
        tr("kami_claims.hud.tax.debt", Format.money(120)) to Palette.danger,
        tr("kami_claims.hud.tax.prepaid", Format.money(300), trn("kami_claims.unit.day", 2)) to Palette.warning,
        tr("kami_claims.hud.tax.rent_due", Format.duration(5 * 3_600_000L)) to Palette.textSecondary,
        tr("kami_claims.hud.tax.claims_last", trn("kami_claims.unit.day", 9)) to Palette.textSecondary
    )

    private fun countryBar(): BarData? {
        val s = ClientResearch.state
        if (s.ownCountry.isEmpty() || s.country != s.ownCountry) return null
        val maxed = ClientResearch.atMaxLevel || !ClientResearch.xpTracked
        val title = ClaimsStore.info?.takeIf { !it.delegated }?.name ?: tr("kami_claims.hud.xp")
        return BarData(title, s.level, if (maxed) 1f else ClientResearch.xpFraction, s.xp - s.xpFloor, s.xpCeiling - s.xpFloor, maxed)
    }

    fun idleWatch() = if (ClientClaims.prefs.taxHud == TaxHudMode.OFF && !HudBars.visible(XP_BAR)) emptyList() else listOf("dashboard")

    private fun tax(g: GuiGraphics, dim: String, now: Long) {
        val mode = ClientClaims.prefs.taxHud
        if (mode == TaxHudMode.OFF || !ClientClaims.active(dim)) return
        if (now - lastTaxRefresh > TAX_REFRESH_MS && Minecraft.getInstance().screen == null) {
            lastTaxRefresh = now
            ClaimsStore.watch(idleWatch(), force = true)
        }
        val view = taxView(now) ?: return
        if (mode == TaxHudMode.IMPORTANT && !view.important) return
        val box = HudLayout.place(TAX, g.guiWidth(), g.guiHeight(), view.width, view.lines.size * HudLayout.LINE_H + 6)
        Draw.sprite(g, Sprites.TOOLTIP, box)
        view.lines.forEachIndexed { i, (text, color) -> Draw.text(g, text, box.x + 5, box.y + 4 + i * HudLayout.LINE_H, color) }
    }

    private fun war(g: GuiGraphics, now: Long) {
        val w = ClaimsStore.warHud.firstOrNull() ?: return
        val text = warMemo.of(w.phase, w.enemy, w.ends, Format.locale, now / 1000) {
            val left = if (w.ends > 0) Format.until(w.ends, now) else tr("kami_claims.warpage.open_ended")
            tr("kami_claims.hud.war", tr("kami_claims.warpage.phase.${w.phase}"), w.enemy, left)
        }
        val color = if (w.phase == "war") Palette.danger else Palette.warning
        val box = HudLayout.place(WAR, g.guiWidth(), g.guiHeight(), Draw.width(text) + 10)
        Draw.sprite(g, Sprites.TOOLTIP, box)
        Draw.fill(g, Rect(box.x + 1, box.y + 1, 2, box.h - 2), color)
        Draw.text(g, text, box.x + 6, box.y + 4, color)
    }

    private fun territory(g: GuiGraphics, dim: String, bx: Int, bz: Int, now: Long) {
        val e = ClientClaims.at(dim, bx shr 4, bz shr 4)
        val owner = e?.country ?: -1
        if (owner != lastOwner || dim != lastDim) {
            if (lastOwner != Int.MIN_VALUE) banner = enterBanner(dim, e, now)
            lastOwner = owner
            lastDim = dim
        }
        val near = if (ClientClaims.prefs.hudBorderDistance) Borders.nearest(8) else null
        val arrow = near?.let { (edge, _) ->
            val cx = (edge.x0 + edge.x1) / 2.0 - bx
            val cz = (edge.z0 + edge.z1) / 2.0 - bz
            ((atan2(cz, cx) / (Math.PI / 4)).roundToInt() + 8) % 8
        }
        val line = territoryMemo.of(e, ClientClaims.countries, Format.locale, arrow, near?.second) {
            val country = e?.let { ClientClaims.country(it) }
            val name = country?.name ?: tr("kami_claims.help.term.nomansland")
            val type = e?.let { ClientClaims.typeName(it) }?.let { Vocabulary.type(it) }
            val extra = when {
                e == null -> tr("kami_claims.guard.no_building")
                e.flags and View.MINE != 0 -> tr("kami_claims.map.chunk.mine")
                else -> type?.label ?: ""
            }.let { if (it.isEmpty()) it else "  $it" }
            val distance = near?.let { "   ${arrows[arrow!!]} ${it.second}" }
            Territory(country, name, extra, type?.color, distance, Draw.width(name) + (if (extra.isNotEmpty()) Draw.width(extra) else 0) + (distance?.let { Draw.width(it) } ?: 0) + (if (country != null) 24 else 10))
        }
        val country = line.country
        val box = HudLayout.place(TERRITORY, g.guiWidth(), g.guiHeight(), line.width)
        Draw.sprite(g, Sprites.TOOLTIP, box)
        var tx = box.x + 5
        if (country != null) { Flags.draw(g, Rect(tx, box.y + 3, 12, 9), country.color, country.pattern, country.emblem, country.secondary); tx += 16 }
        tx = Draw.text(g, line.name, tx, box.y + 4, if (country == null) Palette.textMuted else Palette.text)
        if (line.extra.isNotEmpty()) tx = Draw.text(g, line.extra, tx, box.y + 4, line.typeColor ?: Palette.textMuted)
        line.distance?.let { Draw.text(g, it, tx, box.y + 4, if (near!!.second <= 2) Palette.warning else Palette.textMuted) }
        banner?.let { b ->
            val age = now - b.at
            if (age > 3500) { banner = null; return@let }
            val slide = if (age < 250) (age / 250f) else if (age > 3200) ((3500 - age) / 300f) else 1f
            val bw = maxOf(Draw.width(b.title), Draw.width(b.body)) + 16
            val by = (box.bottom + 2 - 26 + 28 * slide).toInt()
            val r = Rect((box.centerX - bw / 2).coerceIn(0, maxOf(0, g.guiWidth() - bw)), by, bw, 24)
            Draw.sprite(g, Sprites.TOAST, r)
            Draw.fill(g, Rect(r.x + 1, r.y + 1, bw - 2, 1), b.color)
            Draw.text(g, b.title, r.x + 8, r.y + 4, b.color)
            Draw.text(g, b.body, r.x + 8, r.y + 14, Palette.textSecondary)
        }
    }

    private fun enterBanner(dim: String, e: View.Entry?, now: Long): Banner {
        val bx = Minecraft.getInstance().player?.blockX ?: 0
        val bz = Minecraft.getInstance().player?.blockZ ?: 0
        if (e == null) return Banner(tr("kami_claims.help.term.nomansland"), tr("kami_claims.guard.no_building"), Palette.textMuted, now)
        val c = ClientClaims.country(e)
        val relation = c?.relation ?: 0
        val may = AccessGuess.actions.filter { AccessGuess.allowed(dim, bx, bz, it) }.map { tr("kami_claims.action.$it") }
        val body = if (may.isEmpty()) tr("kami_claims.hud.allowed.none") else tr("kami_claims.hud.allowed", may.joinToString(", "))
        return Banner("${c?.name ?: "?"} · ${Vocabulary.relationLabel(relation)}", body, Vocabulary.relationColor(relation), now)
    }

    private fun crosshair(g: GuiGraphics, dim: String) {
        if (!ClientClaims.prefs.blockHints || !ClientClaims.active(dim)) return
        val pos = BlockHint.target() ?: return
        val hint = BlockHint.hint(pos)
        val cx = g.guiWidth() / 2
        val cy = g.guiHeight() / 2
        if (hint == Hint.BLOCKED) Draw.marker(g, Icons.LOCK, cx + 12, cy + 12)
        if (!BlockHint.showDetails()) return
        val e = ClientClaims.at(dim, pos.x shr 4, pos.z shr 4)
        val title = e?.let { ClientClaims.country(it)?.name } ?: tr("kami_claims.help.term.nomansland")
        val type = e?.let { ClientClaims.typeName(it) }?.let { Vocabulary.type(it).label } ?: ""
        val parts = AccessGuess.actions.map { a -> tr("kami_claims.action.$a") to AccessGuess.allowed(dim, pos.x, pos.z, a) }
        val w = 140
        val box = Rect(cx + 12, cy + 12, w, 34)
        Draw.sprite(g, Sprites.TOOLTIP, box)
        Draw.text(g, Draw.fit("$title  $type", w - 10), box.x + 5, box.y + 4, Palette.text)
        parts.forEachIndexed { i, (label, ok) ->
            val x = box.x + 5 + (i % 2) * 66
            val y = box.y + 14 + (i / 2) * 9
            Draw.text(g, if (ok) "✔" else "✖", x, y, if (ok) Palette.success else Palette.danger)
            Draw.text(g, label, x + 9, y, Palette.textSecondary)
        }
    }

    private fun toasts(g: GuiGraphics, now: Long) {
        toasts.removeAll { now - it.at > 7000 }
        if (toasts.isEmpty()) return
        val hint = openHint.of(Format.locale, ClientHooks.openKeyId()) { tr("kami_claims.hud.open_hint", ClientHooks.openKey()) }
        val w = minOf(180, g.guiWidth() - 8)
        val block = HudLayout.place(TOASTS, g.guiWidth(), g.guiHeight(), w, toasts.size * 30 - 4)
        var y = block.y
        toasts.forEach { t ->
            val sev = Severity.of(t.alert.severity)
            val r = Rect(block.x, y, w, 26)
            Draw.sprite(g, Sprites.TOAST, r)
            Draw.fill(g, Rect(r.x + 1, r.y + 1, 2, r.h - 2), sev.color)
            Draw.leadIcon(g, iconFor(sev), r.x + 6, r.centerY)
            Draw.text(g, Draw.fit(trJson(t.alert.title), w - 30), r.x + 24, r.y + 5, sev.color)
            Draw.text(g, hint, r.x + 24, r.y + 15, Palette.textMuted)
            y += 30
        }
    }
}
