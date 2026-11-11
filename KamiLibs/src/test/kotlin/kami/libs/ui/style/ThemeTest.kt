package kami.libs.ui.style

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemeTest {
    private fun defs() = Theme.families().flatMap { listOfNotNull(it.light, it.dark) }

    private fun def(id: String, base: String?, colors: Map<String, Int> = emptyMap(), family: String = id, variant: Variant = Variant.LIGHT) =
        ThemeDef(id, family, id, base, variant, colors)

    @AfterTest
    fun reset() {
        Theme.install(emptyList())
        Theme.use("minecraft", Mode.LIGHT)
        Palette.atlas = emptyMap()
        Palette.vision = Palette.Vision.NORMAL
    }

    @Test
    fun builtInFamiliesAndVariants() {
        val families = Theme.families().associateBy { it.id }
        assertEquals(setOf("minecraft", "light", "dark", "office", "vista", "cyberpunk", "high_contrast"), families.keys)
        assertTrue(families.getValue("minecraft").light != null && families.getValue("minecraft").dark != null)
    }

    @Test
    fun everyThemeResolvesEveryToken() {
        defs().forEach { def ->
            val resolved = ThemeLoader.resolve(def, Theme::lookup)
            assertTrue(Tokens.names.all { it in resolved.values }, def.id)
            assertEquals(Tokens.CHART, resolved.chart.size, def.id)
        }
    }

    @Test
    fun minecraftLightKeepsLegacyValues() {
        Theme.use("minecraft", Mode.LIGHT)
        assertEquals(0xFFC6C6C6.toInt(), Palette.canvas)
        assertEquals(0x88000000.toInt(), Palette.backdrop)
        assertEquals(0xF8C6C6C6.toInt(), Palette.overlay)
        assertEquals(0xC07A7A7A.toInt(), Palette.iconOff)
        assertEquals(0xF0100010.toInt(), Palette.tipBg)
        assertEquals(0xFF2F66B0.toInt(), Palette.brass)
        assertEquals(Palette.brass, Palette.accent)
        assertEquals(0xFFB87800.toInt(), Palette.chart[0])
        assertEquals(0xFF6E6E6E.toInt(), Palette.chart[7])
    }

    @Test
    fun jsonRoundTrip() {
        defs().forEach { assertEquals(it, ThemeLoader.parse(ThemeLoader.toJson(it)), it.id) }
        val parsed = ThemeLoader.parse("""{"id":"x","family":"x","variant":"dark","base":"minecraft","color":{"accent":"#11223344"}}""")
        assertEquals(mapOf("border.brass" to 0x44112233), parsed.colors)
        assertEquals("x", parsed.name)
        assertEquals(Variant.DARK, parsed.variant)
    }

    @Test
    fun overridesParse() {
        assertEquals(mapOf("bg.canvas" to 0xFF123456.toInt()), ThemeLoader.parseOverrides("""{"bg.canvas":"#123456","nope":"#000000"}"""))
        assertFailsWith<IllegalArgumentException> { Tokens.parse("#12345") }
    }

    @Test
    fun inheritanceFillsMissingKeys() {
        val child = def("child", "minecraft", mapOf("bg.canvas" to 0xFF010203.toInt()))
        val resolved = ThemeLoader.resolve(child, Theme::lookup)
        assertEquals(0xFF010203.toInt(), resolved.values.getValue("bg.canvas"))
        assertEquals(0xFFC6C6C6.toInt(), resolved.values.getValue("bg.surface"))
        assertEquals(0xFFB87800.toInt(), resolved.chart[0])
        assertEquals(emptyList(), Theme.install(listOf(child)))
        Theme.use("child", Mode.LIGHT)
        assertEquals(0xFF010203.toInt(), Palette.canvas)
    }

    @Test
    fun cyclesAndBrokenThemesAreRejected() {
        val a = def("a", "b")
        val b = def("b", "a")
        val lookup = mapOf("a" to a, "b" to b)::get
        assertFailsWith<IllegalArgumentException> { ThemeLoader.resolve(a, lookup) }
        assertFailsWith<IllegalArgumentException> { ThemeLoader.resolve(def("c", "missing"), lookup) }
        assertFailsWith<IllegalArgumentException> { ThemeLoader.resolve(def("d", null), lookup) }
        assertEquals(setOf("a", "b", "c", "d"), Theme.install(listOf(a, b, def("c", "missing"), def("d", null))).toSet())
        assertEquals(setOf("minecraft", "light", "dark", "office", "vista", "cyberpunk", "high_contrast"), Theme.families().map { it.id }.toSet())
    }

    @Test
    fun autoModeFollowsClock() {
        (0..23).forEach { assertEquals(if (it in 7..18) Mode.LIGHT else Mode.DARK, Theme.resolve(Mode.AUTO, it), "hour $it") }
        (0..23).forEach {
            assertEquals(Mode.LIGHT, Theme.resolve(Mode.LIGHT, it))
            assertEquals(Mode.DARK, Theme.resolve(Mode.DARK, it))
        }
    }

    @Test
    fun usePicksVariantAndBumpsVersion() {
        val before = Theme.version
        Theme.use("minecraft", Mode.DARK)
        assertEquals("minecraft_dark", Theme.current().id)
        assertTrue(Theme.version > before)
        Theme.use("dark", Mode.LIGHT)
        assertEquals("dark", Theme.current().id)
        Theme.use("light", Mode.DARK)
        assertEquals("light", Theme.current().id)
        Theme.use("missing", Mode.LIGHT)
        assertEquals("minecraft", Theme.current().id)
    }

    @Test
    fun paletteFollowsThemeAndScopes() {
        assertEquals(Tokens.names.toSet(), Palette.names())
        Theme.use("minecraft", Mode.DARK)
        assertEquals(0xFF373737.toInt(), Palette.canvas)
        Theme.use("minecraft", Mode.LIGHT)
        val dark = defs().first { it.id == "dark" }
        assertEquals(0xFF15171B.toInt(), Palette.withTheme(dark) { Palette.canvas })
        assertEquals(0xFFC6C6C6.toInt(), Palette.canvas)
        assertFailsWith<IllegalStateException> { Palette.withTheme(dark) { error("x") } }
        assertEquals(0xFFC6C6C6.toInt(), Palette.canvas)
    }

    @Test
    fun atlasAndVisionLayerOnTheme() {
        Palette.atlas = mapOf("bg.canvas" to 0xFF111111.toInt())
        assertEquals(0xFF111111.toInt(), Palette.canvas)
        Palette.vision = Palette.Vision.DEUTERANOPIA
        assertEquals(0xFF000080.toInt(), Palette.success)
        Theme.use("minecraft", Mode.DARK)
        assertEquals(0xFF4080E0.toInt(), Palette.success)
        assertEquals(0xFF111111.toInt(), Palette.canvas)
    }

    @Test
    fun tooltipPairsFollowTokens() {
        listOf("minecraft" to Mode.LIGHT, "minecraft" to Mode.DARK, "dark" to Mode.DARK).forEach { (family, mode) ->
            Theme.use(family, mode)
            assertEquals(Palette.tipText, Palette.forTip(Palette.text))
            assertEquals(Palette.tipSecondary, Palette.forTip(Palette.textSecondary))
            assertEquals(Palette.tipMuted, Palette.forTip(Palette.textMuted))
            assertNotEquals(Palette.tipText, Palette.forTip(Palette.danger))
        }
    }
}
