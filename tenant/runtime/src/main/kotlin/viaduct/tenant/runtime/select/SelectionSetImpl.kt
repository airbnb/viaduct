package viaduct.tenant.runtime.select

import viaduct.api.internal.InternalSelectionSet
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.RawSelectionSet

/**
 * Provides a type-safe interface for manipulating an untyped [RawSelectionSetImpl]
 */
data class SelectionSetImpl<T : CompositeOutput>(
    override val type: Type<T>,
    override val rawSelectionSet: RawSelectionSet
) : SelectionSet<T>, InternalSelectionSet {
    override fun <U : T> contains(field: Field<U>): Boolean = rawSelectionSet.containsField(field.containingType.name, field.name)

    override fun <U : T> requestsType(type: Type<U>): Boolean = rawSelectionSet.requestsType(type.name)

    override fun <U : T, R : CompositeOutput> selectionSetFor(field: CompositeField<U, R>): SelectionSet<R> =
        SelectionSetImpl(
            field.type,
            rawSelectionSet.selectionSetForField(field.containingType.name, field.name)
        )

    override fun <U : T> selectionSetFor(type: Type<U>): SelectionSet<U> =
        SelectionSetImpl(
            type,
            rawSelectionSet.selectionSetForType(type.name)
        )

    override fun isEmpty(): Boolean = rawSelectionSet.isTransitivelyEmpty()
}
