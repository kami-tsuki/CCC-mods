package kami.claims.social

import kami.claims.KamiClaims
import kami.claims.gov.Perm
import kami.claims.gov.PermGroup
import kami.libs.perm.PermissionSet
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.permission.nodes.PermissionNode

object Perms : PermissionSet(KamiClaims.ID) {
    val USE = node("use") { true }
    val FOUND = node("found") { true }
    val CLAIM = node("claim") { true }
    val PLOT = node("plot") { true }
    val JOBS = node("jobs") { true }
    val ADMIN = node("admin") { it?.hasPermissions(2) ?: true }
    val BYPASS = node("bypass") { it?.hasPermissions(2) ?: false }
    val RESEARCH_BYPASS = node("research_bypass") { false }
    val RESEARCH_ADMIN = node("research_admin") { it?.hasPermissions(2) ?: true }

    private val permNodes: Map<Perm, PermissionNode<Boolean>> = Perm.entries.associateWith { node("perm.${it.node}") { true } }
    private val groupNodes: Map<PermGroup, PermissionNode<Boolean>> = PermGroup.entries.associateWith { node("group.${it.name.lowercase()}") { true } }

    fun permitted(p: ServerPlayer, perm: Perm): Boolean = has(p, permNodes.getValue(perm)) && has(p, groupNodes.getValue(perm.group))
}
