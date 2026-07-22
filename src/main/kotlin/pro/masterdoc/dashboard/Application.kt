package pro.masterdoc.dashboard

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8092
    val catalogBase = System.getenv("CATALOG_BASE_URL") ?: "http://127.0.0.1:8091"
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(MaintenanceMapStore(), CatalogAssetChecker(catalogBase))
    }.start(wait = true)
}

fun Application.module(
    store: MaintenanceMapStore,
    assetChecker: AssetChecker = AllowAllAssetChecker,
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respondText(cause.message ?: "Bad Request", status = HttpStatusCode.BadRequest)
        }
        exception<NoSuchElementException> { call, cause ->
            call.respondText(cause.message ?: "Not Found", status = HttpStatusCode.NotFound)
        }
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        post("/maintenance-maps") {
            val orgId = call.orgId()
            val req = call.receive<CreateMaintenanceMapRequest>()
            if (!assetChecker.exists(orgId, req.assetId)) {
                throw IllegalArgumentException("Unknown asset: ${req.assetId}")
            }
            call.respond(HttpStatusCode.Created, store.create(orgId, req))
        }
        get("/maintenance-maps") {
            val orgId = call.orgId()
            val assetId = call.request.queryParameters["assetId"]
            call.respond(MaintenanceMapList(items = store.list(orgId, assetId)))
        }
        get("/maintenance-maps/{id}") {
            call.respond(store.get(call.orgId(), call.parameters["id"]!!))
        }
        patch("/maintenance-maps/{id}") {
            val req = call.receive<UpdateMaintenanceMapRequest>()
            call.respond(store.update(call.orgId(), call.parameters["id"]!!, req))
        }
        post("/maintenance-maps/{id}/confirm") {
            call.respond(store.confirm(call.orgId(), call.parameters["id"]!!))
        }
        post("/maintenance-maps/{id}/reject") {
            store.reject(call.orgId(), call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.orgId(): String =
    request.header("X-Org-Id")?.takeIf { it.isNotBlank() } ?: "default-org"

@Serializable
enum class RecordStatus { draft, active }

@Serializable
enum class RecordSource { manual, ai_generated }

@Serializable
enum class MapItemKind { inspection, service, overhaul }

@Serializable
enum class Criticality { low, medium, high }

@Serializable
enum class IntervalUnit { days, hours, cycles }

@Serializable
data class Interval(val every: Int, val unit: IntervalUnit)

@Serializable
data class MaintenanceMapItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val kind: MapItemKind,
    val interval: Interval,
    val criticality: Criticality,
    val sourceRef: String? = null,
)

@Serializable
data class MaintenanceMap(
    val id: String,
    val assetId: String,
    val orgId: String,
    val title: String,
    val status: RecordStatus,
    val source: RecordSource,
    val items: List<MaintenanceMapItem>,
)

@Serializable
data class CreateMaintenanceMapRequest(
    val assetId: String,
    val title: String,
    val items: List<MaintenanceMapItemInput>,
    val source: RecordSource = RecordSource.manual,
)

@Serializable
data class MaintenanceMapItemInput(
    val title: String,
    val kind: MapItemKind,
    val interval: Interval,
    val criticality: Criticality,
    val sourceRef: String? = null,
)

@Serializable
data class UpdateMaintenanceMapRequest(
    val title: String? = null,
    val items: List<MaintenanceMapItemInput>? = null,
)

@Serializable
data class MaintenanceMapList(val items: List<MaintenanceMap>)

fun interface AssetChecker {
    fun exists(orgId: String, assetId: String): Boolean
}

object AllowAllAssetChecker : AssetChecker {
    override fun exists(orgId: String, assetId: String) = true
}

class CatalogAssetChecker(private val catalogBaseUrl: String) : AssetChecker {
    private val client = HttpClient(CIO)
    override fun exists(orgId: String, assetId: String): Boolean =
        try {
            kotlinx.coroutines.runBlocking {
                val response =
                    client.get("$catalogBaseUrl/assets/$assetId") {
                        header("X-Org-Id", orgId)
                    }
                response.status == HttpStatusCode.OK
            }
        } catch (_: Exception) {
            false
        }
}

class MaintenanceMapStore {
    private val byId = ConcurrentHashMap<String, MaintenanceMap>()

    fun create(orgId: String, req: CreateMaintenanceMapRequest): MaintenanceMap {
        require(req.title.isNotBlank()) { "title required" }
        require(req.items.isNotEmpty()) { "items required" }
        req.items.forEach {
            require(it.title.isNotBlank()) { "item title required" }
            require(it.interval.every >= 1) { "interval.every must be >= 1" }
        }
        val source = req.source
        val map =
            MaintenanceMap(
                id = UUID.randomUUID().toString(),
                assetId = req.assetId,
                orgId = orgId,
                title = req.title.trim(),
                status = if (source == RecordSource.ai_generated) RecordStatus.draft else RecordStatus.draft,
                source = source,
                items = req.items.map { it.toItem() },
            )
        byId[map.id] = map
        return map
    }

    fun list(orgId: String, assetId: String?): List<MaintenanceMap> =
        byId.values
            .filter { it.orgId == orgId }
            .filter { assetId == null || it.assetId == assetId }
            .sortedBy { it.title }

    fun get(orgId: String, id: String): MaintenanceMap {
        val map = byId[id] ?: throw NoSuchElementException("Map not found")
        if (map.orgId != orgId) throw NoSuchElementException("Map not found")
        return map
    }

    fun update(orgId: String, id: String, req: UpdateMaintenanceMapRequest): MaintenanceMap {
        val map = get(orgId, id)
        if (map.status != RecordStatus.draft) throw IllegalArgumentException("Only draft maps can be updated")
        val updated =
            map.copy(
                title = req.title?.trim()?.takeIf { it.isNotEmpty() } ?: map.title,
                items = req.items?.map { it.toItem() } ?: map.items,
            )
        byId[id] = updated
        return updated
    }

    fun confirm(orgId: String, id: String): MaintenanceMap {
        val map = get(orgId, id)
        if (map.status != RecordStatus.draft) throw IllegalArgumentException("Only draft maps can be confirmed")
        val published = map.copy(status = RecordStatus.active)
        byId[id] = published
        return published
    }

    fun reject(orgId: String, id: String) {
        val map = get(orgId, id)
        if (map.status != RecordStatus.draft) throw IllegalArgumentException("Only draft maps can be rejected")
        byId.remove(id)
    }
}

private fun MaintenanceMapItemInput.toItem() =
    MaintenanceMapItem(
        title = title.trim(),
        kind = kind,
        interval = interval,
        criticality = criticality,
        sourceRef = sourceRef,
    )
