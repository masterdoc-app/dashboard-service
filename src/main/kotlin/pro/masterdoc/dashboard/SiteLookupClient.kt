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

private val log = LoggerFactory.getLogger("pro.masterdoc.dashboard.site")

@Serializable
data class SiteGeofence(
    val id: String,
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val geofenceRadiusM: Int? = null,
)

fun interface SiteLookupClient {
    fun get(orgId: String, siteId: String): SiteGeofence?
}

object NoopSiteLookupClient : SiteLookupClient {
    override fun get(orgId: String, siteId: String): SiteGeofence? = null
}

class HttpSiteLookupClient(
    baseUrl: String,
    private val client: HttpClient = defaultClient(),
) : SiteLookupClient {
    private val baseUrl = baseUrl.trimEnd('/')

    override fun get(orgId: String, siteId: String): SiteGeofence? =
        runBlocking {
            try {
                val response =
                    client.get("$baseUrl/sites/$siteId") {
                        header("X-Org-Id", orgId)
                    }
                if (response.status != HttpStatusCode.OK) {
                    log.warn("event=site_lookup_failed orgId=$orgId siteId=$siteId status=${response.status}")
                    return@runBlocking null
                }
                response.body()
            } catch (e: Exception) {
                log.warn("event=site_lookup_error orgId=$orgId siteId=$siteId cause=${e.message}")
                null
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
