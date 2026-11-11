package kami.claims.client.admin

import kami.claims.client.app.Vocabulary
import kami.claims.gov.Perm
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Ui
import kami.libs.ui.widget.DiffLine
import kami.libs.ui.widget.diffList
import kami.libs.ui.widget.diffListHeight
import kami.libs.ui.widget.diffOf
import kami.libs.ui.widget.permissionRow
import kami.libs.ui.widget.triState
import kami.libs.ui.widget.triStateToggle

private const val MATRIX_ROW = 14
private const val GROUP_INDENT = 12
private const val DIFF_MIN = 2
private const val DIFF_MAX = 8

internal fun permDiff(current: Set<Perm>, staged: Set<Perm>) = diffOf(current, staged, { Vocabulary.perm(it).label })

internal fun Ui.diffBlock(f: Flow, lines: List<DiffLine>, key: String) = diffList(f.take(diffListHeight(lines.size.coerceIn(DIFF_MIN, DIFF_MAX))), lines, key)

internal fun Ui.permissionMatrix(f: Flow, staged: MutableSet<Perm>, allowed: List<Perm>, enabled: Boolean, reason: String?, key: String) {
    allowed.groupBy { it.group }.forEach { (group, perms) ->
        val state = triState(perms.count { it in staged }, perms.size)
        triStateToggle(f.take(MATRIX_ROW), Vocabulary.group(group).label, state, enabled, reason, key = "$key:group:$group")?.let {
            if (it) staged += perms else staged -= perms.toSet()
        }
        perms.forEach { perm ->
            permissionRow(f.take(MATRIX_ROW).dropLeft(GROUP_INDENT), Vocabulary.perm(perm).label, perm in staged, Vocabulary.risk(perm), lockedReason = reason.takeIf { !enabled }, key = "$key:perm:${perm.name}")?.let {
                if (it) staged += perm else staged -= perm
            }
        }
    }
}
