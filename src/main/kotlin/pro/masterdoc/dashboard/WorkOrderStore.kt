package pro.masterdoc.dashboard

import kotlinx.serialization.Serializable
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

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
    val durationHours: Int,
    val assigneeId: String? = null,
    val maintenanceMapId: String? = null,
    val maintenanceMapItemId: String? = null,
    val createdBy: String? = null,
    val description: String? = null,
    val source: WorkOrderSource,
    val createdAt: String,
    val updatedAt: String,
    val startedAt: String? = null,
    val closedAt: String? = null,
)

@Serializable
data class DowntimeInterval(
    val assetId: String,
    val workOrderId: String,
    val title: String,
    val startedAt: String,
    val closedAt: String? = null,
    val status: WorkOrderStatus,
)

@Serializable
data class CreateWorkOrderRequest(
    val type: WorkOrderType,
    val title: String,
    val assetId: String,
    val siteId: String,
    val dueAt: String,
    val durationHours: Int? = null,
    val maintenanceMapId: String? = null,
    val maintenanceMapItemId: String? = null,
    val description: String? = null,
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

class WorkOrderStore(
    private val dataSource: DataSource,
) {

    fun create(
        orgId: String,
        req: CreateWorkOrderRequest,
        createdBy: String? = null,
        now: Instant = Instant.now(),
        maps: MaintenanceMapGateway? = null,
    ): WorkOrder {
        return createJdbc(orgId, req, createdBy, now, maps)
    }

    fun get(orgId: String, id: String): WorkOrder =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM work_orders WHERE id = ? AND org_id = ?",
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, orgId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.toWorkOrder()
                    else throw NoSuchElementException("Work order not found")
                }
            }
        }

    fun update(
        orgId: String,
        id: String,
        status: WorkOrderStatus? = null,
        title: String? = null,
        dueAt: String? = null,
        durationHours: Int? = null,
        assigneePresent: Boolean = false,
        assigneeId: String? = null,
        now: Instant = Instant.now(),
    ): WorkOrder = updateJdbc(orgId, id, status, title, dueAt, durationHours, assigneePresent, assigneeId, now)

    fun list(orgId: String, assigneeId: String? = null, createdBy: String? = null): List<WorkOrder> =
        dataSource.connection.use { connection ->
            val conditions = mutableListOf("org_id = ?")
            val values = mutableListOf(orgId)
            if (assigneeId != null) {
                conditions += "assignee_id = ?"
                values += assigneeId
            }
            if (createdBy != null) {
                conditions += "created_by = ?"
                values += createdBy
            }
            connection.prepareStatement(
                "SELECT * FROM work_orders WHERE ${conditions.joinToString(" AND ")} ORDER BY due_at, title, id",
            ).use { statement ->
                values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(result.toWorkOrder())
                    }
                }
            }
        }

    fun equipmentDowntime(
        orgId: String,
        from: Instant,
        to: Instant,
        now: Instant = Instant.now(),
    ): List<DowntimeInterval> {
        require(!to.isBefore(from)) { "to must be on or after from" }
        return list(orgId)
            .asSequence()
            .filter { it.orgId == orgId && it.startedAt != null }
            .mapNotNull { workOrder ->
                val startedAt = parseInstant(workOrder.startedAt!!) ?: return@mapNotNull null
                val closedAt = workOrder.closedAt?.let(::parseInstant)
                val end = closedAt ?: now
                if (startedAt <= to && end >= from) {
                    DowntimeInterval(
                        assetId = workOrder.assetId,
                        workOrderId = workOrder.id,
                        title = workOrder.title,
                        startedAt = workOrder.startedAt,
                        closedAt = workOrder.closedAt,
                        status = workOrder.status,
                    )
                } else {
                    null
                }
            }
            .sortedBy { it.startedAt }
            .toList()
    }

    fun managerKpis(
        orgId: String,
        from: Instant,
        to: Instant,
        now: Instant = Instant.now(),
    ): ManagerKpis = computeManagerKpis(list(orgId), from, to, now)

    fun board(orgId: String, weekStart: String, weeks: Int, assigneeId: String? = null): BoardResponse {
        require(weeks in 1..52) { "weeks must be 1..52" }
        val start = WeekDates.parseDate(weekStart) ?: throw IllegalArgumentException("weekStart must be YYYY-MM-DD")
        require(WeekDates.isMonday(start)) { "weekStart must be a Monday" }

        val endExclusive = start.plusWeeks(weeks.toLong())
        val inRange =
            list(orgId, assigneeId = assigneeId)
                .filter { it.orgId == orgId }
                .filter { wo ->
                    val d = WeekDates.parseDate(wo.dueAt) ?: return@filter false
                    val occupied = WeekDates.spanWorkingDays(d, wo.durationHours)
                    WeekDates.intersectsRange(occupied, start, endExclusive)
                }

        val columns =
            (0 until weeks).map { i ->
                val ws = start.plusWeeks(i.toLong())
                val items =
                    inRange
                        .filter { wo ->
                            val d = WeekDates.parseDate(wo.dueAt) ?: return@filter false
                            val occupied = WeekDates.spanWorkingDays(d, wo.durationHours)
                            WeekDates.intersectsWeek(occupied, ws)
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
        list(orgId).any {
            it.orgId == orgId &&
                it.type == WorkOrderType.ppr &&
                it.maintenanceMapId == mapId &&
                it.maintenanceMapItemId == itemId &&
                it.dueAt == dueAt
        }

    /** Removes all work orders for an organization (ops / demo reseed). */
    fun clearOrg(orgId: String): Int {
        require(orgId.isNotBlank()) { "orgId required" }
        return dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM work_orders WHERE org_id = ?").use { statement ->
                statement.setString(1, orgId)
                statement.executeUpdate()
            }
        }
    }

    private fun transition(from: WorkOrderStatus, to: WorkOrderStatus): WorkOrderStatus {
        val ok =
            when (from) {
                WorkOrderStatus.new -> to == WorkOrderStatus.in_progress
                WorkOrderStatus.in_progress -> to == WorkOrderStatus.closed
                WorkOrderStatus.closed -> false
            }
        if (!ok) throw IllegalArgumentException("Illegal status transition: $from -> $to")
        return to
    }

    private fun parseInstant(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    private fun createJdbc(
        orgId: String,
        req: CreateWorkOrderRequest,
        createdBy: String?,
        now: Instant,
        maps: MaintenanceMapGateway?,
    ): WorkOrder {
        require(req.title.isNotBlank()) { "title required" }
        require(req.assetId.isNotBlank()) { "assetId required" }
        require(req.siteId.isNotBlank()) { "siteId required" }
        require(req.dueAt.isNotBlank()) { "dueAt required" }
        require(WeekDates.parseDate(req.dueAt) != null) { "dueAt must be YYYY-MM-DD" }
        val description = req.description?.trim()?.takeIf { it.isNotBlank() }
        require(description == null || description.length <= 4000) { "description too long" }
        when (req.type) {
            WorkOrderType.emergency ->
                require(req.maintenanceMapId == null && req.maintenanceMapItemId == null) {
                    "emergency work orders must not reference PPR"
                }
            WorkOrderType.ppr -> {
                require(!req.maintenanceMapId.isNullOrBlank()) { "maintenanceMapId required for ppr" }
                require(!req.maintenanceMapItemId.isNullOrBlank()) { "maintenanceMapItemId required for ppr" }
                val mapStore = maps ?: throw IllegalArgumentException("PPR validation unavailable")
                val map = mapStore.get(orgId, req.maintenanceMapId)
                require(map.assetId == req.assetId) { "assetId must match maintenance map asset" }
                require(map.items.any { it.id == req.maintenanceMapItemId }) { "Unknown maintenanceMapItemId" }
            }
        }
        val hours = req.durationHours ?: 8
        require(hours >= 1) { "durationHours must be >= 1" }
        require(hours <= WeekDates.MAX_DURATION_HOURS) {
            "durationHours must be <= ${WeekDates.MAX_DURATION_HOURS}"
        }
        val stamp = now.toString()
        val workOrder = WorkOrder(
            id = UUID.randomUUID().toString(),
            orgId = orgId,
            type = req.type,
            status = WorkOrderStatus.new,
            title = req.title.trim(),
            assetId = req.assetId,
            siteId = req.siteId,
            dueAt = req.dueAt,
            durationHours = hours,
            assigneeId = null,
            maintenanceMapId = req.maintenanceMapId,
            maintenanceMapItemId = req.maintenanceMapItemId,
            createdBy = createdBy,
            description = description,
            source = req.source,
            createdAt = stamp,
            updatedAt = stamp,
        )
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO work_orders
                (id, org_id, type, status, title, asset_id, site_id, due_at, duration_hours,
                 assignee_id, maintenance_map_id, maintenance_map_item_id, created_by, description,
                 source, created_at, updated_at, started_at, closed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, workOrder.id)
                statement.setString(2, workOrder.orgId)
                statement.setString(3, workOrder.type.name)
                statement.setString(4, workOrder.status.name)
                statement.setString(5, workOrder.title)
                statement.setString(6, workOrder.assetId)
                statement.setString(7, workOrder.siteId)
                statement.setString(8, workOrder.dueAt)
                statement.setInt(9, workOrder.durationHours)
                statement.setNullableString(10, workOrder.assigneeId)
                statement.setNullableString(11, workOrder.maintenanceMapId)
                statement.setNullableString(12, workOrder.maintenanceMapItemId)
                statement.setNullableString(13, workOrder.createdBy)
                statement.setNullableString(14, workOrder.description)
                statement.setString(15, workOrder.source.name)
                statement.setString(16, workOrder.createdAt)
                statement.setString(17, workOrder.updatedAt)
                statement.setNullableString(18, workOrder.startedAt)
                statement.setNullableString(19, workOrder.closedAt)
                if (
                    statement.executeUpdate() == 0 &&
                    workOrder.type == WorkOrderType.ppr &&
                    workOrder.maintenanceMapItemId != null
                ) {
                    connection.prepareStatement(
                        """
                        SELECT * FROM work_orders
                        WHERE org_id = ? AND type = 'ppr'
                          AND maintenance_map_item_id = ? AND due_at = ?
                        """.trimIndent(),
                    ).use { existing ->
                        existing.setString(1, workOrder.orgId)
                        existing.setString(2, workOrder.maintenanceMapItemId)
                        existing.setString(3, workOrder.dueAt)
                        existing.executeQuery().use { result ->
                            if (result.next()) return result.toWorkOrder()
                        }
                    }
                }
            }
        }
        return workOrder
    }

    private fun updateJdbc(
        orgId: String,
        id: String,
        status: WorkOrderStatus?,
        title: String?,
        dueAt: String?,
        durationHours: Int?,
        assigneePresent: Boolean,
        assigneeId: String?,
        now: Instant,
    ): WorkOrder {
        val current = get(orgId, id)
        var next = current
        if (status != null && status != current.status) {
            next = next.copy(
                status = transition(current.status, status),
                startedAt = if (current.status == WorkOrderStatus.new && status == WorkOrderStatus.in_progress) {
                    current.startedAt ?: now.toString()
                } else current.startedAt,
                closedAt = if (current.status == WorkOrderStatus.in_progress && status == WorkOrderStatus.closed) {
                    now.toString()
                } else current.closedAt,
            )
        }
        if (title != null) {
            require(title.isNotBlank()) { "title required" }
            next = next.copy(title = title.trim())
        }
        if (dueAt != null) {
            require(WeekDates.parseDate(dueAt) != null) { "dueAt must be YYYY-MM-DD" }
            next = next.copy(dueAt = dueAt)
        }
        if (durationHours != null) {
            require(durationHours >= 1) { "durationHours must be >= 1" }
            require(durationHours <= WeekDates.MAX_DURATION_HOURS) {
                "durationHours must be <= ${WeekDates.MAX_DURATION_HOURS}"
            }
            next = next.copy(durationHours = durationHours)
        }
        if (assigneePresent) {
            require(current.status != WorkOrderStatus.closed) { "Cannot change assignee on closed work order" }
            next = next.copy(assigneeId = assigneeId?.takeIf { it.isNotBlank() })
        }
        if (next == current) return current
        next = next.copy(updatedAt = now.toString())
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE work_orders SET status=?, title=?, due_at=?, duration_hours=?, assignee_id=?,
                    updated_at=?, started_at=?, closed_at=?
                WHERE id=? AND org_id=?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, next.status.name)
                statement.setString(2, next.title)
                statement.setString(3, next.dueAt)
                statement.setInt(4, next.durationHours)
                statement.setNullableString(5, next.assigneeId)
                statement.setString(6, next.updatedAt)
                statement.setNullableString(7, next.startedAt)
                statement.setNullableString(8, next.closedAt)
                statement.setString(9, id)
                statement.setString(10, orgId)
                statement.executeUpdate()
            }
        }
        return next
    }

    private fun ResultSet.toWorkOrder(): WorkOrder =
        WorkOrder(
            id = getString("id"),
            orgId = getString("org_id"),
            type = WorkOrderType.valueOf(getString("type")),
            status = WorkOrderStatus.valueOf(getString("status")),
            title = getString("title"),
            assetId = getString("asset_id"),
            siteId = getString("site_id"),
            dueAt = getString("due_at"),
            durationHours = getInt("duration_hours"),
            assigneeId = getString("assignee_id"),
            maintenanceMapId = getString("maintenance_map_id"),
            maintenanceMapItemId = getString("maintenance_map_item_id"),
            createdBy = getString("created_by"),
            description = getString("description"),
            source = WorkOrderSource.valueOf(getString("source")),
            createdAt = getString("created_at"),
            updatedAt = getString("updated_at"),
            startedAt = getString("started_at"),
            closedAt = getString("closed_at"),
        )
}

private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, java.sql.Types.VARCHAR) else setString(index, value)
}
