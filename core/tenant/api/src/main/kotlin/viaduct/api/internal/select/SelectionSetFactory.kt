package viaduct.api.internal.select

import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.select.Selections
import viaduct.api.types.CompositeOutput
import viaduct.apiannotations.InternalApi

/** Interface for creating [SelectionSet]'s */
@InternalApi
interface SelectionSetFactory {
    /**
     * Return a [SelectionSet] on the provided type.
     * A SelectionSet is built from a Selections String, which can be in 1 of two forms described in [Selections]
     *
     * @see Selections
     */
    fun <T : CompositeOutput> selectionsOn(
        type: Type<T>,
        @Selections selections: String,
        variables: Map<String, Any?> = mapOf()
    ): SelectionSet<T>
}
