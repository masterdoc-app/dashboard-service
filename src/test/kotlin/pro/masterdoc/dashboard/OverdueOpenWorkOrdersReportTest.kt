package pro.masterdoc.dashboard

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class OverdueOpenWorkOrdersReportTest {
    private val today = LocalDate.parse("2026-08-16")

    @Test
    fun includesOpenPastDueExcludesClosedOnTimeAndBadDue() {
        val overdueNew =
            workOrder("o1", status = WorkOrderStatus.new, dueAt = "2026-08-01")
        val overdueIp =
            workOrder("o2", status = WorkOrderStatus.in_progress, dueAt = "2026-08-10")
        val dueToday =
            workOrder("today", status = WorkOrderStatus.new, dueAt = "2026-08-16")
        val future =
            workOrder("fut", status = WorkOrderStatus.new, dueAt = "2026-08-20")
        val closedOverdue =
            workOrder("cl", status = WorkOrderStatus.closed, dueAt = "2026-08-01", closedAt = "2026-08-05T00:00:00Z")
        val badDue =
            workOrder("bad", status = WorkOrderStatus.new, dueAt = "not-a-date")

        val result =
            selectOverdueOpenWorkOrders(
                listOf(overdueNew, overdueIp, dueToday, future, closedOverdue, badDue),
                today,
            )
        assertEquals(listOf("o1", "o2"), result.map { it.id })
    }

    @Test
    fun sortsByDueAtAscThenIdAsc() {
        val a = workOrder("b", status = WorkOrderStatus.new, dueAt = "2026-08-01")
        val b = workOrder("a", status = WorkOrderStatus.new, dueAt = "2026-08-01")
        val c = workOrder("c", status = WorkOrderStatus.new, dueAt = "2026-07-01")
        assertEquals(
            listOf("c", "a", "b"),
            selectOverdueOpenWorkOrders(listOf(a, b, c), today).map { it.id },
        )
    }

    private fun workOrder(
        id: String,
        status: WorkOrderStatus,
        dueAt: String,
        closedAt: String? = null,
    ) = WorkOrder(
        id = id,
        orgId = "org-1",
        type = WorkOrderType.emergency,
        status = status,
        title = id,
        assetId = "a1",
        siteId = "s1",
        dueAt = dueAt,
        durationHours = 8,
        source = WorkOrderSource.api,
        createdAt = "2026-08-01T00:00:00Z",
        updatedAt = "2026-08-01T00:00:00Z",
        closedAt = closedAt,
    )
}
