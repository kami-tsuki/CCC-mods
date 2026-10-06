package kami.essentials

import kami.essentials.admin.Moderation
import kami.essentials.admin.Mute
import kami.essentials.admin.Mutes
import kami.essentials.admin.PlayerRecords
import kami.essentials.admin.Revoke
import kami.essentials.admin.Warns
import kami.essentials.admin.Spot
import kami.libs.admin.AdminFailure
import kami.libs.db.Db
import kami.libs.db.SavePoint
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AdminServerTest {
    @Test
    fun recordsAndMutesPersist() {
        val world = Files.createTempDirectory("kami-ess-admin")
        val db = world.resolve("kami").resolve("kami.db")
        try {
            EssMigrations.register()
            Db.open(db)
            Db.read { PlayerRecords.load(this); Mutes.load(this) }
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            PlayerRecords.login(a, "Alpha", "10.0.0.1", 1_000)
            PlayerRecords.login(a, "Alpha2", "10.0.0.2", 2_000)
            PlayerRecords.login(b, "Beta", "10.0.0.2", 3_000)
            PlayerRecords.update(a, Spot("minecraft:the_nether", 1.5, 64.0, -3.0, 90f, 10f), 120_000, 4_000)
            Mutes.mute(b, Mute("spam", "Admin", 100, null))
            Mutes.mute(a, Mute("caps", "Admin", 100, 200))
            SavePoint.run()
            Db.flush()
            Db.close()
            Db.open(db)
            Db.read { PlayerRecords.load(this); Mutes.load(this) }
            val r = PlayerRecords[a]!!
            assertEquals("Alpha2", r.name)
            assertEquals(listOf("Alpha", "Alpha2"), r.names.keys.toList())
            assertEquals(listOf("10.0.0.1", "10.0.0.2"), r.ips.keys.toList())
            assertEquals("10.0.0.2", r.lastIp)
            assertEquals("minecraft:the_nether", r.spot?.dim)
            assertEquals(120_000L, r.playtimeMs)
            assertEquals(1_000L, r.firstJoin)
            assertEquals(setOf(b), PlayerRecords.alts(r))
            assertEquals("spam", Mutes[b, 1_000]?.reason)
            assertEquals("caps", Mutes[a, 150]?.reason)
            assertNull(Mutes[a, 300])
            SavePoint.run()
            Db.flush()
            Db.close()
            Db.open(db)
            Db.read { Mutes.load(this) }
            assertNull(Mutes[a, 150])
        } finally {
            Db.close()
            world.toFile().deleteRecursively()
        }
    }

    @Test
    fun warnsPersistAndRevoke() {
        val world = Files.createTempDirectory("kami-ess-warn")
        val db = world.resolve("kami").resolve("kami.db")
        try {
            EssMigrations.register()
            Db.open(db)
            Db.read { Warns.load(this) }
            val a = UUID.randomUUID()
            val first = Warns.warn(a, "spam", "Admin", 100, false)
            val second = Warns.warn(a, "grief", "Admin", 200, true)
            Warns.revoke(first.id, Revoke("Owner", 300, "mistake"))
            assertNull(Warns.revoke(first.id, Revoke("Owner", 400, "again")))
            Db.flush()
            Db.close()
            Db.open(db)
            Db.read { Warns.load(this) }
            assertEquals(listOf(second.id, first.id), Warns.of(a).map { it.id })
            assertEquals(1, Warns.active(a))
            assertEquals(Revoke("Owner", 300, "mistake"), Warns[first.id]?.revoke)
            assertEquals(false, Warns[first.id]?.delivered)
            assertEquals(true, Warns[second.id]?.delivered)
            assertEquals(second.id + 1, Warns.warn(a, "caps", "Admin", 500, false).id)
        } finally {
            Db.close()
            world.toFile().deleteRecursively()
        }
    }

    @Test
    fun durationBecomesExpiry() {
        assertEquals(1_000 + 7_200_000L, Moderation.expiry("2h", 1_000))
        assertNull(Moderation.expiry("perm", 1_000))
        assertFailsWith<AdminFailure> { Moderation.expiry("soon", 1_000) }
    }
}
