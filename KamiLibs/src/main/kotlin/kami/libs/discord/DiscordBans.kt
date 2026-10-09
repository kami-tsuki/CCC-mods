package kami.libs.discord

import kami.libs.util.toUuidOrNull
import com.mojang.authlib.GameProfile
import kami.libs.db.Db
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DiscordBans {
    private val byDiscord = ConcurrentHashMap<Long, UUID>()

    fun load() {
        val rows = Db.read { query("SELECT discord_id, uuid FROM libs_discord_ban") { it.long("discord_id") to it.string("uuid") } }
        synchronized(byDiscord) {
            byDiscord.clear()
            rows.forEach { (id, uuid) -> uuid.toUuidOrNull()?.let { byDiscord[id] = it } }
        }
    }

    fun banned(discordId: Long): UUID? {
        val uuid = byDiscord[discordId] ?: return null
        val bans = ServerLifecycleHooks.getCurrentServer()?.playerList?.bans ?: return uuid
        if (bans.isBanned(GameProfile(uuid, "_"))) return uuid
        clear(uuid)
        return null
    }

    fun mark(discordId: Long, uuid: UUID) {
        synchronized(byDiscord) {
            byDiscord[discordId] = uuid
            Db.write { exec("INSERT OR REPLACE INTO libs_discord_ban (discord_id, uuid, at) VALUES (?, ?, ?)", discordId, uuid.toString(), System.currentTimeMillis()) }
        }
    }

    fun clear(uuid: UUID) {
        synchronized(byDiscord) {
            if (byDiscord.values.removeAll { it == uuid }) Db.write { exec("DELETE FROM libs_discord_ban WHERE uuid = ?", uuid.toString()) }
        }
    }
}
