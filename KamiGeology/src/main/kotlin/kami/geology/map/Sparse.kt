package kami.geology.map

import java.io.ByteArrayOutputStream

object Sparse {
    fun pack(blocksPerColumn: FloatArray): ByteArray {
        val out = ByteArrayOutputStream()
        var count = 0
        val body = ByteArrayOutputStream()
        var last = 0
        for (k in blocksPerColumn.indices) {
            val q = Heatmap.encode(blocksPerColumn[k] * 256.0)
            if (q == 0) continue
            varint(body, k - last)
            body.write(q)
            last = k
            count++
        }
        varint(out, count)
        body.writeTo(out)
        return out.toByteArray()
    }

    fun unpack(data: ByteArray, cells: Int): FloatArray {
        val out = FloatArray(cells)
        var pos = 0
        var count = 0
        var shift = 0
        while (true) {
            val b = data[pos++].toInt()
            count = count or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        var index = 0
        repeat(count) {
            var gap = 0
            shift = 0
            while (true) {
                val b = data[pos++].toInt()
                gap = gap or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            index += gap
            val q = data[pos++].toInt() and 0xFF
            if (index in 0 until cells) out[index] = (Heatmap.decode(q) / 256.0).toFloat()
        }
        return out
    }

    private fun varint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }
}
