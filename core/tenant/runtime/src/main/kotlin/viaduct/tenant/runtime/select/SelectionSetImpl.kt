package viaduct.tenant.runtime.select

import viaduct.api.internal.InternalSelectionSet
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.EngineSelectionSet

/**
 * Provides a type-safe interface for manipulating an untyped [EngineSelectionSetImpl]
 */
data class SelectionSetImpl<T : CompositeOutput>(
    override val type: Type<T>,
    override val engineSelectionSet: EngineSelectionSet
) : SelectionSet<T>, InternalSelectionSet {
    override fun <U : T> contains(field: Field<U>): Boolean = engineSelectionSet.containsField(field.containingType.name, field.name)

    override fun <U : T> requestsType(type: Type<U>): Boolean = engineSelectionSet.requestsType(type.name)

    override fun <U : T, R : CompositeOutput> selectionSetFor(field: CompositeField<U, R>): SelectionSet<R> =
        SelectionSetImpl(
            field.type,
            engineSelectionSet.selectionSetForField(field.containingType.name, field.name)
        )

    override fun <U : T> selectionSetFor(type: Type<U>): SelectionSet<U> =
        SelectionSetImpl(
            type,
            engineSelectionSet.selectionSetForType(type.name)
        )

    override fun isEmpty(): Boolean = engineSelectionSet.isTransitivelyEmpty()
}
