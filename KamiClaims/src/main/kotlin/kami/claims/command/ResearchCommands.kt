package kami.claims.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import kami.claims.Country
import kami.claims.Realm
import kami.claims.research.Capacity
import kami.claims.research.Gate
import kami.claims.research.Levels
import kami.claims.research.Node as ResearchNode
import kami.claims.research.NodeState
import kami.claims.research.Queue
import kami.claims.research.Research
import kami.claims.service.Service
import kami.claims.service.Words
import kami.claims.service.Words.num
import kami.claims.social.Perms
import kami.libs.chat.Theme
import kami.libs.command.*
import kami.libs.text.Phrase
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.resources.ResourceLocation

private fun nodeIds() = Research.defs.nodes.values.map { it.id }.distinct()

private fun Ctx.act(name: String, vararg args: String) = ok(Service.act(me(), name, args.toList()))

private fun Ctx.target(): Country = Realm.country(text("country")) ?: fail(Phrase.of("kami_claims.error.unknown_country"))

private fun status(country: Country, node: ResearchNode): Phrase {
    val state = when {
        node.key in country.research.done -> "done"
        else -> country.research.queue.firstOrNull { it.node == node.key }?.state?.name?.lowercase()
            ?: if (node.conditions().all { it.met(country) }) "available" else "locked"
    }
    return Phrase.of("kami_claims.research.status.$state")
}

private fun taskProgress(country: Country, node: ResearchNode): String {
    val entry = country.research.queue.firstOrNull { it.node == node.key } ?: return ""
    if (entry.state == NodeState.RESEARCHING || entry.state == NodeState.PAUSED) return "${entry.remainingMs / 60_000} min"
    return node.tasks.indices.joinToString(" ") { "${entry.tasks[it] ?: 0}/${node.tasks[it].target}" }
}

private fun Ctx.showQueue(country: Country) {
    if (country.research.queue.isEmpty()) return info(Phrase.of("kami_claims.research.queue.empty"))
    info(Phrase.of("kami_claims.research.queue.title", num(Queue.researching(country)), num(Levels.capacity(country, Capacity.RESEARCH_SLOTS)), num(Queue.waiting(country)), num(Levels.capacity(country, Capacity.QUEUE_SLOTS))))
    country.research.queue.forEachIndexed { index, entry ->
        val node = Research.defs.node(entry.node) ?: return@forEachIndexed
        row {
            muted("${index + 1}  ")
            value(node.id)
            muted("  ")
            add(status(country, node), Theme.MUTED)
            muted("  ${taskProgress(country, node)}")
        }
    }
}

private fun Ctx.showList(country: Country) {
    val nodes = Research.defs.treesFor(country).flatMap { it.nodes }
    if (nodes.isEmpty()) return info(Phrase.of("kami_claims.research.error.disabled"))
    val available = Research.available(country)
    info(Phrase.of("kami_claims.research.list.title", num(country.research.done.size), num(nodes.size), num(available.size)))
    available.forEach { node ->
        row {
            run(net.minecraft.network.chat.Component.literal(node.id), "/claims research enqueue ${node.id}", Phrase.of("kami_claims.research.list.tooltip").component())
            muted("  ")
            add(node.label(), Theme.MUTED)
            muted("  ")
            add(Phrase.money(node.cost), Theme.MUTED)
        }
    }
}

fun researchCmd(): Node {
    fun byNode(name: String) = lit(name).then(word("node", ::nodeIds).does { it.act("research_$name", it.text("node")) })
    return lit("research").requires { Perms.has(it, Perms.USE) }
        .does { ctx -> ctx.showList(Service.home(ctx.me())) }
        .then(lit("list").does { ctx -> ctx.showList(Service.home(ctx.me())) })
        .then(lit("queue").does { ctx -> ctx.showQueue(Service.home(ctx.me())) })
        .then(byNode("enqueue"))
        .then(byNode("start"))
        .then(byNode("pause"))
        .then(lit("move").then(word("node", ::nodeIds).then(arg("index", IntegerArgumentType.integer(1)).does {
            it.act("research_move", it.text("node"), (it.int("index") - 1).toString())
        })))
        .then(lit("deposit").then(word("node", ::nodeIds).then(arg("task", IntegerArgumentType.integer(1)).does {
            it.act("research_deposit", it.text("node"), (it.int("task") - 1).toString())
        })))
}

fun levelCmd(): Node = lit("level").requires { Perms.has(it, Perms.USE) }.does { ctx ->
    val c = Service.home(ctx.me())
    val levels = Research.defs.levels
    val level = Research.level(c)
    val next = if (level >= levels.top) Phrase.of("kami_claims.level.max") else Phrase.of("kami_claims.level.next", num(levels.xpFor(level + 1) - c.xp))
    ctx.info(Phrase.of("kami_claims.level.status", num(level), num(c.xp), next))
    Capacity.entries.forEach { key ->
        ctx.row { add(Phrase.of("kami_claims.level.capacity", Phrase.of("kami_claims.research.capacity.${key.name.lowercase()}"), num(Levels.used(c, key)), num(Levels.capacity(c, key))), Theme.MUTED) }
    }
}

private fun Ctx.why() {
    val profile = GameProfileArgument.getGameProfiles(this, "player").firstOrNull() ?: fail(Phrase.of("kami_claims.error.unknown_player"))
    val id = ResourceLocationArgument.getId(this, "recipe").toString()
    val country = Realm.of(profile.id.toString())
    val why = Gate.why(country, id)
    if (!why.gated) return info(Phrase.of("kami_claims.research.why.free", Words.v(id)))
    info(Phrase.of("kami_claims.research.why.gated", Words.v(id)))
    why.nodes.forEach { row { add(Phrase.of("kami_claims.research.why.node", Words.v(it)), Theme.MUTED) } }
    why.levels.forEach { row { add(Phrase.of("kami_claims.research.why.level", Words.v(it)), Theme.MUTED) } }
    if (why.nodes.isEmpty() && why.levels.isEmpty()) row { add(Phrase.of("kami_claims.research.why.none"), Theme.MUTED) }
    val who = Words.v(profile.name)
    when {
        country == null -> warn(Phrase.of("kami_claims.research.why.no_country", who))
        why.has -> ok(Phrase.of("kami_claims.research.why.has", who, Words.v(country.name)))
        else -> warn(Phrase.of("kami_claims.research.why.missing", who, Words.v(country.name)))
    }
}

fun adminResearchCmd(): Node {
    fun step(name: String, action: (Country, String) -> Unit) = lit(name).then(word("country") { Realm.data.countries.keys }.then(word("node", ::nodeIds).does { ctx ->
        val target = ctx.target()
        action(target, Queue.key(target, ctx.text("node")))
        ctx.ok(Phrase.of("kami_claims.research.admin.$name", Words.v(target.name)), true)
    }))
    return lit("research").requires { Perms.has(it, Perms.RESEARCH_ADMIN) }
        .then(step("grant", Queue::grant))
        .then(step("revoke", Queue::revoke))
        .then(step("finish", Queue::finish))
        .then(lit("why").then(arg("player", GameProfileArgument.gameProfile()).then(arg("recipe", ResourceLocationArgument.id()).suggests { _, b -> SharedSuggestionProvider.suggestResource(Gate.resolution.gated.let { it.recipes + it.blocks }.map(ResourceLocation::parse), b) }.does { it.why() })))
        .then(lit("reset").then(word("country") { Realm.data.countries.keys }
            .does { ctx -> Queue.reset(ctx.target(), null); ctx.ok(Phrase.of("kami_claims.research.admin.reset", Words.v(ctx.target().name)), true) }
            .then(word("node", ::nodeIds).does { ctx -> Queue.reset(ctx.target(), Queue.key(ctx.target(), ctx.text("node"))); ctx.ok(Phrase.of("kami_claims.research.admin.reset", Words.v(ctx.target().name)), true) })))
}

fun adminXpCmd(): Node = lit("xp").requires { Perms.has(it, Perms.RESEARCH_ADMIN) }
    .then(lit("add").then(word("country") { Realm.data.countries.keys }.then(arg("amount", LongArgumentType.longArg(1)).does { ctx ->
        val c = ctx.target()
        Levels.award(c, LongArgumentType.getLong(ctx, "amount"))
        ctx.ok(Phrase.of("kami_claims.level.admin.added", Words.v(c.name), num(c.xp)), true)
    })))
