package kami.libs.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import kami.libs.KamiLibs
import kami.libs.chat.Chat
import kami.libs.config.Configs
import kami.libs.config.Jsonc
import net.minecraft.commands.CommandSourceStack

object KamiCommands {
    private class Module(val name: String, val title: String, val shortcuts: Map<String, String>, val build: Node.() -> Unit)

    private val modules = LinkedHashMap<String, Module>()
    private val owners = HashMap<String, String>()

    @Synchronized
    fun module(name: String, title: String, shortcuts: Map<String, String> = emptyMap(), build: Node.() -> Unit) {
        modules[name] = Module(name, title, shortcuts, build)
        shortcuts.keys.forEach { owners[it] = name }
    }

    fun owner(command: String): String = owners[command] ?: command

    @Synchronized
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val kami = dispatcher.register(
            lit("kami").does(::overview)
                .then(lit("help").does(::overview))
                .then(lit("reload").requires(op).does { reload(it, null) })
        )
        modules.values.forEach { m ->
            val node = lit(m.name).does { help(it, dispatcher, m) }.then(lit("help").does { help(it, dispatcher, m) })
            if (m.name in Configs.mods) node.then(lit("reload").requires(op).does { reload(it, m.name) })
            val built = node.apply(m.build).build()
            kami.addChild(built)
            dispatcher.root.addChild(built)
            m.shortcuts.forEach { (alias, child) -> built.getChild(child)?.let { shortcut(dispatcher, alias, it) } }
        }
    }

    private fun shortcut(dispatcher: CommandDispatcher<CommandSourceStack>, alias: String, target: CommandNode<CommandSourceStack>) {
        val old = dispatcher.root.getChild(alias)
        if (old != null && !drop(dispatcher.root, alias)) return
        val node = lit(alias).requires { target.requirement.test(it) || old?.requirement?.test(it) == true }.executes(target.command)
        target.children.forEach(node::then)
        old?.children?.filter { it is LiteralCommandNode && target.getChild(it.name) == null }?.forEach { c ->
            val guarded = c.createBuilder().requires { old.requirement.test(it) && c.requirement.test(it) }
            if (c.redirect == null) c.children.forEach(guarded::then)
            node.then(guarded)
        }
        dispatcher.root.addChild(node.build())
    }

    private fun drop(node: CommandNode<CommandSourceStack>, name: String) = runCatching {
        listOf("children", "literals").forEach { f ->
            (CommandNode::class.java.getDeclaredField(f).apply { isAccessible = true }.get(node) as MutableMap<*, *>).remove(name)
        }
    }.onFailure { KamiLibs.LOG.warn("Could not replace /{}, the vanilla command stays", name, it) }.isSuccess

    private fun overview(ctx: Ctx) {
        ctx.msg { value("Kami mods"); muted("  /kami <mod> <command>, or /<mod> <command>") }
        modules.values.forEach { m -> ctx.row { run(m.name, "/${m.name} help", "Show the ${m.name} commands"); muted("  ${m.title}") } }
    }

    private fun help(ctx: Ctx, dispatcher: CommandDispatcher<CommandSourceStack>, m: Module) {
        ctx.msg { value(m.title) }
        dispatcher.getSmartUsage(dispatcher.root.getChild(m.name), ctx.source).values.filter { it != "help" }.sorted().forEach { usage ->
            val sub = usage.substringBefore(' ')
            val name = if (m.shortcuts[sub] == sub) "/$sub" else "/${m.name} $sub"
            val rest = usage.substringAfter(' ', "")
            ctx.row { suggest(name, "$name ", "Click to type it"); if (rest.isNotEmpty()) muted(" $rest") }
        }
    }

    private fun reload(ctx: Ctx, mod: String?) = Configs.reload(mod).ifEmpty { fail("Nothing to reload.") }.forEach { (name, result) ->
        val chat = Chat.of(name)
        ctx.reply(result.fold({ chat.ok(listOfNotNull("Config reloaded.", it).joinToString(" ")) }, { chat.bad("Reload failed: ${Jsonc.reason(it)}") }), true)
    }
}
