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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WorkOrderRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fixedInstant = Instant.parse("2026-07-22T10:00:00Z") // Wednesday
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    @Test
    fun createEmergencyStartsNewWithoutAssignee() = testApplication {
        val maps = MaintenanceMapStore()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"Утечка","assetId":"a1","siteId":"s1","dueAt":"2026-07-22"}""",
                )
            }
        assertEquals(HttpStatusCode.Created, create.status)
        val body = json.parseToJsonElement(create.bodyAsText()).jsonObject
        assertEquals("new", body["status"]!!.jsonPrimitive.content)
        assertEquals("emergency", body["type"]!!.jsonPrimitive.content)
        assertNull(body["assigneeId"])
        assertNull(body["maintenanceMapId"])
    }

    @Test
    fun createRequiresSiteNonBlank() = testApplication {
        val maps = MaintenanceMapStore()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val missingSite =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"type":"emergency","title":"X","assetId":"a1","siteId":"","dueAt":"2026-07-22"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, missingSite.status)
    }

    @Test
    fun unknownAssetRejected() = testApplication {
        val maps = MaintenanceMapStore()
        val orders = WorkOrderStore()
        val assets = AssetLookup { _, _ -> null }
        application {
            module(maps, orders, assets, PprScheduler(maps, orders, assets, clock), clock)
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"X","assetId":"missing","siteId":"s1","dueAt":"2026-07-22"}""",
                )
            }
        assertEquals(HttpStatusCode.BadRequest, create.status)
    }

    @Test
    fun createPprValidatesMapAndAssetMatch() = testApplication {
        val maps = MaintenanceMapStore()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val mapCreate =
            client.post("/maintenance-maps") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"assetId":"a1","title":"Карта","items":[{"title":"Осмотр","kind":"inspection","interval":{"every":30,"unit":"days"},"criticality":"low"}]}""",
                )
            }
        val map = json.parseToJsonElement(mapCreate.bodyAsText()).jsonObject
        val mapId = map["id"]!!.jsonPrimitive.content
        val itemId = map["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val noMap =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"ppr","title":"Осмотр","assetId":"a1","siteId":"s1","dueAt":"2026-07-22"}""",
                )
            }
        assertEquals(HttpStatusCode.BadRequest, noMap.status)

        val wrongAsset =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"ppr","title":"Осмотр","assetId":"other","siteId":"s1","dueAt":"2026-07-22","maintenanceMapId":"$mapId","maintenanceMapItemId":"$itemId"}""",
                )
            }
        assertEquals(HttpStatusCode.BadRequest, wrongAsset.status)

        val ok =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"ppr","title":"Осмотр","assetId":"a1","siteId":"s1","dueAt":"2026-07-22","maintenanceMapId":"$mapId","maintenanceMapItemId":"$itemId"}""",
                )
            }
        assertEquals(HttpStatusCode.Created, ok.status)
    }

    @Test
    fun statusTransitionsAndAssigneePatch() = testApplication {
        val maps = MaintenanceMapStore()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"Авария","assetId":"a1","siteId":"s1","dueAt":"2026-07-22"}""",
                )
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val illegal =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"closed"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, illegal.status)

        val assign =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":"user-9"}""")
            }
        assertEquals(HttpStatusCode.OK, assign.status)
        assertEquals(
            "user-9",
            json.parseToJsonElement(assign.bodyAsText()).jsonObject["assigneeId"]!!.jsonPrimitive.content,
        )

        client.patch("/work-orders/$id") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"in_progress"}""")
        }
        val closed =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"closed"}""")
            }
        assertEquals(HttpStatusCode.OK, closed.status)

        val reassign =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":"other"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, reassign.status)

        val clearAttempt =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":null}""")
            }
        assertEquals(HttpStatusCode.BadRequest, clearAttempt.status)
    }

    @Test
    fun boardGroupsByWeekIncludingEmpty() = testApplication {
        val maps = MaintenanceMapStore()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        // Monday 2026-07-20
        client.post("/work-orders") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody(
                """{"type":"emergency","title":"W1","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
            )
        }
        client.post("/work-orders") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody(
                """{"type":"emergency","title":"W3","assetId":"a1","siteId":"s1","dueAt":"2026-08-04"}""",
            )
        }

        val board =
            client.get("/work-orders/board?weekStart=2026-07-20&weeks=4") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, board.status)
        val weeks = json.parseToJsonElement(board.bodyAsText()).jsonObject["weeks"]!!.jsonArray
        assertEquals(4, weeks.size)
        assertEquals("2026-07-20", weeks[0].jsonObject["weekStart"]!!.jsonPrimitive.content)
        assertEquals(1, weeks[0].jsonObject["items"]!!.jsonArray.size)
        assertEquals(0, weeks[1].jsonObject["items"]!!.jsonArray.size)
        assertEquals(1, weeks[2].jsonObject["items"]!!.jsonArray.size)
        assertEquals(0, weeks[3].jsonObject["items"]!!.jsonArray.size)
    }

    @Test
    fun schedulerCreatesFromActiveMapIdempotent() = testApplication {
        val maps = MaintenanceMapStore()
        val orders = WorkOrderStore()
        val assets = AssetLookup { _, _ -> "site-42" }
        application {
            module(maps, orders, assets, PprScheduler(maps, orders, assets, clock, horizonWeeks = 8), clock)
        }
        val mapCreate =
            client.post("/maintenance-maps") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"assetId":"a1","title":"Карта","items":[{"title":"Ежемесячный осмотр","kind":"inspection","interval":{"every":30,"unit":"days"},"criticality":"medium"},{"title":"По моточасам","kind":"service","interval":{"every":100,"unit":"hours"},"criticality":"low"}]}""",
                )
            }
        val mapId = json.parseToJsonElement(mapCreate.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // Draft: no WO
        val tickDraft = client.post("/internal/scheduler/tick?orgId=org-1")
        assertEquals(0, json.parseToJsonElement(tickDraft.bodyAsText()).jsonObject["created"]!!.jsonPrimitive.int)

        val confirm = client.post("/maintenance-maps/$mapId/confirm") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.OK, confirm.status)
        val activatedAt = json.parseToJsonElement(confirm.bodyAsText()).jsonObject["activatedAt"]
        assertTrue(activatedAt != null && activatedAt.toString().isNotBlank())

        // Confirm already ticks once
        val boardAfterConfirm =
            client.get("/work-orders/board?weekStart=2026-07-20&weeks=8") {
                header("X-Org-Id", "org-1")
            }
        val itemsAfterConfirm =
            json.parseToJsonElement(boardAfterConfirm.bodyAsText())
                .jsonObject["weeks"]!!
                .jsonArray
                .sumOf { it.jsonObject["items"]!!.jsonArray.size }
        assertTrue(itemsAfterConfirm >= 1)

        val tick2 = client.post("/internal/scheduler/tick?orgId=org-1")
        assertEquals(0, json.parseToJsonElement(tick2.bodyAsText()).jsonObject["created"]!!.jsonPrimitive.int)
        assertTrue(json.parseToJsonElement(tick2.bodyAsText()).jsonObject["skippedNonDays"]!!.jsonPrimitive.int >= 1)

        val getFirst =
            client.get("/work-orders/board?weekStart=2026-07-20&weeks=8") {
                header("X-Org-Id", "org-1")
            }
        val firstWo =
            json.parseToJsonElement(getFirst.bodyAsText())
                .jsonObject["weeks"]!!
                .jsonArray
                .flatMap { it.jsonObject["items"]!!.jsonArray }
                .first()
                .jsonObject
        assertEquals("ppr", firstWo["type"]!!.jsonPrimitive.content)
        assertEquals("site-42", firstWo["siteId"]!!.jsonPrimitive.content)
        assertEquals("a1", firstWo["assetId"]!!.jsonPrimitive.content)
        assertEquals("scheduler", firstWo["source"]!!.jsonPrimitive.content)
        assertEquals(mapId, firstWo["maintenanceMapId"]!!.jsonPrimitive.content)
    }

    @Test
    fun createDefaultsDurationHoursTo8() = testApplication {
        val maps = MaintenanceMapStore()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val res =
            client.post("/work-orders") {
                header("X-Org-Id", "o1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"Утечка","assetId":"a1","siteId":"s1","dueAt":"2026-07-22"}""",
                )
            }
        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals(
            8,
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["durationHours"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun createRejectsDurationHoursBelow1() = testApplication {
        val maps = MaintenanceMapStore()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val res =
            client.post("/work-orders") {
                header("X-Org-Id", "o1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"X","assetId":"a1","siteId":"s1","dueAt":"2026-07-22","durationHours":0}""",
                )
            }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun patchDurationHours() = testApplication {
        val maps = MaintenanceMapStore()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "o1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"Утечка","assetId":"a1","siteId":"s1","dueAt":"2026-07-22"}""",
                )
            }
        val id = Json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val patched =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "o1")
                contentType(ContentType.Application.Json)
                setBody("""{"durationHours":16}""")
            }
        assertEquals(
            16,
            Json.parseToJsonElement(patched.bodyAsText()).jsonObject["durationHours"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun mondayOnOrBeforeMatchesFixedClock() {
        assertEquals(LocalDate.parse("2026-07-20"), WeekDates.mondayOnOrBefore(LocalDate.parse("2026-07-22")))
        assertFalse(WeekDates.isMonday(LocalDate.parse("2026-07-22")))
        assertTrue(WeekDates.isMonday(LocalDate.parse("2026-07-20")))
    }

    @Test
    fun weekDatesSpanFridayThreeWorkdays() {
        val start = LocalDate.parse("2026-07-24") // Friday
        val occupied = WeekDates.spanWorkingDays(start, durationHours = 24) // ceil(24/8)=3
        assertEquals(
            listOf(
                LocalDate.parse("2026-07-24"),
                LocalDate.parse("2026-07-27"),
                LocalDate.parse("2026-07-28"),
            ),
            occupied,
        )
    }

    @Test
    fun boardIncludesWoInNextWeekWhenSpanCrossesWeekend() = testApplication {
        val maps = MaintenanceMapStore()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"Cross-week","assetId":"a1","siteId":"s1","dueAt":"2026-07-24","durationHours":24}""",
                )
            }
        assertEquals(HttpStatusCode.Created, create.status)
        val woId = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val board =
            client.get("/work-orders/board?weekStart=2026-07-27&weeks=1") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, board.status)
        val items =
            json.parseToJsonElement(board.bodyAsText())
                .jsonObject["weeks"]!!
                .jsonArray[0]
                .jsonObject["items"]!!
                .jsonArray
        assertEquals(1, items.size)
        assertEquals(woId, items[0].jsonObject["id"]!!.jsonPrimitive.content)
    }
}
