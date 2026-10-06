package kami.essentials.net

import kami.essentials.chat.Names
import kami.libs.economy.Account
import kami.libs.economy.Movement
import kami.libs.economy.Numismatics
import kami.libs.economy.Statement
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import java.util.UUID

private const val MOVEMENTS = 300

fun account(p: ServerPlayer): Account {
    val id = p.uuid
    val citizenship = Names.citizenship(id)
    val cache = p.server.profileCache
    val movements = Statement.movements(id).take(MOVEMENTS).map { m ->
        val name = runCatching { UUID.fromString(m.counterparty) }.getOrNull()?.let { cache?.get(it)?.orElse(null)?.name }
        if (name == null) m else Movement(m.at, m.amount, m.flow, name, m.item, m.qty)
    }
    return Account(
        Numismatics.balance(id), Names.stat(p, Stats.PLAY_TIME) * 50L,
        Names.stat(p, Stats.PLAYER_KILLS), Names.stat(p, Stats.DEATHS),
        citizenship?.name, citizenship?.rank,
        movements, Statement.days(id), Statement.totals(id, 7), Statement.totals(id, 30)
    )
}
