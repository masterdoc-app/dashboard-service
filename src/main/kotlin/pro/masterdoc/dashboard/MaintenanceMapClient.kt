package pro.masterdoc.dashboard

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface MaintenanceMapGateway {
    fun get(orgId: String, id: String): MaintenanceMapSnapshot

    fun listActive(orgId: String?, mapId: String?): List<MaintenanceMapSnapshot>
}

@Serializable
data class MaintenanceMapSnapshot(
    val id: String,
    val orgId: String,
    val assetId: String,
    val activatedAt: String? = null,
    val items: List<MaintenanceMapItemSnapshot>,
)

@Serializable
data class MaintenanceMapItemSnapshot(
    val id: String,
    val title: String,
    val interval: MaintenanceIntervalSnapshot,
)

@Serializable
data class MaintenanceIntervalSnapshot(
    val every: Int,
    val unit: IntervalUnit,
)

@Serializable
enum class IntervalUnit { days, hours, cycles }

@Serializable
private data class MaintenanceMapSnapshotList(
    val items: List<MaintenanceMapSnapshot>,
)

class HttpMaintenanceMapGateway(
    baseUrl: String,
    private val client: HttpClient = defaultClient(),
) : MaintenanceMapGateway {
    private val baseUrl = baseUrl.trimEnd('/')

    override fun get(orgId: String, id: String): MaintenanceMapSnapshot =
        runBlocking {
            val response =
                client.get("$baseUrl/maintenance-maps/$id") {
                    header("X-Org-Id", orgId)
                }
            if (response.status == HttpStatusCode.NotFound) {
                throw NoSuchElementException("Map not found")
            }
            check(response.status.isSuccess()) {
                "Maintenance service returned ${response.status}"
            }
            response.body()
        }

    override fun listActive(orgId: String?, mapId: String?): List<MaintenanceMapSnapshot> =
        runBlocking {
            val response =
                client.get("$baseUrl/internal/active-maps") {
                    orgId?.let { parameter("orgId", it) }
                    mapId?.let { parameter("mapId", it) }
                }
            check(response.status.isSuccess()) {
                "Maintenance service returned ${response.status}"
            }
            response.body<MaintenanceMapSnapshotList>().items
        }

    private companion object {
        fun defaultClient(): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
    }
}
