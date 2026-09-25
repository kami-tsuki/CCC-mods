package kami.libs.command

import com.mojang.brigadier.CommandDispatcher
import kami.libs.chat.Chat
import kami.libs.config.Configs
import kami.libs.config.Jsonc
import net.minecraft.commands.CommandSourceStack

object KamiCommands {
    private class Module(val name: String, val title: String, val build: Node.() -> Unit)

    private val modules = LinkedHashMap<String, Module>()

    @Synchronized
    fun module(name: String, title: String, build: Node.() -> Unit) {
        modules[name] = Module(name, title, build)
    }

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
        }
    }

    private fun overview(ctx: Ctx) {
        ctx.msg { value("Kami mods"); muted("  /kami <mod> <command>, or /<mod> <command>") }
        modules.values.forEach { m -> ctx.row { run(m.name, "/${m.name} help", "Show the ${m.name} commands"); muted("  ${m.title}") } }
    }

    private fun help(ctx: Ctx, dispatcher: CommandDispatcher<CommandSourceStack>, m: Module) {
        ctx.msg { value(m.title) }
        dispatcher.getSmartUsage(dispatcher.root.getChild(m.name), ctx.source).values.filter { it != "help" }.sorted().forEach { usage ->
            val name = "/${m.name} ${usage.substringBefore(' ')}"
            val rest = usage.substringAfter(' ', "")
            ctx.row { suggest(name, "$name ", "Click to type it"); if (rest.isNotEmpty()) muted(" $rest") }
        }
    }

    private fun reload(ctx: Ctx, mod: String?) = Configs.reload(mod).ifEmpty { fail("Nothing to reload.") }.forEach { (name, result) ->
        val chat = Chat.of(name)
        ctx.reply(result.fold({ chat.ok(listOfNotNull("Config reloaded.", it).joinToString(" ")) }, { chat.bad("Reload failed: ${Jsonc.reason(it)}") }), true)
    }
}
