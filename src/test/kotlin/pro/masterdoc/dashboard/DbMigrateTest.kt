package pro.masterdoc.dashboard

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class DbMigrateTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("dashboard")
            .withUsername("dashboard")
            .withPassword("dashboard")
    }

    @Test
    fun migratesCleanSchema() {
        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { ds ->
            ds.connection.use { c ->
                c.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY 1",
                    ).use { rs ->
                        val names = buildList { while (rs.next()) add(rs.getString(1)) }
                        assertTrue("work_orders" in names)
                    }
                }
            }
        }
    }
}
