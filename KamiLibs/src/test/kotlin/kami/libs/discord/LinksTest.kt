package kami.libs.discord

import kami.libs.db.Db
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinksTest {
    private fun withDb(block: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("kami-links")
        try {
            val file = root.resolve("kami").resolve("kami.db")
            Db.open(file)
            Links.load()
            block(file)
        } finally {
            Db.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun link(uuid: UUID, name: String, discordId: Long) = Link(uuid.toString(), name, discordId, "d$discordId", 1000L + discordId)

    @Test
    fun linkingAnotherPlayerToTheSameDiscordAccountDisplacesTheOldLink() = withDb {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertTrue(Links.put(link(a, "Alice", 1)))
        assertTrue(Links.put(link(b, "Bob", 1)))
        assertNull(Links.get(a))
        assertEquals(b.toString(), Links.byDiscord(1)?.uuid)
    }

    @Test
    fun removeAllClearsBothIndexesAndRenameUpdatesNameLookup() = withDb {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        Links.put(link(a, "Alice", 1))
        Links.put(link(b, "Bob", 2))
        Links.rename(a, "Alicia")
        assertEquals(emptyList(), Links.byName("alice"))
        assertEquals(a.toString(), Links.byName("ALICIA").single().uuid)
        assertEquals(listOf(b.toString()), Links.removeAll(listOf(b)).map { it.uuid })
        assertNull(Links.byDiscord(2))
        assertEquals(emptyList(), Links.byName("bob"))
    }

    @Test
    fun linksSurviveReopeningTheDatabase() = withDb { file ->
        val a = UUID.randomUUID()
        Links.put(link(a, "Alice", 1))
        Db.flush()
        Db.close()
        Db.open(file)
        Links.load()
        assertEquals(listOf(link(a, "Alice", 1)), Links.all())
    }
}
