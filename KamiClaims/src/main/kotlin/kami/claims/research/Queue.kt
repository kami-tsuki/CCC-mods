package kami.claims.research

import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.Realm
import kami.claims.net.ResearchSync
import kami.claims.economy.Treasury
import kami.claims.now
import kami.claims.service.Fail
import kami.claims.service.Words
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.log.Log
import kami.libs.text.Phrase
import net.neoforged.neoforge.server.ServerLifecycleHooks
import kotlin.math.min

object Queue {
    var clock: () -> Long = ::now
    var onlineCount: (Country) -> Int = { country ->
        val players = ServerLifecycleHooks.getCurrentServer()?.playerList
        val online = players?.players?.mapTo(HashSet()) { it.stringUUID }.orEmpty()
        country.members.keys.count { it in online }
    }
    var refreshCrafting: (Country) -> Unit = { RecipeFilter.refreshCrafting(it) }

    private const val HOUR = 3_600_000L
    /** Longest real gap one update may count, so lag is caught up but a wall clock jump or a hang is not. */
    internal const val MAX_STEP_MS = 60_000L
    private const val XP_PER_NODE_LEVEL = 100L
    private val log = Log.of("research")
    private var lastTick = 0L
    private val playtime = HashMap<String, Long>()

    fun reset() { lastTick = 0L; playtime.clear() }
    private val settings get() = Research.defs.settings

    fun key(country: Country, id: String): String =
        if (id.contains(':')) id else Research.defs.treesFor(country).map { "${it.id}:$id" }.firstOrNull { it in Research.defs.nodes } ?: id

    fun node(country: Country, key: String): Node {
        if (!settings.enabled) throw Fail("kami_claims.research.error.disabled")
        return Research.defs.nodeFor(country, key) ?: throw Fail("kami_claims.research.error.unknown", Words.v(key))
    }

    fun entry(country: Country, key: String) =
        country.research.queue.firstOrNull { it.node == key } ?: throw Fail("kami_claims.research.error.not_queued", Words.v(key))

    fun waiting(country: Country) = country.research.queue.count { it.state != NodeState.RESEARCHING }

    fun researching(country: Country) = country.research.queue.count { it.state == NodeState.RESEARCHING }

    private fun tasksDone(entry: QueueEntry, node: Node) = node.tasks.indices.all { (entry.tasks[it] ?: 0) >= node.tasks[it].target }

    fun enqueue(country: Country, key: String, by: String?) {
        val node = node(country, key)
        if (key in country.research.done) throw Fail("kami_claims.research.error.done", node.label().asValue())
        if (country.research.queue.any { it.node == key }) throw Fail("kami_claims.research.error.queued", node.label().asValue())
        Penalty.hard(node).firstOrNull { !it.met(country) }?.let { throw Fail("kami_claims.research.error.condition", it.describe()) }
        if (waiting(country) >= Levels.capacity(country, Capacity.QUEUE_SLOTS)) throw Fail("kami_claims.research.error.queue_full")
        val penalty = Penalty(country).ms(node)
        val entry = QueueEntry(key, remainingMs = node.time.inWholeMilliseconds + penalty, penaltyMs = penalty, by = by, since = clock())
        country.research.queue += entry
        settle(country, entry, node)
        Realm.changed()
        ResearchSync.touch(country)
    }

    fun credit(country: Country, key: String, index: Int, amount: Long): Long {
        val entry = country.research.queue.firstOrNull { it.node == key && it.state == NodeState.QUEUED } ?: return 0
        val node = Research.defs.node(key) ?: return 0
        val task = node.tasks.getOrNull(index) ?: return 0
        val have = entry.tasks[index] ?: 0
        val added = min(amount, task.target - have)
        if (added <= 0) return 0
        entry.tasks[index] = have + added
        Realm.dirty = true
        if (have + added >= task.target) {
            Levels.add(country, "tasks", 1.0)
            settle(country, entry, node)
        }
        ResearchSync.refresh(country)
        return added
    }

    fun start(country: Country, key: String, actor: String?) {
        val entry = entry(country, key)
        val node = node(country, key)
        if (entry.state != NodeState.READY && entry.state != NodeState.PAUSED) throw Fail("kami_claims.research.error.not_ready", node.label().asValue())
        if (entry.state == NodeState.READY) Penalty.hard(node).firstOrNull { !it.met(country) }?.let { throw Fail("kami_claims.research.error.condition", it.describe()) }
        if (Loans.inDefault(country)) throw Fail("kami_claims.loans.error.default")
        if (researching(country) >= Levels.capacity(country, Capacity.RESEARCH_SLOTS)) throw Fail("kami_claims.research.reason.slots")
        if (!entry.paid) {
            if (country.treasury < node.cost) throw Fail("kami_claims.loans.reason.funds", Words.money(node.cost))
            Treasury.move(country, LedgerKind.RESEARCH, -node.cost, actor, key)
            entry.paid = true
        }
        entry.state = NodeState.RESEARCHING
        Realm.changed()
        ResearchSync.touch(country)
    }

    fun pause(country: Country, key: String) {
        val entry = entry(country, key)
        if (entry.state != NodeState.RESEARCHING) throw Fail("kami_claims.research.error.not_running")
        if (waiting(country) >= Levels.capacity(country, Capacity.QUEUE_SLOTS)) throw Fail("kami_claims.research.error.queue_full")
        entry.state = NodeState.PAUSED
        Realm.changed()
        ResearchSync.touch(country)
    }

    fun move(country: Country, key: String, index: Int) {
        val entry = entry(country, key)
        val queue = country.research.queue
        queue.remove(entry)
        queue.add(index.coerceIn(0, queue.size), entry)
        Realm.changed()
        ResearchSync.touch(country)
    }

    fun tick() {
        val time = clock()
        val interval = settings.tickSeconds * 1000L
        if (lastTick == 0L) lastTick = time
        if (time - lastTick < interval) return
        val elapsed = minOf(time - lastTick, maxOf(MAX_STEP_MS, 2 * interval))
        lastTick = time
        playtime.keys.retainAll(Realm.data.countries.keys)
        Realm.data.countries.values.filter { it.active }.forEach { tick(it, elapsed) }
    }

    internal fun tick(country: Country, elapsed: Long) {
        val online = onlineCount(country)
        if (online > 0) countPlaytime(country, online * elapsed)
        Levels.advance(country)
        val queue = country.research.queue
        if (queue.isEmpty() || !settings.enabled) return
        val penalty = Penalty(country)
        queue.forEach { entry ->
            val node = Research.defs.node(entry.node) ?: return@forEach
            val ms = penalty.ms(node)
            if (ms != entry.penaltyMs) {
                entry.remainingMs += ms - entry.penaltyMs
                entry.penaltyMs = ms
                Realm.dirty = true
            }
            settle(country, entry, node)
        }
        if (online == 0 && settings.onlineRequired) return
        val running = queue.filter { it.state == NodeState.RESEARCHING }
        if (running.isEmpty()) return
        val step = elapsed * 100 / (100 - Levels.researchSpeed(country, online))
        running.forEach { entry ->
            entry.remainingMs -= step
            if (entry.remainingMs <= 0) Research.defs.node(entry.node)?.let { complete(country, it) }
        }
        Realm.dirty = true
        ResearchSync.refresh(country)
    }

    fun dropOrphans() = Realm.data.countries.values.filter { it.active }.forEach { country ->
        val orphans = country.research.queue.filter { Research.defs.node(it.node) == null }
        if (orphans.isEmpty()) return@forEach
        country.research.queue.removeAll(orphans)
        log.warn("Dropped {} queued research entries of {} whose nodes no longer exist: {}", orphans.size, country.id, orphans.map { it.node })
        Mail.officers(country, Phrase.of("kami_claims.research.mail.dropped", Words.v(orphans.size)), Tone.BAD)
        Realm.changed()
        ResearchSync.touch(country)
    }

    fun complete(country: Country, node: Node) {
        markDone(country, node.key)
        Levels.grant(country, "research", node.xp ?: (XP_PER_NODE_LEVEL * maxOf(1, node.level)))
        if (settings.announce) Mail.broadcast(country, Phrase.of("kami_claims.research.mail.done", node.label().asValue()), Tone.OK)
        Realm.changed()
        ResearchSync.touch(country)
        refreshCrafting(country)
    }

    fun grant(country: Country, key: String) {
        val node = Research.defs.node(key) ?: throw Fail("kami_claims.research.error.unknown", Words.v(key))
        if (key in country.research.done) throw Fail("kami_claims.research.error.done", node.label().asValue())
        markDone(country, key)
        Realm.changed()
        ResearchSync.touch(country)
        refreshCrafting(country)
    }

    fun revoke(country: Country, key: String) {
        if (country.research.done.remove(key) == null) throw Fail("kami_claims.research.error.not_done", Words.v(key))
        Realm.changed()
        ResearchSync.touch(country)
    }

    fun finish(country: Country, key: String) {
        val node = Research.defs.node(key) ?: throw Fail("kami_claims.research.error.unknown", Words.v(key))
        if (key in country.research.done) throw Fail("kami_claims.research.error.done", node.label().asValue())
        complete(country, node)
    }

    fun reset(country: Country, key: String?) {
        if (key == null) {
            country.research.done.clear()
            country.research.queue.clear()
        } else {
            country.research.done.remove(key)
            country.research.queue.removeAll { it.node == key }
        }
        Realm.changed()
        ResearchSync.touch(country)
    }

    private fun settle(country: Country, entry: QueueEntry, node: Node) {
        if (entry.state != NodeState.QUEUED) return
        node.tasks.forEachIndexed { index, task ->
            if (task is HoldTask && (entry.tasks[index] ?: 0) < 1 && task.condition.met(country)) {
                entry.tasks[index] = 1
                Levels.add(country, "tasks", 1.0)
                Realm.dirty = true
                ResearchSync.touch(country)
            }
        }
        if (!tasksDone(entry, node)) return
        entry.state = NodeState.READY
        Mail.officers(country, Phrase.of("kami_claims.research.mail.ready", node.label().asValue()))
        Realm.changed()
        ResearchSync.touch(country)
    }

    private fun markDone(country: Country, key: String) {
        country.research.queue.removeAll { it.node == key }
        country.research.done[key] = clock()
    }

    private fun countPlaytime(country: Country, memberMs: Long) {
        val total = (playtime[country.id] ?: 0) + memberMs
        playtime[country.id] = total % HOUR
        if (total >= HOUR) Levels.add(country, "playtime", (total / HOUR).toDouble())
    }
}
