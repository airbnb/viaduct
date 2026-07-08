package viaduct.service.api.scoping

import java.io.Serializable
import viaduct.apiannotations.ExperimentalApi

/**
 * Build-to-runtime contract describing the schema-scoping declarations of a Viaduct application.
 *
 * Emitted by the `viaduct-application` Gradle plugin from the application's `declareScoping { ... }`
 * DSL block, and consumed at runtime when a Viaduct instance resolves which scoped schemas to
 * register. The canonical cross-process exchange format is the JSON manifest written
 * into the application JAR; the [Serializable] implementation supports in-process Gradle plumbing
 * (e.g. configuration-cache state) and is not the wire format for runtime consumption.
 *
 * @property scopeUniverse the complete set of scope IDs declared as valid for this application;
 *  empty when the application does not opt into scoping.
 * @property scopedSchemas mapping from declared scoped-schema ID to its scope set; an empty scope
 *  set is an alias for the base schema.
 * @property version manifest schema version, used to evolve the on-disk JSON format.
 */
@ExperimentalApi
data class SchemaScoping(
    val scopeUniverse: Set<String>,
    val scopedSchemas: Map<String, Set<String>>,
    val version: String = CURRENT_VERSION,
) : Serializable {
    /**
     * Whether this application opts into scope-based filtering. The presence of a declared
     * universe — not the contents of [scopedSchemas] — is the source of truth.
     */
    val isScoped: Boolean get() = scopeUniverse.isNotEmpty()

    companion object {
        const val CURRENT_VERSION: String = "1"

        /** Convenience constant for the "no scoping declared" state. */
        val EMPTY: SchemaScoping = SchemaScoping(emptySet(), emptyMap())
    }
}
