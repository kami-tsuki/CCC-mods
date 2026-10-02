package kami.claims

import kami.claims.client.ClientHooks
import kami.claims.command.ClaimsCommands
import kami.claims.net.Net
import kami.claims.research.Features
import kami.claims.research.Gate
import kami.claims.research.Goals
import kami.claims.research.Levels
import kami.claims.research.Listeners
import kami.claims.research.Queue
import kami.claims.research.RecipeContext
import kami.claims.research.Research
import kami.claims.research.Structures
import kami.claims.service.Upkeep
import kami.claims.social.Mail
import kami.claims.social.Perms
import kami.claims.world.Effects
import kami.claims.world.Guard
import kami.claims.world.Sky
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
import kami.claims.service.View
import kami.libs.config.Configs
import kami.libs.log.Log
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent
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
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import net.minecraft.resources.ResourceLocation
import java.util.UUID

@Mod(KamiClaims.ID)
object KamiClaims {
    const val ID = "kami_claims"
    val LOG = Log.of("claims")

    init {
        MOD_BUS.addListener<FMLCommonSetupEvent> {
            Config.load()
            Configs.onReload("claims", Research::reload)
            ClaimsApi.register(object : ClaimsProvider {
                override fun at(dim: String, x: Int, z: Int): ClaimInfo? {
                    val cl = Realm.index[Key(dim, x, z)] ?: return null
                    return ClaimInfo(cl.country, cl.type, cl.owner?.let { runCatching { UUID.fromString(it) }.getOrNull() })
                }

                override fun isBanished(player: UUID, country: String): Boolean =
                    Realm.data.countries[country]?.outsiders?.get(player.toString()) == Rank.BANISHED

                override fun countryOf(player: UUID): String? = Realm.of(player.toString())?.id

                override fun citizenship(player: UUID): Citizenship? = Realm.of(player.toString())?.let { c ->
                    Citizenship(c.id, c.name, c.color, Realm.country(c.parent)?.name, c.rank(player.toString())?.name ?: Rank.CITIZEN.name)
                }

                override fun country(id: String): CountryInfo? = Realm.live(id)?.let { CountryInfo(it.id, it.name, View.color(it), FlagInfo(it.flag.pattern, it.flag.emblem, it.flag.secondary)) }

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

                override fun allowedRecipe(country: String?, recipe: ResourceLocation) = Gate.recipe(Realm.live(country), recipe)

                override fun allowedBlock(country: String?, block: ResourceLocation) = Gate.block(Realm.live(country), block)

                override fun capacity(player: UUID, key: String): Int? = Realm.of(player.toString())?.let { c ->
                    Goals.capacity(key)?.let { Levels.capacity(c, it) }
                }

                override fun creditTariff(country: String, amount: Long): Long = Realm.live(country)?.let { Diplomacy.creditTariff(it, amount) } ?: 0
            })
        }
        MOD_BUS.addListener<RegisterPayloadHandlersEvent> { Net.register(it) }
        if (FMLEnvironment.dist == Dist.CLIENT) ClientHooks.init()
        FORGE_BUS.addListener<PermissionGatherEvent.Nodes> { Perms.register(it) }
        ClaimsCommands.register()
        Listeners.register()
        Sky.register()
        FORGE_BUS.addListener<ServerStartingEvent> { RecipeContext.bind(Thread.currentThread()) }
        FORGE_BUS.addListener<ServerStartedEvent> { Realm.load(it.server); Research.reload() }
        FORGE_BUS.addListener<OnDatapackSyncEvent> { if (it.player == null) Research.resolve(it.playerList.server) }
        FORGE_BUS.addListener<ServerStoppingEvent> { Realm.save(true); RecipeContext.bind(null) }
        FORGE_BUS.addListener<ServerTickEvent.Post> {
            val t = it.server.tickCount
            if (t % 20 == 0) it.server.playerList.players.forEach(Guard::announce)
            Structures.tick(t, it.server.playerList.players)
            if (t % 10 == 0) it.server.playerList.players.forEach(Effects::borders)
            Net.push(it.server)
            if (t % 20 == 0) Queue.tick()
            if (t % 100 == 0) Levels.advanceAll()
            if (t % 1200 == 0) Upkeep.tick(it.server)
            if (t % 6000 == 0) Realm.save()
        }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedInEvent> { (it.entity as? ServerPlayer)?.let { p -> touch(p); Mail.deliver(p) } }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent> { (it.entity as? ServerPlayer)?.let { p -> touch(p); Guard.forget(p); Net.forget(p); Structures.forget(p) } }
        FORGE_BUS.addListener<BlockEvent.BreakEvent> { Guard.onBreak(it) }
        FORGE_BUS.addListener<BlockEvent.EntityPlaceEvent> { Guard.onPlace(it) }
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickBlock> { Guard.onUse(it) }
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickItem> { Guard.onUseItem(it) }
        FORGE_BUS.addListener<PlayerInteractEvent.EntityInteract> { Guard.onEntityUse(it) }
        FORGE_BUS.addListener<PlayerInteractEvent.EntityInteractSpecific> { Guard.onEntityUse(it) }
        FORGE_BUS.addListener<AttackEntityEvent> { Guard.onAttack(it) }
        FORGE_BUS.addListener<BlockEvent.FarmlandTrampleEvent> { Guard.onTrample(it) }
        FORGE_BUS.addListener<EntityMobGriefingEvent> { Guard.onGrief(it) }
        FORGE_BUS.addListener<ExplosionEvent.Detonate> { Guard.onExplosion(it) }
        FORGE_BUS.addListener<PistonEvent.Pre> { Guard.onPiston(it) }
        FORGE_BUS.addListener<LivingIncomingDamageEvent> { Guard.onDamage(it) }
    }

    private fun touch(p: ServerPlayer) {
        val c = Realm.of(p.stringUUID) ?: return
        c.lastActive = now()
        c.members[p.stringUUID]?.seen = now()
        Realm.dirty = true
    }
}
