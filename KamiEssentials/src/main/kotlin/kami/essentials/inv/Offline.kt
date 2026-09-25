package kami.essentials.inv

import kami.essentials.KamiEssentials
import kami.libs.command.fail
import net.minecraft.Util
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.SimpleContainer
import net.minecraft.world.inventory.PlayerEnderChestContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.util.UUID

class Offline private constructor(val id: UUID, private val server: MinecraftServer) {
    private val dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
    private val file = dir.resolve("$id.dat")
    private val tag: CompoundTag
    val inventory = SimpleContainer(41)
    val enderchest = PlayerEnderChestContainer()
    val viewers = mutableSetOf<ServerPlayer>()
    var live = true
        private set

    init {
        val raw = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        tag = DataFixTypes.PLAYER.updateToCurrentVersion(server.fixerUpper, raw, NbtUtils.getDataVersion(raw, -1))
        tag.getList("Inventory", Tag.TAG_COMPOUND.toInt()).forEach { t ->
            val item = t as CompoundTag
            slot(item.getByte("Slot").toInt() and 255)?.let { inventory.setItem(it, ItemStack.parseOptional(server.registryAccess(), item)) }
        }
        enderchest.fromTag(tag.getList("EnderItems", Tag.TAG_COMPOUND.toInt()), server.registryAccess())
        inventory.addListener { save() }
        enderchest.addListener { save() }
    }

    private fun save() {
        if (!live) return
        runCatching {
            val items = ListTag()
            for (i in 0 until inventory.containerSize) {
                val stack = inventory.getItem(i)
                if (!stack.isEmpty) items.add(stack.save(server.registryAccess(), CompoundTag().apply { putByte("Slot", raw(i).toByte()) }))
            }
            tag.put("Inventory", items)
            tag.put("EnderItems", enderchest.createTag(server.registryAccess()))
            NbtUtils.addCurrentDataVersion(tag)
            val tmp = Files.createTempFile(dir, "$id-", ".dat")
            NbtIo.writeCompressed(tag, tmp)
            Util.safeReplaceFile(file, tmp, dir.resolve("$id.dat_old"))
        }.onFailure { KamiEssentials.LOG.error("Could not save offline edits of {}", id, it) }
    }

    private fun close() {
        live = false
        viewers.toList().forEach { it.closeContainer() }
        open.remove(id)
    }

    companion object {
        private val open = HashMap<UUID, Offline>()

        private fun slot(raw: Int) = when (raw) {
            in 0..35 -> raw
            in 100..103 -> raw - 64
            150 -> 40
            else -> null
        }

        private fun raw(slot: Int) = when (slot) {
            in 0..35 -> slot
            in 36..39 -> slot + 64
            else -> 150
        }

        fun of(server: MinecraftServer, id: UUID): Offline? = open[id] ?: run {
            if (!Files.exists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve("$id.dat"))) return null
            runCatching { Offline(id, server) }
                .onFailure { KamiEssentials.LOG.error("Could not read the saved data of {}", id, it) }
                .getOrElse { fail("Could not read that player's saved data, see the log.") }
                .also { open[id] = it }
        }

        fun release(data: Offline, viewer: ServerPlayer) {
            data.viewers.remove(viewer)
            if (data.viewers.isEmpty() && data.live) open.remove(data.id)
        }

        fun joined(p: ServerPlayer) = open[p.uuid]?.close()
    }
}
