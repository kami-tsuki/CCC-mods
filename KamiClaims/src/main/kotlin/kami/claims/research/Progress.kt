package kami.claims.research

import kami.claims.Country
import kami.claims.Realm
import kami.libs.progress.ProgressEvent
import net.minecraft.server.level.ServerPlayer

object Progress {
    fun of(player: ServerPlayer) = Realm.of(player.stringUUID)

    fun onEvent(event: ProgressEvent) {
        val country = event.country?.let { Realm.country(it) } ?: event.player?.let(::of) ?: return
        report(country, event.kind, event.subject, event.amount, event.once)
    }

    fun report(country: Country, kind: String, subject: String, amount: Long, once: String? = null) {
        if (amount <= 0) return
        if (once != null) {
            if (!country.research.visited.add("once:$once")) return
            Realm.dirty = true
        }
        when (kind) {
            "trade_value" -> Unit
            else -> Levels.add(country, kind, amount.toDouble())
        }
        if (kind == "taxes") Counters.add(country, Counters.TAXES_COLLECTED, amount)
        else if (kind in Research.defs.levels.counted) Counters.event(country, kind, subject, amount)
        if (country.research.queue.isEmpty()) return
        val byNode = Research.defs.taskIndex[kind] ?: return
        country.research.queue.filter { it.state == NodeState.QUEUED }.forEach { entry ->
            val node = Research.defs.node(entry.node) ?: return@forEach
            byNode[entry.node]?.filter { !node.tasks[it].manual && node.tasks[it].accepts(subject) }?.forEach { Queue.credit(country, entry.node, it, amount) }
        }
    }

    fun visited(country: Country, dimension: String) {
        if (country.research.visited.add(dimension)) {
            Levels.add(country, "discovery", 1.0)
            Realm.dirty = true
        }
        report(country, "visit", dimension, 1)
    }

    fun citizenJoined(country: Country, player: String) {
        if (country.research.visited.add("citizen:$player")) {
            Levels.add(country, "citizen", 1.0)
            Realm.dirty = true
        }
    }

    fun structureEntered(country: Country, id: String, tags: Collection<String>) {
        if (country.research.visited.add("structure:$id")) {
            Levels.add(country, "discovery", 1.0)
            Realm.dirty = true
        }
        report(country, "structure", id, 1)
        tags.forEach { report(country, "structure", "#$it", 1) }
    }

    fun processed(country: Country, recipeType: String, output: String) {
        if (Research.defs.taskIndex["process"] != null) report(country, "process", "$recipeType|$output", 1)
    }
}
