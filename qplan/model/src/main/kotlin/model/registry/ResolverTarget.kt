package model.registry

import viaduct.graphql.schema.ViaductSchema

/** Identifies the resolver slot that owns registry declarations and variable instances. */
sealed interface ResolverTarget {
    /** A resolver slot associated with one object field. */
    sealed interface FieldTarget : ResolverTarget {
        val field: ViaductSchema.ObjectField
    }

    /** The raw-value resolver slot of [field]. */
    data class FieldValueResolverTarget(
        override val field: ViaductSchema.ObjectField,
    ) : FieldTarget

    /** The field-checker resolver slot of [field]. */
    data class FieldCheckerTarget(
        override val field: ViaductSchema.ObjectField,
    ) : FieldTarget
}

internal fun ResolverTarget.render(): String =
    when (this) {
        is ResolverTarget.FieldValueResolverTarget ->
            "field-value:${field.containingDef.name}/${field.name}"
        is ResolverTarget.FieldCheckerTarget ->
            "field-checker:${field.containingDef.name}/${field.name}"
    }
