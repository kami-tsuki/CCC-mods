package kami.libs.ui.style

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.platform.TextureUtil
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager

class MipTexture(private val location: ResourceLocation, private val levels: Int) : AbstractTexture() {
    override fun load(rm: ResourceManager) {
        val chain = arrayListOf(rm.open(location).use { NativeImage.read(it) })
        var next = 0
        try {
            repeat(levels) { chain += half(chain.last()) }
            TextureUtil.prepareImage(getId(), levels, chain[0].width, chain[0].height)
            while (next < chain.size) {
                val m = chain[next]
                m.upload(next, 0, 0, 0, 0, m.width, m.height, true, true, levels > 0, true)
                next++
            }
        } catch (e: Throwable) {
            for (i in next until chain.size) chain[i].close()
            throw e
        }
    }

    private fun half(s: NativeImage) = NativeImage(s.width / 2, s.height / 2, false).also { d ->
        val p = IntArray(4)
        for (y in 0 until d.height) for (x in 0 until d.width) {
            p[0] = s.getPixelRGBA(2 * x, 2 * y)
            p[1] = s.getPixelRGBA(2 * x + 1, 2 * y)
            p[2] = s.getPixelRGBA(2 * x, 2 * y + 1)
            p[3] = s.getPixelRGBA(2 * x + 1, 2 * y + 1)
            val a = p.sumOf { it ushr 24 }
            fun ch(sh: Int) = if (a == 0) p.sumOf { (it shr sh) and 255 } / 4 else p.sumOf { ((it shr sh) and 255) * (it ushr 24) } / a
            d.setPixelRGBA(x, y, ((a + 2) / 4 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
        }
    }
}
