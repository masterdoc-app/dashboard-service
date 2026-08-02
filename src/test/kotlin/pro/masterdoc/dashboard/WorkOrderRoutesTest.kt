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

private class FakeMaintenanceMapGateway(
    private val maps: List<MaintenanceMapSnapshot> = emptyList(),
) : MaintenanceMapGateway {
    override fun get(orgId: String, id: String): MaintenanceMapSnapshot =
        maps.firstOrNull { it.orgId == orgId && it.id == id }
            ?: throw NoSuchElementException("Map not found")

    override fun listActive(orgId: String?, mapId: String?): List<MaintenanceMapSnapshot> =
        maps
            .filter { orgId == null || it.orgId == orgId }
            .filter { mapId == null || it.id == mapId }
}

private class FakeCatalogScopeClient(
    private val scopes: Map<String, UserScopeView> = emptyMap(),
    private val assetSites: Map<String, String> = emptyMap(),
) : CatalogScopeClient {
    private fun scopeKey(orgId: String, userId: String) = "$orgId::$userId"

    private fun assetKey(orgId: String, assetId: String) = "$orgId::$assetId"

    override fun getUserScope(orgId: String, userId: String): UserScopeView =
        scopes[scopeKey(orgId, userId)] ?: UserScopeView(userId = userId, orgId = orgId)

    override fun covers(orgId: String, userId: String, assetId: String): Boolean {
        val scope = getUserScope(orgId, userId)
        if (scope.siteIds.isEmpty() && scope.assetIds.isEmpty()) return false
        if (assetId in scope.assetIds) return true
        val siteId = assetSites[assetKey(orgId, assetId)] ?: return false
        return siteId in scope.siteIds
    }
}

/** Fake: only listed userIds have `engineer`. Caller features are irrelevant. */
private class FakeFeatureLookupClient(
    private val engineerUserIds: Set<String> = emptySet(),
) : FeatureLookupClient {
    var lastLookedUpUserId: String? = null

    override fun hasFeature(orgId: String, userId: String, feature: String): Boolean {
        lastLookedUpUserId = userId
        return feature == "engineer" && userId in engineerUserIds
    }
}

class WorkOrderRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fixedInstant = Instant.parse("2026-07-22T10:00:00Z") // Wednesday
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    @Test
    fun createEmergencyStartsNewWithoutAssignee() = testApplication {
        val maps = FakeMaintenanceMapGateway()
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
    fun maintenanceMapRoutesAreNotOwned() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        application { module(maps) }

        assertEquals(HttpStatusCode.NotFound, client.get("/maintenance-maps").status)
    }

    @Test
    fun createRequiresSiteNonBlank() = testApplication {
        val maps = FakeMaintenanceMapGateway()
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
        val maps = FakeMaintenanceMapGateway()
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
        val mapId = "map-1"
        val itemId = "item-1"
        val maps =
            FakeMaintenanceMapGateway(
                listOf(
                    MaintenanceMapSnapshot(
                        id = mapId,
                        orgId = "org-1",
                        assetId = "a1",
                        activatedAt = "2026-07-01T00:00:00Z",
                        items =
                            listOf(
                                MaintenanceMapItemSnapshot(
                                    id = itemId,
                                    title = "Осмотр",
                                    interval = MaintenanceIntervalSnapshot(30, IntervalUnit.days),
                                ),
                            ),
                    ),
                ),
            )
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }

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
        val maps = FakeMaintenanceMapGateway()
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
        val closedJson = json.parseToJsonElement(closed.bodyAsText()).jsonObject
        assertEquals(fixedInstant.toString(), closedJson["startedAt"]!!.jsonPrimitive.content)
        assertEquals(fixedInstant.toString(), closedJson["closedAt"]!!.jsonPrimitive.content)

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
    fun equipmentDowntimeFiltersByOverlapAndOrg() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val first =
            orders.create(
                "org-1",
                CreateWorkOrderRequest(WorkOrderType.emergency, "Overlapping", "asset-1", "site-1", "2026-07-22"),
                now = Instant.parse("2026-07-20T10:00:00Z"),
            )
        orders.update("org-1", first.id, status = WorkOrderStatus.in_progress, now = Instant.parse("2026-07-22T12:00:00Z"))
        val second =
            orders.create(
                "org-1",
                CreateWorkOrderRequest(WorkOrderType.emergency, "Outside", "asset-2", "site-1", "2026-07-22"),
                now = Instant.parse("2026-07-20T10:00:00Z"),
            )
        orders.update("org-1", second.id, status = WorkOrderStatus.in_progress, now = Instant.parse("2026-07-25T00:00:00Z"))
        orders.update("org-1", second.id, status = WorkOrderStatus.closed, now = Instant.parse("2026-07-25T01:00:00Z"))
        val otherOrg =
            orders.create(
                "org-2",
                CreateWorkOrderRequest(WorkOrderType.emergency, "Other org", "asset-3", "site-1", "2026-07-22"),
                now = Instant.parse("2026-07-20T10:00:00Z"),
            )
        orders.update("org-2", otherOrg.id, status = WorkOrderStatus.in_progress, now = Instant.parse("2026-07-22T12:00:00Z"))

        val response =
            client.get("/reports/equipment-downtime?from=2026-07-22&to=2026-07-23") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val items = json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, items.size)
        assertEquals(first.id, items.single().jsonObject["workOrderId"]!!.jsonPrimitive.content)
        assertEquals("in_progress", items.single().jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun managerKpisRouteUsesOrgHeaderAndDateBoundaries() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val emergency =
            orders.create(
                "org-1",
                CreateWorkOrderRequest(WorkOrderType.emergency, "Авария", "asset-1", "site-1", "2026-07-22"),
                now = Instant.parse("2026-07-10T00:00:00Z"),
            )
        orders.update("org-1", emergency.id, status = WorkOrderStatus.in_progress, now = Instant.parse("2026-07-10T01:00:00Z"))

        val response =
            client.get("/reports/manager-kpis?from=2026-07-01&to=2026-07-31") {
                header("X-Org-Id", "org-1")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("2026-07-01", body["from"]!!.jsonPrimitive.content)
        assertEquals("2026-07-31", body["to"]!!.jsonPrimitive.content)
        assertEquals(1, body["emergencyCount"]!!.jsonPrimitive.int)
        assertEquals(1, body["downtimeRanking"]!!.jsonArray.size)
    }

    @Test
    fun engineerWorkCycleAssignStartClose() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(
                maps,
                orders,
                AllowAllAssetLookup,
                PprScheduler(maps, orders, AllowAllAssetLookup, clock),
                clock,
                featureLookup = FakeFeatureLookupClient(setOf("engineer-1")),
            )
        }

        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                header("X-Caller-Features", "board")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"Цикл инженера","assetId":"a1","siteId":"s1","dueAt":"2026-07-22"}""",
                )
            }
        assertEquals(HttpStatusCode.Created, create.status)
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val assign =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                header("X-Caller-Features", "board")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":"engineer-1"}""")
            }
        assertEquals(HttpStatusCode.OK, assign.status)

        val start =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                header("X-Caller-Features", "engineer")
                header("X-User-Id", "engineer-1")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"in_progress"}""")
            }
        assertEquals(HttpStatusCode.OK, start.status)

        val close =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                header("X-Caller-Features", "engineer")
                header("X-User-Id", "engineer-1")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"closed"}""")
            }
        assertEquals(HttpStatusCode.OK, close.status)

        val reassign =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                header("X-Caller-Features", "engineer")
                header("X-User-Id", "engineer-1")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":"engineer-2"}""")
            }
        assertTrue(reassign.status == HttpStatusCode.BadRequest || reassign.status == HttpStatusCode.Forbidden)

        val otherEngineerStatus =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                header("X-Caller-Features", "engineer")
                header("X-User-Id", "engineer-2")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"in_progress"}""")
            }
        assertTrue(
            otherEngineerStatus.status == HttpStatusCode.BadRequest ||
                otherEngineerStatus.status == HttpStatusCode.Forbidden,
        )
    }

    @Test
    fun boardGroupsByWeekIncludingEmpty() = testApplication {
        val maps = FakeMaintenanceMapGateway()
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
        val mapId = "map-1"
        val maps =
            FakeMaintenanceMapGateway(
                listOf(
                    MaintenanceMapSnapshot(
                        id = mapId,
                        orgId = "org-1",
                        assetId = "a1",
                        activatedAt = "2026-07-01T00:00:00Z",
                        items =
                            listOf(
                                MaintenanceMapItemSnapshot(
                                    id = "item-days",
                                    title = "Ежемесячный осмотр",
                                    interval = MaintenanceIntervalSnapshot(30, IntervalUnit.days),
                                ),
                                MaintenanceMapItemSnapshot(
                                    id = "item-hours",
                                    title = "По моточасам",
                                    interval = MaintenanceIntervalSnapshot(100, IntervalUnit.hours),
                                ),
                            ),
                    ),
                ),
            )
        val orders = WorkOrderStore()
        val assets = AssetLookup { _, _ -> "site-42" }
        application {
            module(maps, orders, assets, PprScheduler(maps, orders, assets, clock, horizonWeeks = 8), clock)
        }

        val tick = client.post("/internal/scheduler/tick?orgId=org-1&mapId=$mapId")
        assertTrue(json.parseToJsonElement(tick.bodyAsText()).jsonObject["created"]!!.jsonPrimitive.int >= 1)

        val boardAfterTick =
            client.get("/work-orders/board?weekStart=2026-07-20&weeks=8") {
                header("X-Org-Id", "org-1")
            }
        val itemsAfterTick =
            json.parseToJsonElement(boardAfterTick.bodyAsText())
                .jsonObject["weeks"]!!
                .jsonArray
                .sumOf { it.jsonObject["items"]!!.jsonArray.size }
        assertTrue(itemsAfterTick >= 1)

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
        val maps = FakeMaintenanceMapGateway()
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
        val maps = FakeMaintenanceMapGateway()
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
    fun createRejectsDurationHoursAbove240AndAccepts240() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val tooLong =
            client.post("/work-orders") {
                header("X-Org-Id", "o1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"X","assetId":"a1","siteId":"s1","dueAt":"2026-07-22","durationHours":241}""",
                )
            }
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        assertTrue(tooLong.bodyAsText().contains("durationHours must be <= 240"))

        val max =
            client.post("/work-orders") {
                header("X-Org-Id", "o1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"X","assetId":"a1","siteId":"s1","dueAt":"2026-07-22","durationHours":240}""",
                )
            }
        assertEquals(HttpStatusCode.Created, max.status)
        assertEquals(
            240,
            json.parseToJsonElement(max.bodyAsText()).jsonObject["durationHours"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun patchDurationHours() = testApplication {
        val maps = FakeMaintenanceMapGateway()
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
    fun patchRejectsDurationHoursAbove240() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "o1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"X","assetId":"a1","siteId":"s1","dueAt":"2026-07-22","durationHours":240}""",
                )
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val tooLong =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "o1")
                contentType(ContentType.Application.Json)
                setBody("""{"durationHours":241}""")
            }
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        assertTrue(tooLong.bodyAsText().contains("durationHours must be <= 240"))
        assertEquals(240, orders.get("o1", id).durationHours)
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
        val maps = FakeMaintenanceMapGateway()
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

    @Test
    fun boardScopeFilterEmptyScopeReturnsEmptyItems() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val scope =
            FakeCatalogScopeClient(
                scopes =
                    mapOf(
                        "org-1::engineer-1" to
                            UserScopeView(userId = "engineer-1", orgId = "org-1"),
                    ),
            )
        application {
            module(
                maps,
                orders,
                AllowAllAssetLookup,
                PprScheduler(maps, orders, AllowAllAssetLookup, clock),
                clock,
                scope,
            )
        }
        client.post("/work-orders") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody(
                """{"type":"emergency","title":"W1","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
            )
        }

        val board =
            client.get("/work-orders/board?weekStart=2026-07-20&weeks=1") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "1")
                header("X-User-Id", "engineer-1")
            }
        assertEquals(HttpStatusCode.OK, board.status)
        val weeks = json.parseToJsonElement(board.bodyAsText()).jsonObject["weeks"]!!.jsonArray
        assertEquals(0, weeks[0].jsonObject["items"]!!.jsonArray.size)
    }

    @Test
    fun boardScopeFilterKeepsOnlyInScopeWorkOrders() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val scope =
            FakeCatalogScopeClient(
                scopes =
                    mapOf(
                        "org-1::engineer-1" to
                            UserScopeView(
                                userId = "engineer-1",
                                orgId = "org-1",
                                siteIds = listOf("s1"),
                            ),
                    ),
            )
        val assets =
            AssetLookup { _, assetId ->
                when (assetId) {
                    "a1" -> "s1"
                    "a2" -> "s2"
                    else -> null
                }
            }
        application {
            module(
                maps,
                orders,
                assets,
                PprScheduler(maps, orders, assets, clock),
                clock,
                scope,
            )
        }
        client.post("/work-orders") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody(
                """{"type":"emergency","title":"In scope","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
            )
        }
        client.post("/work-orders") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody(
                """{"type":"emergency","title":"Out of scope","assetId":"a2","siteId":"s2","dueAt":"2026-07-21"}""",
            )
        }

        val board =
            client.get("/work-orders/board?weekStart=2026-07-20&weeks=1") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "true")
                header("X-User-Id", "engineer-1")
            }
        val items =
            json.parseToJsonElement(board.bodyAsText())
                .jsonObject["weeks"]!!
                .jsonArray[0]
                .jsonObject["items"]!!
                .jsonArray
        assertEquals(1, items.size)
        assertEquals("In scope", items[0].jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun boardScopeFilterShowsWorkOrderWhenLiveSiteInScopeDespiteStaleWoSiteId() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val scope =
            FakeCatalogScopeClient(
                scopes =
                    mapOf(
                        "org-1::engineer-1" to
                            UserScopeView(
                                userId = "engineer-1",
                                orgId = "org-1",
                                siteIds = listOf("s1"),
                            ),
                    ),
            )
        val assets = AssetLookup { _, _ -> "s1" }
        application {
            module(
                maps,
                orders,
                assets,
                PprScheduler(maps, orders, assets, clock),
                clock,
                scope,
            )
        }
        client.post("/work-orders") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody(
                """{"type":"emergency","title":"Moved asset","assetId":"a1","siteId":"s2","dueAt":"2026-07-21"}""",
            )
        }

        val board =
            client.get("/work-orders/board?weekStart=2026-07-20&weeks=1") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "1")
                header("X-User-Id", "engineer-1")
            }
        val items =
            json.parseToJsonElement(board.bodyAsText())
                .jsonObject["weeks"]!!
                .jsonArray[0]
                .jsonObject["items"]!!
                .jsonArray
        assertEquals(1, items.size)
        assertEquals("Moved asset", items[0].jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun boardScopeFilterHidesWorkOrderWhenLiveSiteOutOfScopeDespiteMatchingWoSiteId() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val scope =
            FakeCatalogScopeClient(
                scopes =
                    mapOf(
                        "org-1::engineer-1" to
                            UserScopeView(
                                userId = "engineer-1",
                                orgId = "org-1",
                                siteIds = listOf("s1"),
                            ),
                    ),
            )
        val assets = AssetLookup { _, _ -> "s2" }
        application {
            module(
                maps,
                orders,
                assets,
                PprScheduler(maps, orders, assets, clock),
                clock,
                scope,
            )
        }
        client.post("/work-orders") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody(
                """{"type":"emergency","title":"Stale site on WO","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
            )
        }

        val board =
            client.get("/work-orders/board?weekStart=2026-07-20&weeks=1") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "1")
                header("X-User-Id", "engineer-1")
            }
        val items =
            json.parseToJsonElement(board.bodyAsText())
                .jsonObject["weeks"]!!
                .jsonArray[0]
                .jsonObject["items"]!!
                .jsonArray
        assertEquals(0, items.size)
    }

    @Test
    fun boardWithoutScopeFilterReturnsFullBoard() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val scope =
            FakeCatalogScopeClient(
                scopes =
                    mapOf(
                        "org-1::engineer-1" to
                            UserScopeView(userId = "engineer-1", orgId = "org-1"),
                    ),
            )
        application {
            module(
                maps,
                orders,
                AllowAllAssetLookup,
                PprScheduler(maps, orders, AllowAllAssetLookup, clock),
                clock,
                scope,
            )
        }
        repeat(2) { i ->
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"W$i","assetId":"a$i","siteId":"s$i","dueAt":"2026-07-21"}""",
                )
            }
        }

        val board =
            client.get("/work-orders/board?weekStart=2026-07-20&weeks=1") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "engineer-1")
            }
        val items =
            json.parseToJsonElement(board.bodyAsText())
                .jsonObject["weeks"]!!
                .jsonArray[0]
                .jsonObject["items"]!!
                .jsonArray
        assertEquals(2, items.size)
    }

    @Test
    fun patchAssigneeOutOfScopeRejected() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val scope =
            FakeCatalogScopeClient(
                scopes =
                    mapOf(
                        "org-1::engineer-1" to
                            UserScopeView(
                                userId = "engineer-1",
                                orgId = "org-1",
                                siteIds = listOf("other-site"),
                            ),
                    ),
                assetSites = mapOf("org-1::a1" to "s1"),
            )
        application {
            module(
                maps,
                orders,
                AllowAllAssetLookup,
                PprScheduler(maps, orders, AllowAllAssetLookup, clock),
                clock,
                scope,
            )
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"WO","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
                )
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val rejected =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":"engineer-1"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        assertTrue(rejected.bodyAsText().contains("Assignee scope does not cover"))
    }

    @Test
    fun patchAssigneeInScopeSucceeds() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val scope =
            FakeCatalogScopeClient(
                scopes =
                    mapOf(
                        "org-1::engineer-1" to
                            UserScopeView(
                                userId = "engineer-1",
                                orgId = "org-1",
                                assetIds = listOf("a1"),
                            ),
                    ),
            )
        application {
            module(
                maps,
                orders,
                AllowAllAssetLookup,
                PprScheduler(maps, orders, AllowAllAssetLookup, clock),
                clock,
                scope,
            )
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"WO","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
                )
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val assign =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":"engineer-1"}""")
            }
        assertEquals(HttpStatusCode.OK, assign.status)
        assertEquals(
            "engineer-1",
            json.parseToJsonElement(assign.bodyAsText()).jsonObject["assigneeId"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun patchClearAssigneeSucceedsWithoutScopeCheck() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val scope =
            FakeCatalogScopeClient(
                scopes =
                    mapOf(
                        "org-1::engineer-1" to
                            UserScopeView(
                                userId = "engineer-1",
                                orgId = "org-1",
                                assetIds = listOf("a1"),
                            ),
                    ),
            )
        application {
            module(
                maps,
                orders,
                AllowAllAssetLookup,
                PprScheduler(maps, orders, AllowAllAssetLookup, clock),
                clock,
                scope,
            )
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"WO","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
                )
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        client.patch("/work-orders/$id") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"assigneeId":"engineer-1"}""")
        }

        val cleared =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":null}""")
            }
        assertEquals(HttpStatusCode.OK, cleared.status)
        assertNull(json.parseToJsonElement(cleared.bodyAsText()).jsonObject["assigneeId"])
    }

    @Test
    fun patchAssigneeBoardOnlyRejectedEvenWhenInScope() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val features = FakeFeatureLookupClient(engineerUserIds = emptySet())
        val scope =
            FakeCatalogScopeClient(
                scopes =
                    mapOf(
                        "org-1::dispatcher-1" to
                            UserScopeView(
                                userId = "dispatcher-1",
                                orgId = "org-1",
                                assetIds = listOf("a1"),
                            ),
                    ),
            )
        application {
            module(
                maps,
                orders,
                AllowAllAssetLookup,
                PprScheduler(maps, orders, AllowAllAssetLookup, clock),
                clock,
                scope,
                features,
            )
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"WO","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
                )
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val rejected =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "caller-with-engineer")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":"dispatcher-1"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        assertTrue(rejected.bodyAsText().contains("engineer"))
        assertEquals("dispatcher-1", features.lastLookedUpUserId)
    }

    @Test
    fun patchAssigneeEngineerUserInScopeAccepted() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val features = FakeFeatureLookupClient(engineerUserIds = setOf("engineer-1"))
        val scope =
            FakeCatalogScopeClient(
                scopes =
                    mapOf(
                        "org-1::engineer-1" to
                            UserScopeView(
                                userId = "engineer-1",
                                orgId = "org-1",
                                assetIds = listOf("a1"),
                            ),
                    ),
            )
        application {
            module(
                maps,
                orders,
                AllowAllAssetLookup,
                PprScheduler(maps, orders, AllowAllAssetLookup, clock),
                clock,
                scope,
                features,
            )
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"WO","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
                )
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val assign =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":"engineer-1"}""")
            }
        assertEquals(HttpStatusCode.OK, assign.status)
        assertEquals(
            "engineer-1",
            json.parseToJsonElement(assign.bodyAsText()).jsonObject["assigneeId"]!!.jsonPrimitive.content,
        )
        assertEquals("engineer-1", features.lastLookedUpUserId)
    }

    @Test
    fun patchClearAssigneeSucceedsWithoutFeatureLookup() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val features = FakeFeatureLookupClient(engineerUserIds = setOf("engineer-1"))
        val scope =
            FakeCatalogScopeClient(
                scopes =
                    mapOf(
                        "org-1::engineer-1" to
                            UserScopeView(
                                userId = "engineer-1",
                                orgId = "org-1",
                                assetIds = listOf("a1"),
                            ),
                    ),
            )
        application {
            module(
                maps,
                orders,
                AllowAllAssetLookup,
                PprScheduler(maps, orders, AllowAllAssetLookup, clock),
                clock,
                scope,
                features,
            )
        }
        val create =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"WO","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
                )
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        client.patch("/work-orders/$id") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"assigneeId":"engineer-1"}""")
        }
        features.lastLookedUpUserId = null

        val cleared =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":null}""")
            }
        assertEquals(HttpStatusCode.OK, cleared.status)
        assertNull(json.parseToJsonElement(cleared.bodyAsText()).jsonObject["assigneeId"])
        assertNull(features.lastLookedUpUserId)
    }

    @Test
    fun listWorkOrdersFilteredByAssigneeId() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val createMine =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"Mine","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
                )
            }
        val mineId = json.parseToJsonElement(createMine.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.patch("/work-orders/$mineId") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"assigneeId":"engineer-1"}""")
        }
        client.post("/work-orders") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody(
                """{"type":"emergency","title":"Other","assetId":"a2","siteId":"s1","dueAt":"2026-07-22"}""",
            )
        }

        val filtered =
            client.get("/work-orders?assigneeId=engineer-1") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, filtered.status)
        val items = json.parseToJsonElement(filtered.bodyAsText()).jsonArray
        assertEquals(1, items.size)
        assertEquals("Mine", items[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("engineer-1", items[0].jsonObject["assigneeId"]!!.jsonPrimitive.content)
    }

    @Test
    fun boardFilteredByAssigneeId() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val createMine =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"Mine","assetId":"a1","siteId":"s1","dueAt":"2026-07-21"}""",
                )
            }
        val mineId = json.parseToJsonElement(createMine.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.patch("/work-orders/$mineId") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"assigneeId":"engineer-1"}""")
        }
        client.post("/work-orders") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody(
                """{"type":"emergency","title":"Unassigned","assetId":"a2","siteId":"s1","dueAt":"2026-07-21"}""",
            )
        }

        val board =
            client.get("/work-orders/board?weekStart=2026-07-20&weeks=1&assigneeId=engineer-1") {
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
        assertEquals("Mine", items[0].jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun ticketsCallerCreatesWithCreatedByAndDescription() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val res =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "customer-1")
                header("X-Caller-Features", "tickets")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"Шум","assetId":"a1","siteId":"s1","dueAt":"2026-07-29","description":"Сильный шум подшипника"}""",
                )
            }
        assertEquals(HttpStatusCode.Created, res.status)
        val body = json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("customer-1", body["createdBy"]!!.jsonPrimitive.content)
        assertEquals("Сильный шум подшипника", body["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun ticketsOnlyCreateRejectsOutOfScopeAsset() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val scopeClient =
            FakeCatalogScopeClient(
                scopes = mapOf("org-1::customer-1" to UserScopeView("customer-1", "org-1", assetIds = listOf("a2"))),
                assetSites = mapOf("org-1::a1" to "s1", "org-1::a2" to "s1"),
            )
        application {
            module(
                maps,
                orders,
                AllowAllAssetLookup,
                PprScheduler(maps, orders, AllowAllAssetLookup, clock),
                clock,
                scopeClient = scopeClient,
            )
        }

        val res =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "customer-1")
                header("X-Caller-Features", "tickets")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"Шум","assetId":"a1","siteId":"s1","dueAt":"2026-07-29","description":"Сильный шум"}""",
                )
            }

        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun ticketsOnlyCreateRejectsBlankOrMissingDescription() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }

        val bodies =
            listOf(
                """{"type":"emergency","title":"Шум","assetId":"a1","siteId":"s1","dueAt":"2026-07-29"}""",
                """{"type":"emergency","title":"Шум","assetId":"a1","siteId":"s1","dueAt":"2026-07-29","description":"   "}""",
            )
        for (body in bodies) {
            val res =
                client.post("/work-orders") {
                    header("X-Org-Id", "org-1")
                    header("X-User-Id", "customer-1")
                    header("X-Caller-Features", "tickets")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, res.status)
        }
    }

    @Test
    fun ticketsOnlyCreateAcceptsInScopeAssetWithDescription() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        val scopeClient =
            FakeCatalogScopeClient(
                scopes = mapOf("org-1::customer-1" to UserScopeView("customer-1", "org-1", assetIds = listOf("a1"))),
                assetSites = mapOf("org-1::a1" to "s1"),
            )
        application {
            module(
                maps,
                orders,
                AllowAllAssetLookup,
                PprScheduler(maps, orders, AllowAllAssetLookup, clock),
                clock,
                scopeClient = scopeClient,
            )
        }

        val res =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "customer-1")
                header("X-Caller-Features", "tickets")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"Шум","assetId":"a1","siteId":"s1","dueAt":"2026-07-29","description":"Сильный шум"}""",
                )
            }

        assertEquals(HttpStatusCode.Created, res.status)
    }

    @Test
    fun ticketsOnlyListForcesCreatedBySelf() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        suspend fun create(userId: String, title: String): String =
            json.parseToJsonElement(
                client.post("/work-orders") {
                    header("X-Org-Id", "org-1")
                    header("X-User-Id", userId)
                    header("X-Caller-Features", "tickets")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"emergency","title":"$title","assetId":"a1","siteId":"s1","dueAt":"2026-07-29","description":"Ticket description"}""")
                }.bodyAsText(),
            ).jsonObject["id"]!!.jsonPrimitive.content

        create("customer-1", "Mine")
        create("customer-2", "Other")
        val res =
            client.get("/work-orders?createdBy=customer-2") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "customer-1")
                header("X-Caller-Features", "tickets")
            }
        assertEquals(HttpStatusCode.OK, res.status)
        val items = json.parseToJsonElement(res.bodyAsText()).jsonArray
        assertEquals(1, items.size)
        assertEquals("Mine", items[0].jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun ticketsOnlyGetForeignWorkOrderReturns404() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val created =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "customer-1")
                header("X-Caller-Features", "tickets")
                contentType(ContentType.Application.Json)
                setBody("""{"type":"emergency","title":"Чужая","assetId":"a1","siteId":"s1","dueAt":"2026-07-29","description":"Ticket description"}""")
            }
        val id = json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val res =
            client.get("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "customer-2")
                header("X-Caller-Features", "tickets")
            }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun ticketsOnlyPatchReturns400() = testApplication {
        val maps = FakeMaintenanceMapGateway()
        val orders = WorkOrderStore()
        application {
            module(maps, orders, AllowAllAssetLookup, PprScheduler(maps, orders, AllowAllAssetLookup, clock), clock)
        }
        val created =
            client.post("/work-orders") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "customer-1")
                header("X-Caller-Features", "tickets")
                contentType(ContentType.Application.Json)
                setBody("""{"type":"emergency","title":"Моя","assetId":"a1","siteId":"s1","dueAt":"2026-07-29","description":"Ticket description"}""")
            }
        val id = json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val res =
            client.patch("/work-orders/$id") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "customer-1")
                header("X-Caller-Features", "tickets")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Изменено"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }
}
