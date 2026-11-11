package kami.claims.command

import kami.claims.*
import kami.claims.admin.CountryAdmin
import kami.claims.admin.CountryRequests
import kami.claims.gov.JobScope
import kami.claims.gov.Perm
import kami.claims.gov.Permissions
import kami.claims.net.Net
import kami.claims.net.Sync
import kami.claims.service.Housing
import kami.claims.service.NeedsConfirm
import kami.claims.service.Scope
import kami.claims.service.Words
import kami.claims.service.Words.chunks
import kami.claims.service.Words.count
import kami.claims.service.Words.money
import kami.claims.service.Words.num
import kami.claims.service.Service
import kami.claims.service.Upkeep
import kami.claims.social.Perms
import kami.claims.world.Effects
import kami.libs.admin.Audit
import kami.libs.chat.Theme
import kami.libs.command.*
import kami.libs.text.Phrase
import kami.libs.text.Text
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

private val s get() = Config.s

private fun player() = arg("player", GameProfileArgument.gameProfile())

private fun Ctx.who() = (GameProfileArgument.getGameProfiles(this, "player").firstOrNull() ?: fail(Phrase.of("kami_claims.error.unknown_player"))).id.toString()
private fun Ctx.run(name: String, vararg args: String) {
    try {
        ok(Service.act(me(), name, args.toList()))
    } catch (e: NeedsConfirm) {
        e.lines.forEachIndexed { i, line -> if (i == 0) warn(line) else row { add(line) } }
        row {
            button(Phrase.of("kami_libs.common.confirm"), "/${input.removePrefix("/")} confirm", Phrase.of("kami_claims.chat.confirm.tooltip"))
            muted("  ")
            add(Phrase.of("kami_claims.chat.confirm.hint"), Theme.MUTED)
        }
    }
}

private fun color(c: Country?) = c?.color?.takeIf { it != 0 } ?: Theme.ACCENT

private fun Ctx.status(c: Country) {
    val server = source.server
    val viewer = me()
    val full = Scope.of(viewer.stringUUID, Perms.has(viewer, Perms.ADMIN))(c.id)
    val claims = Realm.claims(c.id)
    val sum = Upkeep.summary(c)
    val leader = Permissions.president(c)?.let { Phrase.value(Names.of(server, it)) } ?: Phrase.value(Phrase.of("kami_claims.status.nobody"))
    msg {
        text(c.name, color(c))
        muted("  ")
        add(Phrase.of("kami_claims.status.led_by", leader, count("kami_claims.unit.member", c.members.size)), Theme.MUTED)
    }
    if (!full) return
    row { add(Phrase.of("kami_claims.status.treasury", money(c.treasury), Phrase.value(sum.runway(c.treasury))), Theme.MUTED) }
    row {
        add(Text.msg("kami_claims.status.daily",
            Component.literal("+${sum.income}").withColor(Theme.OK),
            Component.literal("-${sum.upkeep}").withColor(Theme.BAD),
            Component.literal("-${sum.jobs}").withColor(Theme.BAD)), Theme.MUTED)
    }
    row {
        add(Phrase.of("kami_claims.status.land", chunks(claims.size), num(claims.count { it.free })), Theme.MUTED)
        claims.count { it.debt > 0 }.takeIf { it > 0 }?.let { muted(" · "); add(Phrase.of("kami_claims.common.in_debt_x", it), Theme.BAD) }
        claims.groupingBy { it.type }.eachCount().forEach { (type, n) -> muted("  "); add(Words.type(type), Theme.MUTED); muted(" $n") }
    }
}

private fun Ctx.map(p: ServerPlayer) {
    val o = Service.here(p)
    val legend = linkedSetOf<String>()
    val scope = Scope.of(p.stringUUID, Perms.has(p, Perms.ADMIN))
    msg { add(Phrase.of("kami_claims.nav.map"), Theme.VALUE); muted("  "); add(Phrase.of("kami_claims.status.map.hint"), Theme.MUTED) }
    (-4..4).forEach { dz ->
        row {
            (-8..8).forEach { dx ->
                val cl = Realm.index[Key(o.dim, o.x + dx, o.z + dz)]
                when {
                    dx == 0 && dz == 0 -> value("◆")
                    cl == null -> text("▪", 0x3A3A42)
                    else -> { legend += cl.country; text(if (cl.capital && scope(cl.country)) "◈" else "■", color(Realm.country(cl.country))) }
                }
            }
        }
    }
    if (legend.isNotEmpty()) row { legend.forEachIndexed { i, id -> if (i > 0) muted("  "); text("■ ", color(Realm.country(id))); text(Realm.country(id)?.name ?: id) } }
}

private fun Node.countryCore(countries: () -> Collection<String>): Node =
    does { ctx ->
        val p = ctx.me()
        if (!Net.canOpen(p)) ctx.status(Service.home(p)) else Net.send(p, open = true)
    }
    .then(lit("gui").does { ctx ->
        val p = ctx.me()
        if (!Net.canOpen(p)) fail(Phrase.of("kami_claims.error.no_client"))
        Sync.focus(p, Service.here(p).x, Service.here(p).z)
        Net.send(p, open = true)
    })
    .then(lit("create").requires { Perms.has(it, Perms.FOUND) }.then(arg("name", StringArgumentType.word()).does { it.run("create", it.text("name")) }))
    .then(lit("country").then(lit("rename").then(arg("name", StringArgumentType.word()).does { it.run("rename", it.text("name")) }))
        .then(lit("rate").then(word("country", countries).then(arg("stars", DoubleArgumentType.doubleArg(0.5, 5.0)).does {
            val half = DoubleArgumentType.getDouble(it, "stars") * 2
            if (half % 1.0 != 0.0) fail(Phrase.of("kami_claims.error.fame.range"))
            it.run("fame_rate", it.text("country"), half.toInt().toString())
        })))
        .then(lit("minrep").then(arg("value", IntegerArgumentType.integer(0)).does { it.run("min_reputation", IntegerArgumentType.getInteger(it, "value").toString()) })))
    .then(lit("disband").does { it.run("disband") }.then(lit("confirm").does { it.run("disband", "confirm") }))
    .then(lit("info").does { ctx -> ctx.status(Service.home(ctx.me())) }
        .then(word("country", countries).does { ctx ->
            ctx.status(Realm.country(ctx.text("country")) ?: fail(Phrase.of("kami_claims.error.unknown_country_named", Phrase.value(ctx.text("country")))))
        }))
    .then(lit("list").does { ctx ->
        val all = Realm.data.countries.values.sortedByDescending { Realm.claimCount(it.id) }
        if (all.isEmpty()) return@does ctx.info(Phrase.of("kami_claims.list.empty", Phrase.value("/claims create <name>")))
        ctx.info(count("kami_claims.unit.country", all.size))
        all.forEach { c ->
            ctx.row {
                run(Component.literal(c.name), "/claims info ${c.id}", Phrase.of("kami_claims.chat.info.tooltip", c.name).component())
                muted("  ")
                add(Phrase.of("kami_libs.format.list", count("kami_claims.unit.member", c.members.size), chunks(Realm.claimCount(c.id))), Theme.MUTED)
            }
        }
    })
    .then(lit("map").does { ctx -> ctx.map(ctx.me()) })
    .then(lit("border").does { ctx ->
        if (Effects.toggleBorders(ctx.me())) ctx.ok(Phrase.of("kami_claims.border.on")) else ctx.info(Phrase.of("kami_claims.border.off"))
        if (Net.canOpen(ctx.me())) ctx.info(Phrase.of("kami_claims.border.tip", Phrase.value("B")))
    })

private fun Node.countryMembership(countries: () -> Collection<String>): Node {
    fun simple(name: String, action: String = name) = lit(name).does { it.run(action) }
    fun target(name: String, action: String = name) = lit(name).then(player().does { it.run(action, it.who()) })
    fun byCountry(name: String, action: String = name) = lit(name).then(word("country", countries).does { it.run(action, it.text("country")) })

    return then(target("invite"))
        .then(byCountry("accept"))
        .then(byCountry("join"))
        .then(lit("requests").does { ctx ->
            val c = Service.home(ctx.me())
            if (c.requests.isEmpty()) return@does ctx.info(Phrase.of("kami_claims.requests.empty"))
            ctx.info(count("kami_claims.unit.request", c.requests.size))
            c.requests.keys.forEach { id ->
                Names.of(ctx.source.server, id).let { n ->
                    ctx.row {
                        value(n)
                        muted("  ")
                        button(Phrase.of("kami_libs.common.accept"), "/claims approve $n", Phrase.of("kami_claims.chat.approve.tooltip", n))
                        text(" ")
                        button(Phrase.of("kami_libs.common.decline"), "/claims deny $n", Phrase.of("kami_claims.chat.deny.tooltip", n))
                    }
                }
            }
        })
        .then(target("approve"))
        .then(target("deny"))
        .then(simple("leave"))
        .then(target("kick"))
        .then(target("banish"))
        .then(target("ally"))
        .then(target("clear"))
        .then(lit("rank")
            .then(lit("list").does { ctx ->
                val c = Service.home(ctx.me())
                c.ranks.forEach { r -> ctx.row { value(r.name); muted("  ${r.id}  ${Permissions.holders(c, r.id)}") } }
            })
            .then(lit("set").then(player().then(arg("rank", StringArgumentType.word()).does { it.run("rank_assign", it.who(), it.text("rank")) }))))
        .then(lit("president").then(player().does { it.run("president", it.who()) }.then(lit("confirm").does { it.run("president", it.who(), "confirm") })))
}

private fun Node.countryLand(types: () -> Collection<String>): Node =
    then(lit("claim").requires { Perms.has(it, Perms.CLAIM) }
        .does { it.run("claim", s.defaultType, "0") }
        .then(word("type", types).does { it.run("claim", it.text("type"), "0") }
            .then(arg("radius", IntegerArgumentType.integer(0, 8)).does { it.run("claim", it.text("type"), IntegerArgumentType.getInteger(it, "radius").toString()) })))
    .then(lit("unclaim").requires { Perms.has(it, Perms.CLAIM) }.does { it.run("unclaim") })
    .then(lit("type").requires { Perms.has(it, Perms.CLAIM) }.then(word("type", types).does { it.run("type", it.text("type")) }))
    .then(lit("capital").requires { Perms.has(it, Perms.CLAIM) }.does { it.run("capital") })

private fun Node.countryTreasury(types: () -> Collection<String>): Node =
    then(lit("deposit").then(arg("amount", IntegerArgumentType.integer(1)).does { it.run("deposit", IntegerArgumentType.getInteger(it, "amount").toString()) }))
    .then(lit("withdraw").then(arg("amount", IntegerArgumentType.integer(1)).does { it.run("withdraw", IntegerArgumentType.getInteger(it, "amount").toString()) }))
    .then(lit("tax")
        .then(lit("residential").then(arg("amount", IntegerArgumentType.integer(0)).does { it.run("tax", IntegerArgumentType.getInteger(it, "amount").toString()) }))
        .then(lit("here").then(arg("amount", IntegerArgumentType.integer(-1)).does { it.run("plot_offer", "citizen", "on", IntegerArgumentType.getInteger(it, "amount").toString()) })))
    .then(lit("rule").requires { Perms.has(it, Perms.CLAIM) }.then(word("type", types).then(word("field") { Action.values().map { it.name.lowercase() } + "fire" }
        .then(word("value") { Access.values().map { it.name.lowercase() } + listOf("true", "false") }.does { it.run("rule", it.text("type"), it.text("field"), it.text("value")) }))))

private fun Node.countryJobs(): Node =
    then(lit("job").requires { Perms.has(it, Perms.JOBS) }
        .then(lit("list").does { ctx ->
            val me = ctx.me()
            val c = Service.home(me)
            if (!Permissions.holds(c, me.stringUUID, Perm.VIEW_JOBS)) fail(Phrase.of("kami_claims.error.permission"))
            ctx.info(Phrase.of("kami_claims.jobs.title", Phrase.value(c.name)))
            c.jobDefs.values.forEach { def ->
                ctx.row {
                    add(Words.v(def.name))
                    muted("  ")
                    add(Phrase.of("kami_claims.jobs.row", money(def.pay), num(def.quota), Words.days(def.period), if (def.scope == JobScope.TYPE) Words.type(def.scopeRef) else Words.v(def.scope.name.lowercase())), Theme.MUTED)
                }
            }
            c.members.forEach { (id, m) ->
                m.jobs.forEach { (job, state) ->
                    ctx.row {
                        text("• ${Names.of(ctx.source.server, id)} ")
                        add(Phrase.of("kami_claims.jobs.member", Words.v(c.jobDefs[job]?.name ?: job), num(state.progress)), Theme.MUTED)
                        val zone = Realm.claimsView(c.id).count { id in it.workers && it.type == c.jobDefs[job]?.scopeRef }
                        if (zone > 0) { muted(" · "); add(Phrase.of("kami_claims.jobs.zone", chunks(zone)), Theme.MUTED) }
                    }
                }
            }
        })
        .then(lit("add").then(player().then(arg("job", StringArgumentType.word()).does { it.run("job_add", it.who(), it.text("job")) })))
        .then(lit("remove").then(player().then(arg("job", StringArgumentType.word()).does { it.run("job_remove", it.who(), it.text("job")) })))
        .then(lit("assign").then(player().does { it.run("assign", it.who()) }))
        .then(lit("unassign").then(player().does { it.run("unassign", it.who()) })))

private fun Node.countryCmd(): Node {
    val countries = { Realm.data.countries.values.map { it.id } }
    val types = { s.types.keys }

    return requires { Perms.has(it, Perms.USE) }
        .countryCore(countries)
        .countryMembership(countries)
        .countryLand(types)
        .countryTreasury(types)
        .countryJobs()
        .then(provinceCmd(countries))
        .then(warCmd(countries))
}

private fun provinceCmd(countries: () -> Collection<String>): Node {
    fun offer(name: String, action: String) = lit(name).then(word("country", countries).then(word("mode") { listOf("percent", "flat") }
        .then(arg("amount", DoubleArgumentType.doubleArg(0.0)).does {
            it.run(action, it.text("country"), it.text("mode"), DoubleArgumentType.getDouble(it, "amount").toString())
        })))
    fun target(name: String, action: String = "province_$name") = lit(name).then(word("country", countries).does { it.run(action, it.text("country")) })

    return lit("province").requires { Perms.has(it, Perms.USE) }
        .then(offer("invite", "province_invite"))
        .then(target("request"))
        .then(lit("accept").then(word("country", countries).does { it.run("province_accept", it.text("country")) }.then(lit("confirm").does { it.run("province_accept", it.text("country"), "confirm") })))
        .then(offer("approve", "province_approve"))
        .then(target("deny"))
        .then(target("release"))
        .then(target("forgive"))
        .then(offer("tax", "province_tax"))
        .then(lit("give").then(word("country", countries).then(word("newparent", countries).does { it.run("province_give", it.text("country"), it.text("newparent")) }
            .then(lit("confirm").does { it.run("province_give", it.text("country"), it.text("newparent"), "confirm") }))))
        .then(lit("independence").does { it.run("province_independence") }
            .then(lit("withdraw").does { it.run("province_withdraw") })
            .then(lit("decline").then(word("country", countries).does { it.run("province_decline", it.text("country")) })))
        .then(lit("list").does { ctx ->
            val c = Service.home(ctx.me())
            c.parent?.let { Realm.country(it) }?.let { par ->
                ctx.info(Phrase.of("kami_claims.province.status", Phrase.value(par.name), Words.tribute(c.taxMode, c.taxAmount), num(c.provinceDebt), num(s.maxProvinceDebt)))
            }
            c.provinces.mapNotNull { Realm.country(it) }.takeIf { it.isNotEmpty() }?.let { list ->
                ctx.info(count("kami_claims.unit.province", list.size))
                list.forEach { pr ->
                    ctx.row {
                        value(pr.name)
                        muted("  ")
                        add(Phrase.of("kami_claims.province.row", Words.tribute(pr.taxMode, pr.taxAmount), num(pr.provinceDebt), num(s.maxProvinceDebt)), Theme.MUTED)
                        if (pr.independenceRequested) { muted("  "); add(Phrase.of("kami_claims.province.wants_independence"), Theme.WARN) }
                    }
                }
            }
            if (c.parent == null && c.provinces.isEmpty()) ctx.info(Phrase.of("kami_claims.province.none"))
        })
}

private fun plotCmd(): Node =
    lit("plot").requires { Perms.has(it, Perms.PLOT) }
        .then(lit("claim").does { it.run("plot_claim") })
        .then(lit("release").does { it.run("plot_release") })
        .then(lit("remove").does { it.run("plot_remove") }.then(lit("confirm").does { it.run("plot_remove", "confirm") }))
        .then(lit("offer")
            .then(lit("reset").does { it.run("plot_offer", "reset") })
            .then(word("group") { Claimant.values().map { it.name.lowercase() } }.then(word("state") { listOf("on", "off") }
                .then(arg("rent", IntegerArgumentType.integer(-1)).does { it.run("plot_offer", it.text("group"), it.text("state"), IntegerArgumentType.getInteger(it, "rent").toString()) }
                    .then(lit("default").does { it.run("plot_offer", it.text("group"), it.text("state"), IntegerArgumentType.getInteger(it, "rent").toString(), "default") })))))
        .then(lit("limit").then(word("target") { Claimant.values().filter { it != Claimant.CITIZEN }.map { it.name.lowercase() } }
            .then(arg("amount", IntegerArgumentType.integer(-1)).does { it.run("plot_limit", it.text("target"), IntegerArgumentType.getInteger(it, "amount").toString()) })))
        .then(lit("law").then(arg("debt", IntegerArgumentType.integer(0)).then(arg("moveout", IntegerArgumentType.integer(1, 30)).does {
            it.run("plot_law", IntegerArgumentType.getInteger(it, "debt").toString(), IntegerArgumentType.getInteger(it, "moveout").toString())
        })))
        .then(lit("trust").then(player().then(word("role") { Role.values().map { it.name.lowercase() } }.does { it.run("plot_trust", it.who(), it.text("role")) })))
        .then(lit("untrust").then(player().does { it.run("plot_untrust", it.who()) }))
        .then(lit("info").does { ctx ->
            val p = ctx.me()
            val cl = Realm.index[Service.here(p)]?.takeIf { it.owner != null } ?: fail(Phrase.of("kami_claims.error.plot_free"))
            val c = Realm.data.countries.getValue(cl.country)
            val me = p.stringUUID
            val staff = Permissions.holds(c, me, Perm.HOUSING)
            if (cl.owner != me && cl.roles[me] != Role.HOUSEHOLD && !staff) ctx.info(Phrase.of("kami_claims.plot.info.taken"))
            else {
                ctx.info(Phrase.of("kami_claims.plot.info", Phrase.value(Names.of(ctx.source.server, cl.owner!!)), Words.rate(Housing.rate(c, cl), 1), money(cl.rentDebt), money(c.rentDebtLimit)))
                cl.roles.forEach { (id, r) -> ctx.row { value(Names.of(ctx.source.server, id)); muted("  "); add(Phrase.of("kami_claims.role.${r.name.lowercase()}"), Theme.MUTED) } }
            }
        })

private fun adminTreasury(name: String, sign: Long) = lit(name).then(arg("amount", LongArgumentType.longArg(1, CountryAdmin.MAX_MONEY)).then(arg("reason", StringArgumentType.greedyString()).does { ctx ->
    val c = Realm.country(ctx.text("country")) ?: fail(Phrase.of("kami_claims.error.unknown_country"))
    val amount = LongArgumentType.getLong(ctx, "amount")
    val moved = CountryAdmin.money(c, sign > 0, amount, ctx.source.player?.stringUUID)
    Audit.record(ctx.source, CountryRequests.MONEY, c.id, ctx.text("reason"), mapOf("op" to name, "amount" to amount.toString(), "applied" to moved.toString()))
    ctx.ok(Phrase.of("kami_claims.admin.treasury.$name", money(moved), Phrase.value(c.name), money(c.treasury)), true)
}))

private fun adminCmd() = lit("admin").requires { Perms.has(it, Perms.ADMIN) }
    .then(lit("disband").then(word("country") { Realm.data.countries.keys }.does { ctx ->
        val c = Realm.country(ctx.text("country")) ?: fail(Phrase.of("kami_claims.error.unknown_country"))
        CountryAdmin.disband(c)
        Audit.record(ctx.source, "claims.disband", c.id, "")
        ctx.ok(Phrase.of("kami_claims.done.disbanded"), true)
    }))
    .then(lit("treasury").then(word("country") { Realm.data.countries.keys }.then(adminTreasury("give", 1)).then(adminTreasury("take", -1))))
    .then(lit("unclaim").does { ctx ->
        val cl = Realm.index[Service.here(ctx.me())] ?: fail(Phrase.of("kami_claims.error.chunk_free"))
        CountryAdmin.unclaim(Realm.data.countries.getValue(cl.country), cl.key.toString())
        Audit.record(ctx.source, "claims.unclaim", cl.country, "", mapOf("dim" to cl.dim, "x" to cl.x.toString(), "z" to cl.z.toString()))
        ctx.ok(Phrase.of("kami_claims.done.chunk_released"), true)
    })
    .then(lit("day").does { ctx ->
        Realm.data.day = Realm.data.day.coerceAtLeast(0)
        Upkeep.process(++Realm.data.day)
        Audit.record(ctx.source, "claims.day", "", "", mapOf("day" to Realm.data.day.toString()))
        ctx.ok(Phrase.of("kami_claims.admin.day", num(Realm.data.day)), true)
    })
    .then(adminResearchCmd())
    .then(adminXpCmd())
    .then(lit("save").does { ctx ->
        ctx.source.server.saveEverything(false, true, true)
        Audit.record(ctx.source, "claims.save", "", "")
        ctx.ok(Phrase.of("kami_claims.admin.saved"))
    })

object ClaimsCommands {
    fun register() = KamiCommands.module("claims", "Countries, land, plots and provinces") {
        countryCmd().then(researchCmd()).then(levelCmd()).then(plotCmd()).then(adminCmd())
    }
}

private fun warCmd(countries: () -> Collection<String>): Node {
    fun other(name: String, confirm: Boolean): Node {
        val action = "war_$name"
        fun <T : ArgumentBuilder<CommandSourceStack, T>> T.tail(args: (Ctx) -> List<String>): T = does { it.run(action, *args(it).toTypedArray()) }
            .apply { if (confirm) then(lit("confirm").does { it.run(action, *args(it).toTypedArray(), "confirm") }) }
        return lit(name).tail { emptyList() }.then(word("country", countries).tail { listOf(it.text("country")) })
    }
    return lit("war")
        .then(lit("declare").then(word("country", countries)
            .does { it.run("war_declare", it.text("country")) }
            .then(arg("stakes", StringArgumentType.greedyString()).does { it.run("war_declare", it.text("country"), *it.text("stakes").split(' ').filter(String::isNotBlank).toTypedArray()) })))
        .then(lit("offer").then(word("country", countries).then(arg("terms", StringArgumentType.greedyString()).does { it.run("war_offer", it.text("country"), *it.text("terms").split(' ').filter(String::isNotBlank).toTypedArray()) })))
        .then(lit("pick").then(word("country", countries).then(arg("area", StringArgumentType.greedyString()).does { it.run("war_pick", it.text("country"), *it.text("area").split(' ').filter(String::isNotBlank).toTypedArray()) })))
        .then(lit("safe").then(lit("add").does { it.run("war_safe", "add") }).then(lit("remove").does { it.run("war_safe", "remove") }).then(lit("list").does { it.run("war_safe", "list") }))
        .then(lit("repay").then(word("country", countries).then(arg("amount", LongArgumentType.longArg(1)).does { it.run("war_repay", it.text("country"), LongArgumentType.getLong(it, "amount").toString()) })))
        .then(other("accept", true))
        .then(other("decline", false))
        .then(other("fight", false))
        .then(other("agree", true))
        .then(other("withdraw", true))
        .then(other("capitulate", true))
        .then(other("join", true))
}
