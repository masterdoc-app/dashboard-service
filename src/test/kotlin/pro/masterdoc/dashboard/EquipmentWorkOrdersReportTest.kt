package pro.masterdoc.dashboard

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class EquipmentWorkOrdersReportTest {
    private val from = Instant.parse("2026-07-01T00:00:00Z")
    private val to = Instant.parse("2026-07-31T23:59:59Z")

    @Test
    fun includesOpenCreatedBeforePeriodAndClosedInsideAndExcludesOutside() {
        val openOld = workOrder("open-old", assetId = "pump", createdAt = "2026-06-01T00:00:00Z", closedAt = null)
        val closedInside =
            workOrder(
                "closed-inside",
                assetId = "pump",
                createdAt = "2026-07-10T00:00:00Z",
                closedAt = "2026-07-15T00:00:00Z",
            )
        val closedBefore =
            workOrder(
                "closed-before",
                assetId = "pump",
                createdAt = "2026-06-01T00:00:00Z",
                closedAt = "2026-06-15T00:00:00Z",
            )
        val createdAfter =
            workOrder("created-after", assetId = "pump", createdAt = "2026-08-01T00:00:00Z", closedAt = null)
        val otherAsset =
            workOrder("other", assetId = "fan", createdAt = "2026-07-10T00:00:00Z", closedAt = null)

        val result =
            selectEquipmentWorkOrders(
                orders = listOf(openOld, closedInside, closedBefore, createdAfter, otherAsset),
                assetId = "pump",
                from = from,
                to = to,
            )

        assertEquals(listOf("closed-inside", "open-old"), result.map { it.id })
    }

    @Test
    fun dropsUnparseableCreatedAt() {
        val bad = workOrder("bad", assetId = "pump", createdAt = "not-a-date", closedAt = null)
        assertEquals(
            emptyList(),
            selectEquipmentWorkOrders(listOf(bad), "pump", from, to).map { it.id },
        )
    }

    @Test
    fun dropsUnparseableClosedAt() {
        val bad =
            workOrder(
                "bad-closed",
                assetId = "pump",
                createdAt = "2026-07-10T00:00:00Z",
                closedAt = "not-a-date",
            )
        assertEquals(
            emptyList(),
            selectEquipmentWorkOrders(listOf(bad), "pump", from, to).map { it.id },
        )
    }

    @Test
    fun sortsByCreatedAtDescThenIdDesc() {
        val a = workOrder("a", assetId = "pump", createdAt = "2026-07-10T00:00:00Z")
        val b = workOrder("b", assetId = "pump", createdAt = "2026-07-20T00:00:00Z")
        val c = workOrder("c", assetId = "pump", createdAt = "2026-07-20T00:00:00Z")
        assertEquals(
            listOf("c", "b", "a"),
            selectEquipmentWorkOrders(listOf(a, b, c), "pump", from, to).map { it.id },
        )
    }

    private fun workOrder(
        id: String,
        assetId: String,
        createdAt: String,
        closedAt: String? = null,
    ) = WorkOrder(
        id = id,
        orgId = "org-1",
        type = WorkOrderType.emergency,
        status = if (closedAt == null) WorkOrderStatus.new else WorkOrderStatus.closed,
        title = id,
        assetId = assetId,
        siteId = "site",
        dueAt = "2026-07-15",
        durationHours = 8,
        source = WorkOrderSource.api,
        createdAt = createdAt,
        updatedAt = createdAt,
        closedAt = closedAt,
    )
}
