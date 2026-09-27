package kami.claims.client.hud

import kami.libs.ui.text.trJson
import kami.libs.ui.text.tr
import kami.claims.client.ClientClaims
import kami.claims.client.app.Flags
import kami.claims.client.app.Vocabulary
import kami.claims.client.store.ClaimsStore
import kami.claims.client.world.AccessGuess
import kami.claims.client.world.BlockHint
import kami.claims.client.world.Borders
import kami.claims.client.world.Hint
import kami.claims.service.AlertLine
import kami.claims.service.View
import kami.libs.ui.core.Rect
import kami.libs.ui.style.Draw
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
    private class Banner(val title: String, val body: String, val color: Int, val at: Long)
    private class HudToast(val alert: AlertLine, val at: Long)

    private var lastOwner = Int.MIN_VALUE
    private var lastDim = ""
    private var banner: Banner? = null
    private val seenAlerts = HashSet<String>()
    private val toasts = ArrayList<HudToast>()
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
        val dim = player.level().dimension().location().toString()
        val now = System.currentTimeMillis()
        if (ClientClaims.active(dim) && ClientClaims.prefs.hud) territory(g, dim, player.blockX, player.blockZ, now)
        crosshair(g, dim)
        toasts(g, now)
    }

    private fun territory(g: GuiGraphics, dim: String, bx: Int, bz: Int, now: Long) {
        val e = ClientClaims.at(dim, bx shr 4, bz shr 4)
        val owner = e?.country ?: -1
        if (owner != lastOwner || dim != lastDim) {
            if (lastOwner != Int.MIN_VALUE) banner = enterBanner(dim, e, now)
            lastOwner = owner
            lastDim = dim
        }
        val country = e?.let { ClientClaims.country(it) }
        val name = country?.name ?: tr("kami_claims.world.nomansland")
        val type = e?.let { ClientClaims.typeName(it) }?.let { Vocabulary.type(it) }
        val extra = when {
            e == null -> tr("kami_claims.guard.no_building")
            e.flags and View.MINE != 0 -> tr("kami_claims.map.chunk.mine")
            else -> type?.label ?: ""
        }
        val near = if (ClientClaims.prefs.hudBorderDistance) Borders.nearest(8) else null
        val distance = near?.let { (edge, d) ->
            val cx = (edge.x0 + edge.x1) / 2.0 - bx
            val cz = (edge.z0 + edge.z1) / 2.0 - bz
            val index = ((atan2(cz, cx) / (Math.PI / 4)).roundToInt() + 8) % 8
            "${arrows[index]} ${d}"
        }
        val textW = Draw.width(name) + (if (extra.isNotEmpty()) Draw.width("  $extra") else 0) + (distance?.let { Draw.width("   $it") } ?: 0)
        val w = textW + (if (country != null) 24 else 10)
        val x = (g.guiWidth() - w) / 2
        val box = Rect(x, 3, w, 15)
        Draw.sprite(g, Sprites.TOOLTIP, box)
        var tx = x + 5
        if (country != null) { Flags.draw(g, Rect(tx, box.y + 3, 12, 9), country.color, country.pattern, country.emblem, country.secondary); tx += 16 }
        tx = Draw.text(g, name, tx, box.y + 4, if (country == null) Palette.textMuted else Palette.text)
        if (extra.isNotEmpty()) tx = Draw.text(g, "  $extra", tx, box.y + 4, type?.color ?: Palette.textMuted)
        distance?.let { Draw.text(g, "   $it", tx, box.y + 4, if (near.second <= 2) Palette.warning else Palette.textMuted) }
        banner?.let { b ->
            val age = now - b.at
            if (age > 3500) { banner = null; return@let }
            val slide = if (age < 250) (age / 250f) else if (age > 3200) ((3500 - age) / 300f) else 1f
            val bw = maxOf(Draw.width(b.title), Draw.width(b.body)) + 16
            val by = (box.bottom + 2 - 26 + 28 * slide).toInt()
            val r = Rect((g.guiWidth() - bw) / 2, by, bw, 24)
            Draw.sprite(g, Sprites.TOAST, r)
            Draw.fill(g, Rect(r.x + 1, r.y + 1, bw - 2, 1), b.color)
            Draw.text(g, b.title, r.x + 8, r.y + 4, b.color)
            Draw.text(g, b.body, r.x + 8, r.y + 14, Palette.textSecondary)
        }
    }

    private fun enterBanner(dim: String, e: View.Entry?, now: Long): Banner {
        val bx = Minecraft.getInstance().player?.blockX ?: 0
        val bz = Minecraft.getInstance().player?.blockZ ?: 0
        if (e == null) return Banner(tr("kami_claims.world.nomansland"), tr("kami_claims.guard.no_building"), Palette.textMuted, now)
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
        val title = e?.let { ClientClaims.country(it)?.name } ?: tr("kami_claims.world.nomansland")
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
        var y = 22
        toasts.forEach { t ->
            val sev = Severity.of(t.alert.severity)
            val w = 180
            val r = Rect(g.guiWidth() - w - 4, y, w, 26)
            Draw.sprite(g, Sprites.TOAST, r)
            Draw.fill(g, Rect(r.x + 1, r.y + 1, 2, r.h - 2), sev.color)
            Draw.leadIcon(g, iconFor(sev), r.x + 6, r.centerY)
            Draw.text(g, Draw.fit(trJson(t.alert.title), w - 30), r.x + 24, r.y + 5, sev.color)
            Draw.text(g, tr("kami_claims.hud.open_hint", "K"), r.x + 24, r.y + 15, Palette.textMuted)
            y += 30
        }
    }
}
