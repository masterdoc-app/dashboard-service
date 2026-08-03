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

private val EmergencyTitles =
    listOf(
        "Авария компрессора",
        "Авария насоса",
        "Перегрев двигателя",
        "Авария подшипника",
        "Утечка масла",
        "Сбой датчика давления",
        "Обрыв ремня",
        "Засор фильтра",
        "Короткое замыкание",
        "Вибрация выше нормы",
        "Падение давления",
        "Отказ пускателя",
        "Перегрузка линии",
        "Неисправность клапана",
        "Сбой частотного преобразователя",
    )

private val PprTitles =
    listOf(
        "ППР компрессора",
        "ППР насоса",
        "ППР вентилятора",
        "ППР линии подачи",
        "ППР редуктора",
        "ППР конвейера",
        "ППР теплообменника",
        "ППР электрошкафа",
        "ППР насосной станции",
        "ППР узла охлаждения",
    )

private val BacklogTitles =
    listOf(
        "Проверка вибрации",
        "Осмотр электрошкафа",
        "Диагностика редуктора",
        "Замена уплотнений",
        "Калибровка датчиков",
        "Протяжка соединений",
        "Чистка радиатора",
        "Проверка уровня масла",
        "Замена фильтров",
        "Ревизия подшипников",
    )

/**
 * Dense demo/smoke dataset for manager reports: ~90 days of emergencies, PPR,
 * backlog and in-progress work across all provided assets.
 */
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
    var seq = 0

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
                val startedAt =
                    transitionAt
                        .minus(durationHours.coerceAtLeast(1).toLong(), ChronoUnit.HOURS)
                        .coerceAtLeast(createdAt)
                store.update(
                    orgId,
                    workOrder.id,
                    status = WorkOrderStatus.in_progress,
                    now = startedAt,
                )
                store.update(orgId, workOrder.id, status = WorkOrderStatus.closed, now = transitionAt)
            }
            null -> Unit
            else -> store.update(orgId, workOrder.id, status = status, now = transitionAt)
        }
    }

    fun date(offsetDays: Long): String =
        now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(offsetDays).toString()

    fun nextTitle(pool: List<String>): String {
        val title = pool[seq % pool.size]
        seq++
        return title
    }

    // Closed emergencies across ~90 days — MTTR/MTBF/downtime ranking samples.
    // Ensure early assets get multiple failures for MTBF.
    val closedEmergencyCount = 90
    repeat(closedEmergencyCount) { i ->
        val dayOffset = -((i * 87) % 90 + 1).toLong() // spread 1..90 days ago
        val createdAt = now.minus((-dayOffset), ChronoUnit.DAYS).minus((i % 11).toLong(), ChronoUnit.HOURS)
        val repairHours = 2 + (i % 14)
        val closeAt = createdAt.plus(repairHours.toLong(), ChronoUnit.HOURS)
        create(
            type = WorkOrderType.emergency,
            title = nextTitle(EmergencyTitles),
            assetIndex = i % assetIds.size,
            dueAt = date(dayOffset),
            createdAt = createdAt,
            durationHours = repairHours,
            status = WorkOrderStatus.closed,
            transitionAt = closeAt,
        )
    }

    // Extra failures on first assets so MTBF has several samples even with 1–2 assets.
    repeat(assetIds.size.coerceAtMost(8)) { assetIndex ->
        repeat(2) { k ->
            val dayOffset = -(12L + assetIndex * 3 + k * 5)
            val createdAt = now.minus((-dayOffset), ChronoUnit.DAYS)
            val repairHours = 3 + k * 2
            create(
                type = WorkOrderType.emergency,
                title = nextTitle(EmergencyTitles),
                assetIndex = assetIndex,
                dueAt = date(dayOffset),
                createdAt = createdAt,
                durationHours = repairHours,
                status = WorkOrderStatus.closed,
                transitionAt = createdAt.plus(repairHours.toLong(), ChronoUnit.HOURS),
            )
        }
    }

    // PPR compliance mix over ~90 days: on-time, late, open overdue, open pending.
    val pprOnTime = 40
    val pprLate = 25
    val pprOpenOverdue = 20
    val pprOpenPending = 25
    repeat(pprOnTime) { i ->
        val dayOffset = -((i * 2) % 80 + 5).toLong()
        val createdAt = now.minus((-dayOffset) + 2, ChronoUnit.DAYS)
        create(
            type = WorkOrderType.ppr,
            title = nextTitle(PprTitles),
            assetIndex = i,
            dueAt = date(dayOffset),
            createdAt = createdAt,
            durationHours = 4 + (i % 6),
            status = WorkOrderStatus.closed,
            transitionAt = now.minus((-dayOffset) + 1, ChronoUnit.DAYS),
        )
    }
    repeat(pprLate) { i ->
        val dayOffset = -((i * 3) % 70 + 4).toLong()
        val createdAt = now.minus((-dayOffset) + 3, ChronoUnit.DAYS)
        create(
            type = WorkOrderType.ppr,
            title = nextTitle(PprTitles),
            assetIndex = i + 1,
            dueAt = date(dayOffset),
            createdAt = createdAt,
            durationHours = 6 + (i % 5),
            status = WorkOrderStatus.closed,
            transitionAt = now.minus((-dayOffset) - 1, ChronoUnit.DAYS),
        )
    }
    repeat(pprOpenOverdue) { i ->
        val dayOffset = -((i % 10) + 1).toLong()
        create(
            type = WorkOrderType.ppr,
            title = nextTitle(PprTitles),
            assetIndex = i + 2,
            dueAt = date(dayOffset),
            createdAt = now.minus((-dayOffset) + 3, ChronoUnit.DAYS),
            durationHours = 8,
        )
    }
    repeat(pprOpenPending) { i ->
        val dayOffset = ((i % 14) + 1).toLong()
        create(
            type = WorkOrderType.ppr,
            title = nextTitle(PprTitles),
            assetIndex = i + 3,
            dueAt = date(dayOffset),
            createdAt = now.minus((i % 5 + 1).toLong(), ChronoUnit.DAYS),
            durationHours = 8,
        )
    }

    // Open backlog across age buckets: <7d, 7–30d, >30d (+ overdue due dates).
    repeat(18) { i ->
        create(
            type = WorkOrderType.emergency,
            title = nextTitle(BacklogTitles),
            assetIndex = i,
            dueAt = date((i % 5).toLong()),
            createdAt = now.minus((i % 6 + 1).toLong(), ChronoUnit.DAYS),
        )
    }
    repeat(16) { i ->
        create(
            type = WorkOrderType.emergency,
            title = nextTitle(BacklogTitles),
            assetIndex = i + 1,
            dueAt = date((i % 7 - 2).toLong()),
            createdAt = now.minus((10 + i % 18).toLong(), ChronoUnit.DAYS),
        )
    }
    repeat(14) { i ->
        create(
            type = WorkOrderType.emergency,
            title = nextTitle(BacklogTitles),
            assetIndex = i + 2,
            dueAt = date((i % 10 - 3).toLong()),
            createdAt = now.minus((35 + i * 2).toLong(), ChronoUnit.DAYS),
        )
    }

    // Active in-progress emergencies for Gantt / open downtime.
    repeat(12) { i ->
        val startedAgoHours = (6 + i * 5).toLong()
        create(
            type = WorkOrderType.emergency,
            title = nextTitle(EmergencyTitles),
            assetIndex = i,
            dueAt = date((i % 3).toLong()),
            createdAt = now.minus((i % 4 + 1).toLong(), ChronoUnit.DAYS),
            durationHours = 4 + (i % 5),
            status = WorkOrderStatus.in_progress,
            transitionAt = now.minus(startedAgoHours, ChronoUnit.HOURS),
        )
    }

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
