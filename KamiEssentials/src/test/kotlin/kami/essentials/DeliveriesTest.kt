package kami.essentials

import kami.essentials.trade.Deliveries
import kami.essentials.trade.away
import kami.libs.db.Db
import kami.libs.db.SavePoint
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeliveriesTest {
    @Test
    fun leaversGetTheirOfferDelivered() {
        assertTrue(away(disconnected = true, alive = true))
        assertTrue(away(disconnected = false, alive = false))
        assertFalse(away(disconnected = false, alive = true))
    }

    @Test
    fun deliveriesSurviveRestartUntilTaken() {
        val world = Files.createTempDirectory("kami-ess")
        try {
            EssMigrations.register()
            val db = world.resolve("kami").resolve("kami.db")
            val id = UUID.randomUUID()
            Db.open(db)
            Deliveries.load()
            Deliveries.put(id, listOf("{a}", "{b}"))
            SavePoint.run()
            Db.close()
            Db.open(db)
            Deliveries.load()
            assertEquals(listOf("{a}", "{b}"), Deliveries.take(id))
            assertEquals(emptyList(), Deliveries.take(id))
            SavePoint.run()
            Db.close()
            Db.open(db)
            Deliveries.load()
            assertEquals(emptyList(), Deliveries.take(id))
        } finally {
            Db.close()
            Deliveries.unload()
            world.toFile().deleteRecursively()
        }
    }
}
