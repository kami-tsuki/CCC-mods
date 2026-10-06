package kami.claims.war

import kami.claims.Config
import kami.claims.Country
import kami.claims.Realm
import kami.claims.now
import kami.libs.player.Presence
import net.minecraft.Util
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

object Online {
    private const val MIN = 60_000L
    private val s get() = Config.s.war
    private val grace get() = s.returnMinutes * MIN
    private val logins = HashMap<UUID, Long>()
    private val logouts = HashMap<UUID, Long>()
    private val lastExposed = HashMap<String, Long>()

    fun login(p: ServerPlayer) {
        val out = logouts.remove(p.uuid)
        if (out != null && now() - out < grace) logins.putIfAbsent(p.uuid, now()) else logins[p.uuid] = now()
    }

    fun logout(p: ServerPlayer) { logouts[p.uuid] = now() }

    fun reset() { logins.clear(); logouts.clear(); lastExposed.clear() }

    private fun active(p: ServerPlayer): Boolean {
        val since = logins[p.uuid] ?: return false
        return now() - since >= s.onlineMinutes * MIN && Util.getMillis() - p.lastActionTime < s.afkMinutes * MIN && !Presence.hidden(p.uuid)
    }

    fun exposed(c: Country): Boolean = lastExposed[c.id]?.let { now() - it <= maxOf(grace, 2_500L) } == true

    fun sample(players: List<ServerPlayer>) {
        val t = now()
        if (logouts.isNotEmpty()) logouts.filterValues { t - it >= grace }.keys.forEach { logouts.remove(it); logins.remove(it) }
        if (!Wars.fighting()) return lastExposed.clear()
        players.forEach { p ->
            val c = Realm.of(p.stringUUID) ?: return@forEach
            if (Wars.fighting(c) && active(p)) lastExposed[c.id] = t
        }
    }
}
