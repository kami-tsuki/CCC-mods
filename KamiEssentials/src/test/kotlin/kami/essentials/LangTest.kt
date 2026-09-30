package kami.essentials

import kami.libs.text.LangAudit
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LangTest {
    @Test
    fun langFilesAreConsistent() {
        val shared = LangAudit.load(Path.of("../KamiLibs/src/main/resources/assets/kami_libs/lang/en_us.json"))
        val report = LangAudit.audit(Path.of("src/main/resources/assets/kami_essentials/lang"), Path.of("src/main/kotlin"), shared)
        assertEquals(emptyList(), report.problems)
        assertEquals(emptyList(), report.unused)
    }
}
