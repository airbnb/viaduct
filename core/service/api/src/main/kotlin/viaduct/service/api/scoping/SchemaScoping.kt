package viaduct.service.api.scoping

import viaduct.apiannotations.ExperimentalApi

/**
 * Build-to-runtime contract describing the schema scope definitions of a Viaduct application.
 *
 * Decoded from the application's `scopes.yaml` by [SchemaScopeDefinitions], which every build tool
 * shares. These definitions declare which scope IDs exist and which scoped schemas are derivable from
 * them; choosing which of those a given deployment serves happens at runtime, not here.
 *
 * @property scopeUniverse the complete set of scope IDs declared as valid for this application;
 *  empty when the application does not opt into scoping.
 * @property scopedSchemas mapping from declared scoped-schema ID to its non-empty scope set. The
 *  unscoped schema is not declarable — it is requested at runtime by its reserved id.
 * @property version definition format version, used to evolve the file.
 */
@ExperimentalApi
data class SchemaScoping(
    val scopeUniverse: Set<String>,
    val scopedSchemas: Map<String, Set<String>>,
    val version: String = CURRENT_VERSION,
) {
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
