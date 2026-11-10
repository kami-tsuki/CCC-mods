package kami.claims.research

import kami.libs.util.toUuidOrNull
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
import kami.libs.skills.Skills
import kami.libs.stats.Stats
import kami.libs.text.Phrase
import kami.libs.util.Time.HOUR_MS
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID
import kotlin.math.min

object Queue {
    var clock: () -> Long = ::now
    var onlineCount: (Country) -> Int = { country ->
        val players = ServerLifecycleHooks.getCurrentServer()?.playerList
        if (players == null) 0 else country.members.keys.count { id -> id.toUuidOrNull()?.let(players::getPlayer) != null }
    }

    private fun skill(country: Country, key: String) = Skills.bestOnline(country.members.keys.mapNotNull { it.toUuidOrNull() }, key)

    internal const val MAX_STEP_MS = 60_000L
    private const val XP_PER_NODE_LEVEL = 100L
    private val log = Log.of("research")
    private var lastTick = 0L
    private val playtime = HashMap<String, Long>()

    private val settings get() = Research.defs.settings

    fun reset() { lastTick = 0L; playtime.clear() }

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

    internal fun tasksDone(entry: QueueEntry, node: Node) = node.tasks.indices.all { (entry.tasks[it] ?: 0) >= node.tasks[it].target }

    fun enqueue(country: Country, key: String, by: String?) {
        val node = node(country, key)
        if (key in country.research.done) throw Fail("kami_claims.research.error.done", node.label().asValue())
        if (country.research.queue.any { it.node == key }) throw Fail("kami_claims.research.error.queued", node.label().asValue())
        Penalty.hard(node).firstOrNull { !it.met(country) }?.let { throw Fail("kami_claims.research.error.condition", it.describe()) }
        if (waiting(country) >= Levels.capacity(country, Capacity.QUEUE_SLOTS)) throw Fail("kami_claims.research.error.queue_full")
        val penalty = Penalty(country).ms(node)
        val entry = QueueEntry(key, remainingMs = node.time.inWholeMilliseconds + penalty, penaltyMs = penalty, by = by, since = clock(), queuedAt = clock(), sigs = ResearchMigration.sigs(node), targets = ResearchMigration.targets(node))
        country.research.queue += entry
        settle(country, entry, node)
        Realm.changed(false)
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
        if (!begin(country, entry, node, actor)) throw Fail("kami_claims.loans.reason.funds", Words.money(node.cost))
    }

    fun remove(country: Country, key: String) {
        val entry = entry(country, key)
        if (entry.paid || entry.state == NodeState.RESEARCHING || entry.state == NodeState.PAUSED) throw Fail("kami_claims.research.error.started")
        country.research.queue.remove(entry)
        Realm.changed(false)
        ResearchSync.touch(country)
    }

    internal fun autostart(country: Country) {
        if (!settings.enabled || Loans.inDefault(country)) return
        country.research.queue.filter { it.state == NodeState.READY }.forEach { entry ->
            if (researching(country) >= Levels.capacity(country, Capacity.RESEARCH_SLOTS)) return
            val node = Research.defs.node(entry.node) ?: return@forEach
            if (country.treasury < node.cost || Penalty.hard(node).any { !it.met(country) }) return@forEach
            if (begin(country, entry, node, null)) Mail.officers(country, Phrase.of("kami_claims.research.mail.autostart", node.label().asValue(), Words.money(node.cost)))
        }
    }

    private fun begin(country: Country, entry: QueueEntry, node: Node, actor: String?): Boolean {
        if (!entry.paid) {
            val cost = (node.cost * (1.0 - skill(country, Skills.RESEARCH_COST))).toLong()
            if (cost > 0 && (country.treasury < cost || Treasury.bank(country, LedgerKind.RESEARCH, -cost, actor, entry.node) == 0L)) return false
            entry.paid = true
            entry.paidCost = cost
            Stats.event("research.start", "c:${country.id}", entry.node)
            Stats.add("research.cost", "c:${country.id}", entry.node, cost)
        }
        if (entry.startedAt == 0L) entry.startedAt = clock()
        entry.state = NodeState.RESEARCHING
        Realm.changed(false)
        ResearchSync.touch(country)
        return true
    }

    fun pause(country: Country, key: String) {
        val entry = entry(country, key)
        if (entry.state != NodeState.RESEARCHING) throw Fail("kami_claims.research.error.not_running")
        if (waiting(country) >= Levels.capacity(country, Capacity.QUEUE_SLOTS)) throw Fail("kami_claims.research.error.queue_full")
        entry.state = NodeState.PAUSED
        Realm.changed(false)
        ResearchSync.touch(country)
    }

    fun move(country: Country, key: String, index: Int) {
        val entry = entry(country, key)
        val queue = country.research.queue
        queue.remove(entry)
        queue.add(index.coerceIn(0, queue.size), entry)
        Realm.changed(false)
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
        val boost = if (online > 0) Boosts.pct(country, "research") else 0
        if (online > 0) {
            countPlaytime(country, online * elapsed)
            Boosts.tick(country, elapsed)
        }
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
            }
            settle(country, entry, node)
        }
        autostart(country)
        if (online == 0 && settings.onlineRequired) return
        val running = queue.filter { it.state == NodeState.RESEARCHING }
        if (running.isEmpty()) return
        val step = (elapsed * (100 + boost) / (100 - Levels.researchSpeed(country, online).coerceAtMost(99)) / (1.0 - skill(country, Skills.RESEARCH_TIME))).toLong()
        running.forEach { entry ->
            entry.remainingMs -= step
            if (entry.remainingMs <= 0) Research.defs.node(entry.node)?.let { complete(country, it) }
        }
        autostart(country)
    }

    fun dropOrphans() = Realm.data.countries.values.filter { it.active }.forEach { country ->
        val orphans = country.research.queue.filter { Research.defs.node(it.node) == null }
        if (orphans.isEmpty()) return@forEach
        country.research.queue.removeAll(orphans)
        country.research.parked += orphans
        log.warn("Parked {} queued research entries of {} whose nodes no longer exist: {}", orphans.size, country.id, orphans.map { it.node })
        Mail.officers(country, Phrase.of("kami_claims.research.mail.parked", Words.v(orphans.size)), Tone.BAD)
        Realm.changed(false)
        ResearchSync.touch(country)
    }

    fun rescope(vararg countries: Country?) = countries.filterNotNull().forEach { country ->
        val orphans = if (settings.enabled) country.research.queue.filter { Research.defs.nodeFor(country, it.node) == null } else emptyList()
        val refunded = orphans.filter { ResearchMigration.refunded(country, it, Research.defs.node(it.node)) }
        if (refunded.isNotEmpty()) {
            country.research.queue.removeAll(refunded)
            Realm.changed(false)
        }
        ResearchSync.touch(country)
    }

    fun complete(country: Country, node: Node) {
        val entry = country.research.queue.firstOrNull { it.node == node.key }
        markDone(country, node.key)
        val subject = "c:${country.id}"
        Stats.event("research.done", subject, node.key)
        Stats.add("research.node", subject, node.key)
        if (entry != null && entry.startedAt > 0) {
            Stats.event("research.time", subject, node.key, clock() - entry.startedAt)
            if (entry.queuedAt > 0) Stats.event("research.wait", subject, node.key, entry.startedAt - entry.queuedAt)
        }
        Levels.add(country, "research", (node.xp ?: (XP_PER_NODE_LEVEL * maxOf(1, node.level))).toDouble())
        if (settings.announce) Mail.broadcast(country, Phrase.of("kami_claims.research.mail.done", node.label().asValue()), Tone.OK)
        Realm.changed(false)
        ResearchSync.touch(country)
    }

    fun grant(country: Country, key: String) {
        val node = Research.defs.node(key) ?: throw Fail("kami_claims.research.error.unknown", Words.v(key))
        if (key in country.research.done) throw Fail("kami_claims.research.error.done", node.label().asValue())
        markDone(country, key)
        Stats.add("research.forced", "c:${country.id}", key)
        Realm.changed(false)
        ResearchSync.touch(country)
    }

    fun revoke(country: Country, key: String) {
        if (country.research.done.remove(key) == null) throw Fail("kami_claims.research.error.not_done", Words.v(key))
        Realm.changed(false)
        ResearchSync.touch(country)
    }

    fun finish(country: Country, key: String) {
        val node = Research.defs.node(key) ?: throw Fail("kami_claims.research.error.unknown", Words.v(key))
        if (key in country.research.done) throw Fail("kami_claims.research.error.done", node.label().asValue())
        complete(country, node)
    }

    fun reset(country: Country, key: String?) {
        wipe(country, key?.let { listOf(it) })
    }

    fun resetAll(country: Country, scope: Sweep): Int = wipe(country, if (scope.whole) null else scope.keys(country))

    fun finishAll(country: Country, scope: Sweep): Int {
        val todo = scope.keys(country).filter { it !in country.research.done }
        if (todo.isEmpty()) return 0
        todo.forEach { markDone(country, it) }
        return commit(country, todo.size)
    }

    private fun wipe(country: Country, keys: Collection<String>?): Int {
        val state = country.research
        val scope = keys?.toSet()
        val hit = (state.done.keys + state.queue.map { it.node }).filter { scope == null || it in scope }.toSet()
        if (hit.isEmpty()) return 0
        state.done.keys.removeAll(hit)
        state.queue.removeAll { it.node in hit }
        return commit(country, hit.size)
    }

    private fun commit(country: Country, changed: Int): Int {
        Realm.changed(false)
        ResearchSync.touch(country)
        return changed
    }

    internal fun settle(country: Country, entry: QueueEntry, node: Node, notify: Boolean = true) {
        if (entry.state != NodeState.QUEUED) return
        node.tasks.forEachIndexed { index, task ->
            if (task is HoldTask && (entry.tasks[index] ?: 0) < 1 && task.condition.met(country)) {
                entry.tasks[index] = 1
                Levels.add(country, "tasks", 1.0)
                ResearchSync.touch(country)
            }
        }
        if (!tasksDone(entry, node)) return
        entry.state = NodeState.READY
        if (notify) Mail.officers(country, Phrase.of("kami_claims.research.mail.ready", node.label().asValue()))
        Realm.changed(false)
        ResearchSync.touch(country)
    }

    internal fun markDone(country: Country, key: String) {
        country.research.queue.removeAll { it.node == key }
        country.research.done[key] = clock()
    }

    private fun countPlaytime(country: Country, memberMs: Long) {
        val total = (playtime[country.id] ?: 0) + memberMs
        playtime[country.id] = total % HOUR_MS
        if (total >= HOUR_MS) Levels.add(country, "playtime", (total / HOUR_MS).toDouble())
    }
}
