package kami.economy.db

import kami.economy.LOG
import kami.economy.economy.Intent
import kami.libs.config.Configs
import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.db.Table
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal val intentTable = Table<Collection<Intent>>("eco_intent", listOf("id"), listOf("type", "state", "actor", "data")) { list ->
    list.map { listOf(it.id, it.type.name, it.state.name, it.actor, Db.json.encodeToString(Intent.serializer(), it)) }
}

internal fun Session.intents(): List<Intent> =
    query("SELECT data FROM eco_intent ORDER BY id") { Db.json.decodeFromString(Intent.serializer(), it.string("data")) }

internal fun Session.count(sql: String): Long = one(sql) { it.longOrNull("v") } ?: 0L

internal fun legacyIntents(file: Path): List<Intent> {
    if (!Files.exists(file)) return emptyList()
    val json = Configs.json()
    val latest = LinkedHashMap<Long, Intent>()
    var bad = 0
    Files.readAllLines(file, StandardCharsets.UTF_8).forEach { line ->
        if (line.isBlank()) return@forEach
        runCatching { json.decodeFromString(Intent.serializer(), line) }.onSuccess { latest[it.id] = it }.onFailure { bad++ }
    }
    if (bad > 0) LOG.warn("Skipped {} unreadable lines in {}", bad, file)
    return latest.values.toList()
}
