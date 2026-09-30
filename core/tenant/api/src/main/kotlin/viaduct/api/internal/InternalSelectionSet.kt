package viaduct.api.internal

import viaduct.api.reflect.Type
import viaduct.api.select.EmptySelectionSet
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.apiannotations.InternalApi

/** Root-type metadata for framework execution and batch validation, including test implementations. */
@InternalApi
interface InternalSelectionSet<T : CompositeOutput> : SelectionSet<T> {
    val type: Type<T>
}

@InternalApi
@Suppress("UNCHECKED_CAST")
fun <T : CompositeOutput> SelectionSet<T>.internalType(): Type<T> =
    when (this) {
        is EmptySelectionSet<T> -> type
        is SelectionSet.NoSelections -> type as Type<T>
        else -> requireNotNull(this as? InternalSelectionSet<T>) {
            "Custom SelectionSet implementations used by the framework must implement InternalSelectionSet"
        }.type
    }
