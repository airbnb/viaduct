package viaduct.api.context

import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.select.Selections
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query as QueryType
import viaduct.apiannotations.StableApi

/** A generic context for resolving fields or types */
@StableApi
interface ResolverExecutionContext<Q : QueryType> : ExecutionContext {
    /**
     * Loads the provided selections on the root Query type, and returns the response typed as [Q].
     * This is a convenience method that combines [selectionsFor] and [query].
     *
     * Example usage:
     * ```
     * val result = ctx.query("{ user { id name } }")
     * ```
     *
     * @param selections The selections to load on the root Query type
     * @param variables Optional variables to use in the selections
     * @return The query result typed as [Q]
     */
    suspend fun query(
        selections: @Selections String,
        variables: Map<String, Any?> = emptyMap()
    ): Q

    /**
     * Creates a [SelectionSet] on a provided type from the provided [Selections] String
     * @see [Selections]
     */
    fun <T : CompositeOutput> selectionsFor(
        type: Type<T>,
        selections: @Selections String,
        variables: Map<String, Any?> = emptyMap()
    ): SelectionSet<T>

    /**
     * Creates a Node object reference given an ID. Only the ID field is accessible from the
     * created reference. Attempting to access other fields will result in an exception.
     * This can be used to construct resolver responses for fields with Node types.
     */
    fun <T : NodeObject> nodeFor(id: GlobalID<T>): T

    /**
     * Creates a GlobalID and returns it as a String. Example usage:
     *   globalIDStringFor(User.Reflection, "123")
     */
    fun <T : NodeObject> globalIDStringFor(
        type: Type<T>,
        internalID: String
    ): String
}
