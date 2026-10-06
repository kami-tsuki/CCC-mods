package kami.libs.admin

import kami.libs.db.Migration

internal object AdminSchema {
    val migration = Migration(
        "libs", 2, "admin",
        ddl = listOf(
            "CREATE TABLE libs_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL, actor_name TEXT NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL, reason TEXT NOT NULL, args TEXT NOT NULL, ok INTEGER NOT NULL)",
            "CREATE INDEX libs_audit_at ON libs_audit (at)",
            "CREATE INDEX libs_audit_actor_at ON libs_audit (actor, at)",
            "CREATE INDEX libs_audit_target_at ON libs_audit (target, at)",
            "CREATE INDEX libs_audit_action_at ON libs_audit (action, at)",
            "CREATE TRIGGER libs_audit_no_update BEFORE UPDATE ON libs_audit BEGIN SELECT RAISE(ABORT, 'libs_audit is insert-only'); END",
            "CREATE TRIGGER libs_audit_no_delete BEFORE DELETE ON libs_audit BEGIN SELECT RAISE(ABORT, 'libs_audit is insert-only'); END",
            "CREATE TABLE libs_bank (id INTEGER PRIMARY KEY CHECK (id = 1), balance INTEGER NOT NULL, revision INTEGER NOT NULL)",
            "INSERT INTO libs_bank (id, balance, revision) VALUES (1, 0, 0)",
            "CREATE TABLE libs_bank_day (day INTEGER NOT NULL, flow TEXT NOT NULL, inflow INTEGER NOT NULL, outflow INTEGER NOT NULL, PRIMARY KEY (day, flow))",
            "CREATE TABLE libs_journal (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, party TEXT NOT NULL, delta INTEGER NOT NULL, flow TEXT NOT NULL, counterparty TEXT, note TEXT NOT NULL, ref TEXT NOT NULL)",
            "CREATE INDEX libs_journal_party_at ON libs_journal (party, at)",
            "CREATE INDEX libs_journal_flow_at ON libs_journal (flow, at)",
            "CREATE INDEX libs_journal_at ON libs_journal (at)",
            "CREATE INDEX libs_journal_ref ON libs_journal (ref)"
        )
    )
}
