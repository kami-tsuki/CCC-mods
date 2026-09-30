package kami.geology

import kami.libs.text.LangAudit
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LangTest {
    @Test
    fun langFilesAreConsistent() {
        val shared = LangAudit.load(Path.of("../KamiLibs/src/main/resources/assets/kami_libs/lang/en_us.json"))
        val prospectorKeys = (1..8).map { "item.kami_geology.prospector_t$it" }.toSet()
        val report = LangAudit.audit(
            Path.of("src/main/resources/assets/kami_geology/lang"),
            Path.of("src/main/kotlin"),
            shared,
            prospectorKeys
        )
        assertEquals(emptyList(), report.problems)
        assertEquals(emptyList(), report.unused)
    }
}
