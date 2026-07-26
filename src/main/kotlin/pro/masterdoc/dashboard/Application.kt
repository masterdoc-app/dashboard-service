package pro.masterdoc.dashboard

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours

private val log = LoggerFactory.getLogger("pro.masterdoc.dashboard")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8092
    val catalogBase = System.getenv("CATALOG_BASE_URL") ?: "http://127.0.0.1:8091"
    val horizonWeeks = System.getenv("BOARD_HORIZON_WEEKS")?.toIntOrNull() ?: 4
    log.info("event=startup port=$port catalogBase=$catalogBase horizonWeeks=$horizonWeeks")
    val mapStore = MaintenanceMapStore()
    val workOrderStore = WorkOrderStore()
    val assets = CatalogAssetLookup(catalogBase)
    val scheduler = PprScheduler(mapStore, workOrderStore, assets, horizonWeeks = horizonWeeks)
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(mapStore, workOrderStore, assets, scheduler)
        launchHourlyScheduler(scheduler)
    }.start(wait = true)
}

fun Application.launchHourlyScheduler(scheduler: PprScheduler) {
    CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(1.hours)
            runCatching { scheduler.tick() }
                .onFailure { e -> log.error("event=scheduler_failed", e) }
        }
    }
}

fun Application.module(
    mapStore: MaintenanceMapStore,
    workOrderStore: WorkOrderStore = WorkOrderStore(),
    assets: AssetLookup = AllowAllAssetLookup,
    scheduler: PprScheduler =
        PprScheduler(mapStore, workOrderStore, assets, Clock.systemUTC()),
    clock: Clock = Clock.systemUTC(),
) {
    val assetChecker = assets.asChecker()
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    install(CallLogging) {
        level = Level.INFO
    }
    install(ContentNegotiation) {
        json(json)
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            log.warn("event=bad_request reason=${cause.message}")
            call.respondText(cause.message ?: "Bad Request", status = HttpStatusCode.BadRequest)
        }
        exception<NoSuchElementException> { call, cause ->
            log.warn("event=not_found reason=${cause.message}")
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
            call.respond(HttpStatusCode.Created, mapStore.create(orgId, req))
        }
        get("/maintenance-maps") {
            val orgId = call.orgId()
            val assetId = call.request.queryParameters["assetId"]
            call.respond(MaintenanceMapList(items = mapStore.list(orgId, assetId)))
        }
        get("/maintenance-maps/{id}") {
            call.respond(mapStore.get(call.orgId(), call.parameters["id"]!!))
        }
        patch("/maintenance-maps/{id}") {
            val req = call.receive<UpdateMaintenanceMapRequest>()
            call.respond(mapStore.update(call.orgId(), call.parameters["id"]!!, req))
        }
        post("/maintenance-maps/{id}/confirm") {
            val orgId = call.orgId()
            val confirmed = mapStore.confirm(orgId, call.parameters["id"]!!, Instant.now(clock))
            scheduler.tick(orgId = orgId, mapId = confirmed.id)
            call.respond(confirmed)
        }
        post("/maintenance-maps/{id}/reject") {
            mapStore.reject(call.orgId(), call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/work-orders") {
            val orgId = call.orgId()
            val req = call.receive<CreateWorkOrderRequest>()
            if (assets.siteIdOf(orgId, req.assetId) == null) {
                throw IllegalArgumentException("Unknown asset: ${req.assetId}")
            }
            val source =
                when (req.source) {
                    WorkOrderSource.scheduler -> WorkOrderSource.api
                    else -> req.source
                }
            val created =
                workOrderStore.create(
                    orgId = orgId,
                    req = req.copy(source = source),
                    now = Instant.now(clock),
                    maps = mapStore,
                )
            call.respond(HttpStatusCode.Created, created)
        }
        get("/work-orders/board") {
            val orgId = call.orgId()
            val weeks = call.request.queryParameters["weeks"]?.toIntOrNull() ?: 4
            val weekStart =
                call.request.queryParameters["weekStart"]
                    ?: WeekDates.format(WeekDates.mondayOnOrBefore(LocalDate.now(clock)))
            call.respond(workOrderStore.board(orgId, weekStart, weeks))
        }
        get("/work-orders/{id}") {
            call.respond(workOrderStore.get(call.orgId(), call.parameters["id"]!!))
        }
        patch("/work-orders/{id}") {
            val orgId = call.orgId()
            val id = call.parameters["id"]!!
            val raw = call.receiveText()
            val obj = json.parseToJsonElement(raw).jsonObject
            val status =
                obj["status"]?.jsonPrimitive?.contentOrNull?.let { WorkOrderStatus.valueOf(it) }
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
            val dueAt = obj["dueAt"]?.jsonPrimitive?.contentOrNull
            val durationHours = obj["durationHours"]?.jsonPrimitive?.intOrNull
            val assigneePresent = "assigneeId" in obj
            val assigneeId =
                if (assigneePresent) {
                    when (val node = obj["assigneeId"]) {
                        null, JsonNull -> null
                        else -> node.jsonPrimitive.contentOrNull
                    }
                } else {
                    null
                }
            call.respond(
                workOrderStore.update(
                    orgId = orgId,
                    id = id,
                    status = status,
                    title = title,
                    dueAt = dueAt,
                    durationHours = durationHours,
                    assigneePresent = assigneePresent,
                    assigneeId = assigneeId,
                    now = Instant.now(clock),
                ),
            )
        }

        post("/internal/scheduler/tick") {
            val orgId = call.request.queryParameters["orgId"]
            val mapId = call.request.queryParameters["mapId"]
            call.respond(scheduler.tick(orgId = orgId, mapId = mapId))
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
    val activatedAt: String? = null,
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
                status = RecordStatus.draft,
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

    fun activeMaps(orgId: String?): List<MaintenanceMap> =
        byId.values
            .filter { it.status == RecordStatus.active }
            .filter { orgId == null || it.orgId == orgId }

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

    fun confirm(orgId: String, id: String, now: Instant = Instant.now()): MaintenanceMap {
        val map = get(orgId, id)
        if (map.status != RecordStatus.draft) throw IllegalArgumentException("Only draft maps can be confirmed")
        val published =
            map.copy(
                status = RecordStatus.active,
                activatedAt = now.toString(),
            )
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
