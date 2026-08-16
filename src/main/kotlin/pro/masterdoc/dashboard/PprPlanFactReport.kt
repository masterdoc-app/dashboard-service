package pro.masterdoc.dashboard

import java.time.LocalDate

fun selectPprPlanFact(
    orders: List<WorkOrder>,
    fromDate: LocalDate,
    toDate: LocalDate,
): List<WorkOrder> {
    require(!toDate.isBefore(fromDate)) { "to must be on or after from" }
    return orders
        .filter { it.type == WorkOrderType.ppr }
        .mapNotNull { order ->
            val dueDate = WeekDates.parseDate(order.dueAt) ?: return@mapNotNull null
            if (dueDate in fromDate..toDate) order else null
        }
        .sortedWith(compareBy({ WeekDates.parseDate(it.dueAt)!! }, { it.id }))
}
