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
import kotlin.time.Duration.Companion.hours

private val log = LoggerFactory.getLogger("pro.masterdoc.dashboard")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8092
    val catalogBase = System.getenv("CATALOG_BASE_URL") ?: "http://127.0.0.1:8091"
    val maintenanceBase = System.getenv("MAINTENANCE_SERVICE_BASE_URL") ?: "http://127.0.0.1:8098"
    val featureBase = System.getenv("FEATURE_SERVICE_BASE_URL") ?: "http://127.0.0.1:8082"
    val horizonWeeks = System.getenv("BOARD_HORIZON_WEEKS")?.toIntOrNull() ?: 4
    log.info(
        "event=startup port=$port catalogBase=$catalogBase maintenanceBase=$maintenanceBase " +
            "featureBase=$featureBase horizonWeeks=$horizonWeeks",
    )
    val maps = HttpMaintenanceMapGateway(maintenanceBase)
    val workOrderStore = WorkOrderStore()
    val assets = CatalogAssetLookup(catalogBase)
    val scopeClient = HttpCatalogScopeClient(catalogBase)
    val featureLookup = HttpFeatureLookupClient(featureBase)
    val scheduler = PprScheduler(maps, workOrderStore, assets, horizonWeeks = horizonWeeks)
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(
            maps,
            workOrderStore,
            assets,
            scheduler,
            scopeClient = scopeClient,
            featureLookup = featureLookup,
        )
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
    maps: MaintenanceMapGateway,
    workOrderStore: WorkOrderStore = WorkOrderStore(),
    assets: AssetLookup = AllowAllAssetLookup,
    scheduler: PprScheduler =
        PprScheduler(maps, workOrderStore, assets, Clock.systemUTC()),
    clock: Clock = Clock.systemUTC(),
    scopeClient: CatalogScopeClient = AllowAllCatalogScopeClient,
    featureLookup: FeatureLookupClient = AllowAllFeatureLookupClient,
) {
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

        post("/work-orders") {
            val orgId = call.orgId()
            val callerFeatures = call.callerFeatures()
            if (callerFeatures != null && "board" !in callerFeatures && "tickets" !in callerFeatures) {
                throw IllegalArgumentException("Caller requires board or tickets feature to create work orders")
            }
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
                    createdBy = call.userId(),
                    now = Instant.now(clock),
                    maps = maps,
                )
            call.respond(HttpStatusCode.Created, created)
        }
        get("/work-orders") {
            val orgId = call.orgId()
            val assigneeId = call.request.queryParameters["assigneeId"]?.takeIf { it.isNotBlank() }
            var createdBy = call.request.queryParameters["createdBy"]?.takeIf { it.isNotBlank() }
            if (call.isTicketsOnly()) createdBy = call.userId()
            var items = workOrderStore.list(orgId, assigneeId, createdBy)
            if (call.scopeFilterEnabled()) {
                val scope = scopeClient.getUserScope(orgId, call.userId())
                items = filterWorkOrdersByScope(items, scope, assets)
            }
            call.respond(items)
        }
        get("/work-orders/board") {
            val orgId = call.orgId()
            val weeks = call.request.queryParameters["weeks"]?.toIntOrNull() ?: 4
            val weekStart =
                call.request.queryParameters["weekStart"]
                    ?: WeekDates.format(WeekDates.mondayOnOrBefore(LocalDate.now(clock)))
            val assigneeId = call.request.queryParameters["assigneeId"]?.takeIf { it.isNotBlank() }
            var board = workOrderStore.board(orgId, weekStart, weeks, assigneeId)
            if (call.scopeFilterEnabled()) {
                val scope = scopeClient.getUserScope(orgId, call.userId())
                board = filterBoardByScope(board, scope, assets)
            }
            call.respond(board)
        }
        get("/work-orders/{id}") {
            val workOrder = workOrderStore.get(call.orgId(), call.parameters["id"]!!)
            if (call.isTicketsOnly() && workOrder.createdBy != call.userId()) {
                throw NoSuchElementException("Work order not found")
            }
            call.respond(workOrder)
        }
        patch("/work-orders/{id}") {
            if (call.isTicketsOnly()) {
                throw IllegalArgumentException("tickets cannot modify work orders")
            }
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
            val callerFeatures = call.callerFeatures()
            val current = workOrderStore.get(orgId, id)
            if (assigneePresent && callerFeatures != null && "board" !in callerFeatures) {
                throw IllegalArgumentException("Caller requires board feature to change assignee")
            }
            if (status != null && callerFeatures != null && "board" !in callerFeatures) {
                if (call.userId() != current.assigneeId) {
                    throw IllegalArgumentException("Only the assigned engineer may change status")
                }
            }
            val assigneeId =
                if (assigneePresent) {
                    when (val node = obj["assigneeId"]) {
                        null, JsonNull -> null
                        else -> node.jsonPrimitive.contentOrNull
                    }
                } else {
                    null
                }
            if (assigneePresent && assigneeId != null) {
                if (!featureLookup.hasFeature(orgId, assigneeId, "engineer")) {
                    throw IllegalArgumentException(
                        "Assignee must have engineer feature",
                    )
                }
                if (!scopeClient.covers(orgId, assigneeId, current.assetId)) {
                    throw IllegalArgumentException(
                        "Assignee scope does not cover this work order's asset",
                    )
                }
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

private fun io.ktor.server.application.ApplicationCall.userId(): String =
    request.header("X-User-Id")?.takeIf { it.isNotBlank() } ?: "unknown"

private fun io.ktor.server.application.ApplicationCall.callerFeatures(): Set<String>? =
    request.header("X-Caller-Features")?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()

private fun io.ktor.server.application.ApplicationCall.isTicketsOnly(): Boolean {
    val features = callerFeatures() ?: return false
    return "tickets" in features &&
        "board" !in features &&
        "engineer" !in features &&
        "admin" !in features
}

private fun io.ktor.server.application.ApplicationCall.scopeFilterEnabled(): Boolean {
    val value = request.header("X-Scope-Filter")?.trim()?.lowercase() ?: return false
    return value == "1" || value == "true"
}
