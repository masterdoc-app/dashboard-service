package pro.masterdoc.dashboard

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

object Db {
    private const val DEFAULT_DATABASE_URL =
        "jdbc:postgresql://localhost:5432/dashboard?user=dashboard&password=dashboard"

    fun connect(databaseUrl: String = System.getenv("DATABASE_URL") ?: DEFAULT_DATABASE_URL): HikariDataSource =
        migrate(
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = databaseUrl
                    maximumPoolSize = 10
                    minimumIdle = 1
                },
            ),
        )

    fun connect(databaseUrl: String, username: String, password: String): HikariDataSource =
        migrate(
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = databaseUrl
                    this.username = username
                    this.password = password
                    maximumPoolSize = 10
                    minimumIdle = 1
                },
            ),
        )

    private fun migrate(dataSource: HikariDataSource): HikariDataSource {
        Flyway.configure().dataSource(dataSource).load().migrate()
        return dataSource
    }
}
