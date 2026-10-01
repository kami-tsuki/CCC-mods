package kami.claims.client.compat

data class HiddenDiff(val hide: Set<String>, val unhide: Set<String>) {
    val empty get() = hide.isEmpty() && unhide.isEmpty()

    companion object {
        fun of(previous: Set<String>, next: Set<String>) = HiddenDiff(next - previous, previous - next)
    }
}
