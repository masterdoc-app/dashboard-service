package pro.masterdoc.dashboard

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ManagerReportExtrasTest {
    private val from = Instant.parse("2026-07-01T00:00:00Z")
    private val to = Instant.parse("2026-07-30T23:59:59Z")
    private val now = Instant.parse("2026-08-01T00:00:00Z")

    @Test
    fun reactiveCompletionReturnsZeroPercentagesWhenNoOrdersWereCreated() {
        val result = computeReactiveCompletion(emptyList(), from, to)

        assertEquals(0, result.createdCount)
        assertEquals(0, result.closedCount)
        assertEquals(0.0, result.completionRatePercent)
        assertEquals(0.0, result.reactivePercent)
    }

    @Test
    fun reactiveCompletionSeparatesCreatedAndClosedWindows() {
        val result =
            computeReactiveCompletion(
                listOf(
                    workOrder("planned", WorkOrderType.ppr, WorkOrderStatus.new, createdAt = "2026-07-02T00:00:00Z"),
                    workOrder(
                        "emergency",
                        WorkOrderType.emergency,
                        WorkOrderStatus.closed,
                        createdAt = "2026-07-03T00:00:00Z",
                        closedAt = "2026-07-04T00:00:00Z",
                    ),
                    workOrder(
                        "closed-from-earlier",
                        WorkOrderType.emergency,
                        WorkOrderStatus.closed,
                        createdAt = "2026-06-30T00:00:00Z",
                        closedAt = "2026-07-05T00:00:00Z",
                    ),
                ),
                from,
                to,
            )

        assertEquals(2, result.createdCount)
        assertEquals(2, result.closedCount)
        assertEquals(100.0, result.completionRatePercent)
        assertEquals(1, result.emergencyCount)
        assertEquals(1, result.plannedCount)
        assertEquals(50.0, result.reactivePercent)
    }

    @Test
    fun kpiTrendsUsesDailyBucketsForThirtyDaysOrFewer() {
        val result =
            computeKpiTrends(
                listOf(workOrder("planned", WorkOrderType.ppr, WorkOrderStatus.new)),
                from,
                to,
                now,
            )

        assertEquals("day", result.bucket)
        assertEquals(30, result.points.size)
        assertEquals("2026-07-01", result.points.first().bucketStart)
        assertEquals(0, result.points.first().mttrSampleSize)
        assertEquals(100.0, result.points.first().availabilityPercent)
    }

    @Test
    fun kpiTrendsReturnsNoPointsForAnEmptyOrg() {
        val result = computeKpiTrends(emptyList(), from, to, now)

        assertEquals("day", result.bucket)
        assertEquals(emptyList(), result.points)
    }

    @Test
    fun kpiTrendsUsesWeeklyBucketsForLongerPeriods() {
        val result =
            computeKpiTrends(
                listOf(workOrder("planned", WorkOrderType.ppr, WorkOrderStatus.new)),
                from,
                Instant.parse("2026-08-01T23:59:59Z"),
                now,
            )

        assertEquals("week", result.bucket)
        assertEquals(listOf("2026-07-01", "2026-07-08", "2026-07-15", "2026-07-22", "2026-07-29"), result.points.map { it.bucketStart })
    }

    @Test
    fun engineerWorkloadGroupsClosedAssignedOrdersAndSumsParsedDurations() {
        val result =
            computeEngineerWorkload(
                listOf(
                    workOrder(
                        "anna-1",
                        WorkOrderType.emergency,
                        WorkOrderStatus.closed,
                        assigneeId = "anna",
                        startedAt = "2026-07-02T00:00:00Z",
                        closedAt = "2026-07-02T03:30:00Z",
                    ),
                    workOrder(
                        "anna-2",
                        WorkOrderType.ppr,
                        WorkOrderStatus.closed,
                        assigneeId = "anna",
                        closedAt = "2026-07-03T00:00:00Z",
                    ),
                    workOrder(
                        "boris",
                        WorkOrderType.emergency,
                        WorkOrderStatus.closed,
                        assigneeId = "boris",
                        startedAt = "2026-07-04T00:00:00Z",
                        closedAt = "2026-07-04T02:00:00Z",
                    ),
                    workOrder("unassigned", WorkOrderType.emergency, WorkOrderStatus.closed, closedAt = "2026-07-05T00:00:00Z"),
                ),
                from,
                to,
            )

        assertEquals(listOf("anna", "boris"), result.engineers.map { it.userId })
        assertEquals(2, result.engineers.first().closedCount)
        assertEquals(3.5, result.engineers.first().hours)
    }

    @Test
    fun failureFrequencyCountsCreatedEmergenciesAndReturnsTopFifteen() {
        val result =
            computeFailureFrequency(
                buildList {
                    repeat(16) { index ->
                        repeat(index + 1) {
                            add(workOrder("asset-$index-$it", WorkOrderType.emergency, WorkOrderStatus.new, assetId = "asset-$index"))
                        }
                    }
                    add(workOrder("planned", WorkOrderType.ppr, WorkOrderStatus.new, assetId = "asset-15"))
                    add(workOrder("outside", WorkOrderType.emergency, WorkOrderStatus.new, assetId = "outside", createdAt = "2026-06-30T23:59:59Z"))
                },
                from,
                to,
            )

        assertEquals(15, result.assets.size)
        assertEquals("asset-15", result.assets.first().assetId)
        assertEquals(16, result.assets.first().emergencyCount)
        assertEquals("asset-1", result.assets.last().assetId)
    }

    private fun workOrder(
        id: String,
        type: WorkOrderType,
        status: WorkOrderStatus,
        assetId: String = "asset",
        assigneeId: String? = null,
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
        dueAt = "2026-07-15",
        durationHours = 8,
        assigneeId = assigneeId,
        source = WorkOrderSource.api,
        createdAt = createdAt,
        updatedAt = createdAt,
        startedAt = startedAt,
        closedAt = closedAt,
    )
}
