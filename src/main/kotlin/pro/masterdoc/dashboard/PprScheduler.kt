package pro.masterdoc.dashboard

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

private val log = LoggerFactory.getLogger("pro.masterdoc.dashboard.scheduler")

class PprScheduler(
    private val maps: MaintenanceMapGateway,
    private val workOrders: WorkOrderStore,
    private val assets: AssetLookup,
    private val clock: Clock = Clock.systemUTC(),
    private val horizonWeeks: Int = System.getenv("BOARD_HORIZON_WEEKS")?.toIntOrNull() ?: 4,
) {
    fun tick(orgId: String? = null, mapId: String? = null): SchedulerTickResult {
        val today = LocalDate.now(clock)
        val horizonStart = WeekDates.mondayOnOrBefore(today)
        val horizonEnd = horizonStart.plusWeeks(horizonWeeks.toLong())
        val now = Instant.now(clock)

        var created = 0
        var skippedNonDays = 0

        val candidates = maps.listActive(orgId, mapId)

        for (map in candidates) {
            val siteId = assets.siteIdOf(map.orgId, map.assetId) ?: continue
            val activated =
                map.activatedAt?.let { WeekDates.parseDate(it.take(10)) }
                    ?: continue

            for (item in map.items) {
                if (item.interval.unit != IntervalUnit.days) {
                    skippedNonDays++
                    continue
                }
                val every = item.interval.every
                if (every < 1) continue

                var n = 1
                while (true) {
                    val due = activated.plusDays(every.toLong() * n)
                    if (!due.isBefore(horizonEnd)) break
                    if (!due.isBefore(horizonStart)) {
                        val dueStr = WeekDates.format(due)
                        if (!workOrders.existsPprDue(map.orgId, map.id, item.id, dueStr)) {
                            workOrders.create(
                                orgId = map.orgId,
                                req =
                                    CreateWorkOrderRequest(
                                        type = WorkOrderType.ppr,
                                        title = item.title,
                                        assetId = map.assetId,
                                        siteId = siteId,
                                        dueAt = dueStr,
                                        maintenanceMapId = map.id,
                                        maintenanceMapItemId = item.id,
                                        source = WorkOrderSource.scheduler,
                                    ),
                                now = now,
                                maps = maps,
                            )
                            created++
                        }
                    }
                    n++
                    if (n > 10_000) break
                }
            }
        }
        val result = SchedulerTickResult(created = created, skippedNonDays = skippedNonDays)
        log.info(
            "event=scheduler_tick created=${result.created} skippedNonDays=${result.skippedNonDays} " +
                "orgId=${orgId ?: "*"} mapId=${mapId ?: "*"}",
        )
        return result
    }
}
