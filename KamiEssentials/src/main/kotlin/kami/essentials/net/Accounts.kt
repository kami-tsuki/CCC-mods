package kami.essentials.net

import kami.essentials.chat.Names
import kami.libs.economy.Account
import kami.libs.economy.Movement
import kami.libs.economy.Numismatics
import kami.libs.economy.Statement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val MOVEMENTS = 300
private const val TICK_MS = 50L

class Played(val playtime: Long = 0, val kills: Int = 0, val deaths: Int = 0)

fun account(server: MinecraftServer, id: UUID): Account {
    val cache = server.profileCache
    return account(id, Numismatics.balance(id), played(server, id)) { cache?.get(it)?.orElse(null)?.name }
}

internal fun account(id: UUID, balance: Long, played: Played, name: (UUID) -> String?): Account {
    val citizenship = Names.citizenship(id)
    val book = Statement.book(id)
    val movements = book.movements.take(MOVEMENTS).map { m ->
        val who = runCatching { UUID.fromString(m.counterparty) }.getOrNull()?.let(name)
        if (who == null) m else Movement(m.at, m.amount, m.flow, who, m.item, m.qty)
    }
    return Account(
        balance, played.playtime, played.kills, played.deaths,
        citizenship?.name, citizenship?.rank,
        movements, book.days, book.week, book.month
    )
}

fun played(server: MinecraftServer, id: UUID): Played =
    server.playerList.getPlayer(id)?.let(::played) ?: played(server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve("$id.json"))

fun played(p: ServerPlayer) = Played(Names.stat(p, Stats.PLAY_TIME) * TICK_MS, Names.stat(p, Stats.PLAYER_KILLS), Names.stat(p, Stats.DEATHS))

internal fun played(file: Path): Played = runCatching {
    val custom = Json.parseToJsonElement(Files.readString(file)).jsonObject["stats"]?.jsonObject?.get("minecraft:custom")?.jsonObject
    fun stat(key: String) = custom?.get(key)?.jsonPrimitive?.longOrNull ?: 0L
    Played(stat("minecraft:play_time") * TICK_MS, stat("minecraft:player_kills").toInt(), stat("minecraft:deaths").toInt())
}.getOrDefault(Played())
