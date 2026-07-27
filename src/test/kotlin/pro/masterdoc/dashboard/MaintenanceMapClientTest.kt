package pro.masterdoc.dashboard

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MaintenanceMapClientTest {
    private val mapJson =
        """
        {
          "id":"map-1",
          "orgId":"org-1",
          "assetId":"asset-1",
          "activatedAt":"2026-07-01T00:00:00Z",
          "items":[{
            "id":"item-1",
            "title":"Осмотр",
            "interval":{"every":30,"unit":"days"}
          }]
        }
        """.trimIndent()

    @Test
    fun getSendsOrgHeaderAndParsesSnapshot() {
        val gateway =
            gateway { request ->
                assertEquals("/maintenance-maps/map-1", request.url.encodedPath)
                assertEquals("org-1", request.headers["X-Org-Id"])
                respond(
                    content = mapJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val map = gateway.get("org-1", "map-1")

        assertEquals("asset-1", map.assetId)
        assertEquals("item-1", map.items.single().id)
        assertEquals(IntervalUnit.days, map.items.single().interval.unit)
    }

    @Test
    fun listActiveSendsFiltersAndParsesEnvelope() {
        val gateway =
            gateway { request ->
                assertEquals("/internal/active-maps", request.url.encodedPath)
                assertEquals("org-1", request.url.parameters["orgId"])
                assertEquals("map-1", request.url.parameters["mapId"])
                respond(
                    content = """{"items":[$mapJson]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val maps = gateway.listActive("org-1", "map-1")

        assertEquals(listOf("map-1"), maps.map { it.id })
    }

    private fun gateway(handler: io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): HttpMaintenanceMapGateway {
        val client =
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        return HttpMaintenanceMapGateway("http://maintenance.test", client)
    }
}
