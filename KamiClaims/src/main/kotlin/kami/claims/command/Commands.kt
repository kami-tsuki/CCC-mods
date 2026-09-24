package kami.claims.command

import kami.claims.*
import kami.claims.net.Net
import kami.claims.net.Sync
import kami.claims.service.Fail
import kami.claims.service.Service
import kami.claims.service.Upkeep
import kami.claims.social.Perms
import kami.claims.world.Effects

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import kotlin.math.abs

private typealias Ctx = CommandContext<CommandSourceStack>

private val s get() = Config.s

private fun <T : ArgumentBuilder<CommandSourceStack, T>> T.does(f: (Ctx) -> Unit): T = executes {
    try { f(it); 1 } catch (e: Fail) { it.source.sendFailure(Component.literal(e.message ?: "")); 0 }
}

private fun lit(n: String) = Commands.literal(n)
private fun <T> arg(n: String, t: ArgumentType<T>) = Commands.argument(n, t)
private fun word(n: String, options: () -> Collection<String>) =
    arg(n, StringArgumentType.word()).suggests { _, b -> SharedSuggestionProvider.suggest(options(), b) }
private fun player() = arg("player", GameProfileArgument.gameProfile())

private fun Ctx.me() = source.playerOrException
private fun Ctx.text(n: String) = StringArgumentType.getString(this, n)
private fun Ctx.who() = (GameProfileArgument.getGameProfiles(this, "player").firstOrNull() ?: throw Fail("Unknown player.")).id.toString()
private fun Ctx.say(msg: String) = source.sendSuccess({ Component.literal(msg) }, false)
private fun Ctx.run(name: String, vararg args: String) = say("§a" + Service.act(me(), name, args.toList()))

private fun status(c: Country, server: MinecraftServer): List<String> {
    val claims = Realm.claims(c.id)
    val sum = Upkeep.summary(c)
    return listOf(
        "§6${c.name} §7- president ${c.president()?.let { Names.of(server, it) } ?: "none"}, ${c.members.size} members",
        "§7Treasury §e${c.treasury} §7| upkeep §c${sum.upkeep}§7/day | taxes §a${sum.income}§7/day | jobs §c${sum.jobs}§7/day | runway §f${sum.runway(c.treasury)}",
        "§7Chunks ${claims.size} (free ${claims.count { it.free }}, in debt ${claims.count { it.debt > 0 }}) | " + claims.groupingBy { it.type }.eachCount().entries.joinToString { "${it.key} ${it.value}" }
    )
}

private fun map(p: ServerPlayer): List<String> {
    val o = Service.here(p)
    val palette = "abcde6935"
    val legend = linkedSetOf<String>()
    val rows = (-4..4).map { dz ->
        (-8..8).joinToString("") { dx ->
            val cl = Realm.index[Key(o.dim, o.x + dx, o.z + dz)]
            when {
                dx == 0 && dz == 0 -> "§f◆"
                cl == null -> "§8▪"
                else -> { legend += cl.country; "§${palette[abs(cl.country.hashCode()) % palette.length]}${if (cl.capital) "◈" else "■"}" }
            }
        }
    }
    return rows + "§7" + legend.joinToString { "§${palette[abs(it.hashCode()) % palette.length]}$it" }
}

private fun countryCmd(): LiteralArgumentBuilder<CommandSourceStack> {
    val countries = { Realm.data.countries.values.map { it.id } }
    val types = { s.types.keys }
    val jobs = { s.jobs.keys }
    fun simple(name: String, action: String = name) = lit(name).does { it.run(action) }
    fun target(name: String, action: String = name) = lit(name).then(player().does { it.run(action, it.who()) })
    fun byCountry(name: String, action: String = name) = lit(name).then(word("country", countries).does { it.run(action, it.text("country")) })

    return lit("country").requires { Perms.has(it, Perms.USE) }
        .does { ctx ->
            val p = ctx.me()
            if (!Net.canOpen(p)) status(Service.home(p), ctx.source.server).forEach(ctx::say) else Net.send(p, open = true)
        }
        .then(lit("gui").does { ctx ->
            val p = ctx.me()
            if (!Net.canOpen(p)) throw Fail("The Kami Claims mod is not installed on your client.")
            Sync.focus(p, Service.here(p).x, Service.here(p).z)
            Net.send(p, open = true)
        })
        .then(lit("create").requires { Perms.has(it, Perms.FOUND) }.then(arg("name", StringArgumentType.word()).does { it.run("create", it.text("name")) }))
        .then(lit("disband").then(lit("confirm").does { it.run("disband") }))
        .then(lit("info").does { ctx -> status(Service.home(ctx.me()), ctx.source.server).forEach(ctx::say) }
            .then(word("country", countries).does { ctx ->
                status(Realm.country(ctx.text("country")) ?: throw Fail("Unknown country."), ctx.source.server).forEach(ctx::say)
            }))
        .then(lit("list").does { ctx -> Realm.data.countries.values.forEach { ctx.say("§6${it.name} §7${it.members.size} members, ${Realm.claims(it.id).size} chunks") } })
        .then(lit("map").does { ctx -> map(ctx.me()).forEach(ctx::say) })
        .then(lit("border").does { ctx -> ctx.say(if (Effects.toggleBorders(ctx.me())) "§aBorder particles on." else "§7Border particles off.") })
        .then(target("invite"))
        .then(byCountry("accept"))
        .then(byCountry("join"))
        .then(lit("requests").does { ctx ->
            val c = Service.home(ctx.me())
            c.requests.keys.forEach { ctx.say("§7${Names.of(ctx.source.server, it)}") }
        })
        .then(target("approve"))
        .then(target("deny"))
        .then(simple("leave"))
        .then(target("kick"))
        .then(target("banish"))
        .then(target("ally"))
        .then(target("clear"))
        .then(lit("rank").then(player().then(word("rank") { listOf("citizen", "officer", "chancellor") }.does { it.run("rank", it.who(), it.text("rank")) })))
        .then(target("president"))
        .then(lit("claim").requires { Perms.has(it, Perms.CLAIM) }
            .does { it.run("claim", s.defaultType, "0") }
            .then(word("type", types).does { it.run("claim", it.text("type"), "0") }
                .then(arg("radius", IntegerArgumentType.integer(0, 8)).does { it.run("claim", it.text("type"), IntegerArgumentType.getInteger(it, "radius").toString()) })))
        .then(lit("unclaim").requires { Perms.has(it, Perms.CLAIM) }.does { it.run("unclaim") })
        .then(lit("type").requires { Perms.has(it, Perms.CLAIM) }.then(word("type", types).does { it.run("type", it.text("type")) }))
        .then(lit("capital").requires { Perms.has(it, Perms.CLAIM) }.does { it.run("capital") })
        .then(lit("deposit").then(arg("amount", IntegerArgumentType.integer(1)).does { it.run("deposit", IntegerArgumentType.getInteger(it, "amount").toString()) }))
        .then(lit("withdraw").then(arg("amount", IntegerArgumentType.integer(1)).does { it.run("withdraw", IntegerArgumentType.getInteger(it, "amount").toString()) }))
        .then(lit("tax")
            .then(lit("residential").then(arg("amount", IntegerArgumentType.integer(0)).does { it.run("tax", IntegerArgumentType.getInteger(it, "amount").toString()) }))
            .then(lit("here").then(arg("amount", IntegerArgumentType.integer(-1)).does { it.run("plot_tax", IntegerArgumentType.getInteger(it, "amount").toString()) })))
        .then(lit("lapse").then(arg("shutdown", IntegerArgumentType.integer(0)).then(arg("release", IntegerArgumentType.integer(0)).does {
            it.run("lapse", IntegerArgumentType.getInteger(it, "shutdown").toString(), IntegerArgumentType.getInteger(it, "release").toString())
        })))
        .then(lit("rule").requires { Perms.has(it, Perms.CLAIM) }.then(word("type", types).then(word("field") { Action.values().map { it.name.lowercase() } + listOf("machines", "fire", "fluid") }
            .then(word("value") { Access.values().map { it.name.lowercase() } + listOf("true", "false") }.does { it.run("rule", it.text("type"), it.text("field"), it.text("value")) }))))
        .then(lit("job").requires { Perms.has(it, Perms.JOBS) }
            .then(lit("list").does { ctx ->
                val c = Service.home(ctx.me())
                s.jobs.keys.forEach { j -> c.job(j)?.let { ctx.say("§6$j §7pay ${it.pay} / quota ${it.quota} / every ${it.period} day(s), type ${s.jobs.getValue(j).type}") } }
                c.members.forEach { (id, m) -> m.job?.let { ctx.say("§7${Names.of(ctx.source.server, id)}: $it ${m.progress}${if (m.zone.isEmpty()) "" else " [${m.zone.size} zone chunks]"}") } }
            })
            .then(lit("set").then(word("job", jobs).then(word("field") { listOf("pay", "quota", "period") }
                .then(arg("value", IntegerArgumentType.integer(0)).does { it.run("job_set", it.text("job"), it.text("field"), IntegerArgumentType.getInteger(it, "value").toString()) }))))
            .then(lit("assign").then(player().then(word("job", jobs).does { it.run("job_assign", it.who(), it.text("job")) })))
            .then(lit("unassign").then(player().does { it.run("job_unassign", it.who()) }))
            .then(lit("zone").then(lit("add").then(player().does { it.run("zone", it.who(), "add") })).then(lit("clear").then(player().does { it.run("zone", it.who(), "clear") }))))
        .then(provinceCmd(countries))
}

private fun taxTerm(c: Country) = if (c.taxMode == TaxMode.PERCENT) "${(c.taxAmount * 100).toInt()}%" else "${c.taxAmount.toInt()} spur/day"

private fun provinceCmd(countries: () -> Collection<String>): LiteralArgumentBuilder<CommandSourceStack> {
    fun offer(name: String, action: String) = lit(name).then(word("country", countries).then(word("mode") { listOf("percent", "flat") }
        .then(arg("amount", DoubleArgumentType.doubleArg(0.0)).does {
            it.run(action, it.text("country"), it.text("mode"), DoubleArgumentType.getDouble(it, "amount").toString())
        })))
    fun target(name: String, action: String = "province_$name") = lit(name).then(word("country", countries).does { it.run(action, it.text("country")) })

    return lit("province").requires { Perms.has(it, Perms.USE) }
        .then(offer("invite", "province_invite"))
        .then(target("request"))
        .then(target("accept"))
        .then(offer("approve", "province_approve"))
        .then(target("deny"))
        .then(target("release"))
        .then(target("forgive"))
        .then(offer("tax", "province_tax"))
        .then(lit("give").then(word("country", countries).then(word("newparent", countries).does { it.run("province_give", it.text("country"), it.text("newparent")) })))
        .then(lit("independence").does { it.run("province_independence") })
        .then(lit("list").does { ctx ->
            val c = Service.home(ctx.me())
            c.parent?.let { Realm.country(it) }?.let { par -> ctx.say("§6Province of ${par.name} §7${taxTerm(c)}, debt ${c.provinceDebt}/${s.maxProvinceDebt}") }
            c.provinces.mapNotNull { Realm.country(it) }.forEach { pr -> ctx.say("§7${pr.name} §f${taxTerm(pr)}, debt ${pr.provinceDebt}/${s.maxProvinceDebt}${if (pr.independenceRequested) " §e(wants independence)" else ""}") }
            if (c.parent == null && c.provinces.isEmpty()) ctx.say("§7Independent, no provinces.")
        })
}

private fun plotCmd(): LiteralArgumentBuilder<CommandSourceStack> =
    lit("plot").requires { Perms.has(it, Perms.PLOT) }
        .then(lit("claim").does { it.run("plot_claim") })
        .then(lit("release").does { it.run("plot_release") })
        .then(lit("trust").then(player().then(word("role") { Role.values().map { it.name.lowercase() } }.does { it.run("plot_trust", it.who(), it.text("role")) })))
        .then(lit("untrust").then(player().does { it.run("plot_untrust", it.who()) }))
        .then(lit("info").does { ctx ->
            val p = ctx.me()
            val c = Service.home(p)
            val cl = Realm.index[Service.here(p)]?.takeIf { it.country == c.id && it.owner != null } ?: throw Fail("Nobody owns this plot.")
            ctx.say("§6Plot of ${Names.of(ctx.source.server, cl.owner!!)} §7tax ${if (cl.tax >= 0) cl.tax else c.tax}/day, lapse ${cl.lapse}/${c.shutdown + c.release}")
            cl.roles.forEach { (id, r) -> ctx.say("§7${Names.of(ctx.source.server, id)}: ${r.name.lowercase()}") }
        })

private fun adminCmd() = lit("countryadmin").requires { Perms.has(it, Perms.ADMIN) }
    .then(lit("disband").then(word("country") { Realm.data.countries.keys }.does { ctx ->
        Realm.disband(Realm.country(ctx.text("country")) ?: throw Fail("Unknown country."))
        ctx.say("§aDisbanded.")
    }))
    .then(lit("treasury").then(word("country") { Realm.data.countries.keys }.then(arg("amount", LongArgumentType.longArg(0)).does { ctx ->
        val c = Realm.country(ctx.text("country")) ?: throw Fail("Unknown country.")
        c.treasury = LongArgumentType.getLong(ctx, "amount")
        Realm.dirty = true
        ctx.say("§aTreasury set.")
    })))
    .then(lit("unclaim").does { ctx ->
        Realm.unclaim(Realm.index[Service.here(ctx.me())] ?: throw Fail("Nomansland."), false)
        ctx.say("§aChunk released.")
    })
    .then(lit("day").does { ctx ->
        Realm.data.day = Realm.data.day.coerceAtLeast(0)
        Upkeep.process(++Realm.data.day)
        ctx.say("§aProcessed one billing day.")
    })
    .then(lit("save").does { ctx -> Realm.save(true); ctx.say("§aSaved.") })
    .then(lit("reload").does { ctx -> Config.load(); ctx.say("§aConfig reloaded.") })

object Commands {
    fun register(d: CommandDispatcher<CommandSourceStack>) {
        d.register(countryCmd())
        d.register(plotCmd())
        d.register(adminCmd())
    }
}
