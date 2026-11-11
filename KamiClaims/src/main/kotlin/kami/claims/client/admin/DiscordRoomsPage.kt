package kami.claims.client.admin

import kami.claims.admin.AdminRooms
import kami.claims.admin.RoomMember
import kami.claims.admin.RoomOverview
import kami.claims.admin.RoomRow
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.Remote
import kami.libs.ui.app.Route
import kami.libs.ui.app.adminConfirmAct
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.remoteTable
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

private const val REFRESH_MS = 10_000L
private const val TILE_H = 44
private const val GAP = 6
private const val PROP_H = 12

internal class DiscordRoomsPage(private val app: KamiApp) : Page() {
    private val remote = Remote(AdminRooms.VIEW, RoomOverview.serializer(), REFRESH_MS)
    private val rooms = TableState<RoomRow>()
    private val members = TableState<RoomMember>()
    private val roomColumnsMemo = Memo()
    private val memberColumnsMemo = Memo()
    private var selected: String? = null

    override val title get() = tr("kami_claims.admin.discord.title")

    override fun draw(ui: Ui, r: Rect) {
        remote.poll()
        val d = remote.data ?: remote.latest
        tiles(ui, r.top(TILE_H), d)
        val bar = Row(r.dropTop(TILE_H + GAP).top(CONTROL_H), GAP)
        val down = lockUnless(adminManager) ?: off(d)
        action(ui, bar, tr("kami_claims.admin.discord.sync_all"), Icons.CHAIN, down, false, AdminRooms.SYNC, listOf(AdminRooms.ALL), tr("kami_claims.admin.discord.sync_all.title"))
        action(ui, bar, tr("kami_claims.admin.discord.clean"), Icons.REMOVE, down ?: tr("kami_claims.admin.discord.no_orphans").takeIf { d?.orphans.isNullOrEmpty() }, true,
            AdminRooms.CLEAN, emptyList(), tr("kami_claims.admin.discord.clean.title", d?.orphans?.joinToString(", ") { "#${it.name}" }.orEmpty()))
        val body = r.dropTop(TILE_H + CONTROL_H + GAP * 2)
        val (left, right) = body.columns(listOf(1f, 1.2f), 8)
        val rows = d?.rows.orEmpty()
        ui.remoteTable(left, remote, roomColumns(), rows, rooms, { it.id }, tr("kami_claims.admin.empty"), "discord-rooms", severity = { severity(it.state) })
        rooms.selected.lastOrNull()?.let { selected = it.toString() }
        val room = rows.firstOrNull { it.id == selected } ?: rows.firstOrNull()
        detail(ui, right, room, down)
    }

    private fun off(d: RoomOverview?) = when {
        d == null -> tr("kami_libs.admin.error")
        !d.enabled -> tr("kami_claims.admin.discord.disabled")
        !d.category -> tr("kami_claims.admin.discord.no_category")
        !d.ready -> tr("kami_claims.admin.discord.bot_down")
        else -> null
    }

    private fun tiles(ui: Ui, r: Rect, d: RoomOverview?) {
        val active = d?.rows?.filter { it.state != "inactive" }.orEmpty()
        val linked = active.count { it.channel.isNotEmpty() }
        val drift = active.count { it.state == "drift" || it.state == "none" }
        val unlinked = active.sumOf { row -> row.members.count { it.state == "unlinked" } }
        val bot = when {
            d == null -> "-"
            d.ready && d.enabled && d.category -> tr("kami_claims.admin.discord.online")
            else -> tr("kami_claims.admin.discord.offline")
        }
        ui.kpiRow(r, listOf(
            KpiTile(tr("kami_claims.admin.discord.bot"), bot, Icons.GLOBE, if (off(d) == null) Palette.success else Palette.danger, sub = off(d)),
            KpiTile(tr("kami_claims.admin.discord.channels"), "${Format.number(linked.toLong())} / ${Format.number(active.size.toLong())}", Icons.CHAIN),
            KpiTile(tr("kami_claims.admin.discord.drift"), Format.number(drift.toLong()), Icons.DANGER, if (drift > 0) Palette.warning else Palette.text),
            KpiTile(tr("kami_claims.admin.discord.unlinked"), Format.number(unlinked.toLong()), Icons.BROKEN_CHAIN, if (unlinked > 0) Palette.warning else Palette.text),
            KpiTile(tr("kami_claims.admin.discord.orphans"), Format.number(d?.orphans?.size?.toLong() ?: 0), Icons.REMOVE, if (d?.orphans.isNullOrEmpty()) Palette.text else Palette.danger)
        ), key = "discord-kpi")
    }

    private fun detail(ui: Ui, r: Rect, room: RoomRow?, down: String?) {
        val body = ui.card(r, room?.name ?: tr("kami_claims.admin.discord.members"), Icons.PEOPLE, trailing = room?.let { state(it.state) }, key = "discord-room")
        if (room == null) return
        val bar = Row(body.top(CONTROL_H), GAP)
        val lock = down ?: tr("kami_claims.admin.disbanded").takeIf { room.state == "inactive" }
        action(ui, bar, tr("kami_claims.admin.discord.sync"), Icons.CHAIN, lock, false, AdminRooms.SYNC, listOf(room.id), tr("kami_claims.admin.discord.sync.title", room.name))
        action(ui, bar, tr("kami_claims.admin.discord.recreate"), Icons.DANGER, lock, true, AdminRooms.RECREATE, listOf(room.id), tr("kami_claims.admin.discord.recreate.title", room.name))
        val info = body.dropTop(CONTROL_H + GAP).top(PROP_H)
        ui.property(info, tr("kami_claims.admin.discord.channel_id"), room.channel.ifEmpty { tr("kami_libs.common.none") }, copy = room.channel.isNotEmpty())
        val strays = body.dropTop(CONTROL_H + GAP + PROP_H).top(PROP_H)
        ui.property(strays, tr("kami_claims.admin.discord.strays"), Format.number(room.strays.toLong()), if (room.strays > 0) Palette.warning else Palette.text)
        ui.table(body.dropTop(CONTROL_H + GAP * 2 + PROP_H * 2), memberColumns(), room.members, members, { it.uuid }, emptyText = tr("kami_libs.table.empty"),
            severity = { severity(it.state) }, key = "discord-members")
            .opened?.let { m -> app.navigate(Route("player", mapOf("uuid" to m.uuid))) }
    }

    private fun action(ui: Ui, row: Row, label: String, icon: Icon, why: String?, danger: Boolean, act: String, args: List<String>, title: String) {
        if (ui.edgeButton(row, label, icon, if (danger) ButtonStyle.DANGER else ButtonStyle.SECONDARY, why == null, why, pending = adminPending(act, args), left = true, key = "discord:$act:$args"))
            app.adminConfirmAct(title, act, args, danger)
    }

    private fun state(s: String) = tr("kami_claims.admin.discord.state.$s")

    private fun severity(s: String) = when (s) {
        "ok", "in" -> null
        "drift", "missing", "none", "pending", "unlinked" -> Severity.WARNING
        "banned" -> Severity.DANGER
        else -> Severity.NEUTRAL
    }

    private fun roomColumns() = roomColumnsMemo.of(Format.locale) { listOf(
        Column.text<RoomRow>(tr("kami_libs.common.name"), -1) { it.name },
        Column.text<RoomRow>(tr("kami_libs.common.status"), 70) { state(it.state) },
        Column.number<RoomRow>(tr("kami_libs.common.members"), 50) { it.members.size.toLong() },
        Column.number<RoomRow>(tr("kami_claims.admin.discord.in_channel"), 56) { row -> row.members.count { it.state == "in" }.toLong() }
    ) }

    private fun memberColumns() = memberColumnsMemo.of(Format.locale) { listOf(
        Column<RoomMember>(tr("kami_libs.common.name"), -1, sort = textOrder { it.name }) { _, r, row ->
            avatar(row.uuid, r.x, r.y + (r.h - 10) / 2, 10, null)
            fitText(r.x + 14, r.y + (r.h - 8) / 2, r.w - 14, row.name, Palette.text, key = "dm:${row.uuid}")
        },
        Column.text<RoomMember>(tr("kami_libs.common.rank"), 60) { it.rank },
        Column.text<RoomMember>(tr("kami_claims.admin.discord.account"), 80) { it.discord.ifEmpty { "-" } },
        Column.text<RoomMember>(tr("kami_libs.common.status"), 70) { state(it.state) }
    ) }
}
