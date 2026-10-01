package kami.claims.net

import kami.claims.Cap
import kami.claims.Config
import kami.claims.Country
import kami.claims.Realm
import kami.claims.research.*
import kami.claims.service.Service
import kami.claims.social.Perms
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

object ResearchSync {
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
            val home = Realm.of(p.stringUUID)
            val sendDefs = sentDefs[p.uuid] != defsRev
            if (sendDefs) {
                sentDefs[p.uuid] = defsRev
                PacketDistributor.sendToPlayer(p, defsPacket ?: ResearchDefsPacket(ResearchWire.encodeDefs(defs())).also { defsPacket = it })
            }
            if (sendDefs || home?.id in dirty || sentCountry[p.uuid] != home?.id.orEmpty()) {
                sentCountry[p.uuid] = home?.id.orEmpty()
                PacketDistributor.sendToPlayer(p, ResearchStatePacket(state(p, home)))
            }
        }
        dirty.clear()
    }

    fun defs() = DefsView(Research.defs.trees.values.map(::tree), IdExtras.encode(Gate.resolution), levels(Research.defs.levels))

    private fun levels(config: LevelsConfig) = (1..config.top).map { level ->
        LevelView(level, config.xpFor(level), unlocks(config.rewards[level].orEmpty()), config.requirements(level).map { it.describe().json() })
    }

    fun state(p: ServerPlayer, country: Country?): StateView {
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
            (1..levels.top).map { next -> levels.requirements(next).map { it.met(country) } },
            country.tokens.toMap(),
            mapOf(Tokens.RENAME to Config.s.renameCost, Tokens.CAPITAL_MOVE to Config.s.capitalMoveCost),
            country.counters.toMap(),
            Buffs.view(country),
            Loans.view(country)
        )
    }


    private fun tree(tree: Tree) = TreeView(
        tree.id, tree.title, tree.scope,
        tree.categories.map { CategoryView(it.id, it.title, it.icon, it.order) },
        tree.nodes.let { nodes -> val index = nodes.withIndex().associate { (i, n) -> n.key to i }; nodes.map { node(tree, it, index) } }
    )

    private fun node(tree: Tree, node: Node, index: Map<String, Int>): NodeView {
        val hard = node.requires.flatMap { it.hardRefs() }.toSet()
        return NodeView(
            tree.id, node.id, node.category, node.title, node.description, node.icon, node.level, node.cost, node.time.inWholeMilliseconds,
            node.xp ?: -1,
            node.requires.map { it.describe().json() },
            node.requires.flatMap { it.refs() }.distinct().mapNotNull { index[it] },
            node.tasks.map { TaskView(it.kind, subject(it), it.target) },
            unlocks(node.unlocks), node.x, node.y,
            node.requires.flatMap { it.refs() }.distinct().filter { it !in hard }.mapNotNull { index[it] },
            node.requires.flatMap { it.hiddenRefs() }.distinct().mapNotNull { index[it] }
        )
    }

    private fun subject(task: Task) = when (task) {
        is DepositTask -> task.selectors.joinToString(",")
        is MineTask -> task.selectors.joinToString(",")
        is PlaceTask -> task.block
        is KillTask -> task.entity
        is CraftTask -> task.output
        is SmeltTask -> task.output
        is StructureTask -> task.structure
        is ProcessTask -> task.output.ifBlank { task.recipeType }
        is VisitTask -> task.dimension
        is HoldTask -> task.condition.describe().json()
        is EventTask -> task.subject
        else -> ""
    }

    private fun unlocks(list: List<Unlock>): List<UnlockView> = list.flatMap { unlock ->
        when (unlock) {
            is GroupRef -> unlocks(Research.defs.groups[unlock.id]?.unlocks.orEmpty())
            is RecipeUnlock -> listOf(UnlockView("recipe", unlock.id))
            is RecipeTypeUnlock -> listOf(UnlockView("recipe_type", unlock.id))
            is RecipesUnlock -> listOf(
                unlock.output?.let { UnlockView("output", it) } ?: unlock.recipeType?.let { UnlockView("recipe_type", it) } ?: UnlockView("output", unlock.input.orEmpty())
            )
            is OutputUnlock -> listOf(UnlockView("output", unlock.id))
            is ModUnlock -> listOf(UnlockView("mod", unlock.id))
            is BlockUnlock -> listOf(UnlockView("block", unlock.id))
            is CapacityUnlock -> listOf(UnlockView("capacity", Capacity.serializer().descriptor.getElementName(unlock.key.ordinal), unlock.add))
            is MoneyReward -> listOf(UnlockView("money", "", 0, unlock.amount))
            is FeatureUnlock -> listOf(UnlockView("feature", unlock.id))
            is BuffUnlock -> listOf(UnlockView("buff", unlock.effect, unlock.amplifier, if (unlock.mode == BuffMode.PULSE) unlock.cooldownSeconds.toLong() else 0))
            is LoanUnlock -> listOf(UnlockView("loan", unlock.id, unlock.interestPct, unlock.amount))
            is LoanSlotsUnlock -> listOf(UnlockView("loan_slots", "", unlock.add))
            is BuffPointsUnlock -> listOf(UnlockView("buff_points", "", unlock.add))
            is TokenUnlock -> listOf(UnlockView("token", unlock.id, unlock.count))
        }
    }
}
