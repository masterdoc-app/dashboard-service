package pro.masterdoc.dashboard

import java.time.Instant

fun selectSiteWorkOrders(
    orders: List<WorkOrder>,
    siteId: String,
    from: Instant,
    to: Instant,
): List<WorkOrder> {
    require(siteId.isNotBlank()) { "siteId required" }
    require(!to.isBefore(from)) { "to must be on or after from" }
    return orders
        .filter { it.siteId == siteId }
        .filter { overlapsPeriod(it, from, to) }
        .sortedWith(
            compareByDescending<WorkOrder> { it.createdAt.toInstantOrNull() ?: Instant.EPOCH }
                .thenByDescending { it.id },
        )
}

private fun overlapsPeriod(order: WorkOrder, from: Instant, to: Instant): Boolean {
    val start = order.createdAt.toInstantOrNull() ?: return false
    val end =
        if (order.closedAt == null) {
            to
        } else {
            order.closedAt.toInstantOrNull() ?: return false
        }
    return !start.isAfter(to) && !end.isBefore(from)
}

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
