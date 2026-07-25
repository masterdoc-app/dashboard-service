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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MaintenanceMapRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun createUpdateConfirm() = testApplication {
        application { module(MaintenanceMapStore()) }
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
    fun emptyItemsRejected() = testApplication {
        application { module(MaintenanceMapStore()) }
        val create =
            client.post("/maintenance-maps") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"assetId":"a1","title":"Empty","items":[]}""")
            }
        assertEquals(HttpStatusCode.BadRequest, create.status)
    }

    @Test
    fun intervalEveryLessThanOneRejected() = testApplication {
        application { module(MaintenanceMapStore()) }
        val create =
            client.post("/maintenance-maps") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"assetId":"a1","title":"Bad interval","items":[{"title":"Осмотр","kind":"inspection","interval":{"every":0,"unit":"days"},"criticality":"low"}]}""",
                )
            }
        assertEquals(HttpStatusCode.BadRequest, create.status)
    }

    @Test
    fun listFiltersByOrgAndAssetId() = testApplication {
        application { module(MaintenanceMapStore()) }
        val item =
            """{"title":"Осмотр","kind":"inspection","interval":{"every":1,"unit":"days"},"criticality":"low"}"""
        client.post("/maintenance-maps") {
            header("X-Org-Id", "org-a")
            contentType(ContentType.Application.Json)
            setBody("""{"assetId":"asset-1","title":"Map A1","items":[$item]}""")
        }
        client.post("/maintenance-maps") {
            header("X-Org-Id", "org-a")
            contentType(ContentType.Application.Json)
            setBody("""{"assetId":"asset-2","title":"Map A2","items":[$item]}""")
        }
        client.post("/maintenance-maps") {
            header("X-Org-Id", "org-b")
            contentType(ContentType.Application.Json)
            setBody("""{"assetId":"asset-1","title":"Map B1","items":[$item]}""")
        }

        val filtered = client.get("/maintenance-maps?assetId=asset-1") { header("X-Org-Id", "org-a") }
        assertEquals(HttpStatusCode.OK, filtered.status)
        val body = filtered.bodyAsText()
        assertTrue(body.contains("Map A1"))
        assertTrue(!body.contains("Map A2"))
        assertTrue(!body.contains("Map B1"))
    }

    @Test
    fun rejectDraftSucceeds() = testApplication {
        application { module(MaintenanceMapStore()) }
        val create =
            client.post("/maintenance-maps") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"assetId":"a1","title":"Draft map","source":"ai_generated","items":[{"title":"Осмотр","kind":"inspection","interval":{"every":1,"unit":"days"},"criticality":"low"}]}""",
                )
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val reject = client.post("/maintenance-maps/$id/reject") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.NoContent, reject.status)

        val get = client.get("/maintenance-maps/$id") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun updateAndConfirmNonDraftFails() = testApplication {
        application { module(MaintenanceMapStore()) }
        val create =
            client.post("/maintenance-maps") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"assetId":"a1","title":"Manual","source":"manual","items":[{"title":"Осмотр","kind":"inspection","interval":{"every":1,"unit":"days"},"criticality":"low"}]}""",
                )
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/maintenance-maps/$id/confirm") { header("X-Org-Id", "org-1") }

        val patch =
            client.patch("/maintenance-maps/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Should fail"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, patch.status)

        val confirm = client.post("/maintenance-maps/$id/confirm") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.BadRequest, confirm.status)
    }

    @Test
    fun unknownAssetRejected() = testApplication {
        application { module(MaintenanceMapStore(), assets = AssetLookup { _, _ -> null }) }
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
