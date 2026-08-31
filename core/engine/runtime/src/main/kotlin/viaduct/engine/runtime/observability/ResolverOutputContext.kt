package viaduct.engine.runtime.observability

import viaduct.service.api.spi.ErrorReporter

/**
 * Settings for reporting missing fields in the intersection of required and output selection
 * sets.
 *
 * The execution engine stores this context while traversing that intersection and its nested
 * selections.
 */
data class ResolverOutputContext(
    val errorReporter: ErrorReporter,
    val missingFieldErrorsEnabled: Boolean,
)

/** Indicates that a requested field is missing from an output selection set. */
class ResolverOutputMissingFieldException(
    objectType: String,
    fieldName: String,
) : IllegalStateException(
        "Resolver output did not contain requested field `$objectType.$fieldName`"
    ) {
    companion object {
        const val GRAPHQL_ERROR_CODE = "VIADUCT_RESOLVER_OUTPUT_MISSING_FIELD"
    }
}
