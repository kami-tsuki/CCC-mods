package kami.claims

import kami.claims.client.ClientHooks
import kami.claims.command.ClaimsCommands
import kami.claims.net.Net
import kami.claims.service.Upkeep
import kami.claims.social.Mail
import kami.claims.social.Perms
import kami.claims.world.Effects
import kami.claims.world.Guard
import kami.libs.claims.ClaimInfo
import kami.libs.claims.ClaimsApi
import kami.libs.claims.ClaimsProvider
import kami.libs.log.Log
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
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.util.UUID

@Mod(KamiClaims.ID)
object KamiClaims {
    const val ID = "kami_claims"
    val LOG = Log.of("claims")

    init {
        MOD_BUS.addListener<FMLCommonSetupEvent> {
            Config.load()
            ClaimsApi.register(object : ClaimsProvider {
                override fun at(dim: String, x: Int, z: Int): ClaimInfo? {
                    val cl = Realm.index[Key(dim, x, z)] ?: return null
                    return ClaimInfo(cl.country, cl.type, cl.owner?.let { runCatching { UUID.fromString(it) }.getOrNull() })
                }

                override fun isBanished(player: UUID, country: String): Boolean =
                    Realm.data.countries[country]?.outsiders?.get(player.toString()) == Rank.BANISHED

                override fun countryOf(player: UUID): String? = Realm.of(player.toString())?.id
            })
        }
        MOD_BUS.addListener<RegisterPayloadHandlersEvent> { Net.register(it) }
        if (FMLEnvironment.dist == Dist.CLIENT) ClientHooks.init()
        FORGE_BUS.addListener<PermissionGatherEvent.Nodes> { Perms.register(it) }
        ClaimsCommands.register()
        FORGE_BUS.addListener<ServerStartedEvent> { Realm.load(it.server) }
        FORGE_BUS.addListener<ServerStoppingEvent> { Realm.save(true) }
        FORGE_BUS.addListener<ServerTickEvent.Post> {
            val t = it.server.tickCount
            if (t % 20 == 0) it.server.playerList.players.forEach(Guard::announce)
            if (t % 10 == 0) it.server.playerList.players.forEach(Effects::borders)
            Net.push(it.server)
            if (t % 1200 == 0) Upkeep.tick(it.server)
            if (t % 6000 == 0) Realm.save()
        }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedInEvent> { (it.entity as? ServerPlayer)?.let { p -> touch(p); Mail.deliver(p) } }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent> { (it.entity as? ServerPlayer)?.let { p -> touch(p); Guard.forget(p); Net.forget(p) } }
        FORGE_BUS.addListener<BlockEvent.BreakEvent> { Guard.onBreak(it) }
        FORGE_BUS.addListener<BlockEvent.EntityPlaceEvent> { Guard.onPlace(it) }
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickBlock> { Guard.onUse(it) }
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
