package kami.libs.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SavePointWatermarkTest {
    private fun insert(note: String) = Db.write {
        exec("INSERT INTO libs_journal (at, party, delta, flow, counterparty, note, ref) VALUES (?, ?, ?, ?, ?, ?, ?)", 1L, "p", 1L, "F", null, note, "r")
    }

    private fun notes() = Db.read(fresh = true) { query("SELECT note FROM libs_journal ORDER BY id") { it.string("note") } }

    @Test
    fun rowsAfterTheSavePointAreDiscardedOnReopen() {
        val root = Files.createTempDirectory("kami-savepoint")
        try {
            val file = root.resolve("kami").resolve("kami.db")
            Db.open(file)
            insert("before")
            SavePoint.run()
            insert("after")
            Db.flush()
            assertEquals(listOf("before", "after"), notes())
            Db.close()
            Db.open(file)
            assertEquals(listOf("before"), notes())
        } finally {
            Db.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun firstBootDiscardsNothing() {
        val root = Files.createTempDirectory("kami-savepoint")
        try {
            val file = root.resolve("kami").resolve("kami.db")
            Db.open(file)
            insert("unsaved")
            Db.flush()
            Db.close()
            Db.open(file)
            assertEquals(listOf("unsaved"), notes())
        } finally {
            Db.close()
            root.toFile().deleteRecursively()
        }
    }
}
