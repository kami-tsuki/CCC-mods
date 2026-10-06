package kami.essentials.admin

import kami.libs.admin.AdminMetrics
import kami.libs.admin.AdminPerms
import kami.libs.admin.Audit
import kami.libs.economy.ServerBank
import kami.libs.util.Time
import net.minecraft.server.MinecraftServer
import java.time.LocalDate
import java.time.ZoneId

object ServerStats {
    private const val SAMPLE_TICKS = 6000
    private const val SAMPLES = 288
    private val series = ArrayDeque<Int>()
    private var started = System.currentTimeMillis()

    fun start() {
        started = System.currentTimeMillis()
        series.clear()
    }

    fun tick(server: MinecraftServer) {
        if (server.tickCount % SAMPLE_TICKS != 0) return
        series.addLast(server.playerCount)
        while (series.size > SAMPLES) series.removeFirst()
    }

    fun snapshot(server: MinecraftServer, level: AdminPerms.Level): Dashboard {
        val now = System.currentTimeMillis()
        val mspt = server.averageTickTimeNanos / 1_000_000.0
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val online = server.playerList.players.map { it.uuid }.toSet()
        val records = PlayerRecords.all()
        val runtime = Runtime.getRuntime()
        val day = now / Time.DAY_MS
        val past = 1.0 - (now % Time.DAY_MS).toDouble() / Time.DAY_MS
        val net = ServerBank.days(day - 1).sumOf { (it.inflow - it.outflow) * if (it.day == day) 1.0 else past }.toLong()
        return Dashboard(
            tps = minOf(20.0, 1000.0 / mspt.coerceAtLeast(0.001)),
            mspt = mspt,
            online = server.playerCount,
            max = server.maxPlayers,
            uptimeMs = now - started,
            memUsed = runtime.totalMemory() - runtime.freeMemory(),
            memMax = runtime.maxMemory(),
            uniqueToday = records.count { it.lastSeen >= today || it.id in online },
            newToday = records.count { it.firstJoin >= today },
            bank = ServerBank.balance,
            bankNet24h = net,
            metrics = AdminMetrics.snapshot(),
            audit = Audit.query(Audit.Filter(), 0, 8, level).rows,
            onlineSeries = series.toList()
        )
    }
}
