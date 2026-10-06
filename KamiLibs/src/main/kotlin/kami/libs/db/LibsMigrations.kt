package kami.libs.db

import kami.libs.admin.AdminSchema
import kami.libs.admin.BankRefs
import kami.libs.economy.Coins
import kami.libs.economy.MoneyGuard
import kami.libs.economy.StatementMigration
import kami.libs.economy.Wealth

internal object LibsMigrations {
    fun register() = Migrations.register("libs", listOf(StatementMigration.schema, AdminSchema.migration, BankRefs.migration, Wealth.migration, Coins.migration, MoneyGuard.migration), after = emptyList())
}
