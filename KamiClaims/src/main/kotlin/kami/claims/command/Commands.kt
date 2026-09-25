package kami.claims.command

import kami.claims.*
import kami.claims.net.Net
import kami.claims.net.Sync
import kami.claims.service.Service
import kami.claims.service.Upkeep
import kami.claims.social.Perms
import kami.claims.world.Effects

import kami.libs.chat.Theme
import kami.libs.chat.every
import kami.libs.chat.plural
import kami.libs.chat.spur
import kami.libs.command.*
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.server.level.ServerPlayer

private val s get() = Config.s

private fun player() = arg("player", GameProfileArgument.gameProfile())

private fun Ctx.who() = (GameProfileArgument.getGameProfiles(this, "player").firstOrNull() ?: fail("Unknown player.")).id.toString()
private fun Ctx.run(name: String, vararg args: String) = ok(Service.act(me(), name, args.toList()))

private fun color(c: Country?) = c?.color?.takeIf { it != 0 } ?: Theme.ACCENT

private fun Ctx.status(c: Country) {
    val server = source.server
    val claims = Realm.claims(c.id)
    val sum = Upkeep.summary(c)
    msg {
        text(c.name, color(c))
        muted("  led by ")
        value(c.president()?.let { Names.of(server, it) } ?: "nobody")
        muted(", ${plural(c.members.size, "member")}")
    }
    row { muted("Treasury "); value(spur(c.treasury)); muted("  runway "); value(sum.runway(c.treasury)) }
    row { muted("Daily  "); good("+${sum.income} taxes"); muted("  "); bad("-${sum.upkeep} upkeep"); muted("  "); bad("-${sum.jobs} wages") }
    row {
        muted("Land "); value(plural(claims.size, "chunk")); muted(" (${claims.count { it.free }} free")
        claims.count { it.debt > 0 }.takeIf { it > 0 }?.let { muted(", "); bad("$it in debt") }
        muted(")  " + claims.groupingBy { it.type }.eachCount().entries.joinToString("  ") { "${it.key} ${it.value}" })
    }
}

private fun Ctx.map(p: ServerPlayer) {
    val o = Service.here(p)
    val legend = linkedSetOf<String>()
    msg { value("Map"); muted("  you are at the white diamond") }
    (-4..4).forEach { dz ->
        row {
            (-8..8).forEach { dx ->
                val cl = Realm.index[Key(o.dim, o.x + dx, o.z + dz)]
                when {
                    dx == 0 && dz == 0 -> value("◆")
                    cl == null -> text("▪", 0x3A3A42)
                    else -> { legend += cl.country; text(if (cl.capital) "◈" else "■", color(Realm.country(cl.country))) }
                }
            }
        }
    }
    if (legend.isNotEmpty()) row { legend.forEachIndexed { i, id -> if (i > 0) muted("  "); text("■ ", color(Realm.country(id))); text(Realm.country(id)?.name ?: id) } }
}

private fun Node.countryCmd(): Node {
    val countries = { Realm.data.countries.values.map { it.id } }
    val types = { s.types.keys }
    val jobs = { s.jobs.keys }
    fun simple(name: String, action: String = name) = lit(name).does { it.run(action) }
    fun target(name: String, action: String = name) = lit(name).then(player().does { it.run(action, it.who()) })
    fun byCountry(name: String, action: String = name) = lit(name).then(word("country", countries).does { it.run(action, it.text("country")) })

    return requires { Perms.has(it, Perms.USE) }
        .does { ctx ->
            val p = ctx.me()
            if (!Net.canOpen(p)) ctx.status(Service.home(p)) else Net.send(p, open = true)
        }
        .then(lit("gui").does { ctx ->
            val p = ctx.me()
            if (!Net.canOpen(p)) fail("The Kami Claims mod is not installed on your client.")
            Sync.focus(p, Service.here(p).x, Service.here(p).z)
            Net.send(p, open = true)
        })
        .then(lit("create").requires { Perms.has(it, Perms.FOUND) }.then(arg("name", StringArgumentType.word()).does { it.run("create", it.text("name")) }))
        .then(lit("disband").then(lit("confirm").does { it.run("disband") }))
        .then(lit("info").does { ctx -> ctx.status(Service.home(ctx.me())) }
            .then(word("country", countries).does { ctx ->
                ctx.status(Realm.country(ctx.text("country")) ?: fail("Unknown country {${ctx.text("country")}}."))
            }))
        .then(lit("list").does { ctx ->
            val all = Realm.data.countries.values.sortedByDescending { Realm.claims(it.id).size }
            if (all.isEmpty()) return@does ctx.info("No countries yet. Found one with {/claims create <name>}.")
            ctx.info("{${plural(all.size, "country", "countries")}}")
            all.forEach { c -> ctx.row { run(c.name, "/claims info ${c.id}", "Show ${c.name}"); muted("  ${plural(c.members.size, "member")}, ${plural(Realm.claims(c.id).size, "chunk")}") } }
        })
        .then(lit("map").does { ctx -> ctx.map(ctx.me()) })
        .then(lit("border").does { ctx -> if (Effects.toggleBorders(ctx.me())) ctx.ok("Border particles {on}.") else ctx.info("Border particles {off}.") })
        .then(target("invite"))
        .then(byCountry("accept"))
        .then(byCountry("join"))
        .then(lit("requests").does { ctx ->
            val c = Service.home(ctx.me())
            if (c.requests.isEmpty()) return@does ctx.info("No open join requests.")
            ctx.info("{${plural(c.requests.size, "join request")}}")
            c.requests.keys.forEach { id -> Names.of(ctx.source.server, id).let { n -> ctx.row { value(n); muted("  "); button("Approve", "/claims approve $n"); text(" "); button("Deny", "/claims deny $n") } } }
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
                ctx.info("Jobs of {${c.name}}")
                s.jobs.keys.forEach { j -> c.job(j)?.let { ctx.row { value(j); muted("  ${spur(it.pay)} for ${it.quota} actions ${every(it.period)}, in ${s.jobs.getValue(j).type} chunks") } } }
                c.members.forEach { (id, m) -> m.job?.let { ctx.row { text("• ${Names.of(ctx.source.server, id)} "); muted("$it, progress "); value(m.progress); if (m.zone.isNotEmpty()) muted(", ${plural(m.zone.size, "zone chunk")}") } } }
            })
            .then(lit("set").then(word("job", jobs).then(word("field") { listOf("pay", "quota", "period") }
                .then(arg("value", IntegerArgumentType.integer(0)).does { it.run("job_set", it.text("job"), it.text("field"), IntegerArgumentType.getInteger(it, "value").toString()) }))))
            .then(lit("assign").then(player().then(word("job", jobs).does { it.run("job_assign", it.who(), it.text("job")) })))
            .then(lit("unassign").then(player().does { it.run("job_unassign", it.who()) }))
            .then(lit("zone").then(lit("add").then(player().does { it.run("zone", it.who(), "add") })).then(lit("clear").then(player().does { it.run("zone", it.who(), "clear") }))))
        .then(provinceCmd(countries))
}

private fun taxTerm(c: Country) = if (c.taxMode == TaxMode.PERCENT) "${(c.taxAmount * 100).toInt()}%" else "${c.taxAmount.toInt()} spur/day"

private fun provinceCmd(countries: () -> Collection<String>): Node {
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
            c.parent?.let { Realm.country(it) }?.let { par -> ctx.info("Province of {${par.name}}, tribute {${taxTerm(c)}}, missed {${c.provinceDebt}}/${s.maxProvinceDebt}") }
            c.provinces.mapNotNull { Realm.country(it) }.takeIf { it.isNotEmpty() }?.let { list ->
                ctx.info("{${plural(list.size, "province")}}")
                list.forEach { pr -> ctx.row { value(pr.name); muted("  ${taxTerm(pr)}, missed ${pr.provinceDebt}/${s.maxProvinceDebt}"); if (pr.independenceRequested) text("  wants independence", Theme.WARN) } }
            }
            if (c.parent == null && c.provinces.isEmpty()) ctx.info("Independent, no provinces.")
        })
}

private fun plotCmd(): Node =
    lit("plot").requires { Perms.has(it, Perms.PLOT) }
        .then(lit("claim").does { it.run("plot_claim") })
        .then(lit("release").does { it.run("plot_release") })
        .then(lit("trust").then(player().then(word("role") { Role.values().map { it.name.lowercase() } }.does { it.run("plot_trust", it.who(), it.text("role")) })))
        .then(lit("untrust").then(player().does { it.run("plot_untrust", it.who()) }))
        .then(lit("info").does { ctx ->
            val p = ctx.me()
            val c = Service.home(p)
            val cl = Realm.index[Service.here(p)]?.takeIf { it.country == c.id && it.owner != null } ?: fail("Nobody owns this plot.")
            ctx.info("Plot of {${Names.of(ctx.source.server, cl.owner!!)}}, tax {${spur(if (cl.tax >= 0) cl.tax else c.tax)}} a day, unpaid {${cl.lapse}}/${c.shutdown + c.release} days")
            cl.roles.forEach { (id, r) -> ctx.row { value(Names.of(ctx.source.server, id)); muted("  ${r.name.lowercase()}") } }
        })

private fun adminCmd() = lit("admin").requires { Perms.has(it, Perms.ADMIN) }
    .then(lit("disband").then(word("country") { Realm.data.countries.keys }.does { ctx ->
        Realm.disband(Realm.country(ctx.text("country")) ?: fail("Unknown country."))
        ctx.ok("Country disbanded.", true)
    }))
    .then(lit("treasury").then(word("country") { Realm.data.countries.keys }.then(arg("amount", LongArgumentType.longArg(0)).does { ctx ->
        val c = Realm.country(ctx.text("country")) ?: fail("Unknown country.")
        c.treasury = LongArgumentType.getLong(ctx, "amount")
        Realm.dirty = true
        ctx.ok("Treasury of {${c.name}} set to {${spur(c.treasury)}}.", true)
    })))
    .then(lit("unclaim").does { ctx ->
        Realm.unclaim(Realm.index[Service.here(ctx.me())] ?: fail("Nobody owns this chunk."), false)
        ctx.ok("Chunk released.", true)
    })
    .then(lit("day").does { ctx ->
        Realm.data.day = Realm.data.day.coerceAtLeast(0)
        Upkeep.process(++Realm.data.day)
        ctx.ok("Billed day {${Realm.data.day}}.", true)
    })
    .then(lit("save").does { ctx -> Realm.save(true); ctx.ok("Saved.") })

object ClaimsCommands {
    fun register() = KamiCommands.module("claims", "Countries, claims, plots and provinces") {
        countryCmd().then(plotCmd()).then(adminCmd())
    }
}
