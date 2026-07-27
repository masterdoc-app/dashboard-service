package pro.masterdoc.dashboard

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("pro.masterdoc.dashboard.scope")

@Serializable
data class UserScopeView(
    val userId: String,
    val orgId: String,
    val siteIds: List<String> = emptyList(),
    val assetIds: List<String> = emptyList(),
)

@Serializable
private data class CoversResponse(val covers: Boolean)

interface CatalogScopeClient {
    fun getUserScope(orgId: String, userId: String): UserScopeView

    fun covers(orgId: String, userId: String, assetId: String): Boolean
}

object AllowAllCatalogScopeClient : CatalogScopeClient {
    override fun getUserScope(orgId: String, userId: String): UserScopeView =
        UserScopeView(userId = userId, orgId = orgId)

    override fun covers(orgId: String, userId: String, assetId: String): Boolean = true
}

class HttpCatalogScopeClient(
    baseUrl: String,
    private val client: HttpClient = defaultClient(),
) : CatalogScopeClient {
    private val baseUrl = baseUrl.trimEnd('/')

    override fun getUserScope(orgId: String, userId: String): UserScopeView =
        runBlocking {
            val response =
                client.get("$baseUrl/user-scopes/$userId") {
                    header("X-Org-Id", orgId)
                }
            check(response.status == HttpStatusCode.OK) {
                "Catalog scope lookup returned ${response.status}"
            }
            response.body()
        }

    override fun covers(orgId: String, userId: String, assetId: String): Boolean =
        runBlocking {
            val response =
                client.get("$baseUrl/user-scopes/$userId/covers/$assetId") {
                    header("X-Org-Id", orgId)
                }
            when (response.status) {
                HttpStatusCode.OK -> response.body<CoversResponse>().covers
                HttpStatusCode.NotFound -> false
                else -> {
                    log.warn(
                        "event=catalog_covers_failed orgId=$orgId userId=$userId assetId=$assetId status=${response.status}",
                    )
                    false
                }
            }
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

fun filterBoardByScope(board: BoardResponse, scope: UserScopeView): BoardResponse {
    if (scope.siteIds.isEmpty() && scope.assetIds.isEmpty()) {
        return BoardResponse(weeks = board.weeks.map { it.copy(items = emptyList()) })
    }
    val siteSet = scope.siteIds.toSet()
    val assetSet = scope.assetIds.toSet()
    return BoardResponse(
        weeks =
            board.weeks.map { week ->
                week.copy(
                    items =
                        week.items.filter { wo ->
                            wo.assetId in assetSet || wo.siteId in siteSet
                        },
                )
            },
    )
}
