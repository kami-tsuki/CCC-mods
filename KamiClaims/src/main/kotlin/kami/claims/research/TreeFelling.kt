package kami.claims.research

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * Counts felled trees for `fell` tasks. With Dynamic Trees every natural tree is made of branch blocks and one
 * broken branch fells the tree, so a player breaking a branch counts as one tree. Branch breaks of the same
 * player close together in a short time count once, so chopping one tree piece by piece is still one tree.
 */
object TreeFelling {
    private const val SAME_TREE_TICKS = 100L
    private const val SAME_TREE_DIST = 8.0
    private const val BRANCH_CLASS = "com.dtteam.dynamictrees.block.branch.BranchBlock"
    private val LOG = LogUtils.getLogger()
    private val branches: TagKey<Block> by lazy { TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("dynamictrees", "branches")) }

    private class Fell(val player: UUID, val dim: String, val pos: BlockPos, val tick: Long)

    private val recent = ArrayList<Fell>()

    /** A Dynamic Trees branch, by tag or (for branches outside the tag) by class. */
    fun isTree(state: BlockState): Boolean = state.`is`(branches) || isBranchClass(state.block.javaClass)

    private fun isBranchClass(type: Class<*>): Boolean {
        var c: Class<*>? = type
        while (c != null) {
            if (c.name == BRANCH_CLASS) return true
            c = c.superclass
        }
        return false
    }

    /** Called for a player breaking a tree block; reports one felled tree unless it is the same tree as a moment ago. */
    fun broke(player: ServerPlayer, pos: BlockPos, report: (ServerPlayer) -> Unit) {
        val level = player.serverLevel()
        val now = level.gameTime
        val dim = level.dimension().location().toString()
        recent.removeIf { now - it.tick > SAME_TREE_TICKS }
        val same = recent.any { it.player == player.uuid && it.dim == dim && it.pos.distSqr(pos) <= SAME_TREE_DIST * SAME_TREE_DIST }
        recent += Fell(player.uuid, dim, pos.immutable(), now)
        if (same) return
        LOG.info("[KamiClaims] {} felled a tree at {} in {}", player.gameProfile.name, pos.toShortString(), dim)
        report(player)
    }

    fun reset() = recent.clear()
}
