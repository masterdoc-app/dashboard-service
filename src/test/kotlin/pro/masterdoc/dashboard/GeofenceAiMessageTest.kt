package pro.masterdoc.dashboard

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class GeofenceAiMessageTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val maps =
        object : MaintenanceMapGateway {
            override fun get(orgId: String, id: String): MaintenanceMapSnapshot =
                throw NoSuchElementException("Map not found")

            override fun listActive(orgId: String?, mapId: String?): List<MaintenanceMapSnapshot> = emptyList()
        }

    @Test
    fun outsideGeofencePostsAiMessage() = testApplication {
        val requests = mutableListOf<String>()
        val aiClient =
            HttpAiMessageClient(
                "http://ai.test",
                "secret",
                HttpClient(
                    MockEngine { request ->
                        assertEquals("/messages", request.url.encodedPath)
                        assertEquals("secret", request.headers["X-Internal-Token"])
                        requests += (request.body as TextContent).text
                        respond("", HttpStatusCode.Created)
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                },
            )
        application {
            module(
                maps = maps,
                siteLookup = SiteLookupClient { _, _ ->
                    SiteGeofence("s1", name = "Цех 1", lat = 55.0, lon = 37.0, geofenceRadiusM = 200)
                },
                aiMessages = aiClient,
            )
        }

        val response = client.startWorkOrder("""{"lat":55.01,"lon":37.0,"accuracyM":5}""")

        assertEquals(HttpStatusCode.OK, response.status)
        awaitUntil { requests.size == 1 }
        val message = json.parseToJsonElement(requests.single()).jsonObject
        assertEquals("outside_workshop_radius", message["kind"]!!.jsonPrimitive.content)
        assertEquals("Инженер вне цеха", message["title"]!!.jsonPrimitive.content)
        val body = message["body"]!!.jsonPrimitive.content
        assertTrue(body.contains("«Геозона»"), body)
        assertTrue(body.contains("«Цех 1»"), body)
        assertTrue(!body.contains("engineer-1"), body)
        assertTrue(!body.contains("s1"), body)
    }

    @Test
    fun insideGeofenceDoesNotPostAiMessage() = testApplication {
        val messages = mutableListOf<CreateAiMessageRequest>()
        application {
            module(
                maps = maps,
                siteLookup = SiteLookupClient { _, _ -> SiteGeofence("s1", lat = 55.0, lon = 37.0) },
                aiMessages = AiMessageClient { messages += it },
            )
        }

        val response = client.startWorkOrder("""{"lat":55.0,"lon":37.0}""")

        assertEquals(HttpStatusCode.OK, response.status)
        delay(150)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun missingLocationPostsMissingLocationMessage() = testApplication {
        val messages = mutableListOf<CreateAiMessageRequest>()
        application {
            module(
                maps = maps,
                siteLookup = SiteLookupClient { _, _ -> SiteGeofence("s1", lat = 55.0, lon = 37.0) },
                aiMessages = AiMessageClient { messages += it },
            )
        }

        val response = client.startWorkOrder()

        assertEquals(HttpStatusCode.OK, response.status)
        awaitUntil { messages.size == 1 }
        assertEquals(listOf("location_missing"), messages.map { it.kind })
        assertEquals("Нет геолокации при старте", messages.single().title)
        assertEquals("Заявка «Геозона»: начало работы без геолокации.", messages.single().body)
    }

    @Test
    fun siteWithoutCoordinatesDoesNotPostAiMessage() = testApplication {
        val messages = mutableListOf<CreateAiMessageRequest>()
        application {
            module(
                maps = maps,
                siteLookup = SiteLookupClient { _, _ -> SiteGeofence("s1") },
                aiMessages = AiMessageClient { messages += it },
            )
        }

        val response = client.startWorkOrder()

        assertEquals(HttpStatusCode.OK, response.status)
        delay(150)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun aiServiceFailureDoesNotFailStart() = testApplication {
        application {
            module(
                maps = maps,
                siteLookup = SiteLookupClient { _, _ -> SiteGeofence("s1", lat = 55.0, lon = 37.0) },
                aiMessages = AiMessageClient { throw IllegalStateException("AI unavailable") },
            )
        }

        val response = client.startWorkOrder()

        assertEquals(HttpStatusCode.OK, response.status)
        delay(150)
    }

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        repeat(50) {
            if (predicate()) return
            delay(20)
        }
        fail("condition not met in time")
    }

    private suspend fun io.ktor.client.HttpClient.startWorkOrder(location: String? = null) =
        post("/work-orders") {
            header("X-Org-Id", "org-1")
            header("X-User-Id", "engineer-1")
            contentType(ContentType.Application.Json)
            setBody("""{"type":"emergency","title":"Геозона","assetId":"a1","siteId":"s1","dueAt":"2026-08-03"}""")
        }.let { created ->
            val id = json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
            patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "engineer-1")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"in_progress"${location?.let { "," + "\"location\":$it" }.orEmpty()}}""")
            }
        }
}
