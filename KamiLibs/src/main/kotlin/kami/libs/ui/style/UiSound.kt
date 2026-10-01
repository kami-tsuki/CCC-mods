package kami.libs.ui.style

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.Holder
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

object UiSound {
    var volume = 1f

    private fun play(event: SoundEvent, pitch: Float, level: Float = 0.6f) {
        if (volume <= 0f) return
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(event, pitch, level * volume))
    }

    private fun play(event: Holder<SoundEvent>, pitch: Float, level: Float = 0.6f) = play(event.value(), pitch, level)

    fun click() = play(SoundEvents.UI_BUTTON_CLICK, 1f, 0.35f)
    fun page() = play(SoundEvents.BOOK_PAGE_TURN, 1.2f, 0.7f)
    fun open() = play(SoundEvents.BOOK_PUT, 1f, 0.7f)
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
