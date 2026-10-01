package kami.claims.net

import kami.claims.Cap
import kami.claims.Config
import kami.claims.Country
import kami.claims.Realm
import kami.claims.research.*
import kami.claims.research.Limits.MAX_COUNTERS
import kami.claims.research.Limits.MAX_LINKS
import kami.claims.research.Limits.MAX_UNLOCKS
import kami.claims.service.Service
import kami.claims.social.Perms
import kami.libs.log.Log
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

object ResearchSync {
    private val log = Log.of("research")
    private val dirty = HashSet<String>()
    private val sentCountry = HashMap<UUID, String>()
    private val sentDefs = HashMap<UUID, Int>()
    private var defsRev = 0
    private var defsPacket: ResearchDefsPacket? = null

    fun refresh(country: Country) {
        dirty += country.id
    }

    fun touch(country: Country) {
        Gate.invalidate(country)
        Buffs.prune(country)
        dirty += country.id
    }

    fun defsChanged() {
        defsRev++
        defsPacket = null
    }

    fun forget(p: ServerPlayer) {
        sentCountry.remove(p.uuid)
        sentDefs.remove(p.uuid)
    }

    fun flush(server: MinecraftServer) {
        server.playerList.players.filter { it.connection.hasChannel(ResearchStatePacket.TYPE) }.forEach { p ->
            runCatching { send(p) }.onFailure { log.error("Research sync failed for {}", p.gameProfile.name, it) }
        }
        dirty.clear()
    }

    private fun send(p: ServerPlayer) {
        val home = Realm.of(p.stringUUID)
        val sendDefs = sentDefs[p.uuid] != defsRev
        if (sendDefs) {
            PacketDistributor.sendToPlayer(p, defsPacket ?: ResearchDefsPacket(ResearchWire.encodeDefs(defs())).also { defsPacket = it })
            sentDefs[p.uuid] = defsRev
        }
        if (sendDefs || home?.id in dirty || sentCountry[p.uuid] != home?.id.orEmpty()) {
            PacketDistributor.sendToPlayer(p, ResearchStatePacket(state(p, home)))
            sentCountry[p.uuid] = home?.id.orEmpty()
        }
    }

    internal fun defs() = DefsView(Research.defs.trees.values.map(::tree), IdExtras.encode(Gate.resolution), levels(Research.defs.levels))

    private fun levels(config: LevelsConfig) = (1..config.top).map { level ->
        LevelView(level, config.xpFor(level), unlocks(config.rewards[level].orEmpty()), config.requirements(level).take(MAX_LINKS).map { it.describe().json() })
    }

    internal fun state(p: ServerPlayer, country: Country?): StateView {
        if (country == null) return StateView.NONE
        val levels = Research.defs.levels
        val level = Research.level(country)
        val trees = Research.defs.treesFor(country)
        val open = trees.flatMap { it.nodes }.filter { it.key !in country.research.done }
        return StateView(
            country.id, level, country.xp, levels.xpFor(level), if (level >= levels.top) 0 else levels.xpFor(level + 1), country.treasury,
            Perms.has(p, Perms.capNode(Cap.RESEARCH)) && Service.rankOf(country, p) >= Config.s.min(Cap.RESEARCH),
            country.research.done.keys.toList(),
            country.research.queue.map { entry ->
                val tasks = Research.defs.node(entry.node)?.tasks.orEmpty()
                QueueView(entry.node, entry.state, entry.remainingMs, entry.paid, tasks.indices.map { entry.tasks[it] ?: 0 })
            },
            Research.available(country).map { it.key },
            Capacity.entries.map { Levels.used(country, it) },
            Capacity.entries.map { Levels.capacity(country, it) },
            trees.map { it.id },
            open.associate { node -> node.key to listOf(node.level <= level) + node.requires.map { it.met(country) } },
            (1..levels.top).map { next -> levels.requirements(next).take(MAX_LINKS).map { it.met(country) } },
            country.tokens.toMap(),
            mapOf(Tokens.RENAME to Config.s.renameCost, Tokens.CAPITAL_MOVE to Config.s.capitalMoveCost),
            country.counters.filterKeys { it in Research.defs.counterKeys }.entries.sortedBy { it.key }.take(MAX_COUNTERS).associate { it.toPair() },
            Buffs.view(country),
            Loans.view(country)
        )
    }

    private fun tree(tree: Tree): TreeView {
        val index = tree.nodes.withIndex().associate { (i, n) -> n.key to i }
        return TreeView(
            tree.id, tree.title, tree.scope,
            tree.categories.map { CategoryView(it.id, it.title, it.icon, it.order) },
            tree.nodes.map { node(tree, it, index) }
        )
    }

    private fun node(tree: Tree, node: Node, index: Map<String, Int>): NodeView {
        val refs = node.requires.flatMap { it.refs() }.distinct()
        val hard = node.requires.flatMap { it.hardRefs() }.toSet()
        return NodeView(
            tree.id, node.id, node.category, node.title, node.description, node.icon, node.level, node.cost, node.time.inWholeMilliseconds,
            node.xp ?: -1,
            node.requires.map { it.describe().json() },
            refs.mapNotNull { index[it] },
            node.tasks.map { TaskView(it.kind, it.subject, it.target) },
            unlocks(node.unlocks), node.x, node.y,
            refs.filter { it !in hard }.mapNotNull { index[it] },
            node.requires.flatMap { it.hiddenRefs() }.distinct().mapNotNull { index[it] }
        )
    }

    private fun unlocks(list: List<Unlock>): List<UnlockView> = expand(list).take(MAX_UNLOCKS)

    private fun expand(list: List<Unlock>): List<UnlockView> = list.flatMap { unlock ->
        when (unlock) {
            is GroupRef -> expand(Research.defs.groups[unlock.id]?.unlocks.orEmpty())
            is RecipeUnlock -> listOf(UnlockView("recipe", unlock.id))
            is RecipeTypeUnlock -> listOf(UnlockView("recipe_type", unlock.id))
            is RecipesUnlock -> listOf(
                unlock.output?.let { UnlockView("output", it) } ?: unlock.recipeType?.let { UnlockView("recipe_type", it) } ?: UnlockView("output", unlock.input.orEmpty())
            )
            is OutputUnlock -> listOf(UnlockView("output", unlock.id))
            is ModUnlock -> listOf(UnlockView("mod", unlock.id))
            is BlockUnlock -> listOf(UnlockView("block", unlock.id))
            is CapacityUnlock -> listOf(UnlockView("capacity", unlock.key.id, count = unlock.add))
            is MoneyReward -> listOf(UnlockView("money", "", amount = unlock.amount))
            is FeatureUnlock -> listOf(UnlockView("feature", unlock.id))
            is BuffUnlock -> listOf(UnlockView("buff", unlock.effect, amplifier = unlock.amplifier, cooldownSeconds = if (unlock.mode == BuffMode.PULSE) unlock.cooldownSeconds else 0))
            is LoanUnlock -> listOf(UnlockView("loan", unlock.id, interestPct = unlock.interestPct, amount = unlock.amount))
            is LoanSlotsUnlock -> listOf(UnlockView("loan_slots", "", count = unlock.add))
            is BuffPointsUnlock -> listOf(UnlockView("buff_points", "", count = unlock.add))
            is TokenUnlock -> listOf(UnlockView("token", unlock.id, count = unlock.count))
        }
    }
}
