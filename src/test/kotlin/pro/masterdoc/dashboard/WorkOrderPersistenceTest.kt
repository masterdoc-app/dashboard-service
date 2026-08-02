package pro.masterdoc.dashboard

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@Testcontainers(disabledWithoutDocker = true)
class WorkOrderPersistenceTest {
    @Test
    fun survivesNewConnectionPool() {
        val created =
            Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { dataSource ->
                WorkOrderStore(dataSource).create(
                    orgId = "org-persistence",
                    req = CreateWorkOrderRequest(
                        type = WorkOrderType.emergency,
                        title = "Persistent leak",
                        assetId = "asset-1",
                        siteId = "site-1",
                        dueAt = "2026-08-03",
                    ),
                    now = Instant.parse("2026-08-02T18:00:00Z"),
                )
            }

        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { dataSource ->
            val loaded = WorkOrderStore(dataSource).get("org-persistence", created.id)
            assertEquals(created, loaded)
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("dashboard")
            .withUsername("dashboard")
            .withPassword("dashboard")
    }
}
