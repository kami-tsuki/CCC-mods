package kami.essentials.client.admin

import kami.libs.stats.Staff
import kami.libs.ui.app.Page
import kami.libs.ui.app.StatBoard
import kami.libs.ui.app.TimeRange
import kami.libs.ui.app.statPage
import kami.libs.ui.app.topCard
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.KpiTile

internal class StaffPage : Page() {
    override val title get() = tr("kami_essentials.admin.nav.staff")
    private val range = TimeRange("staff:range")
    private val board = StatBoard(range)
    private val metrics = StatMetrics(board, "", "staff", Staff.PREFIX)
    private val staff = board.distinct(Staff.PREFIX + "session.time")
    private val issued = board.total(Staff.PREFIX + "command")
    private val time = board.top(Staff.PREFIX + "session.time")
    private val issuers = board.top(Staff.PREFIX + "command")
    private val commands = board.top(Staff.PREFIX + "command", "o")

    override fun draw(ui: Ui, r: Rect) {
        val head = listOf(
            KpiTile(tr("kami_essentials.admin.staff.members"), Format.number(board[staff].total.toLong()), Icons.SHIELD),
            KpiTile(tr("kami_essentials.admin.staff.issued"), Format.number(board[issued].total.toLong()), Icons.SCROLL)
        )
        val tops = listOf(
            board.topCard(tr("kami_essentials.admin.staff.time"), Icons.CLOCK, time) { Format.duration(it) },
            board.topCard(tr("kami_essentials.admin.staff.issuers"), Icons.PEOPLE, issuers),
            board.topCard(tr("kami_essentials.admin.staff.commands"), Icons.SCROLL, commands)
        )
        ui.statPage("staff", r, range, board, head + metrics.tiles(), tops + metrics.charts() + metrics.objects())
    }
}
