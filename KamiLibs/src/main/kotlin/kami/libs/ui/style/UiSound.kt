package kami.libs.ui.style

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.Holder
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

data class SoundSpec(val event: String, val pitch: Float, val level: Float) {
    companion object {
        val KEYS = setOf("click", "hover", "toggle", "open", "close")
        private val id = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

        fun parse(text: String): SoundSpec? {
            val parts = text.trim().split(' ')
            if (parts.size != 3 || !id.matches(parts[0])) return null
            val pitch = parts[1].toFloatOrNull()?.takeIf { it in 0.5f..2f } ?: return null
            val level = parts[2].toFloatOrNull()?.takeIf { it in 0f..1f } ?: return null
            return SoundSpec(parts[0], pitch, level)
        }
    }
}

object UiSound {
    var volume = 1f

    private val defaults = mapOf(
        "click" to SoundSpec("minecraft:ui.button.click", 1f, 0.35f),
        "toggle" to SoundSpec("minecraft:ui.button.click", 1.3f, 0.3f),
        "open" to SoundSpec("minecraft:item.book.put", 1f, 0.7f),
        "close" to SoundSpec("minecraft:item.book.put", 0.8f, 0.5f)
    )
    private val events = HashMap<String, SoundEvent>()

    private fun play(event: SoundEvent, pitch: Float, level: Float = 0.6f) {
        if (volume <= 0f) return
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(event, pitch, level * volume))
    }

    private fun play(event: Holder<SoundEvent>, pitch: Float, level: Float = 0.6f) = play(event.value(), pitch, level)

    private fun themed(key: String) {
        val spec = Theme.active().sound[key] ?: defaults[key] ?: return
        play(events.getOrPut(spec.event) { SoundEvent.createVariableRangeEvent(ResourceLocation.parse(spec.event)) }, spec.pitch, spec.level)
    }

    fun click() = themed("click")
    fun hover() = themed("hover")
    fun toggle() = themed("toggle")
    fun open() = themed("open")
    fun close() = themed("close")
    fun page() = play(SoundEvents.BOOK_PAGE_TURN, 1.2f, 0.7f)
    fun success() = play(SoundEvents.NOTE_BLOCK_CHIME, 1.3f)
    fun warning() = play(SoundEvents.NOTE_BLOCK_BIT, 0.8f)
    fun danger() = play(SoundEvents.NOTE_BLOCK_BASS, 0.6f)
    fun levelUp() = play(SoundEvents.PLAYER_LEVELUP, 1f, 0.35f)
    fun complete() = play(SoundEvents.NOTE_BLOCK_BELL, 1.2f)
    fun confirm() = play(SoundEvents.ANVIL_USE, 1.5f, 0.3f)

    fun of(severity: Severity) = when (severity) {
        Severity.SUCCESS -> success()
        Severity.WARNING -> warning()
        Severity.DANGER -> danger()
        else -> {}
    }
}
