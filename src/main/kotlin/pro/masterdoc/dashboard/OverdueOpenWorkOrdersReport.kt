package pro.masterdoc.dashboard

import java.time.LocalDate

fun selectOverdueOpenWorkOrders(
    orders: List<WorkOrder>,
    today: LocalDate,
): List<WorkOrder> =
    orders
        .filter { it.status == WorkOrderStatus.new || it.status == WorkOrderStatus.in_progress }
        .filter { due ->
            val d = WeekDates.parseDate(due.dueAt) ?: return@filter false
            d.isBefore(today)
        }
        .sortedWith(compareBy({ it.dueAt }, { it.id }))
