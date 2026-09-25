package kami.libs.command

import com.mojang.brigadier.CommandDispatcher
import kami.libs.chat.Chat
import kami.libs.config.Configs
import kami.libs.config.Jsonc
import net.minecraft.commands.CommandSourceStack

object KamiCommands {
    class Module(val name: String, val title: String, val build: Node.() -> Unit)

    private val modules = LinkedHashMap<String, Module>()

    @Synchronized
    fun module(name: String, title: String, build: Node.() -> Unit) {
        modules[name] = Module(name, title, build)
    }

    @Synchronized
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val nodes = modules.values.map { m ->
            val node = lit(m.name).does { help(it, dispatcher, m) }
            node.then(lit("help").does { help(it, dispatcher, m) })
            if (m.name in Configs.mods) node.then(lit("reload").requires(op).does { reload(it, m.name) })
            m.build(node)
            node.build()
        }
        val kami = dispatcher.register(
            lit("kami").does(::overview)
                .then(lit("help").does(::overview))
                .then(lit("reload").requires(op).does { reload(it, null) })
        )
        nodes.forEach {
            kami.addChild(it)
            dispatcher.root.addChild(it)
        }
    }

    private fun overview(ctx: Ctx) {
        ctx.msg { value("Kami mods"); muted("  /kami <mod> <command>, or /<mod> <command>") }
        modules.values.forEach { m -> ctx.row { run(m.name, "/${m.name} help", "Show the ${m.name} commands"); muted("  ${m.title}") } }
    }

    private fun help(ctx: Ctx, dispatcher: CommandDispatcher<CommandSourceStack>, m: Module) {
        val node = dispatcher.root.getChild(m.name) ?: return
        ctx.msg { value(m.title) }
        dispatcher.getSmartUsage(node, ctx.source).values.filter { it != "help" }.sorted().forEach { usage ->
            val name = usage.substringBefore(' ')
            val rest = usage.substringAfter(' ', "")
            ctx.row { suggest("/${m.name} $name", "/${m.name} $name ", "Click to type it"); if (rest.isNotEmpty()) muted(" $rest") }
        }
    }

    private fun reload(ctx: Ctx, mod: String?) {
        val results = Configs.reload(mod)
        if (results.isEmpty()) fail("Nothing to reload.")
        results.forEach { (name, result) ->
            val chat = Chat.of(name)
            ctx.reply(result.fold({ chat.ok("Config reloaded.${it?.let { d -> " $d" }.orEmpty()}") }, { chat.bad("Reload failed: ${Jsonc.reason(it)}") }), true)
        }
    }
}
