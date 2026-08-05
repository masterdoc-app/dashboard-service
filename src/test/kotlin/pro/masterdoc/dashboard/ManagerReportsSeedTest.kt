package pro.masterdoc.dashboard

import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
class ManagerReportsSeedTest {
    private lateinit var dataSource: HikariDataSource

    @Test
    fun seedProducesNonZeroManagerKpiSamples() =
        withStore { store ->
            val now = Instant.parse("2026-08-02T12:00:00Z")

            val result =
                seedManagerReports(
                    store = store,
                    orgId = "smoke",
                    siteId = "ceh-1",
                    assetIds = listOf("asset-a", "asset-b", "asset-c"),
                    now = now,
                    createdBy = "seed-user-1",
                    assigneeIds = listOf("engineer-a", "engineer-b", "engineer-c"),
                )

            assertTrue(result.created >= 200, "expected dense demo seed, got ${result.created}")
            assertEquals("smoke", result.orgId)
            val orders = store.list("smoke")
            assertEquals(result.created, orders.size)
            assertTrue(orders.all { it.createdBy == "seed-user-1" })
            val closedAssignments =
                orders
                    .filter { it.status == WorkOrderStatus.closed }
                    .groupingBy { it.assigneeId }
                    .eachCount()
            assertEquals(
                orders.count { it.status == WorkOrderStatus.closed },
                closedAssignments.values.sum(),
            )
            assertTrue(closedAssignments["engineer-a"]!! > closedAssignments["engineer-b"]!!)
            assertTrue(closedAssignments["engineer-b"]!! > closedAssignments["engineer-c"]!!)
            val emergencyCountsByAsset =
                orders
                    .filter { it.type == WorkOrderType.emergency && it.status == WorkOrderStatus.closed }
                    .groupingBy { it.assetId }
                    .eachCount()
            assertTrue(emergencyCountsByAsset["asset-a"]!! > emergencyCountsByAsset["asset-c"]!!)
            assertTrue(emergencyCountsByAsset["asset-b"]!! > emergencyCountsByAsset["asset-c"]!!)
            assertTrue(
                orders
                    .filter { it.type == WorkOrderType.emergency && it.status == WorkOrderStatus.closed }
                    .map { it.durationHours }
                    .distinct()
                    .size > 5,
            )

            val kpis =
                store.managerKpis(
                    orgId = "smoke",
                    from = Instant.parse("2026-05-01T00:00:00Z"),
                    to = Instant.parse("2026-08-16T23:59:59.999999999Z"),
                    now = now,
                )
            assertTrue(kpis.mttrSampleSize >= 50)
            assertTrue(kpis.mtbfSampleSize >= 1)
            assertTrue(kpis.emergencyCount >= 50)
            assertTrue(kpis.plannedCount >= 50)
            assertTrue(kpis.pprOnTime >= 10)
            assertTrue(kpis.pprLate >= 10)
            assertTrue(kpis.pprOpenOverdue >= 5)
            assertTrue(kpis.pprOpenPending >= 5)
            assertTrue(kpis.backlogUnder7d >= 5)
            assertTrue(kpis.backlog7to30d >= 5)
            assertTrue(kpis.backlogOver30d >= 5)
            assertTrue(kpis.downtimeRanking.isNotEmpty())
        }

    @Test
    fun seedClearsExistingOrdersAndRequiresAsset() =
        withStore { store ->
            val now = Instant.parse("2026-08-02T12:00:00Z")
            store.create(
                orgId = "smoke",
                req =
                    CreateWorkOrderRequest(
                        type = WorkOrderType.emergency,
                        title = "Старая заявка",
                        assetId = "asset-a",
                        siteId = "ceh-1",
                        dueAt = "2026-08-02",
                    ),
                now = now,
            )

            val result = seedManagerReports(store, "smoke", "ceh-1", listOf("asset-a"), now)

            assertEquals(1, result.deleted)
            assertEquals(result.created, store.list("smoke").size)
            assertTrue(
                store
                    .list("smoke")
                    .filter { it.status == WorkOrderStatus.closed }
                    .all { it.assigneeId == null },
                "without real assigneeIds, closed WOs must not get fake seed-engineer-* ids",
            )
            assertFailsWith<IllegalArgumentException> {
                seedManagerReports(store, "smoke", "ceh-1", emptyList(), now)
            }
        }

    private fun withStore(block: (WorkOrderStore) -> Unit) {
        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { connected ->
            dataSource = connected
            connected.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("TRUNCATE work_orders")
                }
            }
            block(WorkOrderStore(dataSource))
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("dashboard")
                .withUsername("dashboard")
                .withPassword("dashboard")
    }
}
