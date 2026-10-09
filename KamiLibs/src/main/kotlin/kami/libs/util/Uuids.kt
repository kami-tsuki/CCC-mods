package kami.libs.util

import java.util.UUID

fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
