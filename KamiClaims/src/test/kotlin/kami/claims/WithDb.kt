package kami.claims

import kami.libs.db.Db
import kami.libs.economy.ServerBank
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

abstract class WithDb {
    private lateinit var dbDir: Path

    @BeforeTest
    fun openBankDb() {
        RealmDb.register()
        dbDir = Files.createTempDirectory("kami-claims-db")
        Db.open(dbDir.resolve("world").resolve("kami").resolve("kami.db"))
        ServerBank.load()
    }

    @AfterTest
    fun closeBankDb() {
        RealmDb.unload()
        Db.close()
        dbDir.toFile().deleteRecursively()
    }
}
