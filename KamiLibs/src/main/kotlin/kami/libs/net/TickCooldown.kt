package kami.libs.net

import java.util.UUID

class TickCooldown(private val ticks: Int) {
    private val last = HashMap<UUID, Int>()

    fun ready(uuid: UUID, tick: Int, cooldown: Int = ticks): Boolean {
        if (tick - (last[uuid] ?: (-cooldown - 1)) < cooldown) return false
        last[uuid] = tick
        return true
    }

    fun forget(uuid: UUID) {
        last.remove(uuid)
    }
}
