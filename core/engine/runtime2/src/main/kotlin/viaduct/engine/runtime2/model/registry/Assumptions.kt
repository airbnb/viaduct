package viaduct.engine.runtime2.model.registry

import viaduct.engine.runtime2.model.interfaceParentFields
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

    /** Interface fields where at least one concrete implementation declares `@parent`. */
    val interfaceParentFields: Set<ViaductSchema.Field>

    /** Selects precomputed closure; false retains the independent dynamic expansion algorithm. */
    val usePrecomputedConstructionDemand: Boolean

    companion object {
        fun of(
            schema: ViaductSchema,
            resolverRegistry: ResolverRegistry,
            usePrecomputedConstructionDemand: Boolean = false,
        ): Assumptions =
            AssumptionsImpl(
                schema,
                resolverRegistry,
                usePrecomputedConstructionDemand,
            )
    }
}

private class AssumptionsImpl(
    override val schema: ViaductSchema,
    override val resolverRegistry: ResolverRegistry,
    override val usePrecomputedConstructionDemand: Boolean,
) : Assumptions {
    override val parentFieldRelations: Map<ViaductSchema.ObjectField, ViaductSchema.ObjectField> =
        parentFieldRelations(schema)
    override val interfaceParentFields: Set<ViaductSchema.Field> =
        interfaceParentFields(schema)
}
