@file:Suppress("ForbiddenImport")
@file:OptIn(InternalApi::class, ExperimentalApi::class, ExperimentalApi::class)

package viaduct.api.internal

import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.connection.OffsetCursor
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.mocks.MockInternalContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.BackwardConnectionArguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Edge
import viaduct.api.types.ForwardConnectionArguments
import viaduct.api.types.MultidirectionalConnectionArguments
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.MockSchema

/**
 * Tests for ConnectionBuilder utilities.
 *
 * ConnectionBuilder provides pagination utilities for building Connection types:
 * - fromEdges: Build from pre-constructed edges with explicit PageInfo control
 * - fromSlice: Build from a slice of items with automatic cursor encoding
 * - fromList: Build from a full list with automatic pagination
 */
class ConnectionBuilderTest {
    // Schema with Connection, Edge, Node, and PageInfo types
    private val testSchema = MockSchema.mk(
        """
        extend type Query { placeholder: Int }

        type PageInfo {
            hasNextPage: Boolean!
            hasPreviousPage: Boolean!
            startCursor: String
            endCursor: String
        }

        type TestNode {
            id: ID!
            name: String
        }

        type TestEdge {
            node: TestNode
            cursor: String!
        }

        type TestConnection {
            edges: [TestEdge!]!
            pageInfo: PageInfo!
        }
        """.trimIndent()
    )

    private val internalContext = MockInternalContext.create(testSchema)

    private val edgeGraphQLType: GraphQLObjectType
        get() = testSchema.schema.getObjectType("TestEdge")

    private val nodeGraphQLType: GraphQLObjectType
        get() = testSchema.schema.getObjectType("TestNode")

    // Simple input data class (not a GRT)
    private data class SimpleTestNode(val id: String, val name: String)

    // GRT class for creating node ObjectBase values (for fromSlice buildNode lambda)
    private class TestNodeGRT(context: InternalContext, eod: EngineObject) : ObjectBase(context, eod)

    // GRT class matching schema "TestEdge" - used by ViaductObjectBuilder.dynamicBuilderFor
    // Not private: ViaductObjectBuilder.build() uses reflection to call the constructor
    class TestEdge(context: InternalContext, eod: EngineObject) : ObjectBase(context, eod), Edge<Any>

    private interface TestConnection : Connection<TestEdge, Any>

    // Test ConnectionArguments implementations
    private class TestForwardArgs(
        override val first: Int? = null,
        override val after: String? = null
    ) : ForwardConnectionArguments

    private class TestMultidirectionalArgs(
        override val first: Int? = null,
        override val after: String? = null,
        override val last: Int? = null,
        override val before: String? = null
    ) : MultidirectionalConnectionArguments

    private class TestBackwardArgs(
        override val last: Int? = null,
        override val before: String? = null
    ) : BackwardConnectionArguments

    // Mock ConnectionFieldExecutionContext for testing
    private inner class MockConnectionFieldExecutionContext(
        private val internalCtx: InternalContext,
        override val arguments: ConnectionArguments
    ) : ConnectionFieldExecutionContext<Object, Query, ConnectionArguments, TestConnection>, InternalContext by internalCtx {
        override val objectValue: Object
            get() = throw NotImplementedError("Not needed for tests")

        override suspend fun getObjectValue(): Object = throw NotImplementedError("Not needed for tests")

        override val queryValue: Query
            get() = throw NotImplementedError("Not needed for tests")

        override suspend fun getQueryValue(): Query = throw NotImplementedError("Not needed for tests")

        override fun selections(): SelectionSet<TestConnection> = throw NotImplementedError("Not needed for tests")

        override suspend fun query(
            selections: String,
            variables: Map<String, Any?>
        ): Query = throw NotImplementedError("Not needed for tests")

        override fun <T : CompositeOutput> selectionsFor(
            type: Type<T>,
            selections: String,
            variables: Map<String, Any?>
        ): SelectionSet<T> = throw NotImplementedError("Not needed for tests")

        override fun <T : NodeObject> nodeFor(id: GlobalID<T>): T = throw NotImplementedError("Not needed for tests")

        override fun <T : NodeObject> globalIDStringFor(
            type: Type<T>,
            internalID: String
        ): String = throw NotImplementedError("Not needed for tests")

        override fun <T : NodeObject> globalIDFor(
            type: Type<T>,
            internalID: String
        ): GlobalID<T> = throw NotImplementedError("Not needed for tests")

        override val requestContext: Any? = null
    }

    // Concrete ConnectionBuilder implementation for testing
    private inner class TestConnectionBuilder(
        context: ConnectionFieldExecutionContext<*, *, *, TestConnection>,
        graphQLObjectType: GraphQLObjectType,
        baseEngineObjectData: EngineObjectData? = null
    ) : ConnectionBuilder<TestConnection, TestEdge, Any>(
            context,
            graphQLObjectType,
            baseEngineObjectData,
            Type.ofClass(TestEdge::class)
        ) {
        override fun build(): TestConnection {
            return object : TestConnection {}
        }

        // Expose internal state for testing
        fun getBuiltEngineObjectData(): EngineObjectData = buildEngineObjectData()
    }

    private fun createContext(args: ConnectionArguments = TestForwardArgs()): MockConnectionFieldExecutionContext {
        return MockConnectionFieldExecutionContext(internalContext, args)
    }

    private fun createBuilder(args: ConnectionArguments = TestForwardArgs()): TestConnectionBuilder {
        val connectionType = testSchema.schema.getObjectType("TestConnection")
        return TestConnectionBuilder(createContext(args), connectionType)
    }

    /**
     * Creates a test edge as a TestEdge ObjectBase.
     */
    private fun createEdge(
        cursor: String,
        nodeId: String,
        nodeName: String
    ): TestEdge {
        val nodeData = ResolvedEngineObjectData.Builder(nodeGraphQLType)
            .put("id", nodeId)
            .put("name", nodeName)
            .build()

        val edgeData = ResolvedEngineObjectData.Builder(edgeGraphQLType)
            .put("cursor", cursor)
            .put("node", nodeData)
            .build()

        return TestEdge(internalContext, edgeData)
    }

    /**
     * Creates a TestNodeGRT ObjectBase from a SimpleTestNode (for fromSlice buildNode lambda).
     */
    private fun createNodeGRT(node: SimpleTestNode): TestNodeGRT {
        val nodeData = ResolvedEngineObjectData.Builder(nodeGraphQLType)
            .put("id", node.id)
            .put("name", node.name)
            .build()
        return TestNodeGRT(internalContext, nodeData)
    }

    // ==================== fromEdges tests ====================

    @Test
    fun `fromEdges builds connection with provided edges`() {
        val builder = createBuilder()
        val edges = listOf(
            createEdge(OffsetCursor.fromOffset(0).value, "1", "Node1"),
            createEdge(OffsetCursor.fromOffset(1).value, "2", "Node2"),
            createEdge(OffsetCursor.fromOffset(2).value, "3", "Node3")
        )

        val connection = builder.fromEdges(edges, hasNextPage = true, hasPreviousPage = false).build()

        // Verify connection was built
        assertTrue(connection is TestConnection)

        // Verify the built engine object data contains edges
        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>
        assertEquals(3, builtEdges.size)
    }

    @Test
    fun `fromEdges sets hasNextPage correctly`() {
        val builder = createBuilder()
        val edges = listOf(
            createEdge(OffsetCursor.fromOffset(0).value, "1", "Node1")
        )

        builder.fromEdges(edges, hasNextPage = true, hasPreviousPage = false)

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasNextPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasNextPage") }
        assertEquals(true, hasNextPage)
    }

    @Test
    fun `fromEdges sets hasPreviousPage correctly`() {
        val builder = createBuilder()
        val edges = listOf(
            createEdge(OffsetCursor.fromOffset(5).value, "1", "Node1")
        )

        builder.fromEdges(edges, hasNextPage = false, hasPreviousPage = true)

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasPreviousPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasPreviousPage") }
        assertEquals(true, hasPreviousPage)
    }

    @Test
    fun `fromEdges with empty edges produces valid empty connection`() {
        val builder = createBuilder()

        builder.fromEdges(emptyList(), hasNextPage = false, hasPreviousPage = false)

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>
        assertEquals(0, builtEdges.size)

        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasNextPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasNextPage") }
        val hasPreviousPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasPreviousPage") }
        assertEquals(false, hasNextPage)
        assertEquals(false, hasPreviousPage)
    }

    @Test
    fun `fromEdges extracts cursors from first and last edges for PageInfo`() {
        val builder = createBuilder()
        val edges = listOf(
            createEdge(OffsetCursor.fromOffset(10).value, "1", "Node1"),
            createEdge(OffsetCursor.fromOffset(11).value, "2", "Node2"),
            createEdge(OffsetCursor.fromOffset(12).value, "3", "Node3")
        )

        builder.fromEdges(edges, hasNextPage = false, hasPreviousPage = false)

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val fetchedStartCursor = runBlocking { (pageInfo as EngineObjectData).fetch("startCursor") }
        val fetchedEndCursor = runBlocking { (pageInfo as EngineObjectData).fetch("endCursor") }
        // Cursors come from the first and last edges
        assertEquals(OffsetCursor.fromOffset(10).value, fetchedStartCursor)
        assertEquals(OffsetCursor.fromOffset(12).value, fetchedEndCursor)
    }

    // ==================== fromSlice tests ====================

    @Test
    fun `fromSlice builds connection from slice with hasNextPage`() {
        val args = TestForwardArgs(first = 5)
        val builder = createBuilder(args)
        // Simulate providing 5 items (the slice)
        val slice = (0..4).map { SimpleTestNode("$it", "Node$it") }

        builder.fromSlice(slice, hasNextPage = true) { node ->
            createNodeGRT(node)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>
        assertEquals(5, builtEdges.size)

        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasNextPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasNextPage") }
        assertTrue(hasNextPage as Boolean)
    }

    @Test
    fun `fromSlice encodes cursors automatically using OffsetCursor`() {
        val args = TestForwardArgs(first = 3)
        val builder = createBuilder(args)
        val slice = listOf(
            SimpleTestNode("1", "Node1"),
            SimpleTestNode("2", "Node2"),
            SimpleTestNode("3", "Node3")
        )

        builder.fromSlice(slice, hasNextPage = false) { node ->
            createNodeGRT(node)
        }

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val startCursor = runBlocking { (pageInfo as EngineObjectData).fetch("startCursor") } as String
        val endCursor = runBlocking { (pageInfo as EngineObjectData).fetch("endCursor") } as String

        // Verify cursors are valid OffsetCursor values
        assertTrue(OffsetCursor.isValid(startCursor))
        assertTrue(OffsetCursor.isValid(endCursor))
        assertEquals(0, OffsetCursor(startCursor).toOffset())
        assertEquals(2, OffsetCursor(endCursor).toOffset())
    }

    @Test
    fun `fromSlice computes hasPreviousPage from offset`() {
        // Request starting after offset 4 - should have previous page
        val afterCursor = OffsetCursor.fromOffset(4)
        val args = TestForwardArgs(first = 3, after = afterCursor.value)
        val builder = createBuilder(args)
        // Provide the slice starting at offset 5
        val slice = (5..7).map { SimpleTestNode("$it", "Node$it") }

        builder.fromSlice(slice, hasNextPage = true) { node ->
            createNodeGRT(node)
        }

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasPreviousPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasPreviousPage") }
        assertTrue(hasPreviousPage as Boolean)
    }

    @Test
    fun `fromSlice with empty slice produces valid empty connection`() {
        val args = TestForwardArgs(first = 10)
        val builder = createBuilder(args)
        val emptyList = emptyList<SimpleTestNode>()

        builder.fromSlice(emptyList, hasNextPage = false) { node ->
            createNodeGRT(node)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>
        assertEquals(0, builtEdges.size)
    }

    @Test
    fun `fromSlice respects offset from ConnectionArguments`() {
        // Start after offset 5, take 3 items
        val afterCursor = OffsetCursor.fromOffset(5)
        val args = TestForwardArgs(first = 3, after = afterCursor.value)
        val builder = createBuilder(args)
        // Provide slice starting at offset 6
        val slice = (6..8).map { SimpleTestNode("$it", "Node$it") }

        builder.fromSlice(slice, hasNextPage = true) { node ->
            createNodeGRT(node)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>
        assertEquals(3, builtEdges.size)

        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val startCursor = runBlocking { (pageInfo as EngineObjectData).fetch("startCursor") } as String

        // Should start at offset 6 (after 5)
        assertEquals(6, OffsetCursor(startCursor).toOffset())
    }

    @Test
    fun `fromSlice takes at most limit items`() {
        val args = TestForwardArgs(first = 3)
        val builder = createBuilder(args)
        // Provide more items than limit (e.g., fetched limit+1 for hasNextPage detection)
        val itemsPlusOne = (0..3).map { SimpleTestNode("$it", "Node$it") }

        builder.fromSlice(itemsPlusOne, hasNextPage = true) { node ->
            createNodeGRT(node)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>
        assertEquals(3, builtEdges.size) // Should take only 3, not 4
    }

    // ==================== fromList tests ====================

    @Test
    fun `fromList paginates full list correctly`() {
        val args = TestForwardArgs(first = 3)
        val builder = createBuilder(args)
        val fullList = (0..4).map { SimpleTestNode("$it", "Node$it") }

        builder.fromList(fullList) { node ->
            createNodeGRT(node as SimpleTestNode)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>
        assertEquals(3, builtEdges.size)

        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasNextPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasNextPage") }
        assertTrue(hasNextPage as Boolean)
    }

    @Test
    fun `fromList computes hasNextPage from list size`() {
        val args = TestForwardArgs(first = 5)
        val builder = createBuilder(args)
        val fullList = (0..9).map { SimpleTestNode("$it", "Node$it") }

        builder.fromList(fullList) { node ->
            createNodeGRT(node as SimpleTestNode)
        }

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasNextPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasNextPage") }
        assertTrue(hasNextPage as Boolean)
    }

    @Test
    fun `fromList computes hasPreviousPage from offset`() {
        val afterCursor = OffsetCursor.fromOffset(4)
        val args = TestForwardArgs(first = 3, after = afterCursor.value)
        val builder = createBuilder(args)
        val fullList = (0..9).map { SimpleTestNode("$it", "Node$it") }

        builder.fromList(fullList) { node ->
            createNodeGRT(node as SimpleTestNode)
        }

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasPreviousPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasPreviousPage") }
        assertTrue(hasPreviousPage as Boolean)
    }

    // ==================== Edge cases ====================

    @Test
    fun `empty connection has valid PageInfo per Relay spec`() {
        val builder = createBuilder()

        builder.fromEdges(emptyList(), hasNextPage = false, hasPreviousPage = false)

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val pageInfoData = pageInfo as EngineObjectData

        // Per Relay spec, empty connections should have valid PageInfo
        val hasNextPage = runBlocking { pageInfoData.fetch("hasNextPage") }
        val hasPreviousPage = runBlocking { pageInfoData.fetch("hasPreviousPage") }

        assertEquals(false, hasNextPage)
        assertEquals(false, hasPreviousPage)
    }

    @Test
    fun `PageInfo cursors are null when edges empty`() {
        val builder = createBuilder()

        builder.fromEdges(emptyList(), hasNextPage = false, hasPreviousPage = false)

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val pageInfoData = pageInfo as EngineObjectData

        val startCursor = runBlocking { pageInfoData.fetchOrNull("startCursor") }
        val endCursor = runBlocking { pageInfoData.fetchOrNull("endCursor") }

        assertNull(startCursor)
        assertNull(endCursor)
    }

    @Test
    fun `hasNextPage false when at end of list`() {
        val args = TestForwardArgs(first = 5)
        val builder = createBuilder(args)
        val fullList = (0..4).map { SimpleTestNode("$it", "Node$it") }

        // Request all 5 items - fromList determines hasNextPage from list size
        builder.fromList(fullList) { node ->
            createNodeGRT(node as SimpleTestNode)
        }

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasNextPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasNextPage") }

        assertFalse(hasNextPage as Boolean)
    }

    @Test
    fun `hasPreviousPage false when at start of list`() {
        val args = TestForwardArgs(first = 5)
        val builder = createBuilder(args)
        val slice = (0..4).map { SimpleTestNode("$it", "Node$it") }

        // Request from start (no after cursor) - offset is 0
        builder.fromSlice(slice, hasNextPage = true) { node ->
            createNodeGRT(node)
        }

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasPreviousPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasPreviousPage") }

        assertFalse(hasPreviousPage as Boolean)
    }

    // ==================== Additional edge cases ====================

    @Test
    fun `fromList handles list smaller than requested first`() {
        val args = TestForwardArgs(first = 10)
        val builder = createBuilder(args)
        val fullList = listOf(SimpleTestNode("1", "Node1"), SimpleTestNode("2", "Node2"))

        builder.fromList(fullList) { node ->
            createNodeGRT(node as SimpleTestNode)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>
        assertEquals(2, builtEdges.size)

        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasNextPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasNextPage") }
        assertFalse(hasNextPage as Boolean)
    }

    @Test
    fun `fromList with after cursor beyond list size produces empty edges`() {
        // After cursor at offset 10, but list only has 5 items
        val afterCursor = OffsetCursor.fromOffset(10)
        val args = TestForwardArgs(first = 5, after = afterCursor.value)
        val builder = createBuilder(args)
        val fullList = (0..4).map { SimpleTestNode("$it", "Node$it") }

        builder.fromList(fullList) { node ->
            createNodeGRT(node as SimpleTestNode)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>
        assertEquals(0, builtEdges.size)
    }

    @Test
    fun `fromEdges with single edge sets same start and end cursor`() {
        // Using args with after cursor at offset 4 means we start at offset 5
        val afterCursor = OffsetCursor.fromOffset(4)
        val args = TestForwardArgs(first = 10, after = afterCursor.value)
        val builder = createBuilder(args)
        val expectedCursor = OffsetCursor.fromOffset(5).value
        val edges = listOf(createEdge(expectedCursor, "1", "Node1"))

        builder.fromEdges(edges, hasNextPage = false, hasPreviousPage = false)

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val startCursor = runBlocking { (pageInfo as EngineObjectData).fetch("startCursor") }
        val endCursor = runBlocking { (pageInfo as EngineObjectData).fetch("endCursor") }

        // With 1 edge at offset 5, both start and end cursor should be offset 5
        assertEquals(expectedCursor, startCursor)
        assertEquals(expectedCursor, endCursor)
    }

    @Test
    fun `fromSlice with default page size when first not specified`() {
        val args = TestForwardArgs() // no first specified, uses default of 20
        val builder = createBuilder(args)
        // Provide 25 items in the slice
        val slice = (0..24).map { SimpleTestNode("$it", "Node$it") }

        builder.fromSlice(slice, hasNextPage = true) { node ->
            createNodeGRT(node)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>
        assertEquals(20, builtEdges.size) // default page size
    }

    @Test
    fun `fromSlice with multidirectional args forward pagination`() {
        val args = TestMultidirectionalArgs(first = 5)
        val builder = createBuilder(args)
        val slice = (0..4).map { SimpleTestNode("$it", "Node$it") }

        builder.fromSlice(slice, hasNextPage = true) { node ->
            createNodeGRT(node)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>
        assertEquals(5, builtEdges.size)

        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasNextPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasNextPage") }
        assertTrue(hasNextPage as Boolean)
    }

    // ==================== Backward pagination tests ====================

    @Test
    fun `fromList with backward pagination without cursor returns last N items`() {
        // Request last 3 items without a before cursor
        val args = TestBackwardArgs(last = 3)
        val builder = createBuilder(args)
        val fullList = (0..9).map { SimpleTestNode("$it", "Node$it") }

        builder.fromList(fullList) { node ->
            createNodeGRT(node as SimpleTestNode)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>

        // Should return 3 items (the last 3)
        assertEquals(3, builtEdges.size)

        // Verify cursors point to positions 7, 8, 9 (last 3 of 10 items)
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val startCursor = runBlocking { (pageInfo as EngineObjectData).fetch("startCursor") } as String
        val endCursor = runBlocking { (pageInfo as EngineObjectData).fetch("endCursor") } as String

        assertEquals(7, OffsetCursor(startCursor).toOffset())
        assertEquals(9, OffsetCursor(endCursor).toOffset())
    }

    @Test
    fun `fromList with backward pagination without cursor sets hasPreviousPage correctly`() {
        val args = TestBackwardArgs(last = 3)
        val builder = createBuilder(args)
        val fullList = (0..9).map { SimpleTestNode("$it", "Node$it") }

        builder.fromList(fullList) { node ->
            createNodeGRT(node as SimpleTestNode)
        }

        val eod = builder.getBuiltEngineObjectData()
        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasPreviousPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasPreviousPage") }
        val hasNextPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasNextPage") }

        // Should have previous page (items 0-6) but no next page
        assertTrue(hasPreviousPage as Boolean)
        assertFalse(hasNextPage as Boolean)
    }

    @Test
    fun `fromList with backward pagination and before cursor returns correct slice`() {
        // Request 3 items before offset 7
        val beforeCursor = OffsetCursor.fromOffset(7)
        val args = TestBackwardArgs(last = 3, before = beforeCursor.value)
        val builder = createBuilder(args)
        val fullList = (0..9).map { SimpleTestNode("$it", "Node$it") }

        builder.fromList(fullList) { node ->
            createNodeGRT(node as SimpleTestNode)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>

        // Should return 3 items at positions 4, 5, 6 (before position 7)
        assertEquals(3, builtEdges.size)

        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val startCursor = runBlocking { (pageInfo as EngineObjectData).fetch("startCursor") } as String
        val endCursor = runBlocking { (pageInfo as EngineObjectData).fetch("endCursor") } as String

        assertEquals(4, OffsetCursor(startCursor).toOffset())
        assertEquals(6, OffsetCursor(endCursor).toOffset())
    }

    @Test
    fun `fromList with backward pagination when list smaller than last returns all items`() {
        val args = TestBackwardArgs(last = 10)
        val builder = createBuilder(args)
        val fullList = (0..2).map { SimpleTestNode("$it", "Node$it") }

        builder.fromList(fullList) { node ->
            createNodeGRT(node as SimpleTestNode)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>

        // Should return all 3 items
        assertEquals(3, builtEdges.size)

        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val hasPreviousPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasPreviousPage") }
        val hasNextPage = runBlocking { (pageInfo as EngineObjectData).fetch("hasNextPage") }

        // No previous or next page when returning entire list
        assertFalse(hasPreviousPage as Boolean)
        assertFalse(hasNextPage as Boolean)
    }

    @Test
    fun `fromList with multidirectional backward pagination without cursor returns last N items`() {
        val args = TestMultidirectionalArgs(last = 4)
        val builder = createBuilder(args)
        val fullList = (0..9).map { SimpleTestNode("$it", "Node$it") }

        builder.fromList(fullList) { node ->
            createNodeGRT(node as SimpleTestNode)
        }

        val eod = builder.getBuiltEngineObjectData()
        val builtEdges = runBlocking { eod.fetch("edges") } as List<*>

        // Should return 4 items (the last 4: positions 6, 7, 8, 9)
        assertEquals(4, builtEdges.size)

        val pageInfo = runBlocking { eod.fetch("pageInfo") } as EngineObject
        val startCursor = runBlocking { (pageInfo as EngineObjectData).fetch("startCursor") } as String

        assertEquals(6, OffsetCursor(startCursor).toOffset())
    }
}
