package dev.helfferich.judgelies

import kotlinx.serialization.json.Json

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
