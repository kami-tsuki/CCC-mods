package kami.libs.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import kami.libs.KamiLibs
import kami.libs.chat.Chat
import kami.libs.chat.Theme
import kami.libs.text.Phrase
import kami.libs.text.Text
import net.minecraft.network.chat.Component
import kami.libs.config.Configs
import kami.libs.config.Jsonc
import net.minecraft.commands.CommandSourceStack

object KamiCommands {
    private class Module(val name: String, val title: String, val shortcuts: Map<String, String>, val build: Node.() -> Unit) {
        fun titleText() = Phrase.or("kami_libs.command.module.$name", title).component()
    }

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
        ctx.msg { add(Text.msg("kami_libs.command.overview.title"), Theme.VALUE); muted("  "); add(Text.msg("kami_libs.command.overview.usage"), Theme.MUTED) }
        modules.values.forEach { m -> ctx.row { run(Component.literal(m.name), "/${m.name} help", Text.msg("kami_libs.command.module.tooltip", m.name)); muted("  "); add(m.titleText(), Theme.MUTED) } }
    }

    private fun help(ctx: Ctx, dispatcher: CommandDispatcher<CommandSourceStack>, m: Module) {
        ctx.msg { add(m.titleText(), Theme.VALUE) }
        dispatcher.getSmartUsage(dispatcher.root.getChild(m.name), ctx.source).values.filter { it != "help" }.sorted().forEach { usage ->
            val sub = usage.substringBefore(' ')
            val name = if (m.shortcuts[sub] == sub) "/$sub" else "/${m.name} $sub"
            val rest = usage.substringAfter(' ', "")
            ctx.row { suggest(name, "$name ", Text.msg("kami_libs.command.suggest.tooltip")); if (rest.isNotEmpty()) muted(" $rest") }
        }
    }

    private fun reload(ctx: Ctx, mod: String?) = Configs.reload(mod).ifEmpty { fail(Phrase.of("kami_libs.command.reload.nothing")) }.forEach { (name, result) ->
        val chat = Chat.of(name)
        ctx.reply(result.fold(
            { note -> chat.ok(if (note == null) Text.msg("kami_libs.command.reload.done") else Text.msg("kami_libs.command.reload.done_note", note)) },
            { chat.bad(Text.msg("kami_libs.command.reload.failed", Jsonc.reason(it))) }
        ), true)
    }
}
