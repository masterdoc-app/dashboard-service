package pro.masterdoc.dashboard

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MaintenanceMapRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun createUpdateConfirm() = testApplication {
        application { module(MaintenanceMapStore(), AllowAllAssetChecker) }
        val create =
            client.post("/maintenance-maps") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"assetId":"a1","title":"Карта ТО","source":"ai_generated","items":[{"title":"Осмотр","kind":"inspection","interval":{"every":1,"unit":"days"},"criticality":"high"}]}""",
                )
            }
        assertEquals(HttpStatusCode.Created, create.status)
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("draft", json.parseToJsonElement(create.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        val patch =
            client.patch("/maintenance-maps/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Карта ТО v2"}""")
            }
        assertEquals(HttpStatusCode.OK, patch.status)
        assertEquals("Карта ТО v2", json.parseToJsonElement(patch.bodyAsText()).jsonObject["title"]!!.jsonPrimitive.content)

        val confirm = client.post("/maintenance-maps/$id/confirm") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.OK, confirm.status)
        assertEquals("active", json.parseToJsonElement(confirm.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownAssetRejected() = testApplication {
        application { module(MaintenanceMapStore(), AssetChecker { _, _ -> false }) }
        val create =
            client.post("/maintenance-maps") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"assetId":"missing","title":"X","items":[{"title":"Осмотр","kind":"inspection","interval":{"every":1,"unit":"days"},"criticality":"low"}]}""",
                )
            }
        assertEquals(HttpStatusCode.BadRequest, create.status)
    }
}
