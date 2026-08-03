package pro.masterdoc.dashboard

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Serializable
data class SeedManagerReportsRequest(
    val siteId: String,
    val assetIds: List<String>,
    val createdBy: String? = null,
)

@Serializable
data class SeedManagerReportsResponse(
    val deleted: Int,
    val created: Int,
    val orgId: String,
)

fun seedManagerReports(
    store: WorkOrderStore,
    orgId: String,
    siteId: String,
    assetIds: List<String>,
    now: Instant,
    createdBy: String? = null,
): SeedManagerReportsResponse {
    require(orgId.isNotBlank()) { "orgId required" }
    require(siteId.isNotBlank()) { "siteId required" }
    require(assetIds.isNotEmpty()) { "assetIds must not be empty" }
    require(assetIds.all { it.isNotBlank() }) { "assetIds must not contain blank values" }
    val creator = createdBy?.trim()?.takeIf { it.isNotBlank() }

    val deleted = store.clearOrg(orgId)
    val maps = SeedMaintenanceMaps(assetIds)
    var created = 0

    fun create(
        type: WorkOrderType,
        title: String,
        assetIndex: Int,
        dueAt: String,
        createdAt: Instant,
        durationHours: Int = 8,
        status: WorkOrderStatus? = null,
        transitionAt: Instant = now,
    ) {
        val assetId = assetIds[assetIndex % assetIds.size]
        val request =
            CreateWorkOrderRequest(
                type = type,
                title = title,
                assetId = assetId,
                siteId = siteId,
                dueAt = dueAt,
                durationHours = durationHours,
                maintenanceMapId = if (type == WorkOrderType.ppr) maps.mapId(assetId) else null,
                maintenanceMapItemId = if (type == WorkOrderType.ppr) maps.itemId(assetId) else null,
                source = WorkOrderSource.api,
            )
        val workOrder = store.create(orgId, request, createdBy = creator, now = createdAt, maps = maps)
        created++
        when (status) {
            WorkOrderStatus.closed -> {
                store.update(
                    orgId,
                    workOrder.id,
                    status = WorkOrderStatus.in_progress,
                    now = transitionAt.minus(1, ChronoUnit.HOURS),
                )
                store.update(orgId, workOrder.id, status = WorkOrderStatus.closed, now = transitionAt)
            }
            null -> Unit
            else -> store.update(orgId, workOrder.id, status = status, now = transitionAt)
        }
    }

    fun date(offsetDays: Long): String =
        now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(offsetDays).toString()

    // Two failures on the first asset provide MTBF samples; the third adds variety.
    create(
        type = WorkOrderType.emergency,
        title = "Авария компрессора",
        assetIndex = 0,
        dueAt = date(-13),
        createdAt = now.minus(13, ChronoUnit.DAYS),
        durationHours = 3,
        status = WorkOrderStatus.closed,
        transitionAt = now.minus(13, ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS),
    )
    create(
        type = WorkOrderType.emergency,
        title = "Авария насоса",
        assetIndex = 0,
        dueAt = date(-8),
        createdAt = now.minus(8, ChronoUnit.DAYS),
        durationHours = 5,
        status = WorkOrderStatus.closed,
        transitionAt = now.minus(8, ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS),
    )
    create(
        type = WorkOrderType.emergency,
        title = "Перегрев двигателя",
        assetIndex = 1,
        dueAt = date(-4),
        createdAt = now.minus(4, ChronoUnit.DAYS),
        durationHours = 2,
        status = WorkOrderStatus.closed,
        transitionAt = now.minus(4, ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS),
    )

    // PPR compliance sample: on-time, late, overdue open, and pending open.
    create(
        type = WorkOrderType.ppr,
        title = "ППР компрессора",
        assetIndex = 0,
        dueAt = date(-12),
        createdAt = now.minus(12, ChronoUnit.DAYS),
        status = WorkOrderStatus.closed,
        transitionAt = now.minus(13, ChronoUnit.DAYS),
    )
    create(
        type = WorkOrderType.ppr,
        title = "ППР насоса",
        assetIndex = 1,
        dueAt = date(-10),
        createdAt = now.minus(10, ChronoUnit.DAYS),
        status = WorkOrderStatus.closed,
        transitionAt = now.minus(9, ChronoUnit.DAYS),
    )
    create(
        type = WorkOrderType.ppr,
        title = "ППР вентилятора",
        assetIndex = 0,
        dueAt = date(-2),
        createdAt = now.minus(2, ChronoUnit.DAYS),
    )
    create(
        type = WorkOrderType.ppr,
        title = "ППР линии подачи",
        assetIndex = 1,
        dueAt = date(3),
        createdAt = now.minus(1, ChronoUnit.DAYS),
    )

    // Backlog ages: under seven days, seven-to-thirty, and over thirty days.
    create(
        type = WorkOrderType.emergency,
        title = "Проверка вибрации",
        assetIndex = 1,
        dueAt = date(-1),
        createdAt = now.minus(3, ChronoUnit.DAYS),
    )
    create(
        type = WorkOrderType.emergency,
        title = "Осмотр электрошкафа",
        assetIndex = 0,
        dueAt = date(4),
        createdAt = now.minus(15, ChronoUnit.DAYS),
    )
    create(
        type = WorkOrderType.emergency,
        title = "Диагностика редуктора",
        assetIndex = 1,
        dueAt = date(7),
        createdAt = now.minus(45, ChronoUnit.DAYS),
    )

    create(
        type = WorkOrderType.emergency,
        title = "Авария подшипника",
        assetIndex = 0,
        dueAt = date(0),
        createdAt = now.minus(1, ChronoUnit.DAYS),
        status = WorkOrderStatus.in_progress,
        transitionAt = now.minus(6, ChronoUnit.HOURS),
    )

    return SeedManagerReportsResponse(deleted = deleted, created = created, orgId = orgId)
}

private class SeedMaintenanceMaps(
    assetIds: List<String>,
) : MaintenanceMapGateway {
    private val maps =
        assetIds.associateWith { assetId ->
            MaintenanceMapSnapshot(
                id = mapId(assetId),
                orgId = "seed",
                assetId = assetId,
                items =
                    listOf(
                        MaintenanceMapItemSnapshot(
                            id = itemId(assetId),
                            title = "Регламентный осмотр",
                            interval = MaintenanceIntervalSnapshot(30, IntervalUnit.days),
                        ),
                    ),
            )
        }

    override fun get(orgId: String, id: String): MaintenanceMapSnapshot =
        maps.values.firstOrNull { it.id == id }
            ?: throw NoSuchElementException("Seed maintenance map not found")

    override fun listActive(orgId: String?, mapId: String?): List<MaintenanceMapSnapshot> =
        maps.values.filter { mapId == null || it.id == mapId }

    fun mapId(assetId: String): String = "seed-map-$assetId"

    fun itemId(assetId: String): String = "seed-item-$assetId"
}
