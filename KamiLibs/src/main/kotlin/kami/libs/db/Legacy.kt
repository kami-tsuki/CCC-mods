package kami.libs.db

import kami.libs.config.Configs
import kami.libs.log.Log
import kotlinx.serialization.KSerializer
import java.nio.file.Files
import java.nio.file.Path

object Legacy {
    private val log = Log.of("library")

    fun <T> read(world: Path, file: String, serializer: KSerializer<T>): T? {
        val main = world.resolve(file)
        val files = listOf("", ".bak", ".bak2").map { world.resolve(file + it) }.filter { Files.exists(it) }
        if (files.isEmpty()) {
            if (Files.exists(world.resolve("$file.migrated"))) throw IllegalStateException(
                "$file is missing but $file.migrated exists, so this world was already imported into kami.db; restore the kami.db backup or rename the .migrated files back"
            )
            return null
        }
        val failures = ArrayList<Throwable>()
        for (f in files) runCatching { Configs.json().decodeFromString(serializer, Files.readString(f)) }
            .onSuccess {
                if (f != main) log.error("{} could not be read, imported its backup {} instead", file, f.fileName)
                return it
            }.onFailure(failures::add)
        throw IllegalStateException("unreadable $file and its backups", failures.first())
    }
}
