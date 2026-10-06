package viaduct.engine.runtime2.model.registry

import viaduct.graphql.schema.ViaductSchema

/** Identifies the resolver slot that owns registry declarations and variable instances. */
sealed interface ResolverTarget {
    /** A resolver slot associated with one object field. */
    sealed interface FieldTarget : ResolverTarget {
        val field: ViaductSchema.ObjectField
    }

    /** A resolver slot associated with one concrete object type. */
    sealed interface TypeTarget : ResolverTarget {
        val type: ViaductSchema.Object
    }

    /** The raw-value resolver slot of [field]. */
    data class FieldValueResolverTarget(
        override val field: ViaductSchema.ObjectField,
    ) : FieldTarget

    /** The field-checker resolver slot of [field]. */
    data class FieldCheckerTarget(
        override val field: ViaductSchema.ObjectField,
    ) : FieldTarget

    /** The type-checker resolver slot of [type]. */
    data class TypeCheckerTarget(
        override val type: ViaductSchema.Object,
    ) : TypeTarget
}

internal fun ResolverTarget.render(): String =
    when (this) {
        is ResolverTarget.FieldValueResolverTarget ->
            "field-value:${field.containingDef.name}/${field.name}"
        is ResolverTarget.FieldCheckerTarget ->
            "field-checker:${field.containingDef.name}/${field.name}"
        is ResolverTarget.TypeCheckerTarget ->
            "type-checker:${type.name}"
    }
