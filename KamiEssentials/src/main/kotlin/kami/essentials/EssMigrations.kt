package kami.essentials

import kami.libs.util.toUuidOrNull
import kami.libs.db.Legacy
import kami.libs.db.Migration
import kami.libs.db.Migrations
import kami.libs.db.Verify
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
internal class LegacyData(val flags: Map<Flag, List<String>> = emptyMap())

internal object EssMigrations {
    private const val FILE = "kami_essentials.json"

    private val schema = Migration(
        "ess", 1, "schema", legacy = listOf(FILE),
        ddl = listOf("CREATE TABLE ess_flag (flag TEXT NOT NULL, player TEXT NOT NULL, PRIMARY KEY (flag, player))")
    ) { world ->
        val legacy = normalize((Legacy.read(world, FILE, LegacyData.serializer()) ?: LegacyData()).flags)
        batch("INSERT OR IGNORE INTO ess_flag (flag, player) VALUES (?, ?)", legacy.flags.flatMap { (flag, ids) -> ids.map { listOf(flag.name, it) } })
        return@Migration {
            Verify.sameJson("ess flags", LegacyData.serializer(), legacy, normalize(Store.read(this).mapValues { (_, ids) -> ids.map(UUID::toString) }))
            Verify.equal("ess_flag rows", legacy.flags.values.sumOf { it.size }, one("SELECT COUNT(*) AS n FROM ess_flag") { it.int("n") })
        }
    }

    private val players = Migration(
        "ess", 2, "players",
        ddl = listOf(
            "CREATE TABLE ess_player (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, first_join INTEGER NOT NULL, last_seen INTEGER NOT NULL, " +
                "dim TEXT, x REAL, y REAL, z REAL, yaw REAL, pitch REAL, last_ip TEXT, playtime_ms INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE ess_player_ip (uuid TEXT NOT NULL, ip TEXT NOT NULL, last_at INTEGER NOT NULL, PRIMARY KEY (uuid, ip))",
            "CREATE INDEX ess_player_ip_ip ON ess_player_ip (ip)",
            "CREATE TABLE ess_player_name (uuid TEXT NOT NULL, name TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (uuid, name))",
            "CREATE TABLE ess_mute (uuid TEXT PRIMARY KEY, reason TEXT NOT NULL, \"by\" TEXT NOT NULL, since INTEGER NOT NULL, until INTEGER)"
        )
    )

    private val banIps = Migration(
        "ess", 3, "ban_ips",
        ddl = listOf(
            "CREATE TABLE ess_ban_ip (uuid TEXT NOT NULL, ip TEXT NOT NULL, PRIMARY KEY (uuid, ip))",
            "CREATE INDEX ess_ban_ip_ip ON ess_ban_ip (ip)"
        )
    )

    private val warns = Migration(
        "ess", 4, "warns",
        ddl = listOf(
            "CREATE TABLE ess_warn (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, reason TEXT NOT NULL, \"by\" TEXT NOT NULL, at INTEGER NOT NULL, " +
                "delivered INTEGER NOT NULL DEFAULT 0, revoked_by TEXT, revoked_at INTEGER, revoke_reason TEXT)",
            "CREATE INDEX ess_warn_uuid ON ess_warn (uuid, at)"
        )
    )

    private val delivery = Migration(
        "ess", 5, "delivery",
        ddl = listOf("CREATE TABLE ess_delivery (id INTEGER PRIMARY KEY, uuid TEXT NOT NULL, item TEXT NOT NULL)")
    )

    private val skills = Migration(
        "ess", 6, "skills",
        ddl = listOf(
            "CREATE TABLE ess_skill (uuid TEXT PRIMARY KEY, class TEXT, xp REAL NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 0, nodes TEXT NOT NULL DEFAULT '', refunds INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE ess_skill_craft (uuid TEXT NOT NULL, item TEXT NOT NULL, PRIMARY KEY (uuid, item))"
        )
    )

    fun register() = Migrations.register("ess", listOf(schema, players, banIps, warns, delivery, skills), after = listOf("libs"))

    private fun normalize(flags: Map<Flag, Collection<String>>) = LegacyData(Flag.entries.associateWith { flag ->
        flags[flag].orEmpty().mapNotNull { it.toUuidOrNull()?.toString() }.distinct().sorted()
    })
}
