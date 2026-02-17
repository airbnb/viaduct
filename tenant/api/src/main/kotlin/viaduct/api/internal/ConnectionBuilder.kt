package viaduct.api.internal

import graphql.schema.GraphQLObjectType
import viaduct.api.connection.OffsetCursor
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Edge
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineObjectDataBuilder

/**
 * Base builder for Connection types with pagination utilities.
 *
 * Generated Connection builders extend this class to gain access to:
 * - [fromEdges]: Build from pre-constructed edges with explicit PageInfo control
 * - [fromSlice]: Build from a slice of items with automatic cursor encoding
 * - [fromList]: Build from a full list with automatic pagination
 *
 * Type Parameters:
 * - C: The concrete Connection type being built (must implement Connection<E, N>)
 * - E: The Edge type (must implement Edge<N>)
 * - N: The Node type contained in edges
 *
 * Usage in generated builders (bytecode generates equivalent of):
 * ```kotlin
 * class Builder(context: ConnectionFieldExecutionContext<*, *, *, CharactersConnection>) :
 *     ConnectionBuilder<CharactersConnection, CharacterEdge, Character>(
 *         context,
 *         graphQLObjectType
 *     ) {
 *     // Generated setters for edges, pageInfo, etc.
 *     override fun build(): CharactersConnection = ...
 * }
 * ```
 *
 * @see ObjectBase.Builder
 * @see Connection
 * @see Edge
 */
@ExperimentalApi
@OptIn(InternalApi::class)
abstract class ConnectionBuilder<C : Connection<E, N>, E : Edge<N>, N>(
    protected val connectionContext: ConnectionFieldExecutionContext<*, *, *, C>,
    graphQLObjectType: GraphQLObjectType,
    baseEngineObjectData: EngineObjectData?,
    private val edgeType: Type<E>,
) : ObjectBase.Builder<C>(connectionContext.internal, graphQLObjectType, baseEngineObjectData) {
    /**
     * The connection arguments from the execution context.
     * Used for pagination (offset/limit calculation).
     */
    protected val arguments: ConnectionArguments
        get() = connectionContext.arguments

    /**
     * Build connection from pre-constructed edges.
     *
     * Use this when you have already constructed Edge instances and want
     * explicit control over PageInfo values.
     *
     * Cursors for PageInfo are extracted from the first and last edges.
     *
     * @param edges Pre-constructed list of edges
     * @param hasNextPage Whether more items exist after this page
     * @param hasPreviousPage Whether more items exist before this page
     * @return The built Connection instance
     */
    @ExperimentalApi
    fun fromEdges(
        edges: List<E>,
        hasNextPage: Boolean = false,
        hasPreviousPage: Boolean = false
    ): ConnectionBuilder<C, E, N> {
        put("edges", edges)
        val startCursor = edges.firstOrNull()?.let { extractCursor(it) }
        val endCursor = edges.lastOrNull()?.let { extractCursor(it) }
        putInternal("pageInfo", createPageInfo(hasNextPage, hasPreviousPage, startCursor, endCursor))
        return this
    }

    private fun extractCursor(edge: E): String {
        val eod = (edge as ObjectBase).engineObject as EngineObjectData.Sync
        return eod.getOrNull("cursor") as? String
            ?: throw IllegalArgumentException("Cursor not found in edge")
    }

    /**
     * Build connection from a slice of items.
     *
     * This method:
     * 1. Uses the execution context's arguments to calculate offset/limit
     * 2. Takes up to `limit` items from the provided slice
     * 3. Constructs edges with automatically encoded cursors (offset + index)
     * 4. Computes PageInfo based on hasNextPage and offset
     *
     * @param items The items to include (may be larger than limit if +1 fetched for hasNextPage detection)
     * @param hasNextPage Whether more items exist after this slice
     * @param buildNode Function to convert each item to the node value for the edge
     * @return This builder for chaining
     */
    @ExperimentalApi
    fun <I> fromSlice(
        items: List<I>,
        hasNextPage: Boolean,
        buildNode: (item: I) -> N?
    ): ConnectionBuilder<C, E, N> {
        val (offset, limit) = connectionContext.arguments.toOffsetLimit()
        return buildEdges(items, hasNextPage, offset, limit, buildNode)
    }

    /**
     * Build connection from a full list with automatic pagination.
     *
     * This method:
     * 1. Uses the execution context's arguments to calculate offset/limit
     * 2. Extracts the appropriate slice from the full list
     * 3. Constructs edges with automatically encoded cursors
     * 4. Computes PageInfo based on list position
     *
     * @param items The complete list of items (not yet paginated)
     * @param buildNode Function to convert each item to the node value for the edge
     * @return The built Connection instance
     */
    @ExperimentalApi
    fun <I> fromList(
        items: List<I>,
        buildNode: (item: I) -> N?
    ): ConnectionBuilder<C, E, N> {
        val offsetLimit = this.arguments.toOffsetLimit()
        val offset = if (offsetLimit.backwards) maxOf(0, items.size - offsetLimit.limit) else offsetLimit.offset
        val slice = items.drop(offset).take(offsetLimit.limit)
        val hasNextPage = offset + offsetLimit.limit < items.size
        return buildEdges(slice, hasNextPage, offset, offsetLimit.limit, buildNode)
    }

    private fun <I> buildEdges(
        items: List<I>,
        hasNextPage: Boolean,
        offset: Int,
        limit: Int,
        buildNode: (item: I) -> N?
    ): ConnectionBuilder<C, E, N> {
        val edges = items.take(limit).mapIndexed { idx, item ->
            ViaductObjectBuilder.dynamicBuilderFor(connectionContext.internal, edgeType.kcls)
                .put("node", buildNode(item))
                .put("cursor", OffsetCursor.fromOffset(offset + idx).value)
                .build()
        }
        return fromEdges(edges, hasNextPage, offset > 0)
    }

    private fun createPageInfo(
        hasNextPage: Boolean,
        hasPreviousPage: Boolean,
        startCursor: String?,
        endCursor: String?
    ): EngineObjectData {
        val pageInfoType = connectionContext.internal.schema.schema.getObjectType("PageInfo")
            ?: throw IllegalStateException("PageInfo type not found in schema")

        return EngineObjectDataBuilder.from(pageInfoType)
            .put("hasNextPage", hasNextPage)
            .put("hasPreviousPage", hasPreviousPage)
            .put("startCursor", startCursor)
            .put("endCursor", endCursor)
            .build()
    }
}
