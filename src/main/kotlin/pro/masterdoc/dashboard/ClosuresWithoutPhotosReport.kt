package pro.masterdoc.dashboard

import java.time.Instant

fun selectClosuresWithoutPhotos(
    orders: List<WorkOrder>,
    from: Instant,
    to: Instant,
): List<WorkOrder> {
    require(!to.isBefore(from)) { "to must be on or after from" }
    return orders
        .filter { it.status == WorkOrderStatus.closed }
        .filter { closedAtInPeriod(it, from, to) }
        .filter { it.attachmentIds.isEmpty() }
        .sortedWith(
            compareByDescending<WorkOrder> { it.closedAt?.toInstantOrNull() ?: Instant.EPOCH }
                .thenByDescending { it.id },
        )
}

private fun closedAtInPeriod(order: WorkOrder, from: Instant, to: Instant): Boolean {
    val closedAt = order.closedAt?.toInstantOrNull() ?: return false
    return !closedAt.isBefore(from) && !closedAt.isAfter(to)
}

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
