package kami.libs.xaero

import xaero.map.highlight.ChunkHighlighter
import xaero.map.highlight.HighlighterRegistry

object Highlights {
    private val pending = mutableListOf<ChunkHighlighter>()

    fun register(highlighter: ChunkHighlighter) {
        pending += highlighter
    }

    fun attach(registry: Any) {
        (registry as? HighlighterRegistry)?.let { r -> pending.forEach(r::register) }
    }
}
