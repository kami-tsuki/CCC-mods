package kami.essentials.command

import com.mojang.authlib.GameProfile
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import kami.essentials.Config
import kami.essentials.Perms
import kami.essentials.chat.Talk
import kami.essentials.display.Sidebar
import kami.essentials.inv.Views
import kami.essentials.trade.Trades
import kami.essentials.vanish.Vanish
import kami.libs.chat.spur
import kami.libs.command.Ctx
import kami.libs.command.KamiCommands
import kami.libs.command.arg
import kami.libs.command.does
import kami.libs.command.fail
import kami.libs.command.info
import kami.libs.command.lit
import kami.libs.command.me
import kami.libs.command.ok
import kami.libs.command.text
import kami.libs.economy.Numismatics
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.server.level.ServerPlayer

object EssentialsCommands {
    private val own = listOf("invsee", "enderchest", "balance", "invis", "scoreboard", "msg", "r", "trade", "countrychat")
    private val aliases = mapOf("tell" to "msg", "w" to "msg", "bal" to "balance", "ec" to "enderchest", "cc" to "countrychat")

    fun register() = KamiCommands.module("essentials", "Everyday server commands", own.associateWith { it } + aliases) {
        then(lit("invsee").requires(Perms.gate(Perms.INVSEE))
            .then(arg("player", GameProfileArgument.gameProfile()).does { Views.inventory(it.me(), it.profile(), Perms.has(it.source, Perms.INVSEE_EDIT)) }))
        then(lit("enderchest").requires(Perms.gate(Perms.ENDERCHEST))
            .does { Views.enderchest(it.me(), it.me().gameProfile, true) }
            .then(arg("player", GameProfileArgument.gameProfile()).requires(Perms.gate(Perms.ENDERCHEST_OTHERS))
                .does { Views.enderchest(it.me(), it.profile(), Perms.has(it.source, Perms.ENDERCHEST_EDIT)) }))
        then(lit("balance").requires(Perms.gate(Perms.BALANCE))
            .does { balance(it, it.me().gameProfile) }
            .then(arg("player", GameProfileArgument.gameProfile()).requires(Perms.gate(Perms.BALANCE_OTHERS)).does { balance(it, it.profile()) }))
        then(lit("invis").requires(Perms.gate(Perms.INVIS))
            .does { Vanish.toggle(it.me(), null) }
            .then(arg("player", EntityArgument.player()).requires(Perms.gate(Perms.INVIS_OTHERS)).does { Vanish.toggle(it.target(), it.source.player) }))
        then(lit("scoreboard").requires(Perms.gate(Perms.SCOREBOARD))
            .does { sidebar(it, null) }
            .then(lit("toggle").does { sidebar(it, null) })
            .then(arg("show", BoolArgumentType.bool()).does { sidebar(it, BoolArgumentType.getBool(it, "show")) }))
        then(lit("msg").requires(Perms.gate(Perms.MSG))
            .then(arg("player", EntityArgument.player()).then(arg("message", StringArgumentType.greedyString())
                .does { Talk.direct(it.source.player, it.target(), it.text("message")) })))
        then(lit("r").requires(Perms.gate(Perms.MSG))
            .then(arg("message", StringArgumentType.greedyString()).does { Talk.reply(it.me(), it.text("message")) }))
        then(lit("trade").requires(Perms.gate(Perms.TRADE))
            .then(arg("player", EntityArgument.player()).does { Trades.request(it.me(), it.target()) })
            .then(lit("accept").then(arg("from", EntityArgument.player()).does { Trades.accept(it.me(), it.target("from")) }))
            .then(lit("deny").then(arg("from", EntityArgument.player()).does { Trades.deny(it.me(), it.target("from")) }))
            .then(lit("cancel").does { Trades.cancel(it.me()) }))
        then(lit("countrychat").requires(Perms.gate(Perms.COUNTRYCHAT))
            .does { Talk.toggleCountry(it.me()) }
            .then(arg("message", StringArgumentType.greedyString()).does { Talk.country(it.me(), it.text("message")) }))
    }

    private fun Ctx.profile(): GameProfile = GameProfileArgument.getGameProfiles(this, "player").singleOrNull() ?: fail("Pick exactly one player.")

    private fun Ctx.target(name: String = "player"): ServerPlayer {
        val p = EntityArgument.getPlayer(this, name)
        val viewer = source.player
        if (viewer != null && Vanish.hides(p, viewer)) fail("No player named {${p.gameProfile.name}} is online.")
        return p
    }

    private fun balance(ctx: Ctx, who: GameProfile) {
        val amount = spur(Numismatics.balance(who.id))
        if (who.id == ctx.source.player?.uuid) ctx.info("Your balance is {$amount}.") else ctx.info("{${who.name}} has {$amount}.")
    }

    private fun sidebar(ctx: Ctx, show: Boolean?) {
        val p = ctx.me()
        if (!Config.s.sidebar) fail("The sidebar is turned off on this server.")
        val next = show ?: !Sidebar.enabled(p)
        Sidebar.set(p, next)
        ctx.ok(if (next) "Sidebar shown." else "Sidebar hidden.")
    }
}
