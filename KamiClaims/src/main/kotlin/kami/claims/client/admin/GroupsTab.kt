package kami.claims.client.admin

import kami.claims.client.app.permsOf
import kami.claims.admin.CountryDetail
import kami.claims.admin.CountryRequests
import kami.claims.gov.Perm
import kami.claims.gov.Permissions
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.fieldLabel

internal class GroupsTab(kit: GovernanceKit) : GovernanceTab(kit, "gov-groups") {
    private val staged = Permissions.groupKeys.associate { it.name to mutableSetOf<Perm>() }

    override fun revision(d: CountryDetail): Any? = d.governance.groups

    override fun load(d: CountryDetail) {
        staged.forEach { (group, perms) ->
            perms.clear()
            perms += permsOf(d.governance.groups[group].orEmpty())
        }
    }

    override fun lines(ui: Ui, f: Flow, d: CountryDetail) {
        staged.forEach { (group, perms) ->
            ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.groups.${group.lowercase()}"))
            ui.permissionMatrix(f, perms, Perm.outsiderAllowed.toList(), kit.lock(d) == null, kit.lock(d), "gov-group:$group")
            val diff = permDiff(permsOf(d.governance.groups[group].orEmpty()), perms)
            ui.diffBlock(f, diff, "gov-group-diff:$group")
            kit.button(ui, Row(f.take(CONTROL_H)), d, tr("kami_claims.admin.groups.apply"), Icons.LOCK, CountryRequests.GROUP_PERMS, listOf(group, perms.joinToString(",") { it.name }),
                tr("kami_claims.admin.groups.title", tr("kami_claims.admin.groups.${group.lowercase()}"), d.row.name), tr("kami_claims.admin.gov.nochange").takeIf { diff.isEmpty() })
        }
    }
}
