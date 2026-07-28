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

private val log = LoggerFactory.getLogger("pro.masterdoc.dashboard.features")

interface FeatureLookupClient {
    /** Lookup by **target** userId — never the caller's feature set. */
    fun hasFeature(orgId: String, userId: String, feature: String): Boolean
}

/** Test / local default: every user has every feature. Production wires [HttpFeatureLookupClient]. */
object AllowAllFeatureLookupClient : FeatureLookupClient {
    override fun hasFeature(orgId: String, userId: String, feature: String): Boolean = true
}

@Serializable
private data class UserFeaturesResponse(val features: List<String> = emptyList())

/**
 * Looks up the **target** user's features via feature-service
 * `GET /users/{userId}/features` (+ `X-Org-Id`) — never the caller's JWT.
 */
class HttpFeatureLookupClient(
    baseUrl: String,
    private val client: HttpClient = defaultClient(),
) : FeatureLookupClient {
    private val baseUrl = baseUrl.trimEnd('/')

    override fun hasFeature(orgId: String, userId: String, feature: String): Boolean =
        runBlocking {
            try {
                val response =
                    client.get("$baseUrl/users/$userId/features") {
                        header("X-Org-Id", orgId)
                    }
                when (response.status) {
                    HttpStatusCode.OK -> {
                        val body = response.body<UserFeaturesResponse>()
                        feature in body.features
                    }
                    HttpStatusCode.NotFound -> false
                    else -> {
                        log.warn(
                            "event=feature_lookup_failed orgId=$orgId userId=$userId feature=$feature status=${response.status}",
                        )
                        false
                    }
                }
            } catch (e: Exception) {
                log.warn(
                    "event=feature_lookup_error orgId=$orgId userId=$userId feature=$feature cause=${e.message}",
                )
                false
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
