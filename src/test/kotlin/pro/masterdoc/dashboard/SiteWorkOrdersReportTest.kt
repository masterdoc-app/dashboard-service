package pro.masterdoc.dashboard

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SiteWorkOrdersReportTest {
    private val from = Instant.parse("2026-07-01T00:00:00Z")
    private val to = Instant.parse("2026-07-31T23:59:59Z")

    @Test
    fun includesOpenCreatedBeforePeriodAndClosedInsideAndExcludesOutside() {
        val openOld = workOrder("open-old", siteId = "shop-1", createdAt = "2026-06-01T00:00:00Z", closedAt = null)
        val closedInside =
            workOrder(
                "closed-inside",
                siteId = "shop-1",
                createdAt = "2026-07-10T00:00:00Z",
                closedAt = "2026-07-15T00:00:00Z",
            )
        val closedBefore =
            workOrder(
                "closed-before",
                siteId = "shop-1",
                createdAt = "2026-06-01T00:00:00Z",
                closedAt = "2026-06-15T00:00:00Z",
            )
        val createdAfter =
            workOrder("created-after", siteId = "shop-1", createdAt = "2026-08-01T00:00:00Z", closedAt = null)
        val otherSite =
            workOrder("other", siteId = "shop-2", createdAt = "2026-07-10T00:00:00Z", closedAt = null)

        val result =
            selectSiteWorkOrders(
                orders = listOf(openOld, closedInside, closedBefore, createdAfter, otherSite),
                siteId = "shop-1",
                from = from,
                to = to,
            )

        assertEquals(listOf("closed-inside", "open-old"), result.map { it.id })
    }

    @Test
    fun dropsUnparseableCreatedAt() {
        val bad = workOrder("bad", siteId = "shop-1", createdAt = "not-a-date", closedAt = null)
        assertEquals(
            emptyList(),
            selectSiteWorkOrders(listOf(bad), "shop-1", from, to).map { it.id },
        )
    }

    @Test
    fun dropsUnparseableClosedAt() {
        val bad =
            workOrder(
                "bad-closed",
                siteId = "shop-1",
                createdAt = "2026-07-10T00:00:00Z",
                closedAt = "not-a-date",
            )
        assertEquals(
            emptyList(),
            selectSiteWorkOrders(listOf(bad), "shop-1", from, to).map { it.id },
        )
    }

    @Test
    fun sortsByCreatedAtDescThenIdDesc() {
        val a = workOrder("a", siteId = "shop-1", createdAt = "2026-07-10T00:00:00Z")
        val b = workOrder("b", siteId = "shop-1", createdAt = "2026-07-20T00:00:00Z")
        val c = workOrder("c", siteId = "shop-1", createdAt = "2026-07-20T00:00:00Z")
        assertEquals(
            listOf("c", "b", "a"),
            selectSiteWorkOrders(listOf(a, b, c), "shop-1", from, to).map { it.id },
        )
    }

    @Test
    fun requiresNonBlankSiteId() {
        assertFailsWith<IllegalArgumentException> {
            selectSiteWorkOrders(emptyList(), "", from, to)
        }
        assertFailsWith<IllegalArgumentException> {
            selectSiteWorkOrders(emptyList(), "   ", from, to)
        }
    }

    private fun workOrder(
        id: String,
        siteId: String,
        createdAt: String,
        closedAt: String? = null,
    ) = WorkOrder(
        id = id,
        orgId = "org-1",
        type = WorkOrderType.emergency,
        status = if (closedAt == null) WorkOrderStatus.new else WorkOrderStatus.closed,
        title = id,
        assetId = "asset",
        siteId = siteId,
        dueAt = "2026-07-15",
        durationHours = 8,
        source = WorkOrderSource.api,
        createdAt = createdAt,
        updatedAt = createdAt,
        closedAt = closedAt,
    )
}
