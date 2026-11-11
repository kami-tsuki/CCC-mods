package kami.claims.client.app.pages

import kami.claims.client.ClientClaims
import kami.claims.client.map.ChunkCells
import kami.claims.client.app.runway
import kami.claims.client.app.ClaimsPage
import kami.claims.gov.Perm
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Plan(val kind: String?, private val perms: List<Perm>) {
    CLAIM("claim", listOf(Perm.CLAIM, Perm.SPEND)), RETYPE("type", listOf(Perm.CHUNK_TYPE)), UNCLAIM("unclaim", listOf(Perm.UNCLAIM)), SECTOR(null, listOf(Perm.SECTORS));

    fun lock(page: ClaimsPage) = perms.firstNotNullOfOrNull(page::lock)
}

object MapModel {
    private const val BLOCK_LIMIT = 3000

    fun connected(cells: Set<Long>): Boolean {
        val start = cells.firstOrNull() ?: return true
        val seen = hashSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val (x, z) = ChunkCells.cell(queue.removeFirst())
            ChunkCells.SIDES.forEach { (dx, dz) ->
                val next = ClientClaims.key(x + dx, z + dz)
                if (next in cells && seen.add(next)) queue.addLast(next)
            }
        }
        return seen.size == cells.size
    }

    fun jump(text: String): Pair<Int, Int>? {
        val nums = text.split(' ', ',', ';').mapNotNull { it.trim().toIntOrNull() }
        if (nums.size < 2) return null
        val blocks = abs(nums[0]) > BLOCK_LIMIT || abs(nums[1]) > BLOCK_LIMIT || text.contains("b")
        return if (blocks) Math.floorDiv(nums[0], 16) to Math.floorDiv(nums[1], 16) else nums[0] to nums[1]
    }

    fun area(x0: Int, z0: Int, x1: Int, z1: Int, key: (Int, Int) -> Long): List<Long> =
        (min(x0, x1)..max(x0, x1)).flatMap { x -> (min(z0, z1)..max(z0, z1)).map { z -> key(x, z) } }

    fun size(x0: Int, z0: Int, x1: Int, z1: Int) = (abs(x1 - x0) + 1) * (abs(z1 - z0) + 1)

    fun bounds(cells: Collection<Long>): IntArray {
        val points = cells.map(ChunkCells::cell)
        val xs = points.map { it.first }
        val zs = points.map { it.second }
        return intArrayOf(xs.min(), zs.min(), xs.max(), zs.max())
    }

    fun choose(current: Plan, owned: List<Boolean?>): Plan = when {
        current == Plan.SECTOR -> current
        owned.any { it == null } -> Plan.CLAIM
        owned.any { it == true } && current == Plan.CLAIM -> Plan.RETYPE
        else -> current
    }

    fun args(plan: Plan, type: String, cells: Collection<Long>): Array<String> {
        val joined = cells.joinToString(",") { k -> ChunkCells.cell(k).let { "${it.first}:${it.second}" } }
        return if (plan == Plan.UNCLAIM) arrayOf("cells", joined) else arrayOf(type, "cells", joined)
    }

    fun runwayAfter(treasury: Long, cost: Long, net: Long) = runway(treasury - cost, net) ?: Long.MAX_VALUE
}
