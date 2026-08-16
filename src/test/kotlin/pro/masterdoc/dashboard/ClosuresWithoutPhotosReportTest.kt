package pro.masterdoc.dashboard

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClosuresWithoutPhotosReportTest {
    private val from = Instant.parse("2026-07-01T00:00:00Z")
    private val to = Instant.parse("2026-07-31T23:59:59Z")

    @Test
    fun includesOnlyClosedWithoutAttachmentsAndParseableClosedAtInPeriod() {
        val noPhotos =
            workOrder(
                "no-photos",
                closedAt = "2026-07-10T00:00:00Z",
            )
        val boundaryFrom =
            workOrder(
                "from",
                closedAt = "2026-07-01T00:00:00Z",
            )
        val boundaryTo =
            workOrder(
                "to",
                closedAt = "2026-07-31T23:59:59Z",
            )
        val withPhotos =
            workOrder(
                "with-photos",
                closedAt = "2026-07-15T00:00:00Z",
                attachmentIds = listOf("att-1"),
            )
        val before =
            workOrder(
                "before",
                closedAt = "2026-06-30T23:59:59Z",
            )
        val after =
            workOrder(
                "after",
                closedAt = "2026-08-01T00:00:00Z",
            )
        val open =
            workOrder(
                "open",
                status = WorkOrderStatus.in_progress,
                closedAt = null,
            )
        val badClosed =
            workOrder(
                "bad",
                closedAt = "not-a-date",
            )

        val result =
            selectClosuresWithoutPhotos(
                listOf(noPhotos, boundaryFrom, boundaryTo, withPhotos, before, after, open, badClosed),
                from,
                to,
            )

        assertEquals(3, result.size)
        assertEquals(listOf("to", "no-photos", "from"), result.map { it.id })
    }

    @Test
    fun dropsUnparseableClosedAt() {
        val bad = workOrder("bad", closedAt = "not-a-date")
        assertEquals(emptyList(), selectClosuresWithoutPhotos(listOf(bad), from, to))
    }

    @Test
    fun excludesClosedWithAttachments() {
        val withPhotos =
            workOrder(
                "with-photos",
                closedAt = "2026-07-10T00:00:00Z",
                attachmentIds = listOf("att-1"),
            )
        assertEquals(emptyList(), selectClosuresWithoutPhotos(listOf(withPhotos), from, to))
    }

    @Test
    fun sortsClosedAtDescThenIdDesc() {
        val laterHighId = workOrder("b", closedAt = "2026-07-20T00:00:00Z")
        val laterLowId = workOrder("a", closedAt = "2026-07-20T00:00:00Z")
        val earlier = workOrder("c", closedAt = "2026-07-10T00:00:00Z")

        val result =
            selectClosuresWithoutPhotos(
                listOf(laterHighId, earlier, laterLowId),
                from,
                to,
            )

        assertEquals(listOf("b", "a", "c"), result.map { it.id })
    }

    @Test
    fun requiresToOnOrAfterFrom() {
        assertFailsWith<IllegalArgumentException> {
            selectClosuresWithoutPhotos(emptyList(), to, from)
        }
    }

    private fun workOrder(
        id: String,
        status: WorkOrderStatus = WorkOrderStatus.closed,
        closedAt: String?,
        attachmentIds: List<String> = emptyList(),
    ) = WorkOrder(
        id = id,
        orgId = "org-1",
        type = WorkOrderType.emergency,
        status = status,
        title = id,
        assetId = "asset",
        siteId = "site",
        dueAt = "2026-07-15",
        durationHours = 8,
        source = WorkOrderSource.api,
        createdAt = "2026-07-01T00:00:00Z",
        updatedAt = "2026-07-01T00:00:00Z",
        closedAt = closedAt,
        attachmentIds = attachmentIds,
    )
}
