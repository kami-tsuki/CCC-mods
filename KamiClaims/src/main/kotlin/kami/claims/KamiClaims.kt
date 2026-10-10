package kami.claims

import kami.libs.util.toUuidOrNull
import kami.claims.admin.AdminCountries
import kami.claims.admin.ClaimStats
import kami.claims.client.ClientHooks
import kami.claims.compat.DynamicTrees
import kami.claims.compat.JadeServer
import kami.claims.compat.Tacz
import kami.claims.discord.DiscordClaims
import kami.claims.command.ClaimsCommands
import kami.claims.net.Net
import kami.claims.net.ResearchSync
import kami.claims.net.Sync
import kami.claims.service.Work
import kami.claims.war.NameTagSync
import kami.claims.war.NameTagTeams
import kami.claims.war.Online
import kami.claims.war.FreecamSync
import kami.claims.war.PositionSync
import kami.claims.war.Reputation
import kami.claims.war.WarBar
import kami.claims.war.Wars
import kami.claims.research.Boosts
import kami.claims.research.Features
import kami.claims.research.Gate
import kami.claims.research.Goals
import kami.claims.research.Levels
import kami.claims.research.Listeners
import kami.claims.research.Queue
import kami.claims.research.RecipeContext
import kami.claims.research.Research
import kami.claims.research.Structures
import kami.claims.research.TreeFelling
import kami.claims.service.Upkeep
import kami.claims.social.Mail
import kami.claims.social.Perms
import kami.claims.world.Effects
import kami.claims.world.Guard
import kami.claims.world.Sky
import kami.claims.world.TempBlocks
import kami.libs.claims.Citizenship
import kami.libs.claims.ClaimInfo
import kami.libs.claims.ClaimsApi
import kami.libs.claims.ClaimsProvider
import kami.libs.claims.CountryInfo
import kami.libs.claims.FlagInfo
import kami.libs.claims.Goal
import kami.libs.claims.Locks
import kami.libs.claims.Relation
import kami.claims.service.Diplomacy
import kami.claims.service.Gifts
import kami.claims.service.View
import kami.libs.config.Configs
import kami.libs.log.Log
import kami.libs.text.Phrase
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.ExplosionEvent
import net.neoforged.neoforge.event.level.PistonEvent
import net.neoforged.neoforge.event.OnDatapackSyncEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Mod(KamiClaims.ID)
object KamiClaims {
    const val ID = "kami_claims"
    val LOG = Log.of("claims")
    private val dimensions = ConcurrentHashMap<String, ResourceKey<Level>>()

    init {
        RealmDb.register()
        MOD_BUS.addListener<FMLCommonSetupEvent> {
            Config.load()
            Configs.onReload("claims", Research::reload)
            ClaimsApi.register(object : ClaimsProvider {
                override fun at(dim: String, x: Int, z: Int): ClaimInfo? {
                    val cl = Realm.index[Key(dim, x, z)] ?: return null
                    return ClaimInfo(cl.country, cl.type, cl.owner?.toUuidOrNull())
                }

                override fun isBanished(player: UUID, country: String): Boolean =
                    Realm.data.countries[country]?.outsiders?.get(player.toString()) == Rank.BANISHED

                override fun countryOf(player: UUID): String? = Realm.of(player.toString())?.id

                override fun hostile(a: String, b: String): Boolean = Wars.enemies(a, b)

                override fun citizenship(player: UUID): Citizenship? = Realm.of(player.toString())?.let { c ->
                    Citizenship(c.id, c.name, c.color, Realm.country(c.parent)?.name, c.rank(player.toString())?.name ?: Rank.CITIZEN.name)
                }

                override fun country(id: String): CountryInfo? = Realm.live(id)?.let { info(it) }

                override fun countries(): List<CountryInfo> = Realm.data.countries.values.filter { it.active }.map { info(it) }

                override fun relation(a: String, b: String): Relation {
                    val x = Realm.live(a) ?: return Relation.NEUTRAL
                    val y = Realm.live(b) ?: return Relation.NEUTRAL
                    return Relation.valueOf(Diplomacy.relation(x, y).uppercase())
                }

                override fun tariff(buyerCountry: String, sellerCountry: String): Int {
                    val x = Realm.live(buyerCountry) ?: return 0
                    val y = Realm.live(sellerCountry) ?: return 0
                    return Diplomacy.tariff(x, y)
                }

                override fun level(player: UUID): Int? = Realm.of(player.toString())?.let(Research::level)

                override fun lock(player: UUID, feature: String): Phrase? {
                    val c = Realm.of(player.toString()) ?: return Locks.noCountry()
                    return Features.lockReason(c, feature)
                }

                override fun limit(player: UUID, key: String, used: Int): Phrase? {
                    val c = Realm.of(player.toString()) ?: return Locks.noCountry()
                    return Goals.capacity(key)?.let { Features.limit(c, it, used) }
                }

                override fun goals(player: UUID): List<Goal> = Realm.of(player.toString())?.let(Goals::of).orEmpty()

                override fun capacity(player: UUID, key: String): Int? = Realm.of(player.toString())?.let { c ->
                    Goals.capacity(key)?.let { Levels.capacity(c, it) }
                }

                override fun creditTariff(country: String, amount: Long, payer: String, note: String): Long = Realm.live(country)?.let { Diplomacy.creditTariff(it, amount, payer, note) } ?: 0

                override fun traded(a: String, b: String, value: Long) {
                    Reputation.trade(Realm.live(a) ?: return, Realm.live(b) ?: return, value)
                }

                override fun boost(country: String, kind: String, pct: Int, ms: Long): Boolean = Realm.live(country)?.let { Boosts.add(it, kind, pct, ms) } ?: false

                override fun tradeFactor(country: String): Double = Realm.live(country)?.let(Reputation::tradeFactor) ?: 1.0

                override fun canBuild(player: UUID, dim: String, x: Int, y: Int, z: Int): Boolean {
                    if (Realm.index[Key(dim, x shr 4, z shr 4)] == null) return true
                    val server = ServerLifecycleHooks.getCurrentServer() ?: return true
                    val p = server.playerList.getPlayer(player) ?: return false
                    val level = server.getLevel(dimensions.getOrPut(dim) { ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim)) }) ?: return false
                    return Guard.allowed(level, BlockPos(x, y, z), p, Action.PLACE, Blocks.OBSIDIAN, false) || Perms.has(p, Perms.BYPASS)
                }
            })
        }
        MOD_BUS.addListener<RegisterPayloadHandlersEvent> { Net.register(it) }
        if (FMLEnvironment.dist == Dist.CLIENT) ClientHooks.init()
        JadeServer.register()
        ClaimStats.register()
        DiscordClaims.register()
        FORGE_BUS.addListener<PermissionGatherEvent.Nodes> { Perms.register(it) }
        ClaimsCommands.register()
        AdminCountries.register()
        FORGE_BUS.addListener<BlockEvent.BreakEvent>(EventPriority.LOWEST) { Guard.onBreakCredit(it) }
        Listeners.register()
        Tacz.register()
        Sky.register()
        DynamicTrees.register()
        WealthSources.register()
        FORGE_BUS.addListener<ServerStartingEvent> { RecipeContext.bind(Thread.currentThread()) }
        FORGE_BUS.addListener<ServerStartedEvent> { Realm.load(it.server); Research.reload(); Wars.resume(); NameTagTeams.sweep(it.server) }
        FORGE_BUS.addListener<OnDatapackSyncEvent> { if (it.player == null) Research.resolve(it.playerList.server) }
        FORGE_BUS.addListener<ServerStoppingEvent> {
            RecipeContext.bind(null)
            Net.reset()
            Sync.reset()
            ResearchSync.reset()
            Queue.reset()
            TreeFelling.reset()
            Levels.reset()
            Guard.reset()
            Work.reset()
            Gifts.reset()
            Upkeep.reset()
            Online.reset()
            WarBar.reset()
            NameTagTeams.reset()
            NameTagSync.reset()
            PositionSync.reset()
            FreecamSync.reset()
        }
        FORGE_BUS.addListener<ServerStoppedEvent>(EventPriority.LOWEST) { RealmDb.unload() }
        FORGE_BUS.addListener<ServerTickEvent.Post> {
            val t = it.server.tickCount
            val players = it.server.playerList.players
            fun task(name: String, every: Int, run: () -> Unit) {
                if (t % every == 0) runCatching(run).onFailure { e -> LOG.error("{} tick failed", name, e) }
            }
            task("Announce", 20) { players.forEach(Guard::announce) }
            task("Structures", 1) { Structures.tick(t, players) }
            task("Borders", 10) { players.forEach(Effects::borders) }
            task("Push", 1) { Net.push(it.server) }
            task("Research", 20) { Queue.tick() }
            task("Online", 20) { Online.sample(players) }
            task("War bar", 20) { WarBar.tick(players) }
            task("Nametags", 20) { NameTagTeams.syncAll(it.server) }
            task("Nametag lines", 1) { NameTagSync.tick(it.server) }
            task("Positions", 1) { PositionSync.tick(it.server) }
            task("Freecam", 5) { FreecamSync.tick(it.server) }
            task("Temp blocks", 4) { TempBlocks.tick(it.server) }
            task("Levels", 100) { Levels.advanceAll() }
            task("Upkeep", 1200) { Upkeep.tick(it.server) }
            task("War", 1200) { Wars.tick() }
        }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedInEvent> { (it.entity as? ServerPlayer)?.let { p -> touch(p); Mail.deliver(p); Online.login(p); runCatching { NameTagTeams.sync(p) }.onFailure { e -> LOG.error("Nametags failed", e) }; runCatching { WarBar.tick(listOf(p)) }.onFailure { e -> LOG.error("War bar failed", e) } } }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent> { (it.entity as? ServerPlayer)?.let { p -> touch(p); Guard.forget(p); Net.forget(p); Structures.forget(p); Online.logout(p); WarBar.forget(p); PositionSync.forget(p); FreecamSync.forget(p); NameTagSync.forget(p) } }
        FORGE_BUS.addListener<BlockEvent.BreakEvent> { Guard.onBreak(it) }
        FORGE_BUS.addListener<BlockEvent.EntityPlaceEvent> { Guard.onPlace(it) }
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickBlock> { Guard.onUse(it) }
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickItem> { Guard.onUseItem(it) }
        FORGE_BUS.addListener<PlayerInteractEvent.EntityInteract> { Guard.onEntityUse(it) }
        FORGE_BUS.addListener<PlayerInteractEvent.EntityInteractSpecific> { Guard.onEntityUse(it) }
        FORGE_BUS.addListener<AttackEntityEvent> { Guard.onAttack(it) }
        FORGE_BUS.addListener<ProjectileImpactEvent> { Guard.onProjectile(it) }
        FORGE_BUS.addListener<BlockEvent.FarmlandTrampleEvent> { Guard.onTrample(it) }
        FORGE_BUS.addListener<EntityMobGriefingEvent> { Guard.onGrief(it) }
        FORGE_BUS.addListener<ExplosionEvent.Detonate> { Guard.onExplosion(it) }
        FORGE_BUS.addListener<EntityJoinLevelEvent> { Reputation.onJoin(it) }
        FORGE_BUS.addListener<PistonEvent.Pre> { Guard.onPiston(it) }
        FORGE_BUS.addListener<LivingIncomingDamageEvent> { Guard.onDamage(it) }
        FORGE_BUS.addListener<LivingDamageEvent.Post> { Guard.onHurt(it) }
        FORGE_BUS.addListener<LivingDeathEvent>(EventPriority.LOWEST) { Guard.onDeath(it) }
    }

    private fun info(c: Country) = CountryInfo(c.id, c.name, View.color(c), FlagInfo(c.flag.pattern, c.flag.emblem, c.flag.secondary), c.treasury)

    private fun touch(p: ServerPlayer) {
        val c = Realm.of(p.stringUUID) ?: return
        c.lastActive = now()
        c.members[p.stringUUID]?.seen = now()
    }
}
