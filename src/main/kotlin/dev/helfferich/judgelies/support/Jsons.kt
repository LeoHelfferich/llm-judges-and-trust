package dev.helfferich.judgelies.support

import kotlinx.serialization.json.Json

/**
 * [pretty] is for files a person opens, and it writes default values so a manifest is complete.
 * [compact] is for the API body and the cache. Null fields are omitted, so a model that does not accept temperature does not get `"temperature": null`.
 */
object Jsons {
    val pretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val compact = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}
