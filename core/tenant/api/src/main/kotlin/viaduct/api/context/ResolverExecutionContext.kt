package viaduct.api.context

import viaduct.api.documents.QueryFromAnnotation
import viaduct.api.documents.Selections
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.RootObjectField
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query as QueryType
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
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
    @Deprecated("This API is not supported and will be deleted. Use the GraphQLOperation-based query(operation, variables) instead.")
    suspend fun query(
        selections: @Selections String,
        variables: Map<String, Any?> = emptyMap()
    ): Q

    /**
     * Loads the operation declared by a [@GraphQLOperation][viaduct.api.documents.GraphQLOperation]
     * query object on the root Query type, and returns the response typed as [Q].
     *
     * Example usage:
     * ```
     * val result = ctx.query(GetUserQuery)
     * ```
     *
     * @param operation The query operation object declaring the operation document
     * @param variables Optional variables to use in the operation
     * @return The query result typed as [Q]
     */
    @StableApi
    suspend fun query(
        operation: QueryFromAnnotation,
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
    fun <T : NodeObject> nodeRef(id: GlobalID<T>): T

    /**
     * Creates a GlobalID and returns it as a String. Example usage:
     *   globalIDStringFor(User.Reflection, "123")
     */
    fun <T : NodeObject> globalIDStringFor(
        type: Type<T>,
        internalID: String
    ): String

    /**
     * Creates a lazy reference to a root field that will be later resolved by the engine.
     *
     * No fields are accessible from the returned value. Attempting to access fields will result in
     * an exception. The returned value may be used as a resolver return value or passed to a GRT
     * builder.
     *
     * Example usage:
     * ```
     * val product = ctx.ref(ProductFactory.create { name("Air") })
     * ```
     */
    @ExperimentalApi
    fun <T : Object> ref(call: RootFieldCall<T>): T = call.resolve(this)

    /**
     * Creates the lazy reference described by a [RootFieldCall]. Generated
     * [RootFieldCall.resolve] implementations call this; tenant code calls [ref].
     */
    @InternalApi
    fun <A : Arguments, BR : Object> rootFieldRef(
        field: RootObjectField<*, BR, A>,
        arguments: A
    ): BR
}
