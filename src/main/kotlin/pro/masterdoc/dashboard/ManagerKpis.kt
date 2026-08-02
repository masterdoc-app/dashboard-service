package pro.masterdoc.dashboard

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.max

@Serializable
data class ManagerKpis(
    val from: String,
    val to: String,
    val mttrHours: Double,
    val mttrSampleSize: Int,
    val mtbfHours: Double,
    val mtbfSampleSize: Int,
    val plannedCount: Int,
    val emergencyCount: Int,
    val plannedHours: Double,
    val emergencyHours: Double,
    val pprOnTime: Int,
    val pprLate: Int,
    val pprOpenOverdue: Int,
    val pprOpenPending: Int,
    val backlogUnder7d: Int,
    val backlog7to30d: Int,
    val backlogOver30d: Int,
    val backlogOverdue: Int,
    val downtimeRanking: List<ManagerKpiDowntimeRow>,
    val availabilityPercent: Double,
)

@Serializable
data class ManagerKpiDowntimeRow(
    val assetId: String,
    val downtimeHours: Double,
    val openIntervals: Int,
)

fun computeManagerKpis(
    orders: List<WorkOrder>,
    from: Instant,
    to: Instant,
    now: Instant,
): ManagerKpis {
    require(!to.isBefore(from)) { "to must be on or after from" }

    val fromDate = from.atZone(ZoneOffset.UTC).toLocalDate()
    val toDate = to.atZone(ZoneOffset.UTC).toLocalDate()
    val today = now.atZone(ZoneOffset.UTC).toLocalDate()
    val periodOrders = orders.filter { it.createdAt.instantInRange(from, to) }
    val mttrDurations =
        orders
            .asSequence()
            .filter { it.type == WorkOrderType.emergency && it.status == WorkOrderStatus.closed }
            .mapNotNull { order ->
                val started = order.startedAt?.parseInstant() ?: return@mapNotNull null
                val closed = order.closedAt?.parseInstant() ?: return@mapNotNull null
                if (started <= to && closed >= from) closed.hoursSince(started) else null
            }.toList()

    val failuresByAsset =
        orders
            .asSequence()
            .filter { it.type == WorkOrderType.emergency && it.status == WorkOrderStatus.closed }
            .mapNotNull { order -> order.closedAt?.parseInstant()?.let { order.assetId to it } }
            .filter { (_, closed) -> !closed.isAfter(to) }
            .groupBy({ it.first }, { it.second })
    val assetMtbfs =
        failuresByAsset.values.mapNotNull { failures ->
            val sorted = failures.sorted()
            if (sorted.size < 2) {
                null
            } else {
                sorted.zipWithNext().map { (previous, current) -> current.hoursSince(previous) }.average()
            }
        }

    val pprOrders = orders.filter { it.type == WorkOrderType.ppr && it.dueAt.dueAtDate() in fromDate..toDate }
    var pprOnTime = 0
    var pprLate = 0
    var pprOpenOverdue = 0
    var pprOpenPending = 0
    pprOrders.forEach { order ->
        val closedDate = order.closedAt?.parseInstant()?.atZone(ZoneOffset.UTC)?.toLocalDate()
        when {
            order.status == WorkOrderStatus.closed && closedDate != null && closedDate <= order.dueAt.dueAtDate() -> pprOnTime++
            order.status == WorkOrderStatus.closed && closedDate != null -> pprLate++
            order.dueAt.dueAtDate() < today -> pprOpenOverdue++
            else -> pprOpenPending++
        }
    }

    val backlog = orders.filter { it.status == WorkOrderStatus.new || it.status == WorkOrderStatus.in_progress }
    val ageDays = backlog.map { Duration.between(it.createdAt.parseInstant() ?: now, now).toHours() / 24.0 }
    val backlogUnder7d = ageDays.count { it < 7 }
    val backlog7to30d = ageDays.count { it >= 7 && it <= 30 }
    val backlogOver30d = ageDays.count { it > 30 }
    val backlogOverdue = backlog.count { it.dueAt.dueAtDate() < today }

    val downtimeByAsset =
        orders
            .asSequence()
            .filter { it.type == WorkOrderType.emergency && it.startedAt != null }
            .mapNotNull { order ->
                val started = order.startedAt!!.parseInstant() ?: return@mapNotNull null
                val end =
                    if (order.status == WorkOrderStatus.closed) {
                        order.closedAt?.parseInstant() ?: now
                    } else {
                        now
                    }
                val overlapStart = maxInstant(started, from)
                val overlapEnd = minInstant(end, to)
                if (overlapEnd <= overlapStart) {
                    null
                } else {
                    Triple(order.assetId, overlapEnd.hoursSince(overlapStart), order.status != WorkOrderStatus.closed || order.closedAt == null)
                }
            }
            .groupBy { it.first }
            .map { (assetId, intervals) ->
                ManagerKpiDowntimeRow(
                    assetId = assetId,
                    downtimeHours = intervals.sumOf { it.second },
                    openIntervals = intervals.count { it.third },
                )
            }
    val ranking =
        downtimeByAsset
            .sortedByDescending { it.downtimeHours }
            .take(20)
    val totalDowntime = downtimeByAsset.sumOf { it.downtimeHours }
    val denominatorAssetCount =
        if (downtimeByAsset.isNotEmpty()) downtimeByAsset.map { it.assetId }.distinct().size
        else periodOrders.map { it.assetId }.distinct().size.coerceAtLeast(1)
    val periodHours = to.hoursSince(from)
    // Availability uses emergency downtime and the ranked-asset count; with no downtime it is always 100%.
    val availability =
        if (totalDowntime == 0.0) 100.0
        else 100.0 * (1.0 - totalDowntime / (denominatorAssetCount * periodHours))

    return ManagerKpis(
        from = fromDate.toString(),
        to = toDate.toString(),
        mttrHours = mttrDurations.averageOrZero(),
        mttrSampleSize = mttrDurations.size,
        mtbfHours = assetMtbfs.averageOrZero(),
        mtbfSampleSize = assetMtbfs.size,
        plannedCount = periodOrders.count { it.type == WorkOrderType.ppr },
        emergencyCount = periodOrders.count { it.type == WorkOrderType.emergency },
        plannedHours = periodOrders.filter { it.type == WorkOrderType.ppr }.sumOf { it.repairHours() },
        emergencyHours = periodOrders.filter { it.type == WorkOrderType.emergency }.sumOf { it.repairHours() },
        pprOnTime = pprOnTime,
        pprLate = pprLate,
        pprOpenOverdue = pprOpenOverdue,
        pprOpenPending = pprOpenPending,
        backlogUnder7d = backlogUnder7d,
        backlog7to30d = backlog7to30d,
        backlogOver30d = backlogOver30d,
        backlogOverdue = backlogOverdue,
        downtimeRanking = ranking,
        availabilityPercent = availability,
    )
}

private fun WorkOrder.repairHours(): Double {
    if (status != WorkOrderStatus.closed) return if (type == WorkOrderType.ppr) durationHours.toDouble() else 0.0
    val started = startedAt?.parseInstant()
    val closed = closedAt?.parseInstant()
    return if (started != null && closed != null) closed.hoursSince(started) else durationHours.toDouble()
}

private fun String.dueAtDate(): LocalDate = LocalDate.parse(this)

private fun String.parseInstant(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

private fun String.instantInRange(from: Instant, to: Instant): Boolean =
    parseInstant()?.let { it >= from && it <= to } == true

private fun Instant.hoursSince(other: Instant): Double = Duration.between(other, this).toMillis() / 3_600_000.0

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

private fun maxInstant(first: Instant, second: Instant): Instant = if (first >= second) first else second

private fun minInstant(first: Instant, second: Instant): Instant = if (first <= second) first else second
