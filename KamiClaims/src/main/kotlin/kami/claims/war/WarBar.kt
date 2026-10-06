package kami.claims.war

import kami.claims.Config
import kami.claims.Realm
import kami.claims.now
import kami.claims.service.Words.v
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent

object WarBar {
    private val bars = HashMap<String, ServerBossEvent>()

    fun tick(players: List<ServerPlayer>) {
        val wars = Realm.data.wars.filter { it.active }
        if (wars.isEmpty() && bars.isEmpty()) return
        val live = HashSet<String>()
        wars.forEach { w ->
            w.sides.forEach { side ->
                val key = "${w.id}:${if (side === w.attacker) "a" else "d"}"
                live += key
                val bar = bars.getOrPut(key) { ServerBossEvent(Phrase.literal("").component(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS) }
                update(bar, w, side)
                players.forEach { p -> if (Realm.of(p.stringUUID)?.id in side.countries) bar.addPlayer(p) else bar.removePlayer(p) }
            }
        }
        bars.keys.filter { it !in live }.forEach { bars.remove(it)?.removeAllPlayers() }
    }

    private fun update(bar: ServerBossEvent, w: War, side: WarSide) {
        val left = w.phaseEnds - now()
        val enemy = v(Wars.name(w.opponent(side).main))
        val phase = Phrase.of("kami_claims.warpage.phase.${w.phase.name.lowercase()}")
        bar.name = (if (w.phaseEnds > 0) Phrase.of("kami_claims.warbar.title", phase, enemy, Wars.hours(left)) else Phrase.of("kami_claims.warbar.open", phase, enemy)).component()
        bar.progress = progress(w, left).toFloat().coerceIn(0f, 1f)
        bar.color = when (w.phase) {
            WarPhase.DECLARATION -> BossEvent.BossBarColor.YELLOW
            WarPhase.PREPARATION -> BossEvent.BossBarColor.BLUE
            WarPhase.WAR -> BossEvent.BossBarColor.RED
            WarPhase.CEASEFIRE -> BossEvent.BossBarColor.GREEN
            else -> BossEvent.BossBarColor.PURPLE
        }
    }

    private fun progress(w: War, left: Long): Double {
        if (w.phaseEnds <= 0) return 1 - maxOf(w.attacker.exhaustion, w.defender.exhaustion) / 100.0
        val s = Config.s.war
        val total = when (w.phase) {
            WarPhase.CEASEFIRE -> w.phaseEnds - w.ceasefireStart
            WarPhase.DECLARATION -> (s.declarationDays + w.extendedDays) * Config.s.dayMillis
            WarPhase.PREPARATION -> (s.preparationDays + w.extendedDays) * Config.s.dayMillis
            else -> (s.settlementDays + w.extendedDays) * Config.s.dayMillis
        }
        return if (total > 0) left.toDouble() / total else 0.0
    }

    fun forget(p: ServerPlayer) = bars.values.forEach { it.removePlayer(p) }

    fun reset() {
        bars.values.forEach { it.removeAllPlayers() }
        bars.clear()
    }
}
