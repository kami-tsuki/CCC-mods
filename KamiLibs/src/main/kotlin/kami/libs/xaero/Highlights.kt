package kami.libs.xaero

import xaero.map.highlight.ChunkHighlighter
import xaero.map.highlight.HighlighterRegistry

object Highlights {
    private val pending = mutableListOf<ChunkHighlighter>()
    private var attached: HighlighterRegistry? = null

    @Synchronized
    fun register(highlighter: ChunkHighlighter) {
        pending += highlighter
        attached?.register(highlighter)
    }

    @Synchronized
    fun attach(registry: Any) {
        (registry as? HighlighterRegistry)?.let { r -> attached = r; pending.forEach(r::register) }
    }
}
