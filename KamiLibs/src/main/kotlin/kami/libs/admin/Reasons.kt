package kami.libs.admin

object Reasons {
    const val MIN = 3
    const val MAX = 256

    fun clean(raw: String?): String? = raw?.filterNot { it.isISOControl() || it == '§' || Character.getType(it) == Character.FORMAT.toInt() }?.trim()?.takeIf { it.length in MIN..MAX }
}
