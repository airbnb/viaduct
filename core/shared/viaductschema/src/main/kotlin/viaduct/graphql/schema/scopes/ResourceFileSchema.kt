package viaduct.graphql.schema.scopes

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class ResourceFileSchema(
    val declaredSchemaScopes: Set<String>,
    val declaredScopedSchemas: Map<String, Set<String>>,
    val version: String,
) {
    companion object {
        const val FULL_SCHEMA_ID = "FULL"
        const val CURRENT_VERSION = "1"

        fun create(
            declaredSchemaScopes: Set<String> = emptySet(),
            declaredScopedSchemas: Map<String, Set<String>> = emptyMap(),
            version: String = CURRENT_VERSION,
        ): ResourceFileSchema {
            val sortedScopes: Set<String> = declaredSchemaScopes.toSortedSet()
            val sortedScopedSchemas: Map<String, Set<String>> = buildMap {
                // Preserve sorted order by inserting from a sorted iteration over keys.
                val withFull = if (declaredScopedSchemas.containsKey(FULL_SCHEMA_ID)) declaredScopedSchemas
                               else declaredScopedSchemas + (FULL_SCHEMA_ID to emptySet())
                for (key in withFull.keys.toSortedSet()) {
                    put(key, withFull.getValue(key).toSortedSet())
                }
            }
            return ResourceFileSchema(sortedScopes, sortedScopedSchemas, version)
        }

        fun objectMapper(): ObjectMapper = jacksonObjectMapper()
    }
}
