package kami.libs.discord

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object Mc : DiscordFeature {
    private val added = CopyOnWriteArrayList<SlashCommand>()
    private val sections = CopyOnWriteArrayList<(UUID, String) -> List<EmbedField>>()

    fun add(cmd: SlashCommand) {
        added.removeIf { it.name == cmd.name }
        added += cmd
    }

    fun section(provider: (UUID, String) -> List<EmbedField>) {
        sections += provider
    }

    internal fun fields(uuid: UUID, locale: String): List<EmbedField> = sections.flatMap { runCatching { it(uuid, locale) }.getOrDefault(emptyList()) }

    override fun commands() = listOf(
        SlashCommand("mc", DiscordText.get("kami_libs", DiscordText.FALLBACK, "kami_libs.discord.mc.description"), subcommands = McCommands.all + added)
    )
}
