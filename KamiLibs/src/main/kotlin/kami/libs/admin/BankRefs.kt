package kami.libs.admin

import kami.libs.db.Migration

internal object BankRefs {
    val migration = Migration(
        "libs", 3, "bank_once",
        ddl = listOf(
            "CREATE TABLE libs_bank_once (ref TEXT PRIMARY KEY, at INTEGER NOT NULL) WITHOUT ROWID",
            "CREATE INDEX libs_bank_once_at ON libs_bank_once (at)"
        )
    )
}
