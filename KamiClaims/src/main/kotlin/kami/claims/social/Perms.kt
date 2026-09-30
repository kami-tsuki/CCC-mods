package kami.claims.social

import kami.claims.Cap
import kami.claims.KamiClaims
import kami.libs.perm.PermissionSet

import net.neoforged.neoforge.server.permission.nodes.PermissionNode

object Perms : PermissionSet(KamiClaims.ID) {
    val USE = node("use") { true }
    val FOUND = node("found") { true }
    val CLAIM = node("claim") { true }
    val PLOT = node("plot") { true }
    val JOBS = node("jobs") { true }
    val ADMIN = node("admin") { it?.hasPermissions(2) ?: true }
    val BYPASS = node("bypass") { it?.hasPermissions(2) ?: false }

    private val capNodes: Map<Cap, PermissionNode<Boolean>> = Cap.values().associateWith { node("cap.${it.name.lowercase()}") { true } }
    fun capNode(cap: Cap): PermissionNode<Boolean> = capNodes.getValue(cap)
}
