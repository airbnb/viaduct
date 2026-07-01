package viaduct.gradle

import org.gradle.api.GradleException
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.service.api.scoping.SchemaScoping
import viaduct.service.api.scoping.SchemaScopingValidationError
import viaduct.service.api.scoping.SchemaScopingValidator
import viaduct.service.api.scoping.ScopingErrorCodes

/**
 * Receiver of the [ViaductApplicationExtension.declareScoping] lambda. Accumulates one scope
 * universe and a set of scoped-schema entries while the lambda runs, then assembles them into a
 * validated [SchemaScoping] when [build] is called by the extension.
 *
 * Per-ID syntax (scope ID shape, schema ID shape, reserved IDs) and per-call invariants (duplicate
 * `scopedSchema` ID, second `scopes` call, empty `scopes`) throw synchronously inside the offending
 * DSL method so the stack trace points at the user's build script line. Cross-property invariants
 * (`SCOPED_SCHEMAS_WITHOUT_UNIVERSE`, `SCOPED_SCHEMA_UNKNOWN_SCOPE`) run inside [build] and are
 * aggregated into a single message.
 */
@ExperimentalApi
@OptIn(ExperimentalApi::class)
class SchemaScopingBuilder @InternalApi constructor() {
    private val scopeUniverse = mutableSetOf<String>()
    private val scopedSchemas = mutableMapOf<String, Set<String>>()
    private var scopesDeclared = false

    /** Declares the scope universe. May be called at most once inside the block. */
    fun scopes(vararg ids: String) {
        if (scopesDeclared) {
            throw GradleException(
                "[${ScopingErrorCodes.SCHEMA_SCOPES_DECLARED_TWICE}] " +
                    "scopes(...) may only be called once inside declareScoping. " +
                    "Compose convention-plugin contributions into a single call.",
            )
        }
        if (ids.isEmpty()) {
            throw GradleException(
                "[${ScopingErrorCodes.SCHEMA_SCOPES_EMPTY}] " +
                    "scopes(...) requires at least one scope ID. " +
                    "Omit the call entirely if this application does not declare scopes.",
            )
        }
        ids.forEach { scopeId -> throwOnInvalidId(SchemaScopingValidator.validateScopeId(scopeId)) }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw GradleException(
                "[${ScopingErrorCodes.SCHEMA_SCOPE_DUPLICATE_ID}] " +
                    "scopes(...) contains duplicate scope ID(s): ${duplicates.sorted()}. " +
                    "Each scope ID may only appear once.",
            )
        }
        scopesDeclared = true
        scopeUniverse.addAll(ids)
    }

    /**
     * Declares one scoped-schema entry. An empty [scopeIds] is a documented full-schema alias
     * (the entry exposes everything in the declared universe, no filtering).
     */
    fun scopedSchema(id: String, vararg scopeIds: String) {
        throwOnInvalidId(SchemaScopingValidator.validateSchemaId(id))
        scopeIds.forEach { scopeId -> throwOnInvalidId(SchemaScopingValidator.validateScopeId(scopeId)) }
        if (scopedSchemas.containsKey(id)) {
            throw GradleException(
                "[${ScopingErrorCodes.SCOPED_SCHEMA_DUPLICATE_ID}] " +
                    "Duplicate scoped-schema ID '$id'. Each scoped-schema ID may only appear once " +
                    "inside declareScoping.",
            )
        }
        scopedSchemas[id] = scopeIds.toSet()
    }

    /**
     * Assembles the snapshot and runs the cross-property validator. Called by the extension after
     * the lambda returns. Internal because it is part of the extension/builder contract, not the
     * user-facing DSL surface.
     */
    @InternalApi
    fun build(): SchemaScoping {
        val scoping = SchemaScoping(
            scopeUniverse = scopeUniverse.toSet(),
            scopedSchemas = scopedSchemas.toMap(),
        )
        val errors = SchemaScopingValidator.validate(scoping)
        if (errors.isNotEmpty()) {
            throw GradleException(
                "viaductApplication declareScoping configuration is invalid:\n" +
                    errors.joinToString("\n") { "  - [${it.code}] ${it.message}" },
            )
        }
        return scoping
    }

    private fun throwOnInvalidId(error: SchemaScopingValidationError?) {
        if (error != null) throw GradleException("[${error.code}] ${error.message}")
    }
}
