package kami.economy.economy

import kami.economy.db.EcoDb
import kami.economy.db.intentTable
import kami.libs.db.Db
import kami.libs.db.SavePoint
import java.nio.file.Files
import java.nio.file.Path

object TestDb {
    private var world: Path? = null

    private val file get() = checkNotNull(world).resolve("kami").resolve("kami.db")

    fun open() {
        close()
        EcoDb.register()
        world = Files.createTempDirectory("kami-eco")
        Db.open(file)
        Ledger.attach()
    }

    fun restart() {
        SavePoint.run()
        Db.flush()
        Db.close()
        Db.open(file)
        Ledger.attach()
    }

    fun close() {
        Ledger.detach()
        Db.close()
        world?.toFile()?.deleteRecursively()
        world = null
    }

    fun wal(vararg intents: Intent) {
        Db.write {
            exec("DELETE FROM eco_intent")
            intentTable.insert(this, intents.toList())
        }
        Db.flush()
    }

    fun states(): List<String> = Db.read(fresh = true) { query("SELECT state FROM eco_intent") { it.string("state") } }
}
