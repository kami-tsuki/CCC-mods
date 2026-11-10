package kami.claims.research

import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.Realm
import kami.claims.economy.Treasury
import kami.claims.net.ResearchSync
import kami.claims.service.Words
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.config.Configs
import kami.libs.log.Log
import kami.libs.stats.Stats
import kami.libs.text.Phrase
import java.time.Instant
import kotlin.math.min

object ResearchMigration {
    class Refunded(val total: Long, val kept: Int)

    class Change(val country: String, val rule: String, val key: String, val text: String)

    private const val ALIAS = "alias:"
    private const val UNSTAMPED = "-"
    private val log = Log.of("research")

    var last: List<Change> = emptyList()
        private set

    private var simulating = false

    internal var legacy: LegacyData? = null

    private val bundled: LegacyData by lazy {
        runCatching { Defaults.legacy }.getOrElse {
            log.warn("legacy_sigs.json is missing or corrupt ({}), entries without signatures cannot be remapped or refunded exactly", it.message)
            LegacyData()
        }
    }

    private fun legacyData() = legacy ?: bundled

    fun sigs(node: Node) = node.tasks.map { it.signature }.toMutableList()

    fun targets(node: Node) = node.tasks.map { it.target }.toMutableList()

    private fun started(entry: QueueEntry) = entry.paid || entry.state == NodeState.RESEARCHING || entry.state == NodeState.PAUSED

    fun ruleId(node: Node, rule: Grandfather) = "gf:${node.key}<${rule.from.joinToString(",")}@${rule.before}"

    private class Rule(val node: Node, val id: String, val from: List<String>, val cutoff: Long)

    private class Plan(val replaces: Map<String, String>, val rules: List<Rule>)

    private fun plan(): Plan {
        val nodes = Research.defs.nodes
        val replaces = HashMap<String, String>()
        nodes.values.forEach { node -> node.replaces.filter { it !in nodes && it != node.key }.forEach { replaces[it] = node.key } }
        val rules = nodes.values.flatMap { node ->
            node.grandfather.mapNotNull { rule ->
                runCatching { Instant.parse(rule.before).toEpochMilli() }.getOrNull()?.let { Rule(node, ruleId(node, rule), rule.from, it) }
            }
        }
        return Plan(replaces, rules)
    }

    fun run(): List<Change> {
        val mode = Research.defs.settings.migrations
        if (mode == MigrationMode.OFF) return emptyList<Change>().also { last = it }
        val dry = mode == MigrationMode.DRY_RUN
        val plan = plan()
        val all = Realm.data.countries.values.filter { it.active }.flatMap { country ->
            val changes = runCatching { if (dry) simulate(country, plan) else run(country, plan) }.getOrElse { log.error("research migration failed for {}", country.id, it); emptyList() }
            if (changes.isNotEmpty()) {
                changes.forEach { log.info("{}{}: {}", if (dry) "[dry run] " else "", country.id, it.text) }
                if (!dry) {
                    changes.forEach { Stats.event("research.migrated", "c:${country.id}", it.key) }
                    Mail.officers(country, Phrase.of("kami_claims.research.mail.migrated", Words.v(changes.size)))
                    Realm.changed(false)
                    ResearchSync.touch(country)
                }
            }
            changes
        }
        if (!dry) Queue.dropOrphans()
        last = all
        return all
    }

    private fun simulate(country: Country, plan: Plan): List<Change> {
        val json = Configs.json()
        val research = country.research
        val buffs = country.buffs
        country.research = json.decodeFromString(ResearchState.serializer(), json.encodeToString(ResearchState.serializer(), research))
        country.buffs = json.decodeFromString(BuffState.serializer(), json.encodeToString(BuffState.serializer(), buffs))
        simulating = true
        try {
            return run(country, plan)
        } finally {
            simulating = false
            country.research = research
            country.buffs = buffs
        }
    }

    private fun run(country: Country, plan: Plan): List<Change> {
        val out = ArrayList<Change>()
        aliases(country, plan, out)
        rollback(country, out)
        grandfather(country, plan, out)
        unpark(country, out)
        reconcile(country, out)
        return out
    }

    fun owed(entry: QueueEntry, node: Node?): Long? = when {
        !entry.paid -> 0
        entry.paidCost >= 0 -> entry.paidCost
        else -> legacyData().costs[entry.node]
    }

    fun refund(country: Country, entry: QueueEntry, node: Node?): Long {
        val amount = owed(entry, node)?.takeIf { it > 0 } ?: return 0
        if (simulating) return amount
        return Treasury.bank(country, LedgerKind.RESEARCH, amount, null, entry.node, capped = false)
    }

    fun refunded(country: Country, entry: QueueEntry, node: Node?): Boolean {
        val amount = owed(entry, node)
        if (amount == null) {
            log.warn("{}: paid entry {} has no recorded cost and no known legacy cost, keeping it", country.id, entry.node)
            return false
        }
        if (amount <= 0) return true
        val got = refund(country, entry, node)
        if (got in 1 until amount) entry.paidCost = amount - got
        return got >= amount
    }

    fun purgeParked(country: Country): Int = country.research.parked.size.also {
        country.research.parked.clear()
        Realm.changed(false)
    }

    fun refundParked(country: Country): Refunded {
        var total = 0L
        val paid = country.research.parked.filter { entry ->
            val node = Research.defs.node(entry.node)
            val amount = owed(entry, node) ?: 0
            val done = refunded(country, entry, node)
            total += if (done) amount else amount - (if (entry.paidCost >= 0) entry.paidCost else amount)
            done
        }
        country.research.parked.removeAll(paid)
        if (paid.isNotEmpty()) Realm.changed(false)
        return Refunded(total, country.research.parked.size)
    }

    private fun resolve(map: Map<String, String>, key: String): Node? {
        var current = key
        val seen = HashSet<String>()
        while (seen.add(current)) {
            val next = map[current] ?: return null
            Research.defs.node(next)?.let { return it }
            current = next
        }
        return null
    }

    private fun aliases(country: Country, plan: Plan, out: MutableList<Change>) {
        val defs = Research.defs
        val state = country.research
        val map = HashMap<String, String>()
        state.migrations.keys.filter { it.startsWith(ALIAS) && '#' !in it }.forEach { map[it.substring(ALIAS.length).substringBefore('>')] = it.substringAfter('>') }
        map.putAll(plan.replaces)
        val olds = (state.done.keys + (state.queue + state.parked).map { it.node } + country.buffs.enabled + country.buffs.billed).filter { it !in defs.nodes && it in map }.toSet()
        olds.forEach { old ->
            val node = resolve(map, old) ?: return@forEach
            val marker = "$ALIAS$old>${node.key}"
            state.done[old]?.let { time ->
                out += Change(country.id, "alias:$old", node.key, "$old -> ${node.key} (done)")
                state.done.remove(old)
                state.done.putIfAbsent(node.key, time)
                state.retired[old] = time
                state.migrations[marker] = Queue.clock()
            }
            if (country.buffs.enabled.contains(old) || country.buffs.billed.contains(old)) {
                out += Change(country.id, "alias:$old", node.key, "$old -> ${node.key} (buff)")
                if (country.buffs.enabled.remove(old)) country.buffs.enabled += node.key
                if (country.buffs.billed.remove(old)) country.buffs.billed += node.key
                state.migrations[marker] = Queue.clock()
                state.migrations["$marker#b"] = Queue.clock()
            }
            listOf(state.queue, state.parked).forEach { list ->
                list.filter { it.node == old }.forEach { entry ->
                    val other = (state.queue + state.parked).firstOrNull { it !== entry && it.node == node.key }
                    val swap = node.key !in state.done && other != null && started(entry) && !started(other)
                    val duplicate = !swap && (node.key in state.done || other != null)
                    out += Change(country.id, "alias:$old", node.key, "$old -> ${node.key} (${if (duplicate) "refunded duplicate" else if (swap) "kept started entry" else "queue"})")
                    if (swap) {
                        state.queue.remove(other)
                        state.parked.remove(other)
                    }
                    if (duplicate) {
                        if (refunded(country, entry, node)) list.remove(entry)
                    } else {
                        entry.node = node.key
                        state.migrations[marker] = Queue.clock()
                        state.migrations["$marker#q"] = Queue.clock()
                    }
                }
            }
        }
    }

    private fun rollback(country: Country, out: MutableList<Change>) {
        val state = country.research
        val back = state.migrations.keys.filter { it.startsWith(ALIAS) && '#' !in it }
            .map { it.substring(ALIAS.length).substringBefore('>') to it.substringAfter('>') }
            .filter { it.first in Research.defs.nodes }
        (back.map { it.first } + state.retired.keys.filter { it in Research.defs.nodes }).distinct().forEach { key ->
            out += Change(country.id, "rollback:$key", key, "$key restored")
            log.warn("{} exists again after being replaced, node ids must never be reused after a replaces; restoring it", key)
            state.retired.remove(key)?.let { state.done.putIfAbsent(key, it) }
            back.filter { it.first == key }.forEach { (_, to) ->
                val marker = "$ALIAS$key>$to"
                state.migrations.remove(marker)
                if (state.migrations.remove("$marker#b") != null) {
                    if (country.buffs.enabled.remove(to)) country.buffs.enabled += key
                    if (country.buffs.billed.remove(to)) country.buffs.billed += key
                }
                if (state.migrations.remove("$marker#q") != null && key !in state.done && (state.queue + state.parked).none { it.node == key }) {
                    (state.queue + state.parked).firstOrNull { it.node == to }?.node = key
                }
            }
        }
    }

    private fun grandfather(country: Country, plan: Plan, out: MutableList<Change>) {
        val state = country.research
        do {
            var granted = false
            plan.rules.forEach { rule ->
                val node = rule.node
                if (rule.id in state.migrations) return@forEach
                val (from, time) = rule.from.firstNotNullOfOrNull { key -> (state.done[key] ?: state.retired[key])?.takeIf { it < rule.cutoff }?.let { key to it } } ?: return@forEach
                if (node.key in state.done) {
                    state.migrations[rule.id] = Queue.clock()
                    return@forEach
                }
                if (state.queue.firstOrNull { it.node == node.key }?.let { !refunded(country, it, node) } == true) return@forEach
                out += Change(country.id, rule.id, node.key, "${node.key} granted for $from")
                Queue.markDone(country, node.key)
                state.done[node.key] = time
                state.migrations[rule.id] = Queue.clock()
                granted = true
            }
        } while (granted)
    }

    private fun unpark(country: Country, out: MutableList<Change>) {
        val state = country.research
        val researchSlots = Levels.capacity(country, Capacity.RESEARCH_SLOTS)
        val queueSlots = Levels.capacity(country, Capacity.QUEUE_SLOTS)
        state.parked.toList().forEach { entry ->
            val node = Research.defs.nodeFor(country, entry.node) ?: return@forEach
            if (node.key in state.done || state.queue.any { it.node == entry.node }) {
                if (!refunded(country, entry, node)) return@forEach
                out += Change(country.id, "unpark:${entry.node}", entry.node, "${entry.node} refunded")
                state.parked.remove(entry)
                return@forEach
            }
            val next = if (entry.state == NodeState.RESEARCHING && Queue.researching(country) >= researchSlots) NodeState.PAUSED else entry.state
            if (next != NodeState.RESEARCHING && Queue.waiting(country) >= queueSlots) return@forEach
            out += Change(country.id, "unpark:${entry.node}", entry.node, "${entry.node} unparked${if (next != entry.state) " (paused)" else ""}")
            entry.state = next
            state.parked.remove(entry)
            state.queue += entry
        }
    }

    private fun reconcile(country: Country, out: MutableList<Change>) {
        var stamped = false
        country.research.queue.toList().forEach { entry ->
            val node = Research.defs.node(entry.node) ?: return@forEach
            val current = sigs(node)
            val old = if (entry.sigs.isEmpty()) legacyData().sigs[entry.node].orEmpty() else entry.sigs.filter { it != UNSTAMPED }
            val relayout = (entry.sigs.isNotEmpty() || old.isNotEmpty()) && old != current
            val open = entry.state == NodeState.QUEUED || entry.state == NodeState.READY
            if (relayout) out += Change(country.id, "reconcile:${entry.node}", entry.node, "${entry.node} tasks reconciled")
            val before = entry.tasks.toMap()
            if (relayout && open) {
                val oldTargets = entry.targets.takeIf { it.size == old.size } ?: legacyData().targets[entry.node].orEmpty()
                val notes = remap(entry, node, current, old, oldTargets, before)
                if (notes.isNotEmpty()) {
                    notes.forEach { log.info("{}: {} {}", country.id, entry.node, it) }
                    if (!simulating) Mail.officers(country, Phrase.of("kami_claims.research.mail.progress_converted", node.label().asValue()), Tone.BAD)
                }
            }
            if (open) {
                entry.tasks.keys.removeAll { it !in node.tasks.indices }
                entry.tasks.replaceAll { index, have -> min(have, node.tasks[index].target) }
            }
            val state = entry.state
            if (entry.state == NodeState.READY && !Queue.tasksDone(entry, node)) entry.state = NodeState.QUEUED
            if (!simulating) Queue.settle(country, entry, node, notify = state != NodeState.READY)
            if (!relayout && (entry.state != state || entry.tasks != before)) out += Change(country.id, "reconcile:${entry.node}", entry.node, "${entry.node} progress rechecked")
            val stamp = current.ifEmpty { listOf(UNSTAMPED) }
            val targets = targets(node)
            if (entry.sigs != stamp || entry.targets != targets) {
                entry.sigs.clear()
                entry.sigs += stamp
                entry.targets.clear()
                entry.targets += targets
                stamped = true
            }
        }
        if (stamped && !simulating) {
            Realm.changed(false)
            ResearchSync.touch(country)
        }
    }

    private fun kind(sig: String) = sig.substringBefore('|')

    private fun remap(entry: QueueEntry, node: Node, current: List<String>, old: List<String>, oldTargets: List<Long>, before: Map<Int, Long>): List<String> {
        val used = HashSet<Int>()
        val filled = HashSet<Int>()
        entry.tasks.clear()
        current.forEachIndexed { index, sig ->
            val from = old.indices.firstOrNull { it !in used && old[it] == sig } ?: return@forEachIndexed
            used += from
            filled += index
            before[from]?.takeIf { it > 0 }?.let { entry.tasks[index] = it }
        }
        val notes = ArrayList<String>()
        old.indices.filter { it !in used && (before[it] ?: 0) > 0 }.forEach { from ->
            val have = before.getValue(from)
            val label = "${kind(old[from])} task ${from + 1}"
            val same = current.indexOf(old[from])
            if (old[from].startsWith("deposit|") && same >= 0) {
                entry.tasks[same] = (entry.tasks[same] ?: 0) + have
                notes += "$label merged into task ${same + 1}: +$have"
                return@forEach
            }
            val fits = { index: Int -> index !in filled && kind(current[index]) == kind(old[from]) }
            val to = from.takeIf { it in current.indices && fits(it) } ?: current.indices.firstOrNull(fits)
            if (to == null) {
                notes += "$label progress $have has no matching task and was dropped"
                return@forEach
            }
            val target = node.tasks[to].target
            val oldTarget = oldTargets.getOrNull(from)?.takeIf { it > 0 }
            val carried = (if (oldTarget == null) have else have * target / oldTarget).coerceAtLeast(1)
            entry.tasks[to] = carried
            filled += to
            notes += "$label progress $have/${oldTarget ?: "?"} carried to task ${to + 1} as $carried/$target"
        }
        return notes
    }
}
