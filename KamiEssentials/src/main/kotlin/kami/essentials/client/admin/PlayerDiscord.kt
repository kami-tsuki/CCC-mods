package kami.essentials.client.admin

import kami.libs.ui.style.Metrics
import kami.essentials.admin.DiscordDetail
import kami.essentials.admin.DiscordEvent
import kami.essentials.admin.PlayerDetail
import kami.libs.ui.app.Remote
import kami.libs.ui.app.adminConfirmAct
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.remoteFailed
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.card
import kami.libs.ui.widget.cardColumns
import kami.libs.ui.widget.edgeButton
import kami.libs.ui.widget.secretProperty
import kami.libs.ui.widget.skeleton
import kami.libs.ui.widget.table

private const val CARD_W = 190

internal class PlayerDiscord(private val app: AdminApp, private val uuid: String) {
    private val remote = Remote("players.discord", DiscordDetail.serializer(), Metrics.REFRESH_MS) { listOf(uuid) }
    private val table = TableState<DiscordEvent>()

    private fun yes(v: Boolean) = tr(if (v) "kami_essentials.admin.discord.yes" else "kami_essentials.admin.discord.no")

    private fun time(at: Long) = if (at > 0) Format.exact(at) else tr("kami_libs.common.none")

    private fun kind(e: DiscordEvent) = when (e.kind) {
        "link" -> tr("kami_essentials.admin.discord.kind.link")
        "unlink" -> tr("kami_essentials.admin.discord.kind.unlink")
        else -> tr("kami_essentials.admin.discord.kind.code")
    }

    fun draw(ui: Ui, r: Rect, d: PlayerDetail, lock: String?) {
        remote.poll()
        val x = remote.data ?: remote.latest ?: run {
            if (remote.error != null && !remote.loading) ui.remoteFailed(r, remote, "player-discord") else ui.skeleton(r)
            return
        }
        val row = Row(r.top(CONTROL_H), Metrics.gap)
        fun action(label: String, icon: Icon, reason: String?, danger: Boolean, act: String, title: String) {
            if (ui.edgeButton(row, label, icon, if (danger) ButtonStyle.DANGER else ButtonStyle.SECONDARY, reason == null, reason, pending = adminPending(act, listOf(uuid)), left = true, key = "discord:$act"))
                app.adminConfirmAct(title, act, listOf(uuid), danger)
        }
        if (d.canManage) {
            val unlinked = tr("kami_essentials.admin.discord_not_linked").takeUnless { x.linked }
            action(tr("kami_essentials.prefs.discord.unlink"), Icons.BROKEN_CHAIN, lock ?: unlinked, true, "players.discord.unlink", tr("kami_essentials.admin.discord.unlink.title", d.row.name))
            action(
                tr("kami_essentials.admin.discord.resync"), Icons.CHAIN, lock ?: unlinked ?: tr("kami_essentials.admin.discord_down").takeUnless { x.botReady }, false,
                "players.discord.resync", tr("kami_essentials.admin.discord.resync.title", d.row.name)
            )
        }
        val body = r.dropTop(CONTROL_H + Metrics.gap)
        val cards = buildList<(Rect) -> Unit> {
            add { link(ui, it, x) }
            if (x.linked) add { guild(ui, it, x) }
            add { activity(ui, it, x) }
        }
        val cols = cardColumns(body.w, CARD_W, cards.size)
        val rows = cards.chunked(cols)
        rows.forEachIndexed { i, line ->
            Rect(body.x, body.y + i * (INFO_H + Metrics.gap), body.w, INFO_H).columns(cols, Metrics.gap).let { cells -> line.forEachIndexed { j, draw -> draw(cells[j]) } }
        }
        ui.table(
            body.dropTop(rows.size * (INFO_H + Metrics.gap)),
            listOf(
                Column.number<DiscordEvent>(tr("kami_libs.common.when"), 56, format = { Format.ago(it) }) { it.at },
                Column.text<DiscordEvent>(tr("kami_essentials.admin.discord.event"), 70) { kind(it) },
                Column.text<DiscordEvent>(tr("kami_essentials.admin.discord.id"), -1) { it.detail }
            ),
            x.history, table, { it.at.toString() + it.kind }, emptyText = tr("kami_essentials.admin.discord.no_history"), key = "player-discord-history"
        )
    }

    private fun link(ui: Ui, r: Rect, x: DiscordDetail) {
        val f = Flow(ui.card(r, tr("kami_essentials.admin.discord.link"), Icons.CHAIN), 2)
        ui.prop(f, tr("kami_libs.common.status"), tr(if (x.linked) "kami_essentials.admin.discord.linked" else "kami_essentials.prefs.discord.unlinked"), if (x.linked) Palette.success else Palette.textMuted)
        if (!x.linked) return
        ui.prop(f, tr("kami_essentials.admin.discord.account"), x.discordName)
        ui.secretProperty(f.take(PROP_H), tr("kami_essentials.admin.discord.id"), x.discordId)
        ui.prop(f, tr("kami_essentials.admin.discord.linked_at"), time(x.linkedAt))
        ui.prop(f, tr("kami_essentials.admin.discord.nickname"), x.nickname.ifEmpty { tr("kami_libs.common.none") })
    }

    private fun guild(ui: Ui, r: Rect, x: DiscordDetail) {
        val f = Flow(ui.card(r, tr("kami_essentials.admin.discord.guild"), Icons.GLOBE), 2)
        ui.prop(f, tr("kami_essentials.admin.discord.bot"), yes(x.botReady), if (x.botReady) Palette.success else Palette.danger)
        ui.prop(f, tr("kami_essentials.admin.discord.in_guild"), yes(x.inGuild), if (x.inGuild) Palette.success else Palette.warning)
        ui.prop(f, tr("kami_essentials.admin.discord.verified"), yes(x.verified))
        ui.prop(f, tr("kami_essentials.admin.discord.joined"), time(x.joinedGuild))
        ui.prop(f, tr("kami_essentials.admin.discord.roles"), x.roles.joinToString(", ").ifEmpty { tr("kami_libs.common.none") })
    }

    private fun activity(ui: Ui, r: Rect, x: DiscordDetail) {
        val f = Flow(ui.card(r, tr("kami_essentials.admin.activity"), Icons.CLOCK), 2)
        ui.prop(f, tr("kami_essentials.admin.discord.to_mc"), Format.number(x.sentToMc))
        ui.prop(f, tr("kami_essentials.admin.discord.to_discord"), Format.number(x.sentToDiscord))
        ui.prop(f, tr("kami_essentials.prefs.discord.pings"), Format.number(x.pings))
        ui.prop(f, tr("kami_essentials.prefs.discord.alerts"), yes(x.marketAlerts), if (x.marketAlerts) Palette.success else Palette.textMuted)
        ui.prop(f, tr("kami_essentials.admin.discord.last_message"), if (x.lastMessage > 0) Format.ago(x.lastMessage) else tr("kami_libs.common.none"))
    }
}
