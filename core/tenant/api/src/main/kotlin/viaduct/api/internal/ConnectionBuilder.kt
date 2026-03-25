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
     * The pagination arguments from the current GraphQL request (`first`, `after`, `last`,
     * `before`). Available for resolvers that need to fetch data before calling one of the
     * builder methods, e.g. to pass `offset` and `limit` to a backend service.
     * [fromSlice] and [fromList] read these arguments internally.
     */
    protected val arguments: ConnectionArguments
        get() = connectionContext.arguments

    /**
     * Build a connection from pre-constructed edges.
     *
     * Use this when either:
     * - your backend natively returns opaque cursors (pass them through directly), or
     * - your edge type has custom fields beyond `node` and `cursor`
     *   (e.g. a `relevanceScore` on a search edge).
     *
     * You are responsible for setting the `cursor` field on each edge and for
     * determining [hasNextPage] and [hasPreviousPage].
     *
     * `pageInfo.startCursor` and `pageInfo.endCursor` are extracted automatically from the
     * `cursor` field of the first and last edges respectively.
     *
     * @param edges Pre-constructed edges; each must have its `cursor` field set.
     * @param hasNextPage Whether more items exist after this page.
     * @param hasPreviousPage Whether more items exist before this page.
     * @return This builder for chaining.
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
     * Build a connection from a backend-paginated slice of items.
     *
     * Use this when your backend handles pagination itself (e.g. a database query with
     * `LIMIT` and `OFFSET`). The caller is responsible for passing the correct [hasNextPage]
     * flag and for fetching the right slice.
     *
     * Common pattern — fetch one extra item to detect the next page:
     * ```kotlin
     * val (offset, limit) = ctx.arguments.toOffsetLimit()
     * val fetched = repo.fetchPosts(offset = offset, limit = limit + 1)
     * return PostsConnection {
     *     fromSlice(fetched, hasNextPage = fetched.size > limit) { post -> PostNode { … } }
     * }
     * ```
     *
     * This method:
     * 1. Takes up to `limit` items from [items] (extras are discarded).
     * 2. Encodes an [OffsetCursor] for each kept item based on `offset + index`.
     * 3. Sets `hasPreviousPage` to `true` when `offset > 0`.
     *
     * @param items Items to paginate; may contain one extra item (for hasNextPage detection).
     * @param hasNextPage Whether more items exist after this slice.
     * @param buildNode Converts each item to the edge's node value; return `null` to omit.
     * @return This builder for chaining.
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
     * Build a connection from a complete, unpaginated list.
     *
     * Use this when your backend returns the full dataset and you want Viaduct to handle
     * slicing. Best suited for small in-memory collections; for large datasets prefer
     * [fromSlice] with a backend-paginated query.
     *
     * This method:
     * 1. Derives `offset` and `limit` from the execution context's arguments.
     * 2. For backward navigation without a cursor ([OffsetLimit.backwards] `= true`), slices
     *    from the tail so `last N` returns the final N items.
     * 3. Constructs edges with automatically encoded [OffsetCursor]s.
     * 4. Sets `hasNextPage` / `hasPreviousPage` based on position within the full list.
     *
     * @param items The complete, unpaginated list of items.
     * @param buildNode Converts each item to the edge's node value; return `null` to omit.
     * @return This builder for chaining.
     */
    @ExperimentalApi
    fun <I> fromList(
        items: List<I>,
        buildNode: (item: I) -> N?
    ): ConnectionBuilder<C, E, N> {
        val offsetLimit = this.arguments.toOffsetLimit()
        val offset = if (offsetLimit.backwards) maxOf(0, items.size - offsetLimit.limit) else offsetLimit.offset
        val slice = items.drop(offset).take(offsetLimit.limit)
        val hasNextPage = offset.toLong() + offsetLimit.limit.toLong() < items.size
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
