package pro.masterdoc.dashboard

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.sin

@Serializable
data class SeedManagerReportsRequest(
    val siteId: String,
    val assetIds: List<String>,
    val createdBy: String? = null,
    val assigneeIds: List<String> = emptyList(),
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
 *
 * PPR due dates are unique per asset (DB unique on map item + dueAt).
 */
fun seedManagerReports(
    store: WorkOrderStore,
    orgId: String,
    siteId: String,
    assetIds: List<String>,
    now: Instant,
    createdBy: String? = null,
    assigneeIds: List<String> = emptyList(),
): SeedManagerReportsResponse {
    require(orgId.isNotBlank()) { "orgId required" }
    require(siteId.isNotBlank()) { "siteId required" }
    require(assetIds.isNotEmpty()) { "assetIds must not be empty" }
    require(assetIds.all { it.isNotBlank() }) { "assetIds must not contain blank values" }
    val creator = createdBy?.trim()?.takeIf { it.isNotBlank() }
    val seedAssigneeIds =
        assigneeIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .ifEmpty { listOf("seed-engineer-1", "seed-engineer-2", "seed-engineer-3") }

    val deleted = store.clearOrg(orgId)
    val maps = SeedMaintenanceMaps(assetIds)
    var created = 0
    var titleSeq = 0
    // Per-asset past/future due cursors → unique (mapItemId, dueAt).
    val pastDueCursor = IntArray(assetIds.size) { 5 }
    val futureDueCursor = IntArray(assetIds.size) { 1 }
    var closedAssignmentIndex = 0

    fun nextTitle(pool: List<String>): String {
        val title = pool[titleSeq % pool.size]
        titleSeq++
        return title
    }

    fun date(offsetDays: Long): String =
        now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(offsetDays).toString()

    fun nextPastDueOffset(assetIndex: Int): Long {
        val i = assetIndex % assetIds.size
        return -(pastDueCursor[i]++).toLong()
    }

    fun nextFutureDueOffset(assetIndex: Int): Long {
        val i = assetIndex % assetIds.size
        return (futureDueCursor[i]++).toLong()
    }

    fun nextClosedAssigneeId(): String {
        val index = closedAssignmentIndex++ % 10
        return when {
            index < 5 -> seedAssigneeIds[0]
            index < 8 -> seedAssigneeIds.getOrElse(1) { seedAssigneeIds[0] }
            else -> seedAssigneeIds.getOrElse(2) { seedAssigneeIds[0] }
        }
    }

    fun emergencyAssetIndex(index: Int): Int =
        when {
            assetIds.size == 1 -> 0
            assetIds.size == 2 -> index % 2
            index % 20 < 9 -> 0
            index % 20 < 16 -> 1
            else -> 2 + (index % (assetIds.size - 2))
        }

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
        // Deduped PPR insert returns an existing row — do not re-transition it.
        if (workOrder.status != WorkOrderStatus.new && status != null) {
            return
        }
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
                store.update(
                    orgId,
                    workOrder.id,
                    assigneePresent = true,
                    assigneeId = nextClosedAssigneeId(),
                    now = transitionAt,
                )
            }
            null -> Unit
            else -> store.update(orgId, workOrder.id, status = status, now = transitionAt)
        }
    }

    // Closed emergencies across ~90 days — MTTR/MTBF/downtime ranking samples.
    repeat(90) { i ->
        val daysAgo = ((i * 87) % 90 + 1).toLong()
        val createdAt = now.minus(daysAgo, ChronoUnit.DAYS).minus((i % 11).toLong(), ChronoUnit.HOURS)
        val repairHours = 4 + ((sin(i * 0.35) + 1) * 6).toInt()
        val closeAt = createdAt.plus(repairHours.toLong(), ChronoUnit.HOURS)
        create(
            type = WorkOrderType.emergency,
            title = nextTitle(EmergencyTitles),
            assetIndex = emergencyAssetIndex(i),
            dueAt = date(-daysAgo),
            createdAt = createdAt,
            durationHours = repairHours,
            status = WorkOrderStatus.closed,
            transitionAt = closeAt,
        )
    }

    // Extra failures on first assets so MTBF has several samples even with 1–2 assets.
    repeat(assetIds.size.coerceAtMost(8)) { assetIndex ->
        repeat(2) { k ->
            val daysAgo = 12L + assetIndex * 3 + k * 5
            val createdAt = now.minus(daysAgo, ChronoUnit.DAYS)
            val repairHours = 3 + k * 2
            create(
                type = WorkOrderType.emergency,
                title = nextTitle(EmergencyTitles),
                assetIndex = assetIndex,
                dueAt = date(-daysAgo),
                createdAt = createdAt,
                durationHours = repairHours,
                status = WorkOrderStatus.closed,
                transitionAt = createdAt.plus(repairHours.toLong(), ChronoUnit.HOURS),
            )
        }
    }

    // PPR: on-time closed (closed day before due).
    repeat(40) { i ->
        val assetIndex = i % assetIds.size
        val dueOffset = nextPastDueOffset(assetIndex)
        create(
            type = WorkOrderType.ppr,
            title = nextTitle(PprTitles),
            assetIndex = assetIndex,
            dueAt = date(dueOffset),
            createdAt = now.minus((-dueOffset) + 2, ChronoUnit.DAYS),
            durationHours = 4 + (i % 6),
            status = WorkOrderStatus.closed,
            transitionAt = now.minus((-dueOffset) + 1, ChronoUnit.DAYS),
        )
    }

    // PPR: late closed (closed day after due).
    repeat(25) { i ->
        val assetIndex = (i + 1) % assetIds.size
        val dueOffset = nextPastDueOffset(assetIndex)
        create(
            type = WorkOrderType.ppr,
            title = nextTitle(PprTitles),
            assetIndex = assetIndex,
            dueAt = date(dueOffset),
            createdAt = now.minus((-dueOffset) + 3, ChronoUnit.DAYS),
            durationHours = 6 + (i % 5),
            status = WorkOrderStatus.closed,
            transitionAt = now.minus((-dueOffset) - 1, ChronoUnit.DAYS),
        )
    }

    // PPR: open overdue.
    repeat(20) { i ->
        val assetIndex = (i + 2) % assetIds.size
        val dueOffset = nextPastDueOffset(assetIndex)
        create(
            type = WorkOrderType.ppr,
            title = nextTitle(PprTitles),
            assetIndex = assetIndex,
            dueAt = date(dueOffset),
            createdAt = now.minus((-dueOffset) + 3, ChronoUnit.DAYS),
            durationHours = 8,
        )
    }

    // PPR: open pending (future due).
    repeat(25) { i ->
        val assetIndex = (i + 3) % assetIds.size
        val dueOffset = nextFutureDueOffset(assetIndex)
        create(
            type = WorkOrderType.ppr,
            title = nextTitle(PprTitles),
            assetIndex = assetIndex,
            dueAt = date(dueOffset),
            createdAt = now.minus((i % 5 + 1).toLong(), ChronoUnit.DAYS),
            durationHours = 8,
        )
    }

    // Open backlog across age buckets: <7d, 7–30d, >30d.
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
