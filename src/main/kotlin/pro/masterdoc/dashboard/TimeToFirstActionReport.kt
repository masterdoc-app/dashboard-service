package pro.masterdoc.dashboard

import java.time.Duration
import java.time.Instant

fun selectTimeToFirstAction(
    orders: List<WorkOrder>,
    from: Instant,
    to: Instant,
): List<WorkOrder> {
    require(!to.isBefore(from)) { "to must be on or after from" }
    return orders
        .filter { createdAtInPeriod(it, from, to) }
        .sortedWith(
            compareBy<WorkOrder> { it.startedAt != null }
                .thenByDescending { reactionDurationMillis(it) ?: 0L }
                .thenByDescending { it.id },
        )
}

private fun createdAtInPeriod(order: WorkOrder, from: Instant, to: Instant): Boolean {
    val createdAt = order.createdAt.toInstantOrNull() ?: return false
    return !createdAt.isBefore(from) && !createdAt.isAfter(to)
}

private fun reactionDurationMillis(order: WorkOrder): Long? {
    val createdAt = order.createdAt.toInstantOrNull() ?: return null
    val startedAt = order.startedAt?.toInstantOrNull() ?: return null
    return Duration.between(createdAt, startedAt).toMillis()
}

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
