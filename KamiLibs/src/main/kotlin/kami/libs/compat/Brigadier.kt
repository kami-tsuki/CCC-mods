package kami.libs.compat

import com.mojang.brigadier.tree.CommandNode
import kami.libs.KamiLibs
import java.lang.reflect.Field
import java.util.function.Predicate

object Brigadier {
    private val children by lazy { field("children") }
    private val literals by lazy { field("literals") }
    private val requirement by lazy { field("requirement") }

    private fun field(name: String): Field? = runCatching { CommandNode::class.java.getDeclaredField(name).apply { isAccessible = true } }
        .onFailure { KamiLibs.LOG.warn("Brigadier CommandNode.{} is not reachable: {}", name, it.toString()) }.getOrNull()

    fun remove(node: CommandNode<*>, name: String): Boolean = runCatching {
        listOf(children, literals).forEach { f -> (checkNotNull(f).get(node) as MutableMap<*, *>).remove(name) }
    }.isSuccess

    fun <S> require(node: CommandNode<S>, test: Predicate<S>): Boolean = runCatching { checkNotNull(requirement).set(node, test) }.isSuccess
}
