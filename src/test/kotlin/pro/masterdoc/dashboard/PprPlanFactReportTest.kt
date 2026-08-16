package pro.masterdoc.dashboard

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PprPlanFactReportTest {
    private val fromDate = LocalDate.parse("2026-07-01")
    private val toDate = LocalDate.parse("2026-07-31")

    @Test
    fun includesOnlyPprWithParseableDueAtInPeriod() {
        val onTime =
            workOrder("on-time", WorkOrderType.ppr, dueAt = "2026-07-10")
        val boundaryFrom = workOrder("from", WorkOrderType.ppr, dueAt = "2026-07-01")
        val boundaryTo = workOrder("to", WorkOrderType.ppr, dueAt = "2026-07-31")
        val before = workOrder("before", WorkOrderType.ppr, dueAt = "2026-06-30")
        val after = workOrder("after", WorkOrderType.ppr, dueAt = "2026-08-01")
        val emergency = workOrder("emergency", WorkOrderType.emergency, dueAt = "2026-07-15")
        val badDue = workOrder("bad", WorkOrderType.ppr, dueAt = "not-a-date")

        val result =
            selectPprPlanFact(
                listOf(onTime, boundaryFrom, boundaryTo, before, after, emergency, badDue),
                fromDate,
                toDate,
            )

        assertEquals(listOf("from", "on-time", "to"), result.map { it.id })
        assertEquals(3, result.size)
    }

    @Test
    fun dropsUnparseableDueAt() {
        val bad = workOrder("bad", WorkOrderType.ppr, dueAt = "not-a-date")
        assertEquals(emptyList(), selectPprPlanFact(listOf(bad), fromDate, toDate))
    }

    @Test
    fun sortsDueAtAscThenIdAsc() {
        val laterHighId = workOrder("b", WorkOrderType.ppr, dueAt = "2026-07-20")
        val laterLowId = workOrder("a", WorkOrderType.ppr, dueAt = "2026-07-20")
        val earlier = workOrder("c", WorkOrderType.ppr, dueAt = "2026-07-10")

        val result =
            selectPprPlanFact(
                listOf(laterHighId, earlier, laterLowId),
                fromDate,
                toDate,
            )

        assertEquals(listOf("c", "a", "b"), result.map { it.id })
    }

    @Test
    fun requiresToOnOrAfterFrom() {
        assertFailsWith<IllegalArgumentException> {
            selectPprPlanFact(emptyList(), toDate, fromDate)
        }
    }

    private fun workOrder(
        id: String,
        type: WorkOrderType,
        dueAt: String,
    ) = WorkOrder(
        id = id,
        orgId = "org-1",
        type = type,
        status = WorkOrderStatus.new,
        title = id,
        assetId = "asset",
        siteId = "site",
        dueAt = dueAt,
        durationHours = 8,
        source = WorkOrderSource.api,
        createdAt = "2026-07-01T00:00:00Z",
        updatedAt = "2026-07-01T00:00:00Z",
    )
}
