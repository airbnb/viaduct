@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package viaduct.tenant.runtime.featuretests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.api.connection.OffsetCursor
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.MultidirectionalConnectionArguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestSchemaFeatureAppTest
import viaduct.tenant.runtime.featuretests.fixtures.Item
import viaduct.tenant.runtime.featuretests.fixtures.ItemEdge
import viaduct.tenant.runtime.featuretests.fixtures.ItemsConnection
import viaduct.tenant.runtime.featuretests.fixtures.UntypedConnectionContext

/**
 * Feature tests demonstrating connection resolver support in [FeatureTestBuilder].
 *
 * Connection resolvers receive a [viaduct.api.context.ConnectionFieldExecutionContext] providing
 * type-safe access to pagination arguments via [viaduct.api.types.ConnectionArguments].
 *
 * Schema setup: define the connection field with pagination arguments (`first`, `after`) and
 * register the resolver using [FeatureTestBuilder.connection]. Use the generated
 * [ItemsConnection.Builder] with one of three [viaduct.api.internal.ConnectionBuilder] utilities:
 *
 * - [viaduct.api.internal.ConnectionBuilder.fromList]: full list with automatic pagination
 * - [viaduct.api.internal.ConnectionBuilder.fromSlice]: pre-paginated slice with explicit hasNextPage
 * - [viaduct.api.internal.ConnectionBuilder.fromEdges]: pre-constructed edges with explicit PageInfo flags
 */
@ExperimentalCoroutinesApi
class ConnectionResolverTest {
    private val schema = FeatureTestSchemaFeatureAppTest().sdl

    // ==================== fromList tests (field: items) ====================

    @Test
    fun `fromList resolver returns all items when no pagination args given`() {
        val items = listOf("Alice", "Bob", "Charlie")

        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "items") { ctx -> buildConnectionFromList(ctx, items) }
            .build()
            .assertJson(
                """{data: {items: {
                    edges: [
                        {cursor: "${OffsetCursor.fromOffset(0).value}", node: {name: "Alice"}},
                        {cursor: "${OffsetCursor.fromOffset(1).value}", node: {name: "Bob"}},
                        {cursor: "${OffsetCursor.fromOffset(2).value}", node: {name: "Charlie"}}
                    ],
                    pageInfo: {
                        hasNextPage: false,
                        hasPreviousPage: false,
                        startCursor: "${OffsetCursor.fromOffset(0).value}",
                        endCursor: "${OffsetCursor.fromOffset(2).value}"
                    }
                }}}""",
                """{ items { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    @Test
    fun `fromList resolver paginates with first argument`() {
        val items = listOf("Alice", "Bob", "Charlie", "Dave")

        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "items") { ctx -> buildConnectionFromList(ctx, items) }
            .build()
            .assertJson(
                """{data: {items: {
                    edges: [
                        {cursor: "${OffsetCursor.fromOffset(0).value}", node: {name: "Alice"}},
                        {cursor: "${OffsetCursor.fromOffset(1).value}", node: {name: "Bob"}}
                    ],
                    pageInfo: {
                        hasNextPage: true,
                        hasPreviousPage: false,
                        startCursor: "${OffsetCursor.fromOffset(0).value}",
                        endCursor: "${OffsetCursor.fromOffset(1).value}"
                    }
                }}}""",
                """{ items(first: 2) { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    @Test
    fun `fromList resolver navigates forward using after cursor`() {
        val items = listOf("Alice", "Bob", "Charlie", "Dave")
        val afterCursor = OffsetCursor.fromOffset(1).value // cursor pointing at "Bob" (index 1)

        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "items") { ctx -> buildConnectionFromList(ctx, items) }
            .build()
            .assertJson(
                """{data: {items: {
                    edges: [
                        {cursor: "${OffsetCursor.fromOffset(2).value}", node: {name: "Charlie"}},
                        {cursor: "${OffsetCursor.fromOffset(3).value}", node: {name: "Dave"}}
                    ],
                    pageInfo: {
                        hasNextPage: false,
                        hasPreviousPage: true,
                        startCursor: "${OffsetCursor.fromOffset(2).value}",
                        endCursor: "${OffsetCursor.fromOffset(3).value}"
                    }
                }}}""",
                """{ items(first: 2, after: "$afterCursor") { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    @Test
    fun `fromList resolver returns empty connection`() {
        val items = emptyList<String>()

        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "items") { ctx -> buildConnectionFromList(ctx, items) }
            .build()
            .assertJson(
                """{data: {items: {
                    edges: [],
                    pageInfo: {
                        hasNextPage: false,
                        hasPreviousPage: false,
                        startCursor: null,
                        endCursor: null
                    }
                }}}""",
                """{ items(first: 10) { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    // ==================== fromSlice tests (field: itemsFromSlice) ====================

    @Test
    fun `fromSlice resolver returns items from pre-paginated slice`() {
        val slice = listOf("Alice", "Bob", "Charlie")

        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "itemsFromSlice") { ctx -> buildConnectionFromSlice(ctx, slice, hasNextPage = false) }
            .build()
            .assertJson(
                """{data: {itemsFromSlice: {
                    edges: [
                        {cursor: "${OffsetCursor.fromOffset(0).value}", node: {name: "Alice"}},
                        {cursor: "${OffsetCursor.fromOffset(1).value}", node: {name: "Bob"}},
                        {cursor: "${OffsetCursor.fromOffset(2).value}", node: {name: "Charlie"}}
                    ],
                    pageInfo: {
                        hasNextPage: false,
                        hasPreviousPage: false,
                        startCursor: "${OffsetCursor.fromOffset(0).value}",
                        endCursor: "${OffsetCursor.fromOffset(2).value}"
                    }
                }}}""",
                """{ itemsFromSlice { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    @Test
    fun `fromSlice resolver reports hasNextPage when caller signals more items exist`() {
        val slice = listOf("Alice", "Bob")

        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "itemsFromSlice") { ctx -> buildConnectionFromSlice(ctx, slice, hasNextPage = true) }
            .build()
            .assertJson(
                """{data: {itemsFromSlice: {
                    edges: [
                        {cursor: "${OffsetCursor.fromOffset(0).value}", node: {name: "Alice"}},
                        {cursor: "${OffsetCursor.fromOffset(1).value}", node: {name: "Bob"}}
                    ],
                    pageInfo: {
                        hasNextPage: true,
                        hasPreviousPage: false,
                        startCursor: "${OffsetCursor.fromOffset(0).value}",
                        endCursor: "${OffsetCursor.fromOffset(1).value}"
                    }
                }}}""",
                """{ itemsFromSlice(first: 2) { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    @Test
    fun `fromSlice resolver sets hasPreviousPage when after cursor is provided`() {
        val afterCursor = OffsetCursor.fromOffset(1).value // after "Bob", so offset=2
        val slice = listOf("Charlie", "Dave") // pre-fetched slice starting at offset 2

        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "itemsFromSlice") { ctx -> buildConnectionFromSlice(ctx, slice, hasNextPage = false) }
            .build()
            .assertJson(
                """{data: {itemsFromSlice: {
                    edges: [
                        {cursor: "${OffsetCursor.fromOffset(2).value}", node: {name: "Charlie"}},
                        {cursor: "${OffsetCursor.fromOffset(3).value}", node: {name: "Dave"}}
                    ],
                    pageInfo: {
                        hasNextPage: false,
                        hasPreviousPage: true,
                        startCursor: "${OffsetCursor.fromOffset(2).value}",
                        endCursor: "${OffsetCursor.fromOffset(3).value}"
                    }
                }}}""",
                """{ itemsFromSlice(first: 2, after: "$afterCursor") { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    // ==================== fromEdges tests (field: itemsFromEdges) ====================

    @Test
    fun `fromEdges resolver builds connection from pre-constructed edges`() {
        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "itemsFromEdges") { ctx ->
                val edges = listOf(
                    buildEdge(ctx, OffsetCursor.fromOffset(0).value, "Alice"),
                    buildEdge(ctx, OffsetCursor.fromOffset(1).value, "Bob"),
                    buildEdge(ctx, OffsetCursor.fromOffset(2).value, "Charlie"),
                )
                buildConnectionFromEdges(ctx, edges)
            }
            .build()
            .assertJson(
                """{data: {itemsFromEdges: {
                    edges: [
                        {cursor: "${OffsetCursor.fromOffset(0).value}", node: {name: "Alice"}},
                        {cursor: "${OffsetCursor.fromOffset(1).value}", node: {name: "Bob"}},
                        {cursor: "${OffsetCursor.fromOffset(2).value}", node: {name: "Charlie"}}
                    ],
                    pageInfo: {
                        hasNextPage: false,
                        hasPreviousPage: false,
                        startCursor: "${OffsetCursor.fromOffset(0).value}",
                        endCursor: "${OffsetCursor.fromOffset(2).value}"
                    }
                }}}""",
                """{ itemsFromEdges { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    @Test
    fun `fromEdges resolver sets hasNextPage and hasPreviousPage explicitly`() {
        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "itemsFromEdges") { ctx ->
                val edges = listOf(
                    buildEdge(ctx, OffsetCursor.fromOffset(2).value, "Charlie"),
                    buildEdge(ctx, OffsetCursor.fromOffset(3).value, "Dave"),
                )
                buildConnectionFromEdges(ctx, edges, hasNextPage = true, hasPreviousPage = true)
            }
            .build()
            .assertJson(
                """{data: {itemsFromEdges: {
                    edges: [
                        {cursor: "${OffsetCursor.fromOffset(2).value}", node: {name: "Charlie"}},
                        {cursor: "${OffsetCursor.fromOffset(3).value}", node: {name: "Dave"}}
                    ],
                    pageInfo: {
                        hasNextPage: true,
                        hasPreviousPage: true,
                        startCursor: "${OffsetCursor.fromOffset(2).value}",
                        endCursor: "${OffsetCursor.fromOffset(3).value}"
                    }
                }}}""",
                """{ itemsFromEdges { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    @Test
    fun `fromEdges resolver returns empty connection`() {
        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "itemsFromEdges") { ctx ->
                buildConnectionFromEdges(ctx, emptyList())
            }
            .build()
            .assertJson(
                """{data: {itemsFromEdges: {
                    edges: [],
                    pageInfo: {
                        hasNextPage: false,
                        hasPreviousPage: false,
                        startCursor: null,
                        endCursor: null
                    }
                }}}""",
                """{ itemsFromEdges { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    @Test
    fun `fromEdges resolver handles backward pagination using last argument`() {
        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "itemsFromEdges") { ctx ->
                // Resolver inspects args to decide which edges to fetch and return
                val args = ctx.arguments as MultidirectionalConnectionArguments
                val edges = if (args.last != null) {
                    listOf(
                        buildEdge(ctx, OffsetCursor.fromOffset(2).value, "Charlie"),
                        buildEdge(ctx, OffsetCursor.fromOffset(3).value, "Dave"),
                    )
                } else {
                    listOf(buildEdge(ctx, OffsetCursor.fromOffset(0).value, "Alice"))
                }
                buildConnectionFromEdges(ctx, edges, hasNextPage = false, hasPreviousPage = args.last != null)
            }
            .build()
            .assertJson(
                """{data: {itemsFromEdges: {
                    edges: [
                        {cursor: "${OffsetCursor.fromOffset(2).value}", node: {name: "Charlie"}},
                        {cursor: "${OffsetCursor.fromOffset(3).value}", node: {name: "Dave"}}
                    ],
                    pageInfo: {
                        hasNextPage: false,
                        hasPreviousPage: true,
                        startCursor: "${OffsetCursor.fromOffset(2).value}",
                        endCursor: "${OffsetCursor.fromOffset(3).value}"
                    }
                }}}""",
                """{ itemsFromEdges(last: 2) { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    // ==================== itemsBackward tests (field: itemsBackward) ====================

    @Test
    fun `fromList resolver paginates backward using last argument`() {
        val items = listOf("Alice", "Bob", "Charlie")

        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "itemsBackward") { ctx -> buildConnectionFromList(ctx, items) }
            .build()
            .assertJson(
                """{data: {itemsBackward: {
                    edges: [
                        {cursor: "${OffsetCursor.fromOffset(1).value}", node: {name: "Bob"}},
                        {cursor: "${OffsetCursor.fromOffset(2).value}", node: {name: "Charlie"}}
                    ],
                    pageInfo: {
                        hasNextPage: false,
                        hasPreviousPage: true,
                        startCursor: "${OffsetCursor.fromOffset(1).value}",
                        endCursor: "${OffsetCursor.fromOffset(2).value}"
                    }
                }}}""",
                """{ itemsBackward(last: 2) { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    @Test
    fun `fromList resolver navigates backward using before cursor`() {
        val items = listOf("Alice", "Bob", "Charlie")
        val beforeCursor = OffsetCursor.fromOffset(2).value // before "Charlie" (index 2)

        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "itemsBackward") { ctx -> buildConnectionFromList(ctx, items) }
            .build()
            .assertJson(
                """{data: {itemsBackward: {
                    edges: [
                        {cursor: "${OffsetCursor.fromOffset(0).value}", node: {name: "Alice"}},
                        {cursor: "${OffsetCursor.fromOffset(1).value}", node: {name: "Bob"}}
                    ],
                    pageInfo: {
                        hasNextPage: true,
                        hasPreviousPage: false,
                        startCursor: "${OffsetCursor.fromOffset(0).value}",
                        endCursor: "${OffsetCursor.fromOffset(1).value}"
                    }
                }}}""",
                """{ itemsBackward(last: 2, before: "$beforeCursor") { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    @Test
    fun `fromList resolver returns empty connection for backward pagination`() {
        val items = emptyList<String>()

        FeatureTestBuilder(schema, useFakeGRTs = true)
            .connection("Query" to "itemsBackward") { ctx -> buildConnectionFromList(ctx, items) }
            .build()
            .assertJson(
                """{data: {itemsBackward: {
                    edges: [],
                    pageInfo: {
                        hasNextPage: false,
                        hasPreviousPage: false,
                        startCursor: null,
                        endCursor: null
                    }
                }}}""",
                """{ itemsBackward(last: 5) { edges { cursor node { name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"""
            )
    }

    // ==================== builder helpers ====================

    /**
     * Builds a typed [ItemsConnection] using [viaduct.api.internal.ConnectionBuilder.fromList].
     *
     * [viaduct.api.internal.ConnectionBuilder.fromList] handles offset/limit computation from pagination arguments, cursor encoding,
     * edge construction, and PageInfo — no manual pagination boilerplate needed.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildConnectionFromList(
        ctx: UntypedConnectionContext,
        allItems: List<String>
    ): ItemsConnection =
        ItemsConnection.Builder(
            ctx as ConnectionFieldExecutionContext<Object, Query, ConnectionArguments, ItemsConnection>
        ).fromList(allItems) { name -> Item.Builder(ctx).name(name).build() }
            .build()

    /**
     * Builds a typed [ItemsConnection] using [viaduct.api.internal.ConnectionBuilder.fromSlice].
     *
     * [viaduct.api.internal.ConnectionBuilder.fromSlice] is for backend-paginated use cases where the caller has already fetched
     * the relevant slice and knows whether more items exist. Cursors are encoded automatically
     * using the offset from pagination arguments.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildConnectionFromSlice(
        ctx: UntypedConnectionContext,
        slice: List<String>,
        hasNextPage: Boolean
    ): ItemsConnection =
        ItemsConnection.Builder(
            ctx as ConnectionFieldExecutionContext<Object, Query, ConnectionArguments, ItemsConnection>
        ).fromSlice(slice, hasNextPage) { name -> Item.Builder(ctx).name(name).build() }
            .build()

    /**
     * Builds a typed [ItemsConnection] using [viaduct.api.internal.ConnectionBuilder.fromEdges].
     *
     * [viaduct.api.internal.ConnectionBuilder.fromEdges] is for use cases where the caller constructs [ItemEdge] instances directly
     * and controls [hasNextPage]/[hasPreviousPage] explicitly. PageInfo cursors are extracted
     * from the first and last edges.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildConnectionFromEdges(
        ctx: UntypedConnectionContext,
        edges: List<ItemEdge>,
        hasNextPage: Boolean = false,
        hasPreviousPage: Boolean = false
    ): ItemsConnection =
        ItemsConnection.Builder(
            ctx as ConnectionFieldExecutionContext<Object, Query, ConnectionArguments, ItemsConnection>
        ).fromEdges(edges, hasNextPage, hasPreviousPage)
            .build()

    /** Builds a single [ItemEdge] with an explicit [cursor] and a node with the given [name]. */
    private fun buildEdge(
        ctx: UntypedConnectionContext,
        cursor: String,
        name: String
    ): ItemEdge = ItemEdge.Builder(ctx).cursor(cursor).node(Item.Builder(ctx).name(name).build()).build()
}
