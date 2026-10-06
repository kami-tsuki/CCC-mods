package kami.essentials

import kami.essentials.net.account
import kami.essentials.net.played
import kami.libs.db.Db
import kami.libs.economy.Flow
import kami.libs.economy.Statement
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalViewTest {
    @Test
    fun offlineAccountFromStatsFile() {
        val world = Files.createTempDirectory("kami-ess-view")
        try {
            Db.open(world.resolve("kami").resolve("kami.db"))
            val id = UUID.randomUUID()
            val landlord = UUID.randomUUID()
            val stats = world.resolve("$id.json")
            Files.writeString(stats, """{"stats":{"minecraft:custom":{"minecraft:play_time":72000,"minecraft:player_kills":3,"minecraft:deaths":5}},"DataVersion":3955}""")
            Statement.record(id, -40, Flow.RENT, landlord.toString())
            Db.flush()
            val a = account(id, 120, played(stats)) { if (it == landlord) "Landlord" else null }
            assertEquals(listOf(120L, 3_600_000L, 3L, 5L), listOf(a.balance, a.playtime, a.kills.toLong(), a.deaths.toLong()))
            assertEquals("Landlord" to -40L, a.movements.single().let { it.counterparty to it.amount })
            assertEquals(0L, played(world.resolve("missing.json")).playtime)
        } finally {
            Db.close()
            world.toFile().deleteRecursively()
        }
    }
}
