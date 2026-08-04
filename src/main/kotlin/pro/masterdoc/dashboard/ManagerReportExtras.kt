package pro.masterdoc.dashboard

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@Serializable
data class KpiTrendsReport(
    val bucket: String,
    val points: List<KpiTrendPoint>,
)

@Serializable
data class KpiTrendPoint(
    val bucketStart: String,
    val mttrHours: Double,
    val mttrSampleSize: Int,
    val mtbfHours: Double,
    val mtbfSampleSize: Int,
    val availabilityPercent: Double,
)

@Serializable
data class ReactiveCompletionReport(
    val createdCount: Int,
    val closedCount: Int,
    val completionRatePercent: Double,
    val emergencyCount: Int,
    val plannedCount: Int,
    val reactivePercent: Double,
)

@Serializable
data class EngineerWorkloadReport(
    val engineers: List<EngineerWorkloadRow>,
)

@Serializable
data class EngineerWorkloadRow(
    val userId: String,
    val closedCount: Int,
    val hours: Double,
)

@Serializable
data class FailureFrequencyReport(
    val assets: List<FailureFrequencyRow>,
)

@Serializable
data class FailureFrequencyRow(
    val assetId: String,
    val emergencyCount: Int,
)

fun computeReactiveCompletion(
    orders: List<WorkOrder>,
    from: Instant,
    to: Instant,
): ReactiveCompletionReport {
    require(!to.isBefore(from)) { "to must be on or after from" }

    val created = orders.filter { it.createdAt.isInRange(from, to) }
    val emergencyCount = created.count { it.type == WorkOrderType.emergency }
    val plannedCount = created.count { it.type == WorkOrderType.ppr }
    val closedCount =
        orders.count {
            it.status == WorkOrderStatus.closed && it.closedAt?.isInRange(from, to) == true
        }

    return ReactiveCompletionReport(
        createdCount = created.size,
        closedCount = closedCount,
        completionRatePercent = percentage(closedCount, created.size),
        emergencyCount = emergencyCount,
        plannedCount = plannedCount,
        reactivePercent = percentage(emergencyCount, emergencyCount + plannedCount),
    )
}

fun computeKpiTrends(
    orders: List<WorkOrder>,
    from: Instant,
    to: Instant,
    now: Instant,
): KpiTrendsReport {
    require(!to.isBefore(from)) { "to must be on or after from" }

    val fromDate = from.atZone(ZoneOffset.UTC).toLocalDate()
    val toDate = to.atZone(ZoneOffset.UTC).toLocalDate()
    val useDailyBuckets = !toDate.isBefore(fromDate) && fromDate.datesUntil(toDate.plusDays(1)).count() <= 30
    val daysPerBucket = if (useDailyBuckets) 1L else 7L
    val bucket = if (useDailyBuckets) "day" else "week"
    if (orders.isEmpty()) return KpiTrendsReport(bucket = bucket, points = emptyList())
    val points =
        generateSequence(fromDate) { it.plusDays(daysPerBucket) }
            .takeWhile { !it.isAfter(toDate) }
            .map { bucketDate ->
                val bucketFrom = maxOf(from, bucketDate.atStartOfDay().toInstant(ZoneOffset.UTC))
                val bucketTo = minOf(to, bucketDate.plusDays(daysPerBucket).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1))
                val kpis = computeManagerKpis(orders, bucketFrom, bucketTo, now)
                KpiTrendPoint(
                    bucketStart = bucketDate.toString(),
                    mttrHours = kpis.mttrHours,
                    mttrSampleSize = kpis.mttrSampleSize,
                    mtbfHours = kpis.mtbfHours,
                    mtbfSampleSize = kpis.mtbfSampleSize,
                    availabilityPercent = kpis.availabilityPercent,
                )
            }.toList()

    return KpiTrendsReport(bucket = bucket, points = points)
}

fun computeEngineerWorkload(
    orders: List<WorkOrder>,
    from: Instant,
    to: Instant,
): EngineerWorkloadReport {
    require(!to.isBefore(from)) { "to must be on or after from" }

    val engineers =
        orders
            .asSequence()
            .filter { it.status == WorkOrderStatus.closed && it.assigneeId != null && it.closedAt?.isInRange(from, to) == true }
            .groupBy { it.assigneeId!! }
            .map { (userId, assignedOrders) ->
                EngineerWorkloadRow(
                    userId = userId,
                    closedCount = assignedOrders.size,
                    hours =
                        assignedOrders.sumOf { order ->
                            val started = order.startedAt?.toInstantOrNull()
                            val closed = order.closedAt?.toInstantOrNull()
                            if (started != null && closed != null) Duration.between(started, closed).toMillis() / 3_600_000.0 else 0.0
                        },
                )
            }.sortedWith(compareByDescending<EngineerWorkloadRow> { it.closedCount }.thenBy { it.userId })

    return EngineerWorkloadReport(engineers)
}

fun computeFailureFrequency(
    orders: List<WorkOrder>,
    from: Instant,
    to: Instant,
): FailureFrequencyReport {
    require(!to.isBefore(from)) { "to must be on or after from" }

    val assets =
        orders
            .asSequence()
            .filter { it.type == WorkOrderType.emergency && it.createdAt.isInRange(from, to) }
            .groupingBy { it.assetId }
            .eachCount()
            .map { (assetId, emergencyCount) -> FailureFrequencyRow(assetId, emergencyCount) }
            .sortedWith(compareByDescending<FailureFrequencyRow> { it.emergencyCount }.thenBy { it.assetId })
            .take(15)

    return FailureFrequencyReport(assets)
}

private fun percentage(numerator: Int, denominator: Int): Double =
    if (denominator == 0) 0.0 else numerator * 100.0 / denominator

private fun String.isInRange(from: Instant, to: Instant): Boolean =
    toInstantOrNull()?.let { it >= from && it <= to } == true

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
