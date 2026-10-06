package viaduct.engine.runtime2.model.registry

import viaduct.engine.runtime2.model.parentFieldRelations
import viaduct.graphql.schema.ViaductSchema

/**
 * The schema and field resolvers under which model values and operations are interpreted.
 *
 * Equality is undefined for assumptions. Exactly one value is fixed for a reasoning exercise, and
 * every schema definition referenced by its model values belongs to [schema]. Each Assumptions
 * value is configuration only; mutable interpretation state belongs to the semantics operation.
 */
sealed interface Assumptions {
    val schema: ViaductSchema
    val resolverRegistry: ResolverRegistry

    /** The validated parent-field-to-producer-field relation for [schema]. */
    val parentFieldRelations: Map<ViaductSchema.ObjectField, ViaductSchema.ObjectField>

    /** Whether resolver invocation and passive output traversal are selective to supplied demand. */
    val selectiveResolvers: Boolean

    companion object {
        fun of(
            schema: ViaductSchema,
            resolverRegistry: ResolverRegistry,
            selectiveResolvers: Boolean = true,
        ): Assumptions =
            AssumptionsImpl(
                schema,
                resolverRegistry,
                selectiveResolvers,
            )
    }
}

private class AssumptionsImpl(
    override val schema: ViaductSchema,
    override val resolverRegistry: ResolverRegistry,
    override val selectiveResolvers: Boolean,
) : Assumptions {
    override val parentFieldRelations: Map<ViaductSchema.ObjectField, ViaductSchema.ObjectField> =
        parentFieldRelations(schema)
}
