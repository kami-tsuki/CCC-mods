package kami.essentials.chat

import kami.libs.text.Text
import kami.libs.text.Phrase
import com.mojang.authlib.GameProfile
import kami.essentials.Config
import kami.essentials.display.Tab
import kami.essentials.inv.Views
import kami.libs.chat.Theme
import kami.libs.chat.inline.InlineChat
import kami.libs.command.fail
import kami.libs.economy.Coins
import kami.libs.economy.Numismatics
import kami.libs.menu.ItemPeek
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object InlineFeatures {
    private enum class Type { ITEM, INVENTORY, ENDER_CHEST }

    private class Action(
        val type: Type,
        val owner: UUID,
        val ownerName: String,
        val stack: ItemStack,
        val expiresAt: Long,
    )

    private val actions = ConcurrentHashMap<String, Action>()
    private val keyChars = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun render(sender: ServerPlayer, text: String): Component {
        val g = Config.s.inlineGeneral
        val style = Config.s.inlineStyle
        if (!g.enabled) return legacyBody(text)
        val aliases = aliasIndex()
        var used = 0
        return InlineChat.render(
            text,
            plain = { Component.literal(it).withColor(style.textColor) },
            link = { link(it, style.linkColor) },
            inline = { raw ->
                if (used >= g.maxTokensPerMessage) return@render null
                val token = aliases[raw.lowercase(Locale.ROOT)] ?: return@render null
                used++
                token(sender)
            }
        )
    }

    fun open(viewer: ServerPlayer, key: String) {
        val now = System.currentTimeMillis()
        val action = actions.remove(key)?.takeIf { it.expiresAt > now } ?: fail(Phrase.of("kami_essentials.inline.expired"))
        val profile = GameProfile(action.owner, action.ownerName)
        when (action.type) {
            Type.ITEM -> ItemPeek.open(viewer, Text.msg("kami_essentials.inline.item_of", action.ownerName), action.stack)
            Type.INVENTORY -> Views.inventory(viewer, profile, false)
            Type.ENDER_CHEST -> Views.enderchest(viewer, profile, false)
        }
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        actions.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun aliasIndex(): Map<String, (ServerPlayer) -> Component> {
        val t = Config.s.inlineTokens
        return buildMap {
            t.item.forEach { put(it, ::itemToken) }
            t.inventory.forEach { put(it, ::inventoryToken) }
            t.enderChest.forEach { put(it, ::enderChestToken) }
            t.health.forEach { put(it, ::healthToken) }
            t.hunger.forEach { put(it, ::hungerToken) }
            t.armor.forEach { put(it, ::armorToken) }
            t.xp.forEach { put(it, ::xpToken) }
            t.level.forEach { put(it, ::levelToken) }
            t.pos.forEach { put(it, ::posToken) }
            t.balance.forEach { put(it, ::balanceToken) }
            t.country.forEach { put(it, ::countryToken) }
            t.rank.forEach { put(it, ::rankToken) }
            t.ping.forEach { put(it, ::pingToken) }
            t.tps.forEach { put(it, ::tpsToken) }
        }
    }

    private fun itemToken(sender: ServerPlayer): Component {
        val stack = sender.mainHandItem
        val label = if (stack.isEmpty) {
            Text.msg("kami_essentials.inline.air").withColor(Config.s.inlineStyle.valueColor)
        } else {
            val out = Component.empty()
            out.append(Component.literal("◈ ").withColor(Theme.VALUE))
            out.append(itemName(stack).copy().withStyle { it.withColor(Config.s.inlineStyle.valueColor) })
            if (stack.count > 1) out.append(Component.literal(" x ${stack.count}").withColor(Config.s.inlineStyle.valueColor))
            out
        }
        val hover = if (stack.isEmpty) {
            HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.msg("kami_essentials.inline.empty_hand").withColor(Config.s.inlineStyle.textColor))
        } else {
            HoverEvent(HoverEvent.Action.SHOW_ITEM, HoverEvent.ItemStackInfo(stack))
        }
        val click = if (!stack.isEmpty && Config.s.inlineGeneral.itemViewer) ClickEvent(ClickEvent.Action.RUN_COMMAND, "/essentials inline open ${register(Type.ITEM, sender, stack.copy())}") else null
        val note = Phrase.of(if (click == null) "kami_essentials.inline.hover_inspect" else "kami_essentials.inline.click_inspect")
        val card = lineCard(Phrase.of("kami_libs.common.item") to Config.s.inlineStyle.valueColor, note to Config.s.inlineStyle.textColor)
        return InlineChat.pill(label, Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, card), click)
            .withStyle { it.withHoverEvent(hover) }
    }

    private fun inventoryToken(sender: ServerPlayer) = openToken(
        label = Phrase.of("kami_essentials.inline.inventory"),
        tip = Phrase.of("kami_essentials.views.inventory.other", sender.gameProfile.name),
        on = Config.s.inlineGeneral.inventoryViewer,
        key = { register(Type.INVENTORY, sender) }
    )

    private fun enderChestToken(sender: ServerPlayer) = openToken(
        label = Phrase.of("kami_essentials.inline.ender_chest"),
        tip = Phrase.of("kami_essentials.views.ender_chest.other", sender.gameProfile.name),
        on = Config.s.inlineGeneral.enderChestViewer,
        key = { register(Type.ENDER_CHEST, sender) }
    )

    private fun healthToken(sender: ServerPlayer): Component {
        val ratio = (sender.health / sender.maxHealth).coerceIn(0f, 1f)
        val color = when {
            ratio >= 0.7f -> Config.s.inlineStyle.positiveColor
            ratio >= 0.35f -> Config.s.inlineStyle.warningColor
            else -> Config.s.inlineStyle.negativeColor
        }
        val label = "${num(sender.health)}/${num(sender.maxHealth)} ${Config.s.inlineStyle.healthIcon}"
        return InlineChat.pill(label, color, Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(Phrase.of("kami_essentials.inline.health") to Theme.VALUE)))
    }

    private fun hungerToken(sender: ServerPlayer): Component {
        val food = sender.foodData.foodLevel
        val color = when {
            food >= 14 -> Config.s.inlineStyle.positiveColor
            food >= 7 -> Config.s.inlineStyle.warningColor
            else -> Config.s.inlineStyle.negativeColor
        }
        val label = "$food/20 ${Config.s.inlineStyle.hungerIcon}"
        return InlineChat.pill(label, color, Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(Phrase.of("kami_essentials.inline.hunger") to Theme.VALUE)))
    }

    private fun armorToken(sender: ServerPlayer): Component {
        val armor = sender.armorValue
        val color = when {
            armor >= 14 -> Config.s.inlineStyle.positiveColor
            armor >= 7 -> Config.s.inlineStyle.warningColor
            else -> Config.s.inlineStyle.valueColor
        }
        return InlineChat.pill("$armor ${Config.s.inlineStyle.armorIcon}", color, Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(Phrase.of("kami_essentials.inline.armor") to Theme.VALUE)))
    }

    private fun xpToken(sender: ServerPlayer): Component {
        val need = sender.getXpNeededForNextLevel().coerceAtLeast(1)
        val now = (sender.experienceProgress * need).toInt().coerceIn(0, need)
        val label = "$now/$need ${Config.s.inlineStyle.xpIcon}"
        return InlineChat.pill(label, Config.s.inlineStyle.valueColor, Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(Phrase.of("kami_essentials.inline.xp") to Theme.VALUE)))
    }

    private fun levelToken(sender: ServerPlayer): Component =
        InlineChat.pill(Text.msg("kami_essentials.inline.level.short", sender.experienceLevel).withColor(Config.s.inlineStyle.positiveColor), Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(Phrase.of("kami_libs.common.level") to Theme.VALUE)))

    private fun posToken(sender: ServerPlayer): Component {
        val p = sender.blockPosition()
        val dim = sender.level().dimension().location().toString()
        val label = "${p.x}, ${p.y}, ${p.z} @ $dim"
        val click = if (Config.s.inlineGeneral.posSuggestTp) ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/execute in $dim run tp @s ${p.x} ${p.y} ${p.z}") else null
        val hint = Phrase.of(if (click == null) "kami_essentials.inline.location" else "kami_essentials.inline.location.tooltip")
        return InlineChat.pill(label, Config.s.inlineStyle.linkColor, Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(hint to Config.s.inlineStyle.textColor)), click)
    }

    private fun balanceToken(sender: ServerPlayer): Component {
        val compact = Coins.compactParts(Numismatics.balance(sender.uuid))
        val text = Component.empty()
            .append(Component.literal(compact.amount).withColor(Config.s.inlineStyle.positiveColor))
            .append(Component.literal(compact.glyph.toString()).withColor(Theme.VALUE))
        return InlineChat.pill(text, Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(Phrase.of("kami_essentials.inline.balance") to Theme.VALUE)))
    }

    private fun countryToken(sender: ServerPlayer): Component {
        val c = Names.citizenship(sender.uuid)
        val label = c?.let { Component.literal(it.name) } ?: Text.msg("kami_libs.common.no_country")
        val color = c?.let(Names::color) ?: Config.s.inlineStyle.warningColor
        return InlineChat.pill(label.withColor(color), Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(Phrase.of("kami_libs.common.country") to Theme.VALUE)))
    }

    private fun rankToken(sender: ServerPlayer): Component {
        val c = Names.citizenship(sender.uuid)
        val label = c?.let { Names.title(it.rank) } ?: Text.msg("kami_essentials.inline.no_rank")
        val color = c?.let(Names::color) ?: Config.s.inlineStyle.warningColor
        return InlineChat.pill(label.withColor(color), Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(Phrase.of("kami_essentials.names.country_rank") to Theme.VALUE)))
    }

    private fun pingToken(sender: ServerPlayer): Component =
        InlineChat.pill("${sender.connection.latency()} ms", Config.s.inlineStyle.valueColor, Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(Phrase.of("kami_essentials.sidebar.ping") to Theme.VALUE)))

    private fun tpsToken(sender: ServerPlayer): Component =
        InlineChat.pill("${"%.1f".format(Tab.tps(sender.server))} TPS", Config.s.inlineStyle.valueColor, Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(Phrase.of("kami_essentials.inline.tps") to Theme.VALUE)))

    private fun openToken(label: Phrase, tip: Phrase, on: Boolean, key: () -> String): Component {
        val click = if (on) ClickEvent(ClickEvent.Action.RUN_COMMAND, "/essentials inline open ${key()}") else null
        return InlineChat.pill(label.component().withColor(Config.s.inlineStyle.linkColor), Config.s.inlineStyle.bracketColor, HoverEvent(HoverEvent.Action.SHOW_TEXT, lineCard(tip to Config.s.inlineStyle.textColor)), click)
    }

    private fun register(type: Type, sender: ServerPlayer, stack: ItemStack = ItemStack.EMPTY): String {
        val key = randomKey(14)
        val ttl = Config.s.inlineGeneral.actionTtlSeconds.coerceAtLeast(10) * 1000L
        actions[key] = Action(type, sender.uuid, sender.gameProfile.name, stack, System.currentTimeMillis() + ttl)
        return key
    }

    private fun randomKey(len: Int): String = buildString(len) {
        repeat(len) { append(keyChars[(Math.random() * keyChars.length).toInt()]) }
    }

    private fun num(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)

    private fun itemName(stack: ItemStack): Component {
        stack.get(DataComponents.CUSTOM_NAME)?.let { return it.copy() }
        stack.get(DataComponents.ITEM_NAME)?.let { return it.copy() }
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        if (id.startsWith("tacz:")) {
            taczNameFromData(stack)?.let { return Component.literal(it) }
            return Component.translatable(stack.descriptionId)
        }
        return stack.hoverName.copy()
    }

    private fun taczNameFromData(stack: ItemStack): String? {
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null
        val keys = listOf("DisplayName", "displayName", "GunName", "gunName", "Name", "name", "ShortName", "shortName", "AmmoId")
        return keys.firstNotNullOfOrNull { key -> tag.getString(key).takeIf { it.isNotBlank() } }
    }

    private fun lineCard(vararg lines: Pair<Phrase, Int>): Component =
        lines.foldIndexed(Component.empty()) { i, out, (text, color) -> out.append(Component.literal(if (i == 0) "" else "\n").append(text.component()).withColor(color)) }

    private fun link(url: String, color: Int): Component = Component.literal(url).withStyle {
        it.withColor(color).withUnderlined(true).withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, url))
            .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.msg("kami_essentials.inline.link").withColor(Config.s.inlineStyle.textColor)))
    }

    private fun legacyBody(text: String): Component {
        val style = Config.s.inlineStyle
        return InlineChat.render(
            text,
            plain = { Component.literal(it).withColor(style.textColor) },
            link = { link(it, style.linkColor) },
            inline = { null }
        )
    }
}
