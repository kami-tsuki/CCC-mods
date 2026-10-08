package kami.essentials.admin

import com.mojang.authlib.GameProfile
import kami.essentials.Perms
import kami.essentials.chat.Names
import kami.essentials.chat.Talk
import kami.essentials.inv.Offline
import kami.essentials.inv.Views
import kami.essentials.vanish.Vanish
import kami.libs.admin.Admin
import kami.libs.admin.AdminCtx
import kami.libs.admin.AdminPerms
import kami.libs.admin.Audit
import kami.libs.admin.Page
import kami.libs.admin.Players
import kami.libs.admin.flag
import kami.libs.admin.long
import kami.libs.admin.page
import kami.libs.admin.text
import kami.libs.chat.tell
import kami.libs.db.Db
import kami.libs.economy.Flow
import kami.libs.economy.Numismatics
import kami.libs.economy.PlayerParty
import kami.libs.economy.ServerBank
import kami.libs.economy.ServerParty
import kami.libs.economy.Statement
import kami.libs.text.Phrase
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import java.util.UUID

object AdminServer {
    private const val CONSOLE_RANK = 4
    private const val DETAIL_WARNS = 20
    private const val NAMES = 20
    private val CONSOLE = setOf("Server", "Rcon")

    fun register() {
        Admin.view("players.list") { ctx, a -> encode(Page.serializer(PlayerRow.serializer()), list(ctx, a)) }
        Admin.view("players.detail") { ctx, a -> encode(PlayerDetail.serializer(), detail(ctx, target(ctx, a))) }
        Admin.view("players.history") { ctx, a ->
            encode(Page.serializer(Audit.Entry.serializer()), Audit.query(Audit.Filter(target = target(ctx, a).toString()), a.page(1), level = ctx.level))
        }
        Admin.view("moderation.bans") { ctx, a ->
            val server = ctx.player.server
            val rows = Moderation.bans(server).sortedByDescending { it.getCreated() }.mapNotNull { b ->
                Moderation.profile(b)?.let { BanRow(it.id.toString(), it.name, b.getReason(), b.getSource(), b.getCreated().time, b.getExpires()?.time, Moderation.bannedIps(server, it.id).isNotEmpty()) }
            }
            encode(Page.serializer(BanRow.serializer()), Page.of(rows, a.page(0)))
        }
        Admin.view("moderation.mutes") { _, a ->
            val rows = Mutes.active().sortedByDescending { it.second.since }.map { (id, m) -> MuteRow(id.toString(), PlayerRecords[id]?.name ?: id.toString(), m.reason, m.by, m.since, m.until) }
            encode(Page.serializer(MuteRow.serializer()), Page.of(rows, a.page(0)))
        }
        Admin.view("moderation.warns") { _, a ->
            val text = a.text(1)?.lowercase()
            val rows = Warns.all().filter {
                when (a.text(0)) {
                    "active" -> it.revoke == null
                    "revoked" -> it.revoke != null
                    else -> true
                }
            }.map(::warnRow).filter { w -> text == null || w.uuid.startsWith(text) || listOf(w.name, w.reason, w.by).any { text in it.lowercase() } }
            encode(Page.serializer(WarnRow.serializer()), Page.of(rows, a.page(2)))
        }
        Admin.view("players.names") { ctx, a ->
            val prefix = a.text(0)?.lowercase().orEmpty()
            val rows = PlayerRecords.all().filter { it.name.lowercase().startsWith(prefix) }.map { NameRow(it.id.toString(), it.name, visible(ctx, it.id) != null) }
                .sortedWith(compareByDescending<NameRow> { it.online }.thenBy { it.name.lowercase() }).take(NAMES)
            encode(ListSerializer(NameRow.serializer()), rows)
        }
        Admin.view("server.dashboard") { ctx, _ -> encode(Dashboard.serializer(), ServerStats.snapshot(ctx.player.server, ctx.level)) }
        Admin.view("players.inventory") { ctx, a -> inventory(ctx, a, false) }

        Admin.act("players.ban") { ctx, a ->
            val server = ctx.player.server
            val id = known(ctx, guarded(ctx, a))
            Moderation.banned(server, id)?.let { issuer(ctx, it.getSource()) }
            val until = Moderation.expiry(a.text(1) ?: "perm", System.currentTimeMillis())
            val ips = if (a.flag(2)) listOfNotNull(PlayerRecords[id]?.lastIp).ifEmpty { ctx.fail("kami_essentials.admin.no_ip") } else emptyList()
            ips.flatMap(PlayerRecords::onIp).distinct().firstOrNull { !AdminPerms.outranks(ctx.player, profile(server, it)) }
                ?.let { ctx.fail("kami_essentials.admin.ip_outranked", PlayerRecords[it]?.name ?: it) }
            val banned = Moderation.ban(server, profile(server, id), ctx.player.gameProfile.name, ctx.reason, until, ips)
            Admin.done(id, audit = ipAudit(banned))
        }
        Admin.act("players.unban") { ctx, a ->
            val id = target(ctx, a)
            val ban = Moderation.banned(ctx.player.server, id) ?: ctx.fail("kami_essentials.admin.not_banned")
            issuer(ctx, ban.getSource())
            val ips = Moderation.unban(ctx.player.server, id) ?: ctx.fail("kami_essentials.admin.not_banned")
            Admin.done(id, audit = ipAudit(ips))
        }
        Admin.act("players.kick") { ctx, a ->
            val id = guarded(ctx, a)
            if (visible(ctx, id) == null || !Moderation.kick(ctx.player.server, id, ctx.reason)) ctx.fail("kami_essentials.admin.offline")
            Admin.done(id)
        }
        Admin.act("players.mute") { ctx, a ->
            val id = guarded(ctx, a)
            Mutes[id]?.let { issuer(ctx, it.by) }
            val now = System.currentTimeMillis()
            val mute = Mute(ctx.reason, ctx.player.gameProfile.name, now, Moderation.expiry(a.text(1) ?: "perm", now))
            Mutes.mute(id, mute)
            ctx.player.server.playerList.getPlayer(id)?.tell(Talk.chat.warn(Mutes.phrase(mute, now)))
            Admin.done(id)
        }
        Admin.act("players.unmute") { ctx, a ->
            val id = target(ctx, a)
            val mute = Mutes[id] ?: ctx.fail("kami_essentials.admin.not_muted")
            issuer(ctx, mute.by)
            if (!Mutes.unmute(id)) ctx.fail("kami_essentials.admin.not_muted")
            ctx.player.server.playerList.getPlayer(id)?.tell(Talk.chat.ok(Phrase.of("kami_essentials.admin.unmuted")))
            Admin.done(id)
        }
        Admin.act("players.warn") { ctx, a ->
            val id = known(ctx, guarded(ctx, a))
            val live = ctx.player.server.playerList.getPlayer(id)
            val warn = Warns.warn(id, ctx.reason, ctx.player.gameProfile.name, System.currentTimeMillis(), live != null)
            live?.tell(Talk.chat.warn(Warns.phrase(warn)))
            Admin.done(id, audit = mapOf("warn" to warn.id.toString()))
        }
        Admin.act("players.unwarn") { ctx, a ->
            val id = guarded(ctx, a)
            val warn = a.long(1)?.let(Warns::get)?.takeIf { it.uuid == id && it.revoke == null } ?: ctx.fail("kami_essentials.admin.not_warned")
            issuer(ctx, warn.by)
            Warns.revoke(warn.id, Revoke(ctx.player.gameProfile.name, System.currentTimeMillis(), ctx.reason)) ?: ctx.fail("kami_essentials.admin.not_warned")
            Admin.done(id, audit = mapOf("warn" to warn.id.toString()))
        }
        Admin.act("players.teleport") { ctx, a ->
            val id = target(ctx, a)
            val server = ctx.player.server
            val live = visible(ctx, id)
            if (live != null) ctx.player.teleportTo(live.serverLevel(), live.x, live.y, live.z, live.yRot, live.xRot)
            else {
                val spot = PlayerRecords[id]?.let { PlayerRecords.position(server, it) } ?: ctx.fail("kami_essentials.admin.no_position")
                val level = ResourceLocation.tryParse(spot.dim)?.let { server.getLevel(ResourceKey.create(Registries.DIMENSION, it)) } ?: ctx.fail("kami_essentials.admin.no_position")
                ctx.player.teleportTo(level, spot.x, spot.y, spot.z, spot.yaw, spot.pitch)
            }
            Activity.jumped(ctx.player)
            Admin.done(id)
        }
        Admin.act("players.inventory_edit") { ctx, a ->
            guarded(ctx, a)
            inventory(ctx, a, true)
        }
        Admin.act("players.money") { ctx, a ->
            val id = known(ctx, if (a.text(1) == "take") guarded(ctx, a) else target(ctx, a))
            if (id == ctx.player.uuid) ctx.fail("kami_essentials.admin.outranked")
            val amount = a.long(2)?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: ctx.fail("kami_essentials.admin.bad_amount", a.getOrNull(2).orEmpty())
            val applied = when (a.text(1)) {
                "give" -> {
                    if (!Numismatics.deposit(id, amount)) ctx.fail("kami_essentials.admin.money_failed")
                    ServerBank.pay(PlayerParty(id), amount.toLong(), Flow.ADMIN, ctx.reason)
                    amount.toLong()
                }
                "take" -> Numismatics.deduct(id, amount).toLong().also { if (it > 0) ServerBank.collect(PlayerParty(id), it, Flow.ADMIN, ctx.reason) }.let { -it }
                else -> ctx.fail("kami_essentials.admin.bad_amount", a.getOrNull(1).orEmpty())
            }
            Statement.record(id, applied, Flow.ADMIN, ServerParty.key)
            Admin.done(id, "applied" to JsonPrimitive(applied))
        }
    }

    private fun list(ctx: AdminCtx, a: List<String>): Page<PlayerRow> {
        val now = System.currentTimeMillis()
        val banned = Moderation.bans(ctx.player.server, now).mapNotNull { Moderation.profile(it)?.id }.toSet()
        val text = a.text(0)?.lowercase()
        val rows = PlayerRecords.all().filter { text == null || text in it.name.lowercase() || it.id.toString().startsWith(text) }.map { row(ctx, it, it.id in banned, now) }
        val filtered = when (a.text(1)) {
            "online" -> rows.filter { it.online }
            "banned" -> rows.filter { it.banned }
            "muted" -> rows.filter { it.muted }
            else -> rows
        }
        val sorted = when (a.text(2)) {
            "seen" -> filtered.sortedByDescending { it.lastSeen }
            "balance" -> filtered.sortedByDescending { it.balance }
            "playtime" -> filtered.sortedByDescending { it.playtimeMs }
            else -> filtered.sortedBy { it.name.lowercase() }
        }
        return Page.of(sorted, a.page(3))
    }

    private fun detail(ctx: AdminCtx, id: UUID): PlayerDetail {
        val server = ctx.player.server
        val now = System.currentTimeMillis()
        val r = PlayerRecords[id] ?: ctx.fail("kami_essentials.admin.unknown_player", id)
        val ban = Moderation.banned(server, id)
        val row = row(ctx, r, ban != null, now)
        val position = visible(ctx, id)?.let { Pos(it.level().dimension().location().toString(), it.x, it.y, it.z, true) }
            ?: if (server.playerList.getPlayer(id) == null) PlayerRecords.position(server, r)?.let { Pos(it.dim, it.x, it.y, it.z, false) } else null
        val manage = ctx.level >= AdminPerms.Level.MANAGE
        val alts = PlayerRecords.alts(r).mapNotNull { PlayerRecords[it] }.map { row(ctx, it, Moderation.banned(server, it.id) != null, now) }
        return PlayerDetail(
            row = row,
            firstJoin = r.firstJoin,
            lastSeen = row.lastSeen,
            position = position,
            ban = ban?.let { BanInfo(it.getReason(), it.getSource(), it.getCreated().time, it.getExpires()?.time, Moderation.bannedIps(server, id).isNotEmpty()) },
            mute = Mutes[id, now]?.let { MuteInfo(it.reason, it.by, it.since, it.until) },
            lastIp = r.lastIp.takeIf { manage },
            knownIps = r.ips.keys.toList().takeIf { manage },
            alts = alts.takeIf { manage },
            canManage = manage && id != ctx.player.uuid && AdminPerms.outranks(ctx.player, profile(server, id)),
            warns = Warns.of(id).take(DETAIL_WARNS).map(::warnRow)
        )
    }

    private fun row(ctx: AdminCtx, r: PlayerRecord, banned: Boolean, now: Long): PlayerRow {
        val live = visible(ctx, r.id)
        val c = Names.citizenship(r.id)
        return PlayerRow(
            r.id.toString(), r.name, live != null, c?.let { it.parent ?: it.name }, c?.rank, Numismatics.balance(r.id),
            if (live != null) now else r.lastSeen, live?.let(PlayerRecords::playtime) ?: r.playtimeMs, banned, Mutes[r.id, now] != null, Warns.active(r.id)
        )
    }

    private fun warnRow(w: Warn) = WarnRow(
        w.id, w.uuid.toString(), PlayerRecords[w.uuid]?.name ?: w.uuid.toString(), w.reason, w.by, w.at, w.revoke?.let { RevokeInfo(it.by, it.at, it.reason) }
    )

    private fun visible(ctx: AdminCtx, id: UUID) = ctx.player.server.playerList.getPlayer(id)?.takeUnless { Vanish.hides(it, ctx.player) }

    private fun inventory(ctx: AdminCtx, a: List<String>, edit: Boolean): JsonObject {
        val ender = a.flag(1)
        val node = if (ender) (if (edit) Perms.ENDERCHEST_EDIT else Perms.ENDERCHEST_OTHERS) else if (edit) Perms.INVSEE_EDIT else Perms.INVSEE
        if (!Perms.has(ctx.player, node)) ctx.fail("kami_libs.admin.denied")
        val id = target(ctx, a)
        val server = ctx.player.server
        val live = server.playerList.getPlayer(id)
        if (live != null && Vanish.hides(live, ctx.player) || live == null && !Offline.saved(server, id)) ctx.fail("kami_essentials.admin.no_data")
        val profile = profile(server, id)
        if (ender) Views.enderchest(ctx.player, profile, edit) else Views.inventory(ctx.player, profile, edit)
        return Admin.done(id)
    }

    private fun target(ctx: AdminCtx, a: List<String>): UUID {
        val raw = a.text(0).orEmpty()
        return Players.resolve(ctx.player.server, raw) ?: ctx.fail("kami_essentials.admin.unknown_player", raw)
    }

    private fun guarded(ctx: AdminCtx, a: List<String>): UUID {
        val id = target(ctx, a)
        if (id == ctx.player.uuid || !AdminPerms.outranks(ctx.player, profile(ctx.player.server, id))) ctx.fail("kami_essentials.admin.outranked")
        return id
    }

    private fun known(ctx: AdminCtx, id: UUID): UUID {
        if (PlayerRecords[id] == null && ctx.player.server.profileCache?.get(id)?.isPresent != true) ctx.fail("kami_essentials.admin.unknown_player", id)
        return id
    }

    private fun issuer(ctx: AdminCtx, by: String) {
        val server = ctx.player.server
        val rank = if (by in CONSOLE) CONSOLE_RANK else server.playerList.getPlayerByName(by)?.let(AdminPerms::rank)
            ?: PlayerRecords.byName(by)?.let { server.getProfilePermissions(GameProfile(it.id, it.name)) }
            ?: CONSOLE_RANK
        if (rank > AdminPerms.rank(ctx.player)) ctx.fail("kami_essentials.admin.issuer_outranked", by)
    }

    private fun profile(server: MinecraftServer, id: UUID): GameProfile =
        PlayerRecords[id]?.takeIf { server.playerList.getPlayer(id) == null }?.let { GameProfile(id, it.name) } ?: Players.profile(server, id)

    private fun ipAudit(ips: List<String>) = if (ips.isEmpty()) emptyMap() else mapOf("ips" to ips.joinToString(","))

    private fun <T> encode(serializer: KSerializer<T>, value: T) = Db.json.encodeToJsonElement(serializer, value)
}
