package kami.essentials

import kami.libs.db.Db
import kami.libs.db.Session
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class Flag { INVISIBLE, NO_SIDEBAR, COUNTRY_CHAT, ADMIN_CHAT, NO_TRADES, QUIET_DM, QUIET_DISCORD }

object Store {
    private val sets = Flag.entries.associateWith { ConcurrentHashMap.newKeySet<UUID>() }

    fun load() = Db.read { load(this) }

    internal fun load(session: Session) {
        val loaded = read(session)
        sets.forEach { (flag, set) -> set.clear(); set.addAll(loaded.getValue(flag)) }
    }

    internal fun read(session: Session): Map<Flag, Set<UUID>> {
        val out = Flag.entries.associateWith { linkedSetOf<UUID>() }
        session.query("SELECT flag, player FROM ess_flag") { Flag.valueOf(it.string("flag")) to UUID.fromString(it.string("player")) }
            .forEach { (flag, id) -> out.getValue(flag).add(id) }
        return out
    }

    fun ids(flag: Flag): Set<UUID> = sets.getValue(flag)

    operator fun get(flag: Flag, id: UUID) = id in sets.getValue(flag)

    operator fun set(flag: Flag, id: UUID, on: Boolean) {
        val set = sets.getValue(flag)
        synchronized(set) {
            if (!(if (on) set.add(id) else set.remove(id))) return
            Db.write {
                if (on) exec("INSERT OR IGNORE INTO ess_flag (flag, player) VALUES (?, ?)", flag.name, id.toString())
                else exec("DELETE FROM ess_flag WHERE flag = ? AND player = ?", flag.name, id.toString())
            }
        }
    }
}
