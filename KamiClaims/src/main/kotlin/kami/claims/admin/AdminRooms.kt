package kami.claims.admin

import kami.claims.Config
import kami.claims.Country
import kami.claims.Names
import kami.claims.Realm
import kami.claims.discord.CountryChannels
import kami.libs.admin.Admin
import kami.libs.admin.AdminCtx
import kami.libs.db.Db
import kami.libs.discord.DiscordApi
import kami.libs.discord.DiscordBans
import kami.libs.discord.Links
import kami.libs.util.toUuidOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.server.MinecraftServer

@Serializable
class RoomOverview(val enabled: Boolean, val ready: Boolean, val category: Boolean, val rows: List<RoomRow>, val orphans: List<RoomOrphan>)

@Serializable
class RoomRow(val id: String, val name: String, val channel: String, val state: String, val strays: Int, val members: List<RoomMember>)

@Serializable
class RoomMember(val uuid: String, val name: String, val rank: String, val discord: String, val state: String)

@Serializable
class RoomOrphan(val id: String, val name: String)

object AdminRooms {
    const val VIEW = "countries.discord"
    const val SYNC = "countries.discord.sync"
    const val RECREATE = "countries.discord.recreate"
    const val CLEAN = "countries.discord.clean"
    const val ALL = "all"

    fun register() {
        Admin.view(VIEW) { ctx, _ -> Db.encode(RoomOverview.serializer(), overview(ctx.player.server)) }
        Admin.act(SYNC) { ctx, a ->
            val target = a.getOrNull(0)?.takeIf { it != ALL }?.let { country(ctx, it) }
            CountryChannels.resync(target)
            Admin.done(target?.id ?: ALL)
        }
        Admin.act(RECREATE) { ctx, a ->
            val c = country(ctx, a.getOrNull(0))
            if (!c.active) ctx.fail("kami_claims.admin.discord.inactive")
            CountryChannels.recreate(c)
            Admin.done(c.id)
        }
        Admin.act(CLEAN) { ctx, _ ->
            if (!CountryChannels.on) ctx.fail("kami_claims.admin.discord.off")
            val removed = CountryChannels.clean()
            Admin.done(ALL, "applied" to JsonPrimitive(removed), audit = mapOf("removed" to removed.toString()))
        }
    }

    private fun country(ctx: AdminCtx, id: String?) = Realm.country(id) ?: ctx.fail("kami_claims.error.unknown_country")

    private fun overview(server: MinecraftServer) = RoomOverview(
        Config.s.countryChannels, DiscordApi.ready, DiscordApi.roomCategory,
        Realm.data.countries.values.filter { it.active || it.discordChannel != 0L }.sortedBy { it.name.lowercase() }.map { row(server, it) },
        DiscordApi.roomOrphans().map { (id, name) -> RoomOrphan(id.toString(), name) }
    )

    private fun row(server: MinecraftServer, c: Country): RoomRow {
        val actual = c.discordChannel.takeIf { it != 0L }?.let(DiscordApi::roomMembers)
        val members = c.members.entries.sortedByDescending { it.value.rank }.map { (id, m) ->
            val link = id.toUuidOrNull()?.let(Links::get)
            val state = when {
                link == null -> "unlinked"
                DiscordBans.banned(link.discordId) != null -> "banned"
                actual == null -> "pending"
                link.discordId in actual -> "in"
                else -> "missing"
            }
            RoomMember(id, Names.of(server, id), m.rank.name, link?.discordName.orEmpty(), state)
        }
        val wanted = members.mapNotNullTo(HashSet()) { m -> m.uuid.toUuidOrNull()?.let(Links::get)?.discordId?.takeIf { m.state != "banned" } }
        val strays = actual?.count { it !in wanted } ?: 0
        val state = when {
            !c.active -> "inactive"
            actual == null -> "none"
            strays > 0 || members.any { it.state == "missing" } -> "drift"
            else -> "ok"
        }
        return RoomRow(c.id, c.name, c.discordChannel.takeIf { it != 0L }?.toString().orEmpty(), state, strays, members)
    }
}
