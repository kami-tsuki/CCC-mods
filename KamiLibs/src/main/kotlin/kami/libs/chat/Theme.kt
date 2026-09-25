package kami.libs.chat

object Theme {
    const val ACCENT = 0xE8B04B
    const val TEXT = 0xCFCFD4
    const val MUTED = 0x85858F
    const val VALUE = 0xFFFFFF
    const val LINK = 0x6CC4F0
    const val OK = 0x7CD992
    const val WARN = 0xF2C14E
    const val WARN_TEXT = 0xF5D98E
    const val BAD = 0xF06A6A
    const val BAD_TEXT = 0xF4A3A3
    const val SEP = " › "
}

enum class Tone(val mark: Int, val body: Int) {
    INFO(Theme.MUTED, Theme.TEXT),
    OK(Theme.OK, Theme.TEXT),
    WARN(Theme.WARN, Theme.WARN_TEXT),
    BAD(Theme.BAD, Theme.BAD_TEXT);

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: INFO
    }
}
