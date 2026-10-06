package kami.libs.economy

import kami.libs.db.Legacy
import kami.libs.db.Migration
import kami.libs.db.Session
import kami.libs.db.Verify
import kami.libs.economy.Statement.days
import kami.libs.economy.Statement.movements

internal object StatementMigration {
    private const val FILE = "kami_libs_statement.json"

    val schema = Migration(
        "libs", 1, "schema", legacy = listOf(FILE),
        ddl = listOf(
            "CREATE TABLE libs_statement (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, at INTEGER NOT NULL, amount INTEGER NOT NULL, flow TEXT NOT NULL, counterparty TEXT NOT NULL, item TEXT NOT NULL, qty INTEGER NOT NULL)",
            "CREATE INDEX libs_statement_player_at ON libs_statement (player, at)",
            "CREATE TABLE libs_statement_day (player TEXT NOT NULL, day INTEGER NOT NULL, income INTEGER NOT NULL, expense INTEGER NOT NULL, PRIMARY KEY (player, day))"
        )
    ) { world ->
        val data = Legacy.read(world, FILE, StatementData.serializer()) ?: StatementData()
        batch(
            "INSERT INTO libs_statement (player, at, amount, flow, counterparty, item, qty) VALUES (?, ?, ?, ?, ?, ?, ?)",
            data.books.flatMap { (p, b) -> b.entries.asReversed().map { listOf(p, it.at, it.amount, it.flow.name, it.counterparty, it.item, it.qty) } }
        )
        batch(
            "INSERT INTO libs_statement_day (player, day, income, expense) VALUES (?, ?, ?, ?)",
            data.books.flatMap { (p, b) -> b.days.map { listOf(p, it.day, it.income, it.expense) } }
        )
        return@Migration { verify(data) }
    }

    private fun Session.verify(data: StatementData) {
        val expected = data.books.filterValues { it.entries.isNotEmpty() || it.days.isNotEmpty() }
        val players = query("SELECT player FROM libs_statement UNION SELECT player FROM libs_statement_day") { it.string("player") }.toSet()
        Verify.equal("statement players", expected.keys.sorted(), players.sorted())
        expected.forEach { (p, book) ->
            Verify.sameJson("statement of $p", StatementBook.serializer(), book, StatementBook(movements(p).toMutableList(), days(p).toMutableList()))
        }
        val sums = one("SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS s FROM libs_statement") { it.int("n") to it.long("s") }
        Verify.equal("statement movements (count, sum)", expected.values.sumOf { it.entries.size } to expected.values.sumOf { b -> b.entries.sumOf { it.amount } }, sums)
        val days = one("SELECT COUNT(*) AS n, COALESCE(SUM(income), 0) AS i, COALESCE(SUM(expense), 0) AS e FROM libs_statement_day") { Triple(it.int("n"), it.long("i"), it.long("e")) }
        Verify.equal(
            "statement days (count, income, expense)",
            Triple(expected.values.sumOf { it.days.size }, expected.values.sumOf { b -> b.days.sumOf { it.income } }, expected.values.sumOf { b -> b.days.sumOf { it.expense } }),
            days
        )
    }
}
