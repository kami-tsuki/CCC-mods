package kami.claims.net

import kami.libs.claims.Boost as BoostLimits
import kami.claims.Cap
import kami.claims.Config
import kami.claims.Country
import kami.claims.Realm
import kami.claims.research.*
import kami.claims.research.Limits.MAX_COUNTERS
import kami.claims.research.Limits.MAX_LINKS
import kami.claims.research.Limits.MAX_UNLOCKS
import kami.claims.service.Oversight
import kami.claims.service.Service
import kami.claims.social.Perms
import kami.libs.log.Log
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

object ResearchSync {
    private val log = Log.of("research")
    private class Sent(val view: String, val own: String)

    private class Failed(val defs: Int, val key: String, val at: Long)

    private const val REFRESH_TICKS = 10
    private const val RETRY_MS = 60_000L
    private const val MAX_DEFS_BYTES = 900_000

    private val dirty = HashSet<String>()
    private val pending = HashSet<String>()
    private val sentCountry = HashMap<UUID, Sent>()
    private val sentDefs = HashMap<UUID, Int>()
    private val failing = HashMap<UUID, Failed>()
    private val ids = HashMap<UUID, String>()
    private val menus = HashSet<String?>()
    private var defsRev = 0
    private var oversizeLogged = -1
    private var ticks = 0
    private var defsPacket: ResearchDefsPacket? = null

    fun refresh(country: Country) {
        pending += country.id
    }

    fun touch(country: Country) {
        Gate.invalidate(country)
        Buffs.prune(country)
        dirty += country.id
        menus += country.id
    }

    fun menus(country: Country?) {
        menus += country?.id
    }

    fun defsChanged() {
        defsRev++
        defsPacket = null
    }

    fun reset() { dirty.clear(); pending.clear(); sentCountry.clear(); sentDefs.clear(); failing.clear(); menus.clear() }

    fun forget(p: ServerPlayer) {
        ids.remove(p.uuid)
        sentCountry.remove(p.uuid)
        sentDefs.remove(p.uuid)
        failing.remove(p.uuid)
    }

    fun flush(server: MinecraftServer) {
        if (++ticks % REFRESH_TICKS == 0 && pending.isNotEmpty()) {
            dirty += pending
            pending.clear()
        }
        val built = HashMap<String, StateView>()
        for (p in server.playerList.players) {
            if (!p.connection.hasChannel(ResearchStatePacket.TYPE)) continue
            val own = Realm.of(ids.getOrPut(p.uuid) { p.stringUUID })
            val viewed = Sync.viewed(p)
            if (failing[p.uuid]?.let { it.defs == defsRev && it.key == viewKey(own, viewed) && System.currentTimeMillis() - it.at < RETRY_MS } == true && dirty.isEmpty()) continue
            try {
                send(p, own, viewed, built)
                failing.remove(p.uuid)
            } catch (e: Exception) {
                sentCountry.remove(p.uuid)
                if (failing.put(p.uuid, Failed(defsRev, viewKey(own, viewed), System.currentTimeMillis())) == null) log.error("Research sync failed for {}", p.gameProfile.name, e)
            }
        }
        dirty.clear()
        if (menus.isEmpty()) return
        if (null in menus) RecipeFilter.refreshOpen(null) else menus.filterNotNull().forEach { id -> Realm.country(id)?.let(RecipeFilter::refreshOpen) }
        menus.clear()
    }

    private fun viewKey(own: Country?, viewed: Country?) = "${viewed?.id}/${own?.id}"

    private fun send(p: ServerPlayer, own: Country?, viewed: Country?, built: HashMap<String, StateView>) {
        val shown = viewed ?: own
        val sendDefs = sentDefs[p.uuid] != defsRev
        if (sendDefs) {
            pushDefs(p)
            sentDefs[p.uuid] = defsRev
        }
        val sent = sentCountry[p.uuid]
        val view = shown?.id.orEmpty()
        val ownId = own?.id.orEmpty()
        val stale = sent == null || sent.view != view || sent.own != ownId || (shown != null && shown.id in dirty) || (own != null && own.id in dirty)
        if (!sendDefs && !stale) return
        val base = shown?.let { c -> built.getOrPut(c.id) { build(c) } } ?: StateView.NONE
        val ownBase = own?.let { c -> built.getOrPut(c.id) { build(c) } }
        val state = base.copy(
            canManage = shown != null && Perms.has(p, Perms.capNode(Cap.RESEARCH)) && Oversight.may(shown, p, Cap.RESEARCH),
            ownCountry = ownBase?.country.orEmpty(), ownLevel = ownBase?.level ?: 0, ownDone = ownBase?.done.orEmpty()
        )
        PacketDistributor.sendToPlayer(p, ResearchStatePacket(state))
        sentCountry[p.uuid] = Sent(view, ownId)
    }

    private fun pushDefs(p: ServerPlayer) {
        val packet = defsPacket ?: ResearchDefsPacket(ResearchWire.encodeDefs(defs())).also { defsPacket = it }
        if (packet.data.size <= MAX_DEFS_BYTES) return PacketDistributor.sendToPlayer(p, packet)
        if (oversizeLogged != defsRev) {
            oversizeLogged = defsRev
            log.error("Research defs are {} bytes, over the {} byte packet budget; clients get no research data", packet.data.size, MAX_DEFS_BYTES)
        }
    }

    internal fun defs() = DefsView(Research.defs.trees.values.map(::tree), IdExtras.encode(Gate.resolution), levels(Research.defs.levels))

    private fun levels(config: LevelsConfig) = (1..config.top).map { level ->
        LevelView(level, config.xpFor(level), unlocks(config.rewards[level].orEmpty()), config.requirements(level).take(MAX_LINKS).map { it.describe().json() })
    }

    internal fun build(country: Country): StateView {
        val levels = Research.defs.levels
        val level = Research.level(country)
        val trees = Research.defs.treesFor(country)
        val open = trees.flatMap { it.nodes }.filter { it.key !in country.research.done }
        return StateView(
            country.id, level, country.xp, levels.xpFor(level), if (level >= levels.top) 0 else levels.xpFor(level + 1), country.treasury,
            false,
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
            Loans.view(country),
            (1..levels.top).map { next -> levels.requirements(next).take(MAX_LINKS).map { Goals.progress(country, it) } },
            Penalty(country).let { penalty -> open.associate { it.key to penalty.ms(it) }.filterValues { it > 0 } },
            Levels.researchSpeed(country, Queue.onlineCount(country)),
            BoostLimits.KINDS.mapNotNull { kind -> country.boosts[kind]?.takeIf { it.isNotEmpty() }?.let { BoostView(kind, it.first().pct, it.first().leftMs, it.size - 1, it.drop(1).sumOf { b -> b.leftMs }) } },
            Queue.onlineCount(country) > 0
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
        val hidden = node.requires.flatMap { it.hiddenRefs() }.distinct()
        return NodeView(
            tree.id, node.id, node.category, node.title, node.description, node.icon, node.level, node.cost, node.time.inWholeMilliseconds,
            node.xp ?: -1,
            node.requires.map { it.describe().json() },
            refs.mapNotNull { index[it] },
            node.tasks.map { TaskView(it.kind, it.subject, it.target) },
            unlocks(node.unlocks), node.x, node.y,
            refs.filter { it !in hard }.mapNotNull { index[it] },
            hidden.mapNotNull { index[it] },
            refs.filter { it !in index }.take(MAX_LINKS),
            hidden.filter { it !in index }.take(MAX_LINKS)
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
            is SafeChunksUnlock -> listOf(UnlockView("safe_chunks", "", count = unlock.add))
            is TokenUnlock -> listOf(UnlockView("token", unlock.id, count = unlock.count))
            is ResearchSpeedUnlock -> listOf(UnlockView("research_speed", "", count = unlock.perCitizen, amount = unlock.cap.toLong()))
        }
    }
}
