package kami.claims.client.compat.jei

import com.mojang.blaze3d.platform.InputConstants
import kami.claims.client.ClientHooks
import kami.claims.client.app.pages.ResearchGraph
import kami.claims.client.app.pages.ResearchLook
import kami.claims.client.app.pages.isItemUnlock
import kami.claims.client.app.pages.taskItems
import kami.claims.client.app.pages.taskText
import kami.claims.client.app.pages.unlockText
import kami.claims.client.stackOf
import kami.claims.client.store.ClientResearch
import kami.claims.client.store.NodeStatus
import kami.claims.net.NodeView
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.text.tr
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.builder.ITooltipBuilder
import mezz.jei.api.gui.drawable.IDrawable
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.gui.inputs.IJeiInputHandler
import mezz.jei.api.gui.inputs.IJeiUserInput
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.category.IRecipeCategory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW

private const val W = 170
private const val H = 160
private const val HEADER_H = 20
private const val INFO_Y = 23
private const val BOX_Y = 37
private const val BOX_H = 58
private const val TASKS_Y = 99
private const val UNLOCKS_Y = 120
private const val GRID_X = 46
private const val GRID_COLS = 6
private const val BUTTON_Y = 143
private const val BUTTON_H = 16
private const val GAP = 6
private const val NODE_CONDITION = "kami_claims.research.cond.node"

private const val TEXT = 0xFF404040.toInt()
private const val MUTED = 0xFF707070.toInt()
private const val GOOD = 0xFF2E7D32.toInt()
private const val BAD = 0xFFA82020.toInt()
private const val WARN = 0xFFB05A00.toInt()
private const val MONEY = 0xFF8A6A00.toInt()
private const val INFO = 0xFF1F5FA8.toInt()
private const val RULE = 0xFF9A9A9A.toInt()
private const val BUTTON = 0xFF6B6B6B.toInt()
private const val BUTTON_HOVER = 0xFF7F8FB0.toInt()
private const val BUTTON_EDGE = 0xFF2A2A2A.toInt()

class ResearchCategory(guiHelper: IGuiHelper) : IRecipeCategory<NodeView> {
    private class Seg(val text: String, val color: Int, val x: Int, val tip: List<FormattedText>)

    private val icon = guiHelper.createDrawableItemStack(ItemStack(Items.KNOWLEDGE_BOOK))
    private val font get() = Minecraft.getInstance().font

    override fun getRecipeType() = TYPE
    override fun getTitle(): Component = Component.translatable("kami_claims.jei.research")
    override fun getWidth() = W
    override fun getHeight() = H
    override fun getIcon(): IDrawable = icon
    override fun getRegistryName(recipe: NodeView): ResourceLocation? = ResourceLocation.tryBuild("kami_claims", "research/${recipe.tree}/${recipe.id}")

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: NodeView, focuses: IFocusGroup) {
        recipe.tasks.map(::taskItems).filter { it.isNotEmpty() }.forEach { builder.addInputSlot().addItemStacks(it) }
        outputs(recipe).forEach { builder.addOutputSlot().addItemStack(it) }
    }

    override fun createRecipeExtras(builder: IRecipeExtrasBuilder, recipe: NodeView, focuses: IFocusGroup) {
        builder.addScrollBoxWidget(W, BOX_H, 0, BOX_Y).setContents(details(recipe))
        listOf(RecipeIngredientRole.INPUT to TASKS_Y, RecipeIngredientRole.OUTPUT to UNLOCKS_Y).forEach { (role, y) ->
            builder.recipeSlots.getSlots(role).takeIf { it.isNotEmpty() }?.let { builder.addScrollGridWidget(it, GRID_COLS, 1).setPosition(GRID_X, y) }
        }
        builder.addInputHandler(Open(recipe.key, ScreenRectangle(0, 0, W, HEADER_H)))
        builder.addInputHandler(Open(recipe.key, ScreenRectangle(0, BUTTON_Y, W, BUTTON_H)))
    }

    override fun draw(recipe: NodeView, recipeSlotsView: IRecipeSlotsView, g: GuiGraphics, mouseX: Double, mouseY: Double) {
        val status = ClientResearch.status(recipe.key)
        val state = ResearchLook.label(status)
        val stateW = font.width(state)
        stackOf(recipe.icon).takeUnless { it.isEmpty }?.let { g.renderItem(it, 0, 1) }
        g.drawString(font, Draw.fit(recipe.label().resolve(), W - 24 - stateW), 20, 1, TEXT, false)
        g.drawString(font, state, W - stateW, 1, color(status), false)
        g.drawString(font, Draw.fit(subtitle(recipe, status), W - 20), 20, 11, MUTED, false)
        segments(recipe, status).forEach { g.drawString(font, it.text, it.x, INFO_Y, it.color, false) }
        if (recipe.xp > 0) Format.number(recipe.xp).plus(" " + tr("kami_claims.research.detail.xp")).let { g.drawString(font, it, W - font.width(it), INFO_Y, MUTED, false) }
        g.fill(0, BOX_Y - 3, W, BOX_Y - 2, RULE)
        label(g, recipeSlotsView, RecipeIngredientRole.INPUT, "kami_claims.research.detail.tasks", TASKS_Y)
        label(g, recipeSlotsView, RecipeIngredientRole.OUTPUT, "kami_claims.research.detail.unlocks", UNLOCKS_Y)
        val hover = mouseX in 0.0..W.toDouble() && mouseY in BUTTON_Y.toDouble()..(BUTTON_Y + BUTTON_H).toDouble()
        g.fill(0, BUTTON_Y, W, BUTTON_Y + BUTTON_H, BUTTON_EDGE)
        g.fill(1, BUTTON_Y + 1, W - 1, BUTTON_Y + BUTTON_H - 1, if (hover) BUTTON_HOVER else BUTTON)
        val open = tr("kami_claims.jei.open")
        g.drawString(font, open, (W - font.width(open)) / 2, BUTTON_Y + 4, -1, true)
    }

    override fun getTooltip(tooltip: ITooltipBuilder, recipe: NodeView, recipeSlotsView: IRecipeSlotsView, mouseX: Double, mouseY: Double) {
        val x = mouseX.toInt()
        when (mouseY.toInt()) {
            in 0 until HEADER_H, in BUTTON_Y until BUTTON_Y + BUTTON_H -> tooltip.add(Component.translatable("kami_claims.jei.open.tip").withColor(MUTED))
            in INFO_Y - 1 until INFO_Y + 9 -> segments(recipe, ClientResearch.status(recipe.key)).firstOrNull { x in it.x until it.x + font.width(it.text) }?.let { tooltip.addAll(it.tip) }
        }
    }

    private fun label(g: GuiGraphics, view: IRecipeSlotsView, role: RecipeIngredientRole, key: String, y: Int) {
        g.drawString(font, Draw.fit(tr(key), GRID_X - 4), 0, y + 5, TEXT, false)
        if (view.getSlotViews(role).isEmpty()) g.drawString(font, "–", GRID_X, y + 5, MUTED, false)
    }

    private fun subtitle(node: NodeView, status: NodeStatus): String {
        val tree = ClientResearch.defs.trees.firstOrNull { it.id == node.tree }?.label()?.resolve().orEmpty()
        val queue = ClientResearch.queued(node.key)?.takeIf { status == NodeStatus.RESEARCHING || status == NodeStatus.PAUSED } ?: return tree
        return "$tree · " + Format.duration(queue.remainingMs * (100 - ClientResearch.state.speedPct) / 100)
    }

    private fun penalty(node: NodeView, status: NodeStatus) = if (status == NodeStatus.DONE) 0L else ClientResearch.penalty(node.key)

    private fun segments(node: NodeView, status: NodeStatus): List<Seg> {
        val penalty = penalty(node, status)
        val parts = listOfNotNull(
            Triple(tr("kami_libs.common.level") + " " + node.level, if (node.level > ClientResearch.state.level) WARN else TEXT, listOf(tip(tr("kami_claims.research.cond.level", node.level)))),
            Triple(Format.money(node.cost), MONEY, listOf(tip(tr("kami_claims.jei.cost")))),
            Triple(Format.duration(node.timeMs), TEXT, listOf(tip(tr("kami_claims.jei.time")))),
            Triple("+" + Format.duration(penalty), WARN, breakdown(node, penalty)).takeIf { penalty > 0 }
        )
        var x = 0
        return parts.map { (text, color, tip) -> Seg(text, color, x, tip).also { x += font.width(text) + GAP } }
    }

    private fun breakdown(node: NodeView, ms: Long): List<FormattedText> =
        listOf(Component.translatable("kami_claims.research.penalty.title").append(" +" + Format.duration(ms)).withColor(WARN)) +
            ClientResearch.missing(node).map { Component.literal(it.label().resolve() + "  ").append(Component.literal(Format.duration(it.timeMs)).withColor(WARN)) } +
            tip(tr("kami_claims.jei.penalty.hint"))

    private fun details(node: NodeView): List<FormattedText> = buildList {
        val status = ClientResearch.status(node.key)
        val done = status == NodeStatus.DONE
        val state = ClientResearch.state
        val flags = ClientResearch.conditionMet(node.key)
        node.summary().resolve().takeIf { it.isNotEmpty() }?.let { add(line(it, MUTED)) }
        val tree = ClientResearch.defs.trees.firstOrNull { it.id == node.tree }
        val deps = node.requires.mapNotNull { tree?.nodes?.getOrNull(it) }
        val requirements = buildList<Pair<String, Boolean?>> {
            if (node.level > 0) add(tr("kami_claims.research.cond.level", node.level) to (flags?.firstOrNull() ?: (state.level >= node.level)))
            deps.forEach { add(it.label().resolve() to (it.key in state.done)) }
            node.external.forEach { add(ResearchGraph.externalLabel(it) to (it in state.done)) }
            node.conditionTexts().withIndex().filter { it.value.key != NODE_CONDITION }.forEach { (i, p) -> add(p.resolve() to flags?.getOrNull(i + 1)) }
        }
        if (requirements.isNotEmpty()) {
            add(head("kami_claims.research.detail.requirements"))
            requirements.forEach { (text, met) -> add(check(text, if (done) true else met)) }
            if (!done && deps.any { it.key !in state.done }) add(line(tr("kami_claims.research.optional"), MUTED))
        }
        if (node.tasks.isNotEmpty()) {
            add(head("kami_claims.research.detail.tasks"))
            val progress = ClientResearch.queued(node.key)?.tasks
            node.tasks.forEachIndexed { i, task ->
                val have = if (done) task.target else (progress?.getOrNull(i) ?: 0L).coerceAtMost(task.target)
                add(Component.literal("• " + taskText(task) + " ").withColor(TEXT).append(Component.literal("${Format.number(have)}/${Format.number(task.target)}").withColor(if (have >= task.target) GOOD else INFO)))
            }
        }
        node.unlocks.filterNot(::isItemUnlock).takeIf { it.isNotEmpty() }?.let { others ->
            add(head("kami_claims.research.detail.unlocks"))
            others.forEach { add(line("• " + unlockText(it), TEXT)) }
        }
    }

    private fun outputs(node: NodeView): List<ItemStack> =
        (ClientResearch.outputs(node.key) + node.unlocks.filter(::isItemUnlock).map { it.id }).map(::stackOf).filterNot { it.isEmpty }.distinctBy { it.item }

    private fun line(text: String, color: Int): FormattedText = Component.literal(text).withColor(color)

    private fun tip(text: String): FormattedText = line(text, MUTED)

    private fun head(key: String): FormattedText = Component.translatable(key).withStyle { it.withBold(true).withColor(TEXT) }

    private fun check(text: String, met: Boolean?): FormattedText = when (met) {
        true -> line("✔ $text", GOOD)
        false -> line("✘ $text", BAD)
        null -> line("• $text", MUTED)
    }

    private fun color(status: NodeStatus) = when (status) {
        NodeStatus.DONE, NodeStatus.READY -> GOOD
        NodeStatus.RESEARCHING, NodeStatus.QUEUED -> INFO
        NodeStatus.PAUSED -> WARN
        NodeStatus.AVAILABLE -> TEXT
        NodeStatus.LOCKED -> BAD
    }

    private class Open(private val key: String, private val area: ScreenRectangle) : IJeiInputHandler {
        override fun getArea() = area

        override fun handleInput(mouseX: Double, mouseY: Double, input: IJeiUserInput): Boolean {
            if (input.key.type != InputConstants.Type.MOUSE || input.key.value != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
            if (!input.isSimulate) ClientHooks.openResearch(key)
            return true
        }
    }

    companion object {
        val TYPE: RecipeType<NodeView> = RecipeType.create("kami_claims", "research", NodeView::class.java)
    }
}
