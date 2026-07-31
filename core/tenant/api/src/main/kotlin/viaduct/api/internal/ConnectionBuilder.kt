package viaduct.api.internal

import graphql.schema.GraphQLObjectType
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.types.BackwardConnectionArguments
import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Edge
import viaduct.api.types.ForwardConnectionArguments
import viaduct.api.types.OffsetCursor
import viaduct.api.types.OffsetLimit
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.errors.handleFrameworkErrors

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
 * class Builder(context: ExecutionContext) :
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
    protected val executionContext: ExecutionContext,
    graphQLObjectType: GraphQLObjectType,
    baseEngineObjectData: EngineObjectData.Sync?,
    private val edgeType: Type<E>,
) : ObjectBase.Builder<C>(executionContext.internal, graphQLObjectType, baseEngineObjectData) {
    /**
     * Retains the original constructor descriptor for existing generated connection builders.
     */
    constructor(
        connectionContext: ConnectionFieldExecutionContext<*, *, *, C>,
        graphQLObjectType: GraphQLObjectType,
        baseEngineObjectData: EngineObjectData.Sync?,
        edgeType: Type<E>,
    ) : this(
        connectionContext as ExecutionContext,
        graphQLObjectType,
        baseEngineObjectData,
        edgeType
    )

    /**
     * Retains source compatibility for subclasses that accessed the original protected property.
     * New code should use [executionContext] and [arguments].
     */
    @Deprecated("Use executionContext or arguments")
    @Suppress("UNCHECKED_CAST")
    protected val connectionContext: ConnectionFieldExecutionContext<*, *, *, C>
        get() =
            executionContext as? ConnectionFieldExecutionContext<*, *, *, C>
                ?: throw IllegalStateException(
                    "This connection builder was created without connection arguments"
                )

    /**
     * The pagination arguments from the current GraphQL request.
     *
     * Access fails when this builder was created by an unpaged or filter-only connection resolver,
     * whose context intentionally has no [ConnectionArguments]. [fromEdges] and the explicit
     * [fromSlice] overload remain available to those resolvers.
     *
     * ([ForwardConnectionArguments.first], [ForwardConnectionArguments.after],
     * [BackwardConnectionArguments.last], [BackwardConnectionArguments.before]).
     *
     * Available for resolvers that need the offset/limit before calling a builder method.
     * [fromSlice] and [fromList] read these arguments internally.
     */
    protected val arguments: ConnectionArguments
        get() =
            (executionContext as? ConnectionFieldExecutionContext<*, *, *, *>)?.arguments
                ?: throw IllegalStateException(
                    "Connection pagination requires a ConnectionFieldExecutionContext with " +
                        "ConnectionArguments; this builder was created with " +
                        executionContext::class.qualifiedName
                )

    /**
     * Build a connection from pre-constructed edges.
     *
     * Use this when your backend returns opaque cursors directly, or when your edge type
     * has custom fields beyond `node` and `cursor` (e.g. a `relevanceScore` on a search edge).
     *
     * You are responsible for setting `cursor` on each edge and for supplying [hasNextPage]
     * and [hasPreviousPage]. `pageInfo.startCursor` and `pageInfo.endCursor` are extracted
     * automatically from the first and last edges.
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
    ): ConnectionBuilder<C, E, N> =
        handleFrameworkErrors("ConnectionBuilder.fromEdges") {
            put("edges", edges)
            val startCursor = edges.firstOrNull()?.let { extractCursor(it) }
            val endCursor = edges.lastOrNull()?.let { extractCursor(it) }
            putInternal("pageInfo", createPageInfo(hasNextPage, hasPreviousPage, startCursor, endCursor))
            this
        }

    private fun extractCursor(edge: E): String {
        val eod = (edge as ObjectBase).__engineObject as EngineObjectData.Sync
        return eod.getOrNull("cursor") as? String
            ?: throw IllegalArgumentException("Cursor not found in edge")
    }

    /**
     * Build a connection from a backend-paginated slice of items.
     *
     * Use this when your backend handles pagination (e.g. a SQL query with `LIMIT`/`OFFSET`).
     * You are responsible for fetching the right slice and supplying [hasNextPage].
     *
     * A common pattern is to fetch one extra item to detect whether a next page exists:
     * ```kotlin
     * val (offset, limit) = ctx.arguments.toOffsetLimit()
     * val fetched = repo.fetchPosts(offset = offset, limit = limit + 1)
     * return PostsConnection {
     *     fromSlice(fetched, hasNextPage = fetched.size > limit) { post -> PostNode { … } }
     * }
     * ```
     *
     * [OffsetCursor]s are encoded automatically per item, and `hasPreviousPage` is set to
     * `true` when `offset > 0`.
     *
     * This overload derives the offset/limit from the request arguments via [ConnectionArguments.toOffsetLimit].
     * It therefore cannot serve backward pagination that only specifies `last` (no `before`),
     * where the offset can only be computed from the backend's total item count: use
     * [fromSlice] with an explicit [OffsetLimit] in that case (the same one you resolved via
     * [ConnectionArguments.toOffsetLimit] to fetch the slice). This method fails fast rather
     * than silently encoding the negative tail-relative offset that
     * [ConnectionArguments.toOffsetLimit] returns for that shape.
     *
     * @param items Items to paginate; may contain one extra item for next-page detection.
     * @param hasNextPage Whether more items exist after this slice.
     * @param buildNode Converts each item to the edge's node value; return `null` to omit.
     * @return This builder for chaining.
     * @throws IllegalArgumentException if the arguments require a total count to resolve the
     *   offset (see [ConnectionArguments.requiresTotalCountForOffsetLimit]).
     */
    @ExperimentalApi
    fun <I> fromSlice(
        items: List<I>,
        hasNextPage: Boolean,
        buildNode: (item: I) -> N?
    ): ConnectionBuilder<C, E, N> {
        require(!arguments.requiresTotalCountForOffsetLimit()) {
            "fromSlice(items, hasNextPage, buildNode) cannot resolve the offset for backward " +
                "pagination with only `last` (no `before`): the offset depends on the backend's " +
                "total item count. Resolve the offset with arguments.toOffsetLimit(totalCount) " +
                "and pass it to fromSlice(items, offsetLimit, hasNextPage, buildNode)."
        }
        val (offset, limit) = arguments.toOffsetLimit()
        return buildEdges(items, hasNextPage, offset, limit, buildNode)
    }

    /**
     * Build a connection from a backend-paginated slice of items, using an [OffsetLimit] you
     * already resolved to fetch that slice.
     *
     * This is the counterpart to [fromSlice] for pagination shapes whose offset depends on the
     * backend's total item count — backward pagination with only `last` (no `before`). Resolve the
     * bounds once and reuse them for both the fetch and the cursor/`PageInfo` construction:
     * ```kotlin
     * val offsetLimit = if (ctx.arguments.requiresTotalCountForOffsetLimit()) {
     *     ctx.arguments.toOffsetLimit(totalCount = repo.count())
     * } else {
     *     ctx.arguments.toOffsetLimit()
     * }
     * val fetched = repo.fetchPosts(offset = offsetLimit.offset, limit = offsetLimit.limit + 1)
     * return PostsConnection {
     *     fromSlice(fetched, offsetLimit, hasNextPage = fetched.size > offsetLimit.limit) { … }
     * }
     * ```
     *
     * [OffsetCursor]s are encoded automatically per item starting from [OffsetLimit.offset], and
     * `hasPreviousPage` is set to `true` when that offset is `> 0`.
     *
     * @param items Items to paginate; may contain one extra item for next-page detection.
     * @param offsetLimit The resolved offset/limit used to fetch [items]; must have a non-negative
     *   [OffsetLimit.offset] (the tail-relative signal from the no-count overload is not accepted).
     * @param hasNextPage Whether more items exist after this slice.
     * @param buildNode Converts each item to the edge's node value; return `null` to omit.
     * @return This builder for chaining.
     */
    @ExperimentalApi
    fun <I> fromSlice(
        items: List<I>,
        offsetLimit: OffsetLimit,
        hasNextPage: Boolean,
        buildNode: (item: I) -> N?
    ): ConnectionBuilder<C, E, N> {
        require(offsetLimit.offset >= 0) {
            "fromSlice requires a resolved, non-negative offset, got: ${offsetLimit.offset}. " +
                "For backward pagination with only `last`, resolve it via " +
                "arguments.toOffsetLimit(totalCount) before fetching the slice."
        }
        return buildEdges(items, hasNextPage, offsetLimit.offset, offsetLimit.limit, buildNode)
    }

    /**
     * Build a connection from a complete, unpaginated list.
     *
     * Use this when your backend returns the full dataset and Viaduct should handle slicing.
     * For large datasets prefer [fromSlice] with a backend-paginated query.
     *
     * Pagination arguments are derived from the execution context: forward ([ForwardConnectionArguments.first]/[ForwardConnectionArguments.after])
     * slices from the head; backward ([BackwardConnectionArguments.last] without [BackwardConnectionArguments.before]) slices from the tail.
     * Cursors are encoded automatically and `hasNextPage`/`hasPreviousPage` are set based
     * on position within the full list.
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
        val offset = if (offsetLimit.offset < 0) maxOf(0, items.size + offsetLimit.offset) else offsetLimit.offset
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
        val edges = items.take(limit).mapIndexedNotNull { idx, item ->
            val node = buildNode(item) ?: return@mapIndexedNotNull null
            // Documented contract: a null mapper result omits the item — no edge is created. The
            // cursor of each surviving edge stays anchored to its original position (offset + idx),
            // so after/before cursors still resume pagination correctly against the source list.
            ViaductObjectBuilder.dynamicBuilderFor(executionContext.internal, edgeType.kcls)
                .put("node", node)
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
    ): EngineObjectData.Sync {
        val pageInfoType = executionContext.internal.schema.schema.getObjectType("PageInfo")
            ?: error("PageInfo type not found in schema")

        return EngineObjectDataBuilder.from(pageInfoType)
            .put("hasNextPage", hasNextPage)
            .put("hasPreviousPage", hasPreviousPage)
            .put("startCursor", startCursor)
            .put("endCursor", endCursor)
            .build()
    }
}
