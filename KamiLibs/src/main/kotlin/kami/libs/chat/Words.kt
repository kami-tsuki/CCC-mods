package kami.libs.chat

import kami.libs.text.Phrase
import kami.libs.ui.style.Format

fun duration(seconds: Long): Phrase {
    fun unit(name: String, n: Long) = Phrase.of("kami_libs.unit.$name.short", Format.number(n))
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3600
    val minutes = seconds % 3600 / 60
    return when {
        days > 0 -> Phrase.of("kami_libs.format.join", unit("day", days), unit("hour", hours))
        hours > 0 -> Phrase.of("kami_libs.format.join", unit("hour", hours), unit("minute", minutes))
        else -> unit("minute", minutes)
    }
}
