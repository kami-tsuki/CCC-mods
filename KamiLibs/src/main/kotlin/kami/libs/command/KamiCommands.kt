package kami.libs.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.tree.CommandNode
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
            lit("kami").does { overview(it) }
                .then(lit("help").does { overview(it) })
                .then(lit("reload").requires(op).does { reload(it, null) })
        )
        nodes.forEach {
            kami.addChild(it)
            dispatcher.root.addChild(it)
        }
    }

    private fun overview(ctx: Ctx) {
        ctx.say("§6Kami mods §7use §f/kami <mod> <command>§7, or §f/<mod> <command>§7 for short.")
        modules.values.forEach { ctx.say("§f/${it.name} §7${it.title}") }
        ctx.say("§7Try §f/<mod> help§7 for a mod's commands.")
    }

    private fun help(ctx: Ctx, dispatcher: CommandDispatcher<CommandSourceStack>, m: Module) {
        val node: CommandNode<CommandSourceStack> = dispatcher.root.getChild(m.name) ?: return
        ctx.say("§6/${m.name} §7${m.title}")
        dispatcher.getSmartUsage(node, ctx.source).values.filter { it != "help" }.sorted().forEach { ctx.say("§f/${m.name} $it") }
    }

    private fun reload(ctx: Ctx, mod: String?) {
        val results = Configs.reload(mod)
        if (results.isEmpty()) fail("Nothing to reload.")
        results.forEach { (name, result) ->
            ctx.say(result.fold({ "§a✔ $name §7${it ?: "reloaded"}" }, { "§c✘ $name §7${Jsonc.reason(it)}" }), true)
        }
    }
}
