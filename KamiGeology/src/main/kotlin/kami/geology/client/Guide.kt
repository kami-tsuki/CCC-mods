package kami.geology.client

import kami.geology.net.GuideData
import kotlinx.serialization.json.Json

object Guide {
    private val json = Json { ignoreUnknownKeys = true }
    private val listeners = ArrayList<() -> Unit>()

    var data = GuideData(emptyList(), emptyList())
        private set

    fun receive(json: String) {
        data = runCatching { this.json.decodeFromString<GuideData>(json) }.getOrNull() ?: return
        listeners.toList().forEach { it() }
    }

    fun listen(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }
}
