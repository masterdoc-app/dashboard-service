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
                    assetIds = listOf("asset-a", "asset-b"),
                    now = now,
                    createdBy = "seed-user-1",
                )

            assertTrue(result.created >= 8)
            assertEquals("smoke", result.orgId)
            assertEquals(result.created, store.list("smoke").size)
            assertTrue(store.list("smoke").all { it.createdBy == "seed-user-1" })

            val kpis =
                store.managerKpis(
                    orgId = "smoke",
                    from = Instant.parse("2026-07-03T00:00:00Z"),
                    to = Instant.parse("2026-08-02T23:59:59.999999999Z"),
                    now = now,
                )
            assertTrue(kpis.mttrSampleSize > 0)
            assertTrue(kpis.emergencyCount > 0)
            assertTrue(kpis.plannedCount > 0)
            assertTrue(kpis.pprOnTime + kpis.pprLate + kpis.pprOpenOverdue + kpis.pprOpenPending > 0)
            assertTrue(kpis.backlogUnder7d + kpis.backlog7to30d + kpis.backlogOver30d > 0)
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
