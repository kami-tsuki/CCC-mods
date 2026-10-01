package kami.claims.research

import kami.claims.Realm
import kami.libs.claims.ClaimsApi
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import java.util.function.Supplier

class ContextStack(private val resolve: (source: Any, pos: Long) -> String?) {
    private var sources = arrayOfNulls<Any>(8)
    private var positions = LongArray(8)
    private var countries = arrayOfNulls<String>(8)
    private var resolved = BooleanArray(8)

    var depth = 0
        private set

    val active get() = depth > 0

    fun push(source: Any, pos: Long = 0) {
        if (depth == sources.size) grow()
        sources[depth] = source
        positions[depth] = pos
        countries[depth] = null
        resolved[depth] = false
        depth++
    }

    fun pop() {
        if (depth == 0) return
        depth--
        sources[depth] = null
        countries[depth] = null
    }

    fun country(): String? {
        val top = depth - 1
        if (top < 0) return null
        if (!resolved[top]) {
            countries[top] = resolve(sources[top]!!, positions[top])
            resolved[top] = true
        }
        return countries[top]
    }

    private fun grow() {
        val size = sources.size * 2
        sources = sources.copyOf(size)
        positions = positions.copyOf(size)
        countries = countries.copyOf(size)
        resolved = resolved.copyOf(size)
    }
}

object RecipeContext {
    @Volatile
    private var serverThread: Thread? = null
    private val stack = ContextStack(::resolve)

    @JvmStatic
    fun bind(thread: Thread?) {
        serverThread = thread
    }

    @JvmStatic
    val active get() = stack.active && Thread.currentThread() === serverThread

    @JvmStatic
    fun push(source: Any, pos: Long): Boolean {
        if (Thread.currentThread() !== serverThread) return false
        stack.push(source, pos)
        return true
    }

    @JvmStatic
    fun pop(pushed: Boolean) {
        if (pushed) stack.pop()
    }

    @JvmStatic
    fun wrap(player: ServerPlayer, task: Runnable) = Runnable {
        val pushed = push(player, 0)
        try {
            task.run()
        } finally {
            pop(pushed)
        }
    }

    @JvmStatic
    fun <T> wrap(player: ServerPlayer, task: Supplier<T>) = Supplier {
        val pushed = push(player, 0)
        try {
            task.get()
        } finally {
            pop(pushed)
        }
    }

    @JvmStatic
    fun countryId(): String? = if (active) stack.country() else null

    @JvmStatic
    fun allowed(recipe: ResourceLocation): Boolean {
        if (!active || Research.allowed(null, recipe)) return true
        return Research.allowed(Realm.country(stack.country()), recipe)
    }

    @JvmStatic
    fun allowedBlock(block: ResourceLocation): Boolean {
        if (!active || Research.allowedBlock(null, block)) return true
        return Research.allowedBlock(Realm.country(stack.country()), block)
    }

    private fun resolve(source: Any, pos: Long): String? = when (source) {
        is ServerPlayer -> Realm.of(source.stringUUID)?.id
        is Level -> ClaimsApi.countryAt(source, BlockPos.of(pos))
        is Entity -> ClaimsApi.countryAt(source.level(), source.blockPosition())
        else -> null
    }
}
