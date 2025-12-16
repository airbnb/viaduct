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
interface ResolverExecutionContext : ExecutionContext {
    /**
     * Loads the provided [SelectionSet] on the root Query type, and return the response
     */
    suspend fun <T : QueryType> query(selections: SelectionSet<T>): T

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
