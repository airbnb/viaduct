package viaduct.service.api.scoping

import java.io.Serializable

/**
 * Canonical, build-to-runtime description of schema scoping for a Viaduct application.
 *
 * This type is the contract between the Viaduct Gradle plugin (which emits it from the
 * `viaductApplication { ... }` DSL) and the runtime (which consumes it when materializing
 * scoped schemas). It is a sibling of [viaduct.service.api.SchemaId] in `service/api`
 * because it is the same kind of build-to-runtime public type.
 *
 * @property scopeUniverse the complete set of scope IDs declared for the application.
 * @property scopedSchemas a mapping from scoped-schema ID to the set of scope IDs that
 *   schema is restricted to. A scoped schema with an empty scope set denotes an alias for
 *   the full schema.
 * @property version the version of this configuration format.
 */
data class SchemaScoping(
    val scopeUniverse: Set<String>,
    val scopedSchemas: Map<String, Set<String>>,
    val version: String = CURRENT_VERSION,
) : Serializable {
    /** True when at least one scope has been declared. */
    val isScoped: Boolean get() = scopeUniverse.isNotEmpty()

    companion object {
        const val CURRENT_VERSION = "1"

        /** The empty (unscoped) configuration. */
        val EMPTY = SchemaScoping(emptySet(), emptyMap())

        private const val serialVersionUID: Long = 1L
    }
}
