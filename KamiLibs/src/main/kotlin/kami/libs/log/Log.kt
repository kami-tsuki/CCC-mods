package kami.libs.log

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class Log private constructor(mod: String) {
    private val tag = "[KAMI|${mod.uppercase()}|"
    private val out: Logger = LoggerFactory.getLogger("kami.$mod")

    fun debug(msg: String, vararg args: Any?) {
        if (out.isDebugEnabled) out.debug(line("DEBUG", msg), *args)
    }

    fun info(msg: String, vararg args: Any?) = out.info(line("INFO", msg), *args)
    fun warn(msg: String, vararg args: Any?) = out.warn(line("WARN", msg), *args)
    fun error(msg: String, vararg args: Any?) = out.error(line("ERROR", msg), *args)

    private fun line(level: String, msg: String) = "$tag$level] $msg"

    companion object {
        private val all = ConcurrentHashMap<String, Log>()

        fun of(mod: String): Log = all.computeIfAbsent(mod, ::Log)
    }
}
