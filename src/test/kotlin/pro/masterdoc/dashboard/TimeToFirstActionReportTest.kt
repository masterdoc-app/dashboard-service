package pro.masterdoc.dashboard

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimeToFirstActionReportTest {
    private val from = Instant.parse("2026-07-01T00:00:00Z")
    private val to = Instant.parse("2026-07-31T23:59:59Z")

    @Test
    fun includesOnlyParseableCreatedAtInPeriod() {
        val inside =
            workOrder("inside", createdAt = "2026-07-10T00:00:00Z", startedAt = "2026-07-10T04:00:00Z")
        val boundaryFrom = workOrder("from", createdAt = "2026-07-01T00:00:00Z")
        val boundaryTo = workOrder("to", createdAt = "2026-07-31T23:59:59Z")
        val before = workOrder("before", createdAt = "2026-06-30T23:59:59Z")
        val after = workOrder("after", createdAt = "2026-08-01T00:00:00Z")
        val badCreated = workOrder("bad", createdAt = "not-a-date")

        val result =
            selectTimeToFirstAction(
                listOf(inside, boundaryFrom, boundaryTo, before, after, badCreated),
                from,
                to,
            )

        assertEquals(listOf("from", "inside", "to"), result.map { it.id }.sorted())
        assertEquals(3, result.size)
    }

    @Test
    fun dropsUnparseableCreatedAt() {
        val bad = workOrder("bad", createdAt = "not-a-date")
        assertEquals(emptyList(), selectTimeToFirstAction(listOf(bad), from, to))
    }

    @Test
    fun sortsNullStartedAtFirstThenDurationDescThenIdDesc() {
        val notStartedB = workOrder("ns-b", createdAt = "2026-07-05T00:00:00Z", startedAt = null)
        val notStartedA = workOrder("ns-a", createdAt = "2026-07-06T00:00:00Z", startedAt = null)
        val slow =
            workOrder(
                "slow",
                createdAt = "2026-07-10T00:00:00Z",
                startedAt = "2026-07-12T00:00:00Z",
            )
        val fast =
            workOrder(
                "fast",
                createdAt = "2026-07-10T00:00:00Z",
                startedAt = "2026-07-10T02:00:00Z",
            )
        val sameDurationHighId =
            workOrder(
                "z",
                createdAt = "2026-07-11T00:00:00Z",
                startedAt = "2026-07-12T00:00:00Z",
            )
        val sameDurationLowId =
            workOrder(
                "y",
                createdAt = "2026-07-11T00:00:00Z",
                startedAt = "2026-07-12T00:00:00Z",
            )

        val result =
            selectTimeToFirstAction(
                listOf(slow, notStartedA, fast, sameDurationLowId, notStartedB, sameDurationHighId),
                from,
                to,
            )

        assertEquals(
            listOf("ns-b", "ns-a", "slow", "z", "y", "fast"),
            result.map { it.id },
        )
    }

    @Test
    fun requiresToOnOrAfterFrom() {
        assertFailsWith<IllegalArgumentException> {
            selectTimeToFirstAction(emptyList(), to, from)
        }
    }

    private fun workOrder(
        id: String,
        createdAt: String,
        startedAt: String? = null,
    ) = WorkOrder(
        id = id,
        orgId = "org-1",
        type = WorkOrderType.emergency,
        status = if (startedAt == null) WorkOrderStatus.new else WorkOrderStatus.in_progress,
        title = id,
        assetId = "asset",
        siteId = "site",
        dueAt = "2026-07-15",
        durationHours = 8,
        source = WorkOrderSource.api,
        createdAt = createdAt,
        updatedAt = createdAt,
        startedAt = startedAt,
    )
}
