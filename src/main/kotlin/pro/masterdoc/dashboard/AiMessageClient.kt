package pro.masterdoc.dashboard

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("pro.masterdoc.dashboard.ai_messages")

@Serializable
data class CreateAiMessageRequest(
    val orgId: String,
    val kind: String,
    val workOrderId: String,
    val siteId: String,
    val engineerId: String,
    val title: String,
    val body: String,
    val distanceM: Double? = null,
    val radiusM: Int? = null,
    val engineerLat: Double? = null,
    val engineerLon: Double? = null,
    val siteLat: Double? = null,
    val siteLon: Double? = null,
    val accuracyM: Double? = null,
)

fun interface AiMessageClient {
    /** Sends a system message; implementations must not throw into the PATCH route. */
    fun post(message: CreateAiMessageRequest)
}

object NoopAiMessageClient : AiMessageClient {
    override fun post(message: CreateAiMessageRequest) = Unit
}

class HttpAiMessageClient(
    baseUrl: String,
    private val internalToken: String?,
    private val client: HttpClient = defaultClient(),
) : AiMessageClient {
    private val baseUrl = baseUrl.trimEnd('/')

    override fun post(message: CreateAiMessageRequest) {
        runBlocking {
            try {
                val response =
                    client.post("$baseUrl/messages") {
                        header("X-Internal-Token", internalToken.orEmpty())
                        contentType(ContentType.Application.Json)
                        setBody(message)
                    }
                if (response.status != HttpStatusCode.Created) {
                    log.warn(
                        "event=ai_message_failed workOrderId=${message.workOrderId} kind=${message.kind} " +
                            "status=${response.status}",
                    )
                }
            } catch (e: Exception) {
                log.warn(
                    "event=ai_message_error workOrderId=${message.workOrderId} kind=${message.kind} cause=${e.message}",
                )
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
