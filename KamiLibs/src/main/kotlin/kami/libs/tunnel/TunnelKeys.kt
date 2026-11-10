package kami.libs.tunnel

import kami.libs.config.ConfigFolder
import kami.libs.config.restrict
import kotlinx.serialization.Serializable
import net.neoforged.fml.loading.FMLPaths
import java.net.URI

@Serializable
data class TunnelKeys(val enabled: Boolean = false, val url: String = "", val server: String = "", val token: String = "") {
    override fun toString() = "TunnelKeys(***)"

    fun problem(): String? {
        val uri = runCatching { URI(url) }.getOrNull()
        return when {
            uri == null || uri.host == null -> "url must be an address like https://mc-ingest.example.org"
            uri.scheme != "https" && !(uri.scheme == "http" && uri.host in LOOPBACK) -> "url must use https"
            uri.rawPath.orEmpty().isNotEmpty() || uri.rawQuery != null || uri.rawUserInfo != null -> "url must not contain a path, query or login"
            !SERVER.matches(server) -> "server must be 1-32 characters of a-z, 0-9 and -"
            token.length < MIN_TOKEN || !TOKEN.matches(token) -> "token must be at least $MIN_TOKEN printable characters without spaces"
            else -> null
        }
    }
}

private val SERVER = Regex("[a-z0-9][a-z0-9-]{0,31}")
private val TOKEN = Regex("[\\x21-\\x7e]+")
private val LOOPBACK = setOf("localhost", "127.0.0.1")
private const val MIN_TOKEN = 32

internal object TunnelFile {
    private const val NAME = "kami-tunnel.json"

    private val folder by lazy { ConfigFolder(FMLPaths.CONFIGDIR.get(), "Restart the server to apply", prepare = ::restrict) }

    private val docs = mapOf(
        "enabled" to "Open the tunnel that lets the cluster read this server's statistics live. Nothing is sent while it is false.",
        "url" to "Address of the connector, for example https://mc-ingest.tsuki.wtf. Must be https and have no path.",
        "server" to "Name of this server in the connector, for example mc-server-1.",
        "token" to "Secret of this server, created together with the connector. Keep it secret."
    )

    fun load(): TunnelKeys {
        folder.startLoad()
        val keys = folder.file(NAME, TunnelKeys.serializer(), TunnelKeys(), docs, "Kami tunnel keys")
        restrict(FMLPaths.CONFIGDIR.get().resolve(NAME))
        return keys.copy(url = keys.url.trim().trimEnd('/'), server = keys.server.trim(), token = keys.token.trim())
    }
}
