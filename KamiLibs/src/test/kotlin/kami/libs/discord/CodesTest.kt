package kami.libs.discord

import kami.libs.db.Db
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CodesTest {
    private fun withDb(block: () -> Unit) {
        val root = Files.createTempDirectory("kami-codes")
        try {
            Db.open(root.resolve("kami").resolve("kami.db"))
            Codes.load()
            block()
        } finally {
            Db.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun codeIsSingleUse() = withDb {
        val id = UUID.randomUUID()
        val code = Codes.issue(id, "Alice", 10)
        val pending = Codes.consume(code)
        assertEquals(id, pending?.uuid)
        assertEquals("Alice", pending?.name)
        assertNull(Codes.consume(code))
    }

    @Test
    fun consumeIgnoresCaseAndDashes() = withDb {
        val id = UUID.randomUUID()
        val code = Codes.issue(id, "Alice", 10)
        val loose = code.take(4).lowercase() + "-" + code.drop(4).lowercase()
        assertEquals(id, Codes.consume(loose)?.uuid)
    }

    @Test
    fun reissueForTheSamePlayerKeepsTheCode() = withDb {
        val id = UUID.randomUUID()
        val first = Codes.issue(id, "Alice", 10)
        assertEquals(first, Codes.issue(id, "Alice", 10))
        assertNotEquals(first, Codes.issue(UUID.randomUUID(), "Bob", 10))
    }

    @Test
    fun expiredOrUnknownCodesAreRejected() = withDb {
        val code = Codes.issue(UUID.randomUUID(), "Alice", 0)
        assertNull(Codes.consume(code))
        assertNull(Codes.consume("NOSUCHCD"))
        Codes.restore("OLDCODE1", Pending(UUID.randomUUID(), "Old", System.currentTimeMillis() - 1000))
        assertNull(Codes.consume("OLDCODE1"))
    }
}
