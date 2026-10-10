package kami.libs.compat

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ImplLeakTest {
    private val leak = Regex("""^\s*import\s+kami\.libs\.(\w+\.)*impl\.""")
    private val detection = Regex("""\bModList\b|Class\.forName""")

    private fun sources() = Path.of("..").toAbsolutePath().normalize().let { Files.list(it).use { s -> s.toList() } }
        .filter { it.fileName.toString().let { n -> n.startsWith("Kami") && n != "KamiLibs" } }
        .map { it.resolve("src/main") }
        .filter(Files::isDirectory)
        .flatMap { root -> Files.walk(root).use { s -> s.filter { it.toString().let { n -> n.endsWith(".kt") || n.endsWith(".java") } }.toList() } }

    @Test
    fun modsNeverImportLibsImpl() {
        val leaks = sources().flatMap { f -> Files.readAllLines(f).filter(leak::containsMatchIn).map { "$f: $it" } }
        assertTrue(leaks.isEmpty(), leaks.joinToString("\n"))
    }

    @Test
    fun modCompatPackagesNeverDetectOrReflect() {
        val leaks = sources().filter { it.toString().replace('\\', '/').contains("/compat/") }
            .flatMap { f -> Files.readAllLines(f).filter(detection::containsMatchIn).map { "$f: $it" } }
        assertTrue(leaks.isEmpty(), leaks.joinToString("\n"))
    }
}
