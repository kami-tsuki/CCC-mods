package kami.claims.research

import kami.claims.Country
import kami.claims.Realm
import kami.libs.progress.ProgressEvent
import net.minecraft.server.level.ServerPlayer

object Progress {
    fun onEvent(event: ProgressEvent) {
        val country = event.country?.let { Realm.country(it) } ?: event.player?.let { Realm.of(it.stringUUID) } ?: return
        report(country, event.kind, event.subject, event.amount, event.once)
    }

    fun report(player: ServerPlayer, kind: String, subject: String, amount: Long = 1) {
        Realm.of(player.stringUUID)?.let { report(it, kind, subject, amount) }
    }

    fun report(country: Country, kind: String, subject: String, amount: Long, once: String? = null) {
        if (amount <= 0) return
        if (once != null) {
            if (!country.research.visited.add("once:$once")) return
            Realm.dirty = true
        }
        if (kind != Kinds.TRADE_VALUE) Levels.add(country, kind, amount.toDouble())
        if (kind == Kinds.TAXES) Counters.add(country, Counters.TAXES_COLLECTED, amount)
        else if (kind in Research.defs.levels.counted) Counters.event(country, kind, subject, amount)
        if (country.research.queue.isEmpty()) return
        val byNode = Research.defs.taskIndex[kind] ?: return
        country.research.queue.filter { it.state == NodeState.QUEUED }.forEach { entry ->
            val node = Research.defs.node(entry.node) ?: return@forEach
            byNode[entry.node]?.filter { !node.tasks[it].manual && node.tasks[it].accepts(subject) }?.forEach { Queue.credit(country, entry.node, it, amount) }
        }
    }

    fun visited(player: ServerPlayer, dimension: String) {
        Realm.of(player.stringUUID)?.let { visited(it, dimension) }
    }

    fun visited(country: Country, dimension: String) {
        firstTime(country, dimension, "discovery")
        report(country, Kinds.VISIT, dimension, 1)
    }

    fun citizenJoined(country: Country, player: String) {
        firstTime(country, "citizen:$player", "citizen")
    }

    fun structureEntered(player: ServerPlayer, id: String, tags: Collection<String>) {
        Realm.of(player.stringUUID)?.let { structureEntered(it, id, tags) }
    }

    fun structureEntered(country: Country, id: String, tags: Collection<String>) {
        firstTime(country, "structure:$id", "discovery")
        report(country, Kinds.STRUCTURE, id, 1)
        tags.forEach { report(country, Kinds.STRUCTURE, "#$it", 1) }
    }

    fun processed(country: Country, recipeType: String, output: String) {
        report(country, Kinds.PROCESS, "$recipeType|$output", 1)
    }

    private fun firstTime(country: Country, key: String, source: String) {
        if (!country.research.visited.add(key)) return
        Levels.add(country, source, 1.0)
        Realm.dirty = true
    }
}
