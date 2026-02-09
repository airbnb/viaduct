package viaduct.graphql.schema.validation

import viaduct.graphql.schema.ViaductSchema

/**
 * Orchestrates schema validation by running phases of rules.
 *
 * Each phase bundles multiple rules into a single schema traversal.
 * All phases run regardless of errors in previous phases.
 */
class SchemaValidator(
    private val phases: List<List<ValidationRule>>
) {
    fun validate(schema: ViaductSchema): List<SchemaValidationError> {
        val ctx = ValidationContext(schema)
        for (phase in phases) {
            ctx.walkSchema(phase)
        }
        return ctx.errors
    }

    fun validate(ctx: ValidationContext): List<SchemaValidationError> {
        for (phase in phases) {
            ctx.walkSchema(phase)
        }
        return ctx.errors
    }
}
