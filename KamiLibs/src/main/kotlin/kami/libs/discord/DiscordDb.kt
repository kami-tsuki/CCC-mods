package kami.libs.discord

import kami.libs.util.toUuidOrNull
import kami.libs.db.Legacy
import kami.libs.db.Migration
import kami.libs.db.Row
import kami.libs.db.Verify
import kotlinx.serialization.builtins.ListSerializer
import net.neoforged.fml.loading.FMLPaths

internal object DiscordDb {
    private fun config(name: String) = runCatching { FMLPaths.CONFIGDIR.get().resolve(name).toAbsolutePath().toString() }.getOrNull()

    private val linkFile = config("kami-discord-links.json")
    private val codeFile = config("kami-discord-codes.json")

    val migration = Migration(
        "libs", 8, "discord", legacy = listOfNotNull(linkFile, codeFile),
        ddl = listOf(
            "CREATE TABLE libs_discord_link (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, discord_id INTEGER NOT NULL UNIQUE, discord_name TEXT NOT NULL, linked_at INTEGER NOT NULL)",
            "CREATE TABLE libs_discord_code (code TEXT PRIMARY KEY, uuid TEXT NOT NULL, name TEXT NOT NULL, expires_at INTEGER NOT NULL)"
        )
    ) { world ->
        val links = linkFile?.let { Legacy.read(world, it, LinkFile.serializer()) }?.links.orEmpty()
            .associateBy { it.uuid }.values.associateBy { it.discordId }.values.sortedBy { it.uuid }
        val now = System.currentTimeMillis()
        val codes = codeFile?.let { runCatching { Legacy.read(world, it, ListSerializer(StoredCode.serializer())) }.getOrNull() }.orEmpty()
            .filter { it.expiresAt > now && it.uuid.toUuidOrNull() != null }
            .associateBy { it.code }.values.sortedBy { it.code }.take(500)
        batch("INSERT INTO libs_discord_link (uuid, name, discord_id, discord_name, linked_at) VALUES (?, ?, ?, ?, ?)", links.map { listOf(it.uuid, it.name, it.discordId, it.discordName, it.linkedAt) })
        batch("INSERT INTO libs_discord_code (code, uuid, name, expires_at) VALUES (?, ?, ?, ?)", codes.map { listOf(it.code, it.uuid, it.name, it.expiresAt) })
        return@Migration {
            Verify.sameJson("discord links", ListSerializer(Link.serializer()), links, query("SELECT uuid, name, discord_id, discord_name, linked_at FROM libs_discord_link ORDER BY uuid", row = Row::link))
            Verify.sameJson(
                "discord codes", ListSerializer(StoredCode.serializer()), codes,
                query("SELECT code, uuid, name, expires_at FROM libs_discord_code ORDER BY code") { StoredCode(it.string("code"), it.string("uuid"), it.string("name"), it.long("expires_at")) }
            )
        }
    }

    val ban = Migration(
        "libs", 10, "discord_ban",
        ddl = listOf("CREATE TABLE libs_discord_ban (discord_id INTEGER PRIMARY KEY, uuid TEXT NOT NULL, at INTEGER NOT NULL)")
    )

    val pref = Migration(
        "libs", 9, "discord_pref",
        ddl = listOf("CREATE TABLE libs_discord_pref (uuid TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (uuid, key)) WITHOUT ROWID")
    )
}
