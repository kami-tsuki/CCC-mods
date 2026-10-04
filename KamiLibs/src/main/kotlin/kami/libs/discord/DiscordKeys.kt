package kami.libs.discord

import kami.libs.config.ConfigFolder
import kotlinx.serialization.Serializable
import net.neoforged.fml.loading.FMLPaths

@Serializable
data class DiscordKeys(
    val token: String = "", val guildId: String = "", val chatChannelId: String = "", val consoleChannelId: String = "",
    val chatWebhookUrl: String = "", val consoleWebhookUrl: String = "", val verifiedRoleId: String = "", val adminRoleId: String = "", val inviteLink: String = ""
) {
    override fun toString() = "DiscordKeys(***)"
}

internal object KeyFile {
    private val folder by lazy { ConfigFolder(FMLPaths.CONFIGDIR.get(), "Reload with /discord reload") }

    private val docs = mapOf(
        "token" to "Bot token from the Discord developer portal. Keep it secret.",
        "guildId" to "Id of the Discord server (guild) the bot works in.",
        "chatChannelId" to "Id of the channel that is bridged with the in-game chat.",
        "consoleChannelId" to "Id of the channel that receives console output. Empty disables the console feed.",
        "chatWebhookUrl" to "Optional webhook URL for the chat channel. Empty lets the bot create its own webhook.",
        "consoleWebhookUrl" to "Optional webhook URL for the console channel. Empty lets the bot create its own webhook.",
        "verifiedRoleId" to "Id of the role given to linked players. Empty disables role handling.",
        "adminRoleId" to "Id of the role allowed to use admin slash commands.",
        "inviteLink" to "Invite link shown to players who still have to verify."
    )

    private val path by lazy { FMLPaths.CONFIGDIR.get().resolve("kami-discord-bot.json") }

    val broken: Boolean get() = folder.problems.isNotEmpty()

    fun load(): DiscordKeys {
        folder.startLoad()
        val keys = folder.file("kami-discord-bot.json", DiscordKeys.serializer(), DiscordKeys(), docs, "Kami Discord bot keys").trimmed()
        restrict(path)
        return keys
    }

    private fun DiscordKeys.trimmed() = DiscordKeys(
        token.trim(), guildId.trim(), chatChannelId.trim(), consoleChannelId.trim(), chatWebhookUrl.trim(), consoleWebhookUrl.trim(), verifiedRoleId.trim(), adminRoleId.trim(), inviteLink.trim()
    )
}
