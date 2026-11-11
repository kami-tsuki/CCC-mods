package kami.claims.client.admin

import kami.claims.admin.CountryDetail
import kami.claims.client.app.Vocabulary
import kami.claims.net.AuditLine
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.table

internal class AuditTab {
    private val columnsMemo = Memo()
    private val state = TableState<AuditLine>()

    fun draw(ui: Ui, r: Rect, d: CountryDetail) {
        ui.table(r, columns(), d.governance.audit, state, { it.at to it.action to it.target }, emptyText = tr("kami_libs.table.empty"), key = "country-audit")
    }

    private fun columns() = columnsMemo.of(Format.locale) { listOf(
        Column.number<AuditLine>(tr("kami_libs.common.time"), 60, format = { Format.ago(it) }) { it.at },
        Column.text<AuditLine>(tr("kami_claims.admin.col.actor"), 80) { it.actor },
        Column.text<AuditLine>(tr("kami_claims.admin.col.action"), 90) { Vocabulary.audit(it.action) },
        Column.text<AuditLine>(tr("kami_claims.admin.col.target"), 80) { it.target },
        Column.text<AuditLine>(tr("kami_claims.admin.col.detail"), -1) { it.detail }
    ) }
}
