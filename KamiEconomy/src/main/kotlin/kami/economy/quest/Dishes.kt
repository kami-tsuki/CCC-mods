package kami.economy.quest

import com.simibubi.create.content.processing.recipe.ProcessingRecipe
import kami.economy.Config
import kami.economy.LOG
import kami.economy.Market
import kami.economy.StarterGood
import kami.economy.economy.Blacklist
import kami.economy.economy.Stocks
import kami.economy.economy.Variants
import kami.economy.sane
import kami.economy.villager.RecipeSeed
import kami.libs.mc.ItemSpec
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.TagKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import kotlin.math.roundToInt

object Dishes {
    const val SEED_VERSION = 2
    private val basic = setOf("minecraft:crafting", "minecraft:smelting", "minecraft:smoking", "minecraft:campfire_cooking", "minecraft:blasting")
    private val plates = setOf("bowl", "plate")
    private val tagGuess = listOf(
        "c:crops" to 0.12, "c:seeds" to 0.05, "c:foods/raw_meat" to 0.6, "c:raw_meats" to 0.6, "c:foods/raw_fish" to 0.5, "c:raw_fishes" to 0.5,
        "c:eggs" to 0.25, "c:mushrooms" to 0.2
    )
    private val pathGuess = listOf("seeds" to 0.05, "seed" to 0.05, "egg" to 0.25, "sugar" to 0.15, "flour" to 0.15, "mushroom" to 0.2)
    @Volatile private var dishes: Map<String, Dish> = emptyMap()
    @Volatile var recipes: List<Rec> = emptyList()
        private set

    fun of(item: String): Dish? = dishes[item]

    fun all(): Collection<Dish> = dishes.values

    fun scan(server: MinecraftServer) {
        val access = server.registryAccess()
        val recs = server.recipeManager.recipes.mapNotNull { h -> runCatching { rec(h.value(), access) }.getOrNull() }
        recipes = recs
        RecipeSeed.reset()
        val patterns = Config.s.quests.namespaces.map { p -> Regex(p.split('*').joinToString(".*") { Regex.escape(it) }) }
        val scope = { id: String -> patterns.any { it.matches(id.substringBefore(':')) } }
        val plated = recs.filter { r -> r.inputs.any { i -> i.options.any { it.substringAfter(':') in plates } } }.mapTo(HashSet()) { it.out }
        val targets = BuiltInRegistries.ITEM.entrySet().mapNotNull { (key, item) ->
            val id = key.location().toString()
            id.takeIf { scope(it) && runCatching { item.defaultInstance.get(DataComponents.FOOD) != null || (item is BlockItem && id in plated) }.getOrDefault(false) }
        }
        val course = { id: String -> Stocks.good(id)?.takeUnless { Config.s.quests.seed == 1 && seeded(it) }?.let { Stocks.course(id) / Config.s.lotOf(id) }?.takeIf { it > 0.0 } }
        dishes = DishGraph.solve(recs, targets, course, ::guess, scope)
        if (dishes.values.none { it.quest }) LOG.warn("Quest dish scan found no quest dishes in {} recipes; quests cannot spawn", recs.size)
        runCatching { report(server) }.onFailure { LOG.warn("Dish report failed: {}", it.message) }
        runCatching { seed() }.onFailure { LOG.error("Dish seeding failed", it) }
    }

    private fun rec(r: Recipe<*>, access: HolderLookup.Provider): Rec? {
        val result = r.getResultItem(access).takeUnless { it.isEmpty } ?: return null
        val items = r.ingredients.filterNot { it.isEmpty }.map { i -> In(i.items.map(ItemSpec::spec).distinct()) }
        if (items.any { it.options.isEmpty() }) return null
        val fluids = (r as? ProcessingRecipe<*, *>)?.fluidIngredients.orEmpty().mapNotNull { f ->
            val bucket = f.fluids.firstOrNull()?.fluid?.bucket?.takeIf { it != Items.AIR } ?: return@mapNotNull null
            In(listOf(BuiltInRegistries.ITEM.getKey(bucket).toString()), f.amount() / 1000.0, true)
        }
        val station = BuiltInRegistries.RECIPE_TYPE.getKey(r.type)?.toString()?.takeIf { it !in basic }
        return Rec(ItemSpec.spec(result), result.count, items + fluids, listOfNotNull(station))
    }

    private fun guess(id: String): Double {
        val item = ResourceLocation.tryParse(id)?.let { BuiltInRegistries.ITEM.get(it) }
        item?.let { i -> tagGuess.firstOrNull { (t, _) -> runCatching { i.defaultInstance.`is`(TagKey.create(Registries.ITEM, ResourceLocation.parse(t))) }.getOrDefault(false) } }?.let { return it.second }
        val path = id.substringAfter(':')
        return when {
            path == "milk_bucket" -> 1.0
            path.startsWith("raw_") && (path.endsWith("beef") || path.endsWith("pork") || path.endsWith("mutton") || path.endsWith("chicken") || path.endsWith("rabbit")) -> 0.6
            path in setOf("beef", "porkchop", "mutton", "chicken", "rabbit") -> 0.6
            path in setOf("cod", "salmon", "tropical_fish", "pufferfish") -> 0.5
            else -> pathGuess.firstOrNull { it.first in path }?.second ?: 0.3
        }
    }

    private fun lotOf(d: Dish): Int = Stocks.good(d.item)?.lot ?: DishGraph.lot(d.price)

    private fun report(server: MinecraftServer) {
        val dir = server.getWorldPath(LevelResource.ROOT).resolve("kami")
        Files.createDirectories(dir)
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val lines = listOf("item,mod,score,tier,price,lot,inputs,stations,guessed,quest") + dishes.values.sortedBy { it.item }.map { d ->
            listOf(d.item, d.item.substringBefore(':'), d.score, d.tier, "%.3f".format(java.util.Locale.ROOT, d.price), lotOf(d), q(d.inputs.joinToString(";")), q(d.stations.joinToString(";")), d.guessed, d.quest).joinToString(",")
        }
        Files.write(dir.resolve("quest_dishes.csv"), lines)
    }

    private fun seed() {
        if (Config.s.quests.seed >= SEED_VERSION || dishes.isEmpty()) return
        val kept = if (Config.s.quests.seed == 1) Config.s.starterGoods.filterNot(::stale) else Config.s.starterGoods
        val needed = HashSet<String>()
        val queue = ArrayDeque(dishes.values.filter { it.quest }.map { it.item })
        while (queue.isNotEmpty()) queue.removeFirst().takeIf(needed::add)?.let { dishes[it]?.inputs?.let(queue::addAll) }
        val fresh = dishes.values.sortedBy { it.item }.filter { d ->
            d.item in needed &&
            d.item !in DishGraph.containers && Stocks.configured(d.item)?.let { g -> kept.any { it.item == g.item } } != true && Market.peek(d.item)?.let { it.sells.isNotEmpty() || it.buys.isNotEmpty() } != true && kept.none { it.item == d.item } && Variants.of(d.item) == null && Blacklist.sellable(d.item)
        }.map { d ->
            val lot = DishGraph.lot(d.price)
            StarterGood(d.item, (d.price * lot).roundToInt().coerceAtLeast(1), lot, supply = 0, demand = 0, sellCap = 0, resell = true)
        }
        val replaced = Config.s.starterGoods.size - kept.size
        Config.save(Config.s.copy(starterGoods = kept + fresh, quests = Config.s.quests.copy(seed = SEED_VERSION)).sane())
        Stocks.reindex()
        LOG.info("Seeded {} dish and ingredient goods, replaced {} from the first seeding", fresh.size, replaced)
    }

    private fun seeded(g: StarterGood) = g.supply == 0 && g.demand == 0 && g.sellCap == 0 && g.resell && g.depth == 32 && g.floor == null && g.ceiling == null && g.spread == null

    private fun stale(g: StarterGood) = g.item in dishes && seeded(g) && Market.peek(g.item)?.let { it.sells.isNotEmpty() || it.buys.isNotEmpty() } != true
}
