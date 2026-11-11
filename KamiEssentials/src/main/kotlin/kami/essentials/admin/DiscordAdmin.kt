package kami.essentials.admin

import kami.essentials.discord.Gate
import kami.essentials.discord.Relay
import kami.libs.admin.Admin
import kami.libs.admin.AdminPerms
import kami.libs.admin.Players
import kami.libs.db.Db
import kami.libs.discord.DiscordApi
import kami.libs.discord.DiscordPrefs
import kami.libs.discord.Links
import kami.libs.stats.Stats
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
class DiscordEvent(val at: Long, val kind: String, val detail: String)

@Serializable
class DiscordDetail(
    val linked: Boolean,
    val discordId: String = "",
    val discordName: String = "",
    val linkedAt: Long = 0,
    val nickname: String = "",
    val avatar: String = "",
    val joinedGuild: Long = 0,
    val roles: List<String> = emptyList(),
    val verified: Boolean = false,
    val inGuild: Boolean = false,
    val botReady: Boolean = false,
    val sentToMc: Long = 0,
    val sentToDiscord: Long = 0,
    val pings: Long = 0,
    val lastMessage: Long = 0,
    val history: List<DiscordEvent> = emptyList(),
    val marketAlerts: Boolean = false
)

object DiscordAdmin {
    private const val HISTORY = 50
    private val kinds = listOf("discord.link", "discord.unlink", "discord.code")

    fun register() {
        Admin.view("players.discord") { ctx, a -> Db.encode(DiscordDetail.serializer(), detail(AdminServer.target(ctx, a))) }
        Admin.act("players.discord.unlink") { ctx, a ->
            val id = AdminServer.target(ctx, a)
            if (id != ctx.player.uuid && !AdminPerms.outranks(ctx.player, Players.profile(ctx.player.server, id))) ctx.fail("kami_essentials.admin.outranked")
            val link = Links.get(id) ?: ctx.fail("kami_essentials.admin.discord_not_linked", id)
            Gate.unlink(id)
            Admin.done(id, audit = mapOf("discord" to link.discordId.toString()))
        }
        Admin.act("players.discord.resync") { ctx, a ->
            val id = AdminServer.target(ctx, a)
            val link = Links.get(id) ?: ctx.fail("kami_essentials.admin.discord_not_linked", id)
            if (!DiscordApi.ready) ctx.fail("kami_essentials.admin.discord_down")
            val server = ctx.player.server
            DiscordApi.info(link.discordId) { info ->
                if (info != null && Links.get(id)?.discordId == link.discordId) {
                    Links.rename(id, Players.name(server, id) ?: link.name, info.userName)
                    DiscordApi.grant(link.discordId)
                }
            }
            Admin.done(id, audit = mapOf("discord" to link.discordId.toString()))
        }
    }

    private fun detail(id: UUID): DiscordDetail {
        val subject = "p:$id"
        val history = kinds.flatMap { key -> Stats.events(key, null, subject, HISTORY).map { (at, o, _) -> DiscordEvent(at, key.substringAfter('.'), o) } }
            .sortedByDescending { it.at }.take(HISTORY)
        val sentToMc = Stats.total("discord.in", null, subject)
        val sentToDiscord = Stats.total("discord.out", null, subject)
        val pings = Stats.total("discord.ping", null, subject)
        val link = Links.get(id) ?: return DiscordDetail(false, botReady = DiscordApi.ready, sentToMc = sentToMc, sentToDiscord = sentToDiscord, pings = pings, lastMessage = Relay.lastMessage(id), history = history)
        val info = DiscordApi.info(link.discordId, 0)
        return DiscordDetail(
            true, link.discordId.toString(), link.discordName, link.linkedAt, info?.nickname.orEmpty(), info?.avatar.orEmpty(), info?.joined ?: 0, info?.roles.orEmpty(),
            info?.verified == true, info != null, DiscordApi.ready, sentToMc, sentToDiscord, pings, Relay.lastMessage(id), history,
            DiscordPrefs.on(id, DiscordPrefs.MARKET_ALERTS)
        )
    }
}
