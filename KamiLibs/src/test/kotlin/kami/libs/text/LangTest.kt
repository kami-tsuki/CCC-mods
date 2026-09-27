package kami.libs.text

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LangTest {
    @Test
    fun langFilesAreConsistent() {
        val report = LangAudit.audit(Path.of("src/main/resources/assets/kami_libs/lang"), Path.of("src/main/kotlin"))
        report.unused.forEach { println("unused key: $it") }
        assertEquals(emptyList(), report.problems)
    }
}
