package kami.libs.net

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

const val DEFLATE_ABOVE = 512 * 1024

class WireWriter {
    private val out = ByteArrayOutputStream()

    fun varLong(value: Long) = apply {
        var rest = value
        while (rest and 0x7FL.inv() != 0L) {
            out.write(((rest and 0x7F) or 0x80).toInt())
            rest = rest ushr 7
        }
        out.write(rest.toInt())
    }

    fun varInt(value: Int) = varLong(value.toLong() and 0xFFFFFFFFL)

    fun bool(value: Boolean) = varInt(if (value) 1 else 0)

    fun utf(value: String, max: Int) = apply {
        require(value.length <= max) { "text too long" }
        bytes(value.toByteArray(Charsets.UTF_8))
    }

    fun bytes(value: ByteArray) = apply {
        varInt(value.size)
        out.write(value)
    }

    fun <T> list(items: List<T>, write: (T) -> Unit) = apply {
        varInt(items.size)
        items.forEach(write)
    }

    fun nullableInt(value: Int?) = apply {
        bool(value != null)
        if (value != null) varInt(value)
    }

    fun toBytes(): ByteArray = out.toByteArray()
}

class WireReader(private val data: ByteArray) {
    private var at = 0

    private val remaining get() = data.size - at

    fun varLong(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(at < data.size && shift < 64) { "truncated number" }
            val b = data[at++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun varInt(): Int = varLong().also { require(it in 0..0xFFFFFFFFL) { "bad number" } }.toInt()

    fun bool() = when (varInt()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("bad flag")
    }

    fun count(max: Int) = varInt().also { require(it in 0..max) { "list too long" } }

    fun bytes(max: Int): ByteArray {
        val size = count(max)
        require(size <= remaining) { "truncated data" }
        return data.copyOfRange(at, at + size).also { at += size }
    }

    fun utf(max: Int) = String(bytes(max * 3), Charsets.UTF_8).also { require(it.length <= max) { "text too long" } }

    fun <T> list(max: Int, read: () -> T): List<T> = List(count(max)) { read() }

    fun nullableInt() = if (bool()) varInt() else null

    fun <E : Enum<E>> enum(values: List<E>) = values.getOrNull(varInt()) ?: throw IllegalArgumentException("bad enum")
}

object Blob {
    fun pack(raw: ByteArray, deflateAbove: Int = DEFLATE_ABOVE): ByteArray {
        val packed = if (raw.size > deflateAbove) deflate(raw) else null
        return WireWriter().bool(packed != null).varInt(raw.size).bytes(packed ?: raw).toBytes()
    }

    fun unpack(data: ByteArray, maxBytes: Int): ByteArray {
        val reader = WireReader(data)
        val packed = reader.bool()
        val size = reader.count(maxBytes)
        val body = reader.bytes(maxBytes)
        val raw = if (packed) inflate(body, size) else body
        require(raw.size == size) { "blob size mismatch" }
        return raw
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(packed: ByteArray, size: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(packed)
        val out = ByteArray(size)
        val read = runCatching { inflater.inflate(out) }.getOrElse { -1 }
        val complete = read == size && inflater.finished()
        inflater.end()
        require(complete) { "corrupt blob" }
        return out
    }
}
