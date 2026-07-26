package pro.masterdoc.dashboard

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("pro.masterdoc.dashboard.catalog")

fun interface AssetLookup {
    /** Returns siteId when asset exists in org; null if missing. */
    fun siteIdOf(orgId: String, assetId: String): String?
}

object AllowAllAssetLookup : AssetLookup {
    override fun siteIdOf(orgId: String, assetId: String): String = "default-site"
}

/** Adapts [AssetLookup] to the map-create asset existence check. */
fun AssetLookup.asChecker(): AssetChecker =
    AssetChecker { orgId, assetId -> siteIdOf(orgId, assetId) != null }

class CatalogAssetLookup(private val catalogBaseUrl: String) : AssetLookup {
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun siteIdOf(orgId: String, assetId: String): String? =
        try {
            kotlinx.coroutines.runBlocking {
                val response =
                    client.get("$catalogBaseUrl/assets/$assetId") {
                        header("X-Org-Id", orgId)
                    }
                if (response.status != HttpStatusCode.OK) return@runBlocking null
                json.parseToJsonElement(response.bodyAsText())
                    .jsonObject["siteId"]
                    ?.jsonPrimitive
                    ?.content
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            log.warn("event=catalog_lookup_failed assetId=$assetId orgId=$orgId error=${e.message}")
            null
        }
}
