package pro.masterdoc.dashboard

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class WorkOrderType { ppr, emergency }

@Serializable
enum class WorkOrderStatus { new, in_progress, closed }

@Serializable
enum class WorkOrderSource { manual, scheduler, api }

@Serializable
data class WorkOrder(
    val id: String,
    val orgId: String,
    val type: WorkOrderType,
    val status: WorkOrderStatus,
    val title: String,
    val assetId: String,
    val siteId: String,
    val dueAt: String,
    val assigneeId: String? = null,
    val maintenanceMapId: String? = null,
    val maintenanceMapItemId: String? = null,
    val source: WorkOrderSource,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CreateWorkOrderRequest(
    val type: WorkOrderType,
    val title: String,
    val assetId: String,
    val siteId: String,
    val dueAt: String,
    val maintenanceMapId: String? = null,
    val maintenanceMapItemId: String? = null,
    val source: WorkOrderSource = WorkOrderSource.api,
)

@Serializable
data class BoardWeek(
    val weekStart: String,
    val items: List<WorkOrder>,
)

@Serializable
data class BoardResponse(val weeks: List<BoardWeek>)

@Serializable
data class SchedulerTickResult(
    val created: Int,
    val skippedNonDays: Int = 0,
)

class WorkOrderStore {
    private val byId = ConcurrentHashMap<String, WorkOrder>()

    fun create(
        orgId: String,
        req: CreateWorkOrderRequest,
        now: Instant = Instant.now(),
        maps: MaintenanceMapStore? = null,
    ): WorkOrder {
        require(req.title.isNotBlank()) { "title required" }
        require(req.assetId.isNotBlank()) { "assetId required" }
        require(req.siteId.isNotBlank()) { "siteId required" }
        require(req.dueAt.isNotBlank()) { "dueAt required" }
        require(WeekDates.parseDate(req.dueAt) != null) { "dueAt must be YYYY-MM-DD" }

        when (req.type) {
            WorkOrderType.emergency -> {
                require(req.maintenanceMapId == null && req.maintenanceMapItemId == null) {
                    "emergency work orders must not reference PPR"
                }
            }
            WorkOrderType.ppr -> {
                require(!req.maintenanceMapId.isNullOrBlank()) { "maintenanceMapId required for ppr" }
                require(!req.maintenanceMapItemId.isNullOrBlank()) { "maintenanceMapItemId required for ppr" }
                val mapStore = maps ?: throw IllegalArgumentException("PPR validation unavailable")
                val map = mapStore.get(orgId, req.maintenanceMapId)
                require(map.assetId == req.assetId) { "assetId must match maintenance map asset" }
                require(map.items.any { it.id == req.maintenanceMapItemId }) {
                    "Unknown maintenanceMapItemId"
                }
            }
        }

        val stamp = now.toString()
        val wo =
            WorkOrder(
                id = UUID.randomUUID().toString(),
                orgId = orgId,
                type = req.type,
                status = WorkOrderStatus.new,
                title = req.title.trim(),
                assetId = req.assetId,
                siteId = req.siteId,
                dueAt = req.dueAt,
                assigneeId = null,
                maintenanceMapId = req.maintenanceMapId,
                maintenanceMapItemId = req.maintenanceMapItemId,
                source = req.source,
                createdAt = stamp,
                updatedAt = stamp,
            )
        byId[wo.id] = wo
        return wo
    }

    fun get(orgId: String, id: String): WorkOrder {
        val wo = byId[id] ?: throw NoSuchElementException("Work order not found")
        if (wo.orgId != orgId) throw NoSuchElementException("Work order not found")
        return wo
    }

    fun update(
        orgId: String,
        id: String,
        status: WorkOrderStatus? = null,
        title: String? = null,
        dueAt: String? = null,
        assigneePresent: Boolean = false,
        assigneeId: String? = null,
        now: Instant = Instant.now(),
    ): WorkOrder {
        val current = get(orgId, id)
        var next = current

        if (status != null && status != current.status) {
            next = next.copy(status = transition(current.status, status))
        }
        if (title != null) {
            require(title.isNotBlank()) { "title required" }
            next = next.copy(title = title.trim())
        }
        if (dueAt != null) {
            require(WeekDates.parseDate(dueAt) != null) { "dueAt must be YYYY-MM-DD" }
            next = next.copy(dueAt = dueAt)
        }
        if (assigneePresent) {
            if (current.status == WorkOrderStatus.closed) {
                throw IllegalArgumentException("Cannot change assignee on closed work order")
            }
            next = next.copy(assigneeId = assigneeId?.takeIf { it.isNotBlank() })
        }

        if (next == current) return current
        next = next.copy(updatedAt = now.toString())
        byId[id] = next
        return next
    }

    fun board(orgId: String, weekStart: String, weeks: Int): BoardResponse {
        require(weeks in 1..52) { "weeks must be 1..52" }
        val start = WeekDates.parseDate(weekStart) ?: throw IllegalArgumentException("weekStart must be YYYY-MM-DD")
        require(WeekDates.isMonday(start)) { "weekStart must be a Monday" }

        val endExclusive = start.plusWeeks(weeks.toLong())
        val inRange =
            byId.values
                .filter { it.orgId == orgId }
                .filter { due ->
                    val d = WeekDates.parseDate(due.dueAt) ?: return@filter false
                    !d.isBefore(start) && d.isBefore(endExclusive)
                }

        val columns =
            (0 until weeks).map { i ->
                val ws = start.plusWeeks(i.toLong())
                val we = ws.plusDays(7)
                val items =
                    inRange
                        .filter {
                            val d = WeekDates.parseDate(it.dueAt)!!
                            !d.isBefore(ws) && d.isBefore(we)
                        }
                        .sortedWith(compareBy({ it.dueAt }, { it.title }, { it.id }))
                BoardWeek(weekStart = WeekDates.format(ws), items = items)
            }
        return BoardResponse(weeks = columns)
    }

    fun existsPprDue(
        orgId: String,
        mapId: String,
        itemId: String,
        dueAt: String,
    ): Boolean =
        byId.values.any {
            it.orgId == orgId &&
                it.type == WorkOrderType.ppr &&
                it.maintenanceMapId == mapId &&
                it.maintenanceMapItemId == itemId &&
                it.dueAt == dueAt
        }

    private fun transition(from: WorkOrderStatus, to: WorkOrderStatus): WorkOrderStatus {
        val ok =
            when (from) {
                WorkOrderStatus.new -> to == WorkOrderStatus.in_progress
                WorkOrderStatus.in_progress -> to == WorkOrderStatus.closed
                WorkOrderStatus.closed -> false
            }
        if (!ok) throw IllegalArgumentException("Illegal status transition: $from → $to")
        return to
    }
}
