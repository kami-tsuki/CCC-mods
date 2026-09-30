package kami.libs.text

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LangTest {
    @Test
    fun langFilesAreConsistent() {
        val extraUsedKeys = listOf("KamiClaims", "KamiEconomy", "KamiEssentials", "KamiGeology")
            .flatMap { LangAudit.referencedKeys(Path.of("../$it/src/main/kotlin")) }
            .filter { it.startsWith("kami_libs.") }
            .toSet()
        val report = LangAudit.audit(Path.of("src/main/resources/assets/kami_libs/lang"), Path.of("src/main/kotlin"), extraUsedKeys = extraUsedKeys)
        assertEquals(emptyList(), report.problems)
        assertEquals(emptyList(), report.unused)
    }
}
