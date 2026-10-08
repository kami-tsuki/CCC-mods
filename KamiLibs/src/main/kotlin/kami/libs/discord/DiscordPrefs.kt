package kami.libs.discord

import kami.libs.db.Db
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DiscordPrefs {
    const val MARKET_ALERTS = "alerts.market"

    private val values = ConcurrentHashMap<String, Boolean>()

    fun load() {
        values.clear()
        Db.read { query("SELECT uuid, key, value FROM libs_discord_pref") { Triple(it.string("uuid"), it.string("key"), it.string("value")) } }
            .forEach { (uuid, key, value) -> values["$uuid|$key"] = value == ON }
    }

    fun on(uuid: UUID, key: String): Boolean = values["$uuid|$key"] == true

    fun set(uuid: UUID, key: String, on: Boolean) {
        synchronized(values) {
            if (values.put("$uuid|$key", on) == on) return
            Db.write { exec("INSERT OR REPLACE INTO libs_discord_pref (uuid, key, value) VALUES (?, ?, ?)", uuid.toString(), key, if (on) ON else OFF) }
        }
    }

    private const val ON = "1"
    private const val OFF = "0"
}
