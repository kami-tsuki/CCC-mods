package kami.essentials.command

import com.mojang.authlib.GameProfile
import kami.essentials.Perms
import kami.essentials.discord.Bot
import kami.essentials.discord.Gate
import kami.libs.command.Ctx
import kami.libs.command.KamiCommands
import kami.libs.command.arg
import kami.libs.command.does
import kami.libs.command.fail
import kami.libs.command.info
import kami.libs.command.lit
import kami.libs.command.me
import kami.libs.command.ok
import kami.libs.config.Configs
import kami.libs.config.Jsonc
import kami.libs.discord.Link
import kami.libs.discord.Links
import kami.libs.text.Phrase
import net.minecraft.commands.arguments.GameProfileArgument

object DiscordCommands {
    fun register() = KamiCommands.module("discord", "Discord link and bot") {
        then(lit("unlink").does { unlink(it, it.me().gameProfile, "self") })
        then(lit("admin").requires(Perms.gate(Perms.DISCORD_ADMIN))
            .then(lit("unlink").then(arg("player", GameProfileArgument.gameProfile()).does { unlink(it, it.profile(), "other") }))
            .then(lit("lookup").then(arg("player", GameProfileArgument.gameProfile()).does { lookup(it, it.profile()) })))
        then(lit("reload").requires(Perms.gate(Perms.DISCORD_RELOAD)).does(::reload))
    }


    private fun linked(profile: GameProfile): Link = Links.get(profile.id) ?: fail(Phrase.of("kami_essentials.discord.command.not_linked", Phrase.value(profile.name)))

    private fun unlink(ctx: Ctx, profile: GameProfile, kind: String) {
        val link = linked(profile)
        Gate.unlink(profile.id, "unlinked")
        ctx.ok(Phrase.of("kami_essentials.discord.command.unlinked.$kind", Phrase.value(link.name)))
    }

    private fun lookup(ctx: Ctx, profile: GameProfile) {
        val link = linked(profile)
        ctx.info(Phrase.of("kami_essentials.discord.command.lookup", Phrase.value(link.name), Phrase.value(link.discordName), Phrase.value(link.discordId)))
    }

    private fun reload(ctx: Ctx) {
        Configs.reload("essentials").forEach { (_, result) -> result.exceptionOrNull()?.let { fail(Phrase.of("kami_essentials.discord.command.reload_failed", Jsonc.reason(it))) } }
        Bot.reload(ctx.source.server)
        ctx.ok(Phrase.of("kami_essentials.discord.command.reloaded"))
    }
}
