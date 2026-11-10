package kami.libs.config

import kami.libs.log.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

internal fun restrict(path: Path) {
    try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    } catch (_: UnsupportedOperationException) {
    } catch (e: Exception) {
        Log.of("config").warn("Cannot restrict the permissions of {}: {}", path, e.message)
    }
}
