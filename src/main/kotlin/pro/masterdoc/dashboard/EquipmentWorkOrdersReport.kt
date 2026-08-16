package pro.masterdoc.dashboard

import java.time.Instant

fun selectEquipmentWorkOrders(
    orders: List<WorkOrder>,
    assetId: String,
    from: Instant,
    to: Instant,
): List<WorkOrder> {
    require(assetId.isNotBlank()) { "assetId required" }
    require(!to.isBefore(from)) { "to must be on or after from" }
    return orders
        .filter { it.assetId == assetId }
        .filter { overlapsPeriod(it, from, to) }
        .sortedWith(
            compareByDescending<WorkOrder> { it.createdAt.toInstantOrNull() ?: Instant.EPOCH }
                .thenByDescending { it.id },
        )
}

private fun overlapsPeriod(order: WorkOrder, from: Instant, to: Instant): Boolean {
    val start = order.createdAt.toInstantOrNull() ?: return false
    val end = order.closedAt?.toInstantOrNull() ?: to
    return !start.isAfter(to) && !end.isBefore(from)
}

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
