package kami.libs.admin

import kami.libs.db.Db
import kami.libs.economy.Flow
import kami.libs.economy.Journal
import kami.libs.economy.Party
import kami.libs.economy.PlayerParty
import kami.libs.economy.ServerBank
import kami.libs.economy.ServerParty
import kotlinx.serialization.json.JsonPrimitive

internal object AdminViews {
    fun register() {
        Admin.register("admin.level", AdminPerms.Level.NONE, false) { ctx, _ -> JsonPrimitive(ctx.level.name) }
        Admin.view("audit.query", async = true) { ctx, a ->
            val filter = Audit.Filter(a.text(0), a.text(1), a.text(2), a.long(3), a.long(4), a.text(5)?.toBooleanStrictOrNull())
            Db.encode(Page.serializer(Audit.Entry.serializer()), Audit.query(filter, a.page(6), level = ctx.level))
        }
        StatsViews.register()
        Admin.view("bank.summary") { _, _ -> Db.encode(ServerBank.Summary.serializer(), ServerBank.summary()) }
        Admin.view("bank.movements", async = true) { _, a -> journal(ServerParty, a) }
        Admin.view("journal.query", async = true) { ctx, a -> journal(a.text(0)?.let { raw -> ctx.onServer { party(ctx, raw) } }, a.drop(1)) }
    }

    private fun journal(party: Party?, a: List<String>) = Db.encode(
        Page.serializer(Journal.Entry.serializer()),
        Journal.query(Journal.Filter(party, flows(a.text(0)), a.long(1), a.long(2), a.long(3), a.long(4), a.text(5)), a.page(6))
    )

    private fun flows(raw: String?) = raw?.split(',')?.mapNotNull { f -> Flow.entries.firstOrNull { it.name == f.trim().uppercase() } }?.toSet().orEmpty()

    private fun party(ctx: AdminCtx, raw: String): Party = Party.parse(raw)
        ?: Players.resolve(ctx.player.server, raw)?.let(::PlayerParty)
        ?: ctx.fail("kami_libs.admin.unknown_party", raw)
}
