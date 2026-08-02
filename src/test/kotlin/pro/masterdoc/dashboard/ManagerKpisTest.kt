package pro.masterdoc.dashboard

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManagerKpisTest {
    private val from = Instant.parse("2026-07-01T00:00:00Z")
    private val to = Instant.parse("2026-07-31T23:59:59Z")
    private val now = Instant.parse("2026-07-22T12:00:00Z")

    @Test
    fun computesMttrAndMtbfFromClosedEmergencyWorkOrders() {
        val orders =
            listOf(
                workOrder("e1", WorkOrderType.emergency, WorkOrderStatus.closed, "asset-a",
                    createdAt = "2026-07-01T00:00:00Z", startedAt = "2026-07-10T00:00:00Z", closedAt = "2026-07-10T04:00:00Z"),
                workOrder("e2", WorkOrderType.emergency, WorkOrderStatus.closed, "asset-a",
                    createdAt = "2026-07-02T00:00:00Z", startedAt = "2026-07-20T00:00:00Z", closedAt = "2026-07-20T08:00:00Z"),
                workOrder("e3", WorkOrderType.emergency, WorkOrderStatus.closed, "asset-a",
                    createdAt = "2026-06-01T00:00:00Z", startedAt = "2026-06-20T00:00:00Z", closedAt = "2026-06-20T02:00:00Z"),
                workOrder("e4", WorkOrderType.emergency, WorkOrderStatus.closed, "asset-b",
                    createdAt = "2026-07-03T00:00:00Z", startedAt = "2026-07-15T00:00:00Z", closedAt = "2026-07-15T06:00:00Z"),
            )

        val result = computeManagerKpis(orders, from, to, now)

        assertEquals(6.0, result.mttrHours)
        assertEquals(3, result.mttrSampleSize)
        assertEquals(363.0, result.mtbfHours)
        assertEquals(1, result.mtbfSampleSize)
    }

    @Test
    fun computesPprComplianceBuckets() {
        val orders =
            listOf(
                workOrder("on-time", WorkOrderType.ppr, WorkOrderStatus.closed, dueAt = "2026-07-10", closedAt = "2026-07-10T12:00:00Z"),
                workOrder("late", WorkOrderType.ppr, WorkOrderStatus.closed, dueAt = "2026-07-10", closedAt = "2026-07-11T00:00:00Z"),
                workOrder("overdue", WorkOrderType.ppr, WorkOrderStatus.in_progress, dueAt = "2026-07-21"),
                workOrder("pending", WorkOrderType.ppr, WorkOrderStatus.new, dueAt = "2026-07-22"),
                workOrder("outside", WorkOrderType.ppr, WorkOrderStatus.new, dueAt = "2026-08-01"),
            )

        val result = computeManagerKpis(orders, from, to, now)

        assertEquals(1, result.pprOnTime)
        assertEquals(1, result.pprLate)
        assertEquals(1, result.pprOpenOverdue)
        assertEquals(1, result.pprOpenPending)
    }

    @Test
    fun computesPlannedEmergencyCountsAndHoursForCreatedPeriod() {
        val orders =
            listOf(
                workOrder("p1", WorkOrderType.ppr, WorkOrderStatus.closed, durationHours = 3,
                    createdAt = "2026-07-05T00:00:00Z", closedAt = "2026-07-06T00:00:00Z"),
                workOrder("p2", WorkOrderType.ppr, WorkOrderStatus.new, durationHours = 5,
                    createdAt = "2026-07-06T00:00:00Z"),
                workOrder("e1", WorkOrderType.emergency, WorkOrderStatus.closed, durationHours = 2,
                    createdAt = "2026-07-07T00:00:00Z", startedAt = "2026-07-07T00:00:00Z", closedAt = "2026-07-07T02:00:00Z"),
                workOrder("outside", WorkOrderType.emergency, WorkOrderStatus.closed, durationHours = 9,
                    createdAt = "2026-06-01T00:00:00Z", startedAt = "2026-06-01T00:00:00Z", closedAt = "2026-06-01T09:00:00Z"),
            )

        val result = computeManagerKpis(orders, from, to, now)

        assertEquals(2, result.plannedCount)
        assertEquals(1, result.emergencyCount)
        assertEquals(8.0, result.plannedHours)
        assertEquals(2.0, result.emergencyHours)
    }

    @Test
    fun computesBacklogAgeAndOverdueBuckets() {
        val orders =
            listOf(
                workOrder("new", WorkOrderType.emergency, WorkOrderStatus.new, dueAt = "2026-07-30",
                    createdAt = "2026-07-20T12:00:00Z"),
                workOrder("week-old", WorkOrderType.emergency, WorkOrderStatus.in_progress, dueAt = "2026-07-21",
                    createdAt = "2026-07-10T12:00:00Z"),
                workOrder("old", WorkOrderType.ppr, WorkOrderStatus.new, dueAt = "2026-07-01",
                    createdAt = "2026-06-01T12:00:00Z"),
                workOrder("closed", WorkOrderType.emergency, WorkOrderStatus.closed, dueAt = "2026-06-01",
                    createdAt = "2026-05-01T12:00:00Z"),
            )

        val result = computeManagerKpis(orders, from, to, now)

        assertEquals(1, result.backlogUnder7d)
        assertEquals(1, result.backlog7to30d)
        assertEquals(1, result.backlogOver30d)
        assertEquals(2, result.backlogOverdue)
    }

    @Test
    fun ranksEmergencyDowntimeOnlyAndComputesAvailability() {
        val orders =
            listOf(
                workOrder("emergency-a", WorkOrderType.emergency, WorkOrderStatus.closed, "asset-a",
                    startedAt = "2026-07-10T00:00:00Z", closedAt = "2026-07-10T06:00:00Z"),
                workOrder("emergency-b", WorkOrderType.emergency, WorkOrderStatus.in_progress, "asset-a",
                    startedAt = "2026-07-20T00:00:00Z"),
                workOrder("planned", WorkOrderType.ppr, WorkOrderStatus.closed, "asset-b",
                    startedAt = "2026-07-01T00:00:00Z", closedAt = "2026-07-31T00:00:00Z"),
            )

        val result = computeManagerKpis(orders, from, to, now)

        assertEquals(listOf("asset-a"), result.downtimeRanking.map { it.assetId })
        assertEquals(66.0, result.downtimeRanking.single().downtimeHours)
        assertEquals(1, result.downtimeRanking.single().openIntervals)
        assertEquals(100.0 * (1.0 - 66.0 / (1 * (31 * 24.0))), result.availabilityPercent, 0.001)
    }

    @Test
    fun rejectsInvertedPeriod() {
        assertFailsWith<IllegalArgumentException> {
            computeManagerKpis(emptyList(), to, from, now)
        }
    }

    private fun workOrder(
        id: String,
        type: WorkOrderType,
        status: WorkOrderStatus,
        assetId: String = "asset",
        dueAt: String = "2026-07-15",
        durationHours: Int = 8,
        createdAt: String = "2026-07-01T00:00:00Z",
        startedAt: String? = null,
        closedAt: String? = null,
    ) = WorkOrder(
        id = id,
        orgId = "org-1",
        type = type,
        status = status,
        title = id,
        assetId = assetId,
        siteId = "site",
        dueAt = dueAt,
        durationHours = durationHours,
        source = WorkOrderSource.api,
        createdAt = createdAt,
        updatedAt = createdAt,
        startedAt = startedAt,
        closedAt = closedAt,
    )
}
