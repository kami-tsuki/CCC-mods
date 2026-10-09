package kami.claims.war

import kami.claims.Config
import kami.claims.KamiClaims.LOG
import kami.claims.Realm
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.scores.Team
import java.util.UUID

enum class TeamStep { KEEP, JOIN, LEAVE, SKIP }

object NameTagTeams {
    const val PREFIX = "kc_"
    private const val MAX = 16
    private val warned = HashSet<UUID>()

    fun teamName(country: String): String {
        if (PREFIX.length + country.length <= MAX) return PREFIX + country
        return PREFIX + Integer.toHexString(country.hashCode()).padStart(8, '0') + country.take(MAX - PREFIX.length - 8)
    }

    fun decide(wanted: String?, current: String?) = when {
        current == null -> if (wanted == null) TeamStep.KEEP else TeamStep.JOIN
        !current.startsWith(PREFIX) -> TeamStep.SKIP
        wanted == null -> TeamStep.LEAVE
        wanted == current -> TeamStep.KEEP
        else -> TeamStep.JOIN
    }

    private fun wanted(p: ServerPlayer): String? {
        if (!Config.s.visibility.hideNametags) return null
        val c = Realm.of(p.stringUUID) ?: return null
        return if (Visibility.warring(c.id)) teamName(c.id) else null
    }

    fun sync(p: ServerPlayer) {
        val board = p.server.scoreboard
        val name = p.scoreboardName
        val current = board.getPlayersTeam(name)?.name
        val wanted = wanted(p)
        when (decide(wanted, current)) {
            TeamStep.KEEP -> Unit
            TeamStep.SKIP -> if (wanted != null && warned.add(p.uuid)) LOG.warn("{} is in team {}, nametag hiding skipped", name, current)
            TeamStep.LEAVE -> {
                board.getPlayersTeam(name)?.let { board.removePlayerFromTeam(name, it) }
                if (current != null) dropIfEmpty(p.server, current)
            }
            TeamStep.JOIN -> {
                val team = board.getPlayerTeam(wanted!!) ?: board.addPlayerTeam(wanted)
                team.nameTagVisibility = Team.Visibility.HIDE_FOR_OTHER_TEAMS
                board.addPlayerToTeam(name, team)
                if (current != null) dropIfEmpty(p.server, current)
            }
        }
    }

    fun syncAll(server: MinecraftServer) {
        val players = server.playerList.players
        players.forEach { p -> runCatching { sync(p) }.onFailure { LOG.warn("Nametag sync failed for {}", p.scoreboardName, it) } }
        warned.retainAll(players.mapTo(HashSet()) { it.uuid })
        val online = players.mapTo(HashSet()) { it.scoreboardName }
        val board = server.scoreboard
        board.playerTeams.filter { it.name.startsWith(PREFIX) }.forEach { team ->
            team.players.filter { it !in online }.forEach { board.removePlayerFromTeam(it, team) }
            if (team.players.isEmpty()) board.removePlayerTeam(team)
        }
    }

    fun sweep(server: MinecraftServer) {
        val board = server.scoreboard
        board.playerTeams.filter { it.name.startsWith(PREFIX) }.forEach(board::removePlayerTeam)
    }

    fun reset() = warned.clear()

    private fun dropIfEmpty(server: MinecraftServer, team: String) {
        val board = server.scoreboard
        board.getPlayerTeam(team)?.takeIf { it.players.isEmpty() }?.let(board::removePlayerTeam)
    }
}
