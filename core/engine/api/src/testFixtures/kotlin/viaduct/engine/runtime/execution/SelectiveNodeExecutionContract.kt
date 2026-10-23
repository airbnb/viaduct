package viaduct.engine.runtime.execution

import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.instrumentation.InstrumentNodeFetchingParameters
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.graphql.test.assertJson
import viaduct.graphql.test.assertMatches
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

interface SelectiveNodeExecutionContract : QueryExecutionContract {
    @Test
    fun `simple selective node`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("id", schema.schema.getObjectType("Foo")!!) }
            }
            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) {
                                put("x", 2)
                            }
                        }
                    )
                }
            }
            field("Foo" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest {
            runQuery("{ foo { y } }").assertJson("{data: {foo: {y: 6}}}")
        }
    }

    @Test
    fun `list of selective nodes`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foos: [Foo] }
                type Foo implements Node { id: ID!, x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foos") {
                valueFromContext {
                    listOf(
                        it.createNodeReference("1", schema.schema.getObjectType("Foo")!!),
                        it.createNodeReference("2", schema.schema.getObjectType("Foo")!!),
                    )
                }
            }
            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { id, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) {
                                put("x", id.toInt() * 2)
                            }
                        }
                    )
                }
            }
            field("Foo" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest {
            runQuery("{ foos { y } }").assertJson("{data: {foos: [{y:6}, {y:12}]}}")
        }
    }

    @Test
    fun `nested list of selective nodes`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foos: [[Foo]] }
                type Foo implements Node { id: ID!, x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foos") {
                valueFromContext {
                    listOf(
                        listOf(
                            it.createNodeReference("1", schema.schema.getObjectType("Foo")!!),
                            it.createNodeReference("2", schema.schema.getObjectType("Foo")!!),
                        ),
                        listOf(
                            it.createNodeReference("3", schema.schema.getObjectType("Foo")!!),
                            it.createNodeReference("4", schema.schema.getObjectType("Foo")!!),
                        )
                    )
                }
            }
            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { id, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) {
                                put("x", id.toInt() * 2)
                            }
                        }
                    )
                }
            }
            field("Foo" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest {
            runQuery("{ foos { y } }")
                .assertJson("{data: {foos: [[{y:6}, {y:12}], [{y:18}, {y:24}]]}}")
        }
    }

    @Test
    fun `same node resolves different fields`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { barA: Bar, barB:Bar, x:Int  }
                type Bar implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext {
                    createEngineObjectData(schema.schema.getObjectType("Foo")!!, emptyMap())
                }
            }

            field("Foo" to "barA") {
                valueFromContext { ctx ->
                    ctx.createNodeReference("bar", schema.schema.getObjectType("Bar")!!)
                }
            }
            field("Foo" to "barB") {
                valueFromContext { ctx ->
                    ctx.createNodeReference("bar", schema.schema.getObjectType("Bar")!!)
                }
            }
            field("Foo" to "x") {
                resolver {
                    objectSelections("barA { x } barB { y }")
                    fn { _, obj, _, _, _ ->
                        val barA = obj.fetchAs<EngineObjectData>("barA")
                        val barB = obj.fetchAs<EngineObjectData>("barB")
                        barA.fetchAs<Int>("x") * barB.fetchAs<Int>("y")
                    }
                }
            }

            type("Bar") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val values = buildMap {
                        if (sels!!.containsField("Bar", "x")) {
                            put("x", 2)
                        }
                        if (sels.containsField("Bar", "y")) {
                            put("y", 3)
                        }
                    }

                    createEngineObjectData(objectType, values)
                }
            }
        }.toEngineTestModule().runContractTest(withoutDefaultQueryNodeResolvers = true) {
            withTimeout("{ foo { x } }")
                .assertJson("{ data: { foo: { x: 6 } } }")
        }
    }

    @Test
    fun `aliased rss reads node field`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("alias: y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("alias") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "y")) {
                                put("y", 2)
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }
    }

    @Test
    fun `batched selective node cache distinguishes nested selections across query paths`() {
        val barResolverSelections = mutableSetOf<Set<String>>()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo, bar: Bar }
                type Foo implements Node { id: ID!, bar: Bar }
                type Bar implements Node { id: ID!, nested: Nested }
                type Nested { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { ctx ->
                    ctx.createNodeReference("foo", schema.schema.getObjectType("Foo"))
                }
            }
            field("Query" to "bar") {
                valueFromContext { ctx ->
                    ctx.createNodeReference("bar", schema.schema.getObjectType("Bar"))
                }
            }

            type("Foo") {
                nodeBatchedExecutor(selective = true) { selectors, ctx ->
                    selectors.associateWith { selector ->
                        Result.success(
                            createEngineObjectData(
                                objectType,
                                buildMap {
                                    if (selector.selections.containsField("Foo", "bar")) {
                                        put(
                                            "bar",
                                            ctx.createNodeReference("bar", schema.schema.getObjectType("Bar"))
                                        )
                                    }
                                }
                            )
                        )
                    }
                }
            }
            type("Bar") {
                nodeBatchedExecutor(selective = true) { selectors, _ ->
                    barResolverSelections += selectors.map { selector ->
                        selector.selections
                            .selectionSetForField("Bar", "nested")
                            .selections()
                            .map { it.fieldName }
                            .toSet()
                    }
                    selectors.associateWith { selector ->
                        Result.success(
                            createEngineObjectData(
                                objectType,
                                buildMap {
                                    if (selector.selections.containsField("Bar", "nested")) {
                                        val nestedSelections =
                                            selector.selections.selectionSetForField("Bar", "nested")
                                        put(
                                            "nested",
                                            createEngineObjectData(
                                                schema.schema.getObjectType("Nested"),
                                                buildMap {
                                                    if (nestedSelections.containsField("Nested", "x")) {
                                                        put("x", 1)
                                                    }
                                                    if (nestedSelections.containsField("Nested", "y")) {
                                                        put("y", 2)
                                                    }
                                                }
                                            )
                                        )
                                    }
                                }
                            )
                        )
                    }
                }
            }
        }.toEngineTestModule().runContractTest(withoutDefaultQueryNodeResolvers = true) {
            withTimeout(
                """
                    {
                      bar {
                        nested {
                          x
                        }
                      }
                      foo {
                        bar {
                          nested {
                            y
                          }
                        }
                      }
                    }
                """.trimIndent()
            ).assertJson(
                """
                    {
                      data: {
                        bar: {
                          nested: {
                            x: 1
                          }
                        },
                        foo: {
                          bar: {
                            nested: {
                              y: 2
                            }
                          }
                        }
                      }
                    }
                """.trimIndent()
            )
        }

        assertEquals(setOf(setOf("x"), setOf("y")), barResolverSelections)
    }

    @Test
    fun `cached node can fetch omitted id`() {
        // Query.foo returns a reference to Foo("foo"), and the outer `foo { self { id } }`
        // selection first resolves that node with selections that omit `id`. The resolved value is
        // cached for Foo("foo") with only `self`.
        //
        // Resolving `self { id }` then requests the same selective Foo node resolver for the same id
        // with only `id` selected. The node-loader cache can reuse the cached `self` result because
        // the top-level node `id` is supplied from the reference rather than the resolver result.
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, self: Foo }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, ctx ->
                            ctx.createNodeReference("foo", schema.schema.getObjectType("Foo")!!)
                        }
                    )
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { id, sels, ctx ->
                    val values = buildMap {
                        if (sels!!.containsField("Foo", "id")) {
                            put("id", id)
                        }
                        if (sels.containsField("Foo", "self")) {
                            put("self", ctx.createNodeReference("foo", schema.schema.getObjectType("Foo")!!))
                        }
                    }

                    createEngineObjectData(objectType, values)
                }
            }
        }.toEngineTestModule().runContractTest(withoutDefaultQueryNodeResolvers = true) {
            withTimeout(
                """
                    {
                      foo {
                        self {
                          id
                        }
                      }
                    }
                """.trimIndent()
            ).assertJson("{data: {foo: {self: {id: \"foo\"}}}}")
        }
    }

    @Test
    fun `covered nested rss reuses node source`() {
        val fooCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, bar: Bar }
                type Bar { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Bar" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    fooCalls.incrementAndGet()
                    val barSelections =
                        sels!!.selectionSetForField("Foo", "bar")
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "bar" to
                                createEngineObjectData(
                                    schema.schema.getObjectType("Bar"),
                                    buildMap {
                                        if (barSelections.containsField("Bar", "x")) {
                                            put("x", 2)
                                        }
                                    },
                                )
                        ),
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { bar { x y } } }")
                .assertJson("{data: {foo: {bar: {x: 2, y: 6}}}}")
        }

        assertEquals(1, fooCalls.get())
    }

    @Test
    fun `deep recursive node failure is attributed to rss consumer`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo implements Node { id:ID!, x:Int, y:Int, next:Foo }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("1", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("next { next { y } }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("next")
                            .fetchAs<EngineObjectData>("next")
                            .fetchAs<Int>("y")
                    }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { id, sels, ctx ->
                    val requested = sels!!
                    if (id == "3" && requested.containsField("Foo", "y")) {
                        error("terminal node failed")
                    }
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (requested.containsField("Foo", "next")) {
                                put(
                                    "next",
                                    ctx.createNodeReference(
                                        (id.toInt() + 1).toString(),
                                        objectType,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x } }")
                .assertMatches {
                    "data" to {
                        "foo" to {
                            "x" to null
                        }
                    }
                    "errors" to arrayOf(
                        {
                            "message" to ".*terminal node failed.*"
                            "path" to listOf("foo", "x")
                        }
                    )
                }
        }
    }

    @Test
    fun `deeper overlapping recursive selection is retained`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo implements Node { id:ID!, x:Int, next:Foo }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("1", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections(
                        """
                            next { next { __typename } }
                            next { next { next { __typename } } }
                        """.trimIndent()
                    )
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("next")
                        1
                    }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { id, sels, ctx ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "next")) {
                                put(
                                    "next",
                                    ctx.createNodeReference(
                                        (id.toInt() + 1).toString(),
                                        objectType,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 1}}}")
        }
    }

    @Test
    fun `field checker denial after selective node materialization is reported`() {
        val checkerCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo implements Node { id:ID!, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                checker {
                    objectSelections("fields", "foo { y }")
                    fn { _, objects ->
                        val y = objects.getValue("fields")
                            .fetchAs<EngineObjectData>("foo")
                            .fetchAs<Int>("y")
                        checkerCalls.incrementAndGet()
                        if (y == 2) noAccess("foo denied")
                    }
                }
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "y")) {
                                put("y", 2)
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { __typename } }").assertMatches {
                "data" to {
                    "foo" to null
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*foo denied.*"
                        "path" to listOf("foo")
                    }
                )
            }
        }

        assertEquals(1, checkerCalls.get())
    }

    @Test
    fun `fragment on other node type is ignored`() {
        // Query.bar returns a reference to a Bar node, while the only child selection is a named
        // fragment on Foo nested under the Node interface. Since the concrete object is Bar, the
        // Foo fragment should not apply and the response should contain an empty Bar object.
        //
        // This fails when the lazy node resolution path rebuilds the nested selection set without
        // carrying over the named fragment definition, so resolving Query.bar throws "Missing
        // fragment definition: FooFields" before it can ignore the non-matching fragment.
        MockTenantModuleBootstrapper(
            """
                extend type Query { bar: Bar }
                type Foo implements Node { id: ID!, x: Int }
                type Bar implements Node { id: ID! }
            """.trimIndent()
        ) {
            field("Query" to "bar") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, ctx ->
                            ctx.createNodeReference("bar", schema.schema.getObjectType("Bar")!!)
                        }
                    )
                }
            }

            type("Bar") {
                nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                    createEngineObjectData(objectType, emptyMap())
                }
            }
        }.toEngineTestModule().runContractTest(withoutDefaultQueryNodeResolvers = true) {
            withTimeout(
                """
                    {
                      bar {
                        ... on Node {
                          ...FooFields
                        }
                      }
                    }

                    fragment FooFields on Foo {
                      x
                    }
                """.trimIndent()
            ).assertJson("{data: {bar: {}}}")
        }
    }

    @Test
    fun `fully skipped node selections still resolve rss reads`() {
        val yRequests = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { b: Int, foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Query" to "b") {
                resolver {
                    objectSelections("foo { y }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("foo").fetchAs<Int>("y") * 5
                    }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val fields = sels!!.selections().map { it.fieldName }.toSet()
                    if ("y" in fields) yRequests.incrementAndGet()
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if ("y" in fields) put("y", 2)
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout(
                """
                    {
                      b
                      foo { aliasedX: x @skip(if: true) }
                    }
                """.trimIndent()
            ).assertJson("{data: {b: 10, foo: {}}}")
        }

        assertTrue(yRequests.get() > 0)
    }

    @Test
    fun `initial selective node materialization instruments resolver once`() {
        val nodeExecutions = AtomicInteger()
        val instrumentedNodeExecutions = AtomicInteger()
        val instrumentation = object : ViaductResolverInstrumentation {
            override fun <T> instrumentResolverExecution(
                resolver: ResolverFunction<T>,
                parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
                state: ViaductResolverInstrumentation.InstrumentationState?,
            ): ResolverFunction<T> {
                if (parameters.resolverMetadata.name != "Node:Foo") return resolver
                return ResolverFunction {
                    instrumentedNodeExecutions.incrementAndGet()
                    resolver.resolve()
                }
            }
        }

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                    nodeExecutions.incrementAndGet()
                    createEngineObjectData(objectType, mapOf("x" to 1))
                }
            }
        }.toEngineTestModule().runContractTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                resolverInstrumentation = instrumentation,
            )
        ) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 1}}}")
        }

        assertEquals(1, nodeExecutions.get())
        assertEquals(1, instrumentedNodeExecutions.get())
    }

    @Test
    fun `merged node field occurrences resolve once with unioned selections`() {
        val nodeCalls = AtomicInteger()
        var nodeSelections: EngineSelectionSet? = null

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    nodeCalls.incrementAndGet()
                    nodeSelections = sels
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) put("x", 1)
                            if (sels.containsField("Foo", "y")) put("y", 2)
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x } foo { y } }")
                .assertJson("{data: {foo: {x: 1, y: 2}}}")
        }

        assertEquals(1, nodeCalls.get())
        assertEquals(setOf("x", "y"), checkNotNull(nodeSelections).selections().map { it.fieldName }.toSet())
    }

    @Test
    fun `node materializes embedded list items`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo implements Node { id:ID!, bars:[Bar] }
                type Bar { x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val barSelections =
                        sels!!.selectionSetForField("Foo", "bars")
                    val includeY = barSelections.containsField("Bar", "y")
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "bars" to listOf(2, 3).map { y ->
                                createEngineObjectData(
                                    schema.schema.getObjectType("Bar"),
                                    if (includeY) mapOf("y" to y) else emptyMap(),
                                )
                            }
                        ),
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { bars { x } } }")
                .assertJson("{data: {foo: {bars: [{x: 10}, {x: 15}]}}}")
        }
    }

    @Test
    fun `node resolver hydrates unselected fields in its output selection set`() {
        val nodeCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }
            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("y" to if (nodeCalls.incrementAndGet() == 1) -1 else 1)
                    )
                }
            }
            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") + 1 }
                }
            }
        }.toEngineTestModule().runContractTest {
            runQuery("{ foo { x } }").assertJson("{data: {foo: {x: 0}}}")
        }

        assertEquals(1, nodeCalls.get())
    }

    @Test
    fun `node selected in rss is not shadowed by node plan under skipped rss`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { a: Int, foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Query" to "a") {
                resolver {
                    objectSelections("foo { x }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("foo").fetchAs<Int>("x") * 3
                    }
                }
            }

            field("Foo" to "y") {
                resolver {
                    querySelections("a")
                    fn { _, _, query, _, _ -> query.fetchAs<Int>("a") * 5 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val requested = sels!!
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (requested.containsField("Foo", "x")) put("x", 2)
                            if (requested.containsField("Foo", "y")) put("y", 3)
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout(
                """
                    query(${'$'}skipX: Boolean! = true) {
                      foo { x @skip(if: ${'$'}skipX) }
                      a
                    }
                """.trimIndent()
            ).assertJson("{data: {foo: {}, a: 6}}")
        }
    }

    @Test
    fun `node surplus does not override field resolver ownership`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            fieldWithValue("Foo" to "x", 3)

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                    createEngineObjectData(objectType, mapOf("x" to 2, "y" to 1))
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x y } }")
                .assertJson("{data: {foo: {x: 3, y: 1}}}")
        }
    }

    @Test
    fun `query node fields dispatch concrete selective resolvers`() {
        val fooId = GlobalIDCodecDefault.serialize("Foo", "id")
        val barId = GlobalIDCodecDefault.serialize("Bar", "id")

        MockTenantModuleBootstrapper(
            """
                type Foo implements Node { id: ID!, x: Int, y: Int }
                type Bar implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val requested = sels!!
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (requested.containsField("Foo", "x")) put("x", 2)
                        },
                    )
                }
            }

            type("Bar") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val requested = sels!!
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (requested.containsField("Bar", "x")) put("x", 3)
                        },
                    )
                }
            }

            field("Foo" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 5 }
                }
            }

            field("Bar" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 7 }
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout(
                """
                    {
                      node(id: "$fooId") {
                        __typename
                        ... on Foo { y }
                      }
                      nodes(ids: ["$fooId", "$barId"]) {
                        __typename
                        ... on Foo { y }
                        ... on Bar { y }
                      }
                    }
                """.trimIndent()
            ).assertJson(
                """
                    {
                      data: {
                        node: {__typename: "Foo", y: 10},
                        nodes: [
                          {__typename: "Foo", y: 10},
                          {__typename: "Bar", y: 21}
                        ]
                      }
                    }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `query node type checker materializes checker field`() {
        val fooId = GlobalIDCodecDefault.serialize("Foo", "id")

        MockTenantModuleBootstrapper("type Foo implements Node { id:ID!, x:Int }") {
            type("Foo") {
                checker {
                    objectSelections("fields", "x")
                    fn { _, objects ->
                        check(objects.getValue("fields").fetchAs<Int>("x") == 2)
                    }
                }
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) put("x", 2)
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ node(id: \"$fooId\") { __typename } }")
                .assertJson("{data: {node: {__typename: \"Foo\"}}}")
        }
    }

    @Test
    fun `recursive rss is materialized`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int, next: Foo }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("1", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("next { next { y } }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("next")
                            .fetchAs<EngineObjectData>("next")
                            .fetchAs<Int>("y")
                    }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { id, sels, ctx ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "next")) {
                                put(
                                    "next",
                                    ctx.createNodeReference(
                                        (id.toInt() + 1).toString(),
                                        objectType,
                                    ),
                                )
                            }
                            if (sels.containsField("Foo", "y")) {
                                put("y", id.toInt())
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 3}}}")
        }
    }

    @Test
    fun `recursive self reference reuses covering node cache entry`() {
        val nodeCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo implements Node { id:ID!, self:Foo }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, ctx ->
                    nodeCalls.incrementAndGet()
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "self")) {
                                put("self", ctx.createNodeReference("foo", objectType))
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { self { id } } }")
                .assertJson("{data: {foo: {self: {id: \"foo\"}}}}")
        }

        assertEquals(1, nodeCalls.get())
    }

    @Test
    fun `returned nested rss coverage reuses node source`() {
        val fooCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, z: Int, bar: Bar }
                type Bar { y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("bar { y }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("bar").fetchAs<Int>("y") * 3
                    }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    fooCalls.incrementAndGet()
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "z")) put("z", 1)
                            put(
                                "bar",
                                createEngineObjectData(schema.schema.getObjectType("Bar"), mapOf("y" to 2)),
                            )
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x z } }")
                .assertJson("{data: {foo: {x: 6, z: 1}}}")
        }

        assertEquals(1, fooCalls.get())
    }

    @Test
    fun `same node lists resolve different fields`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { barsA: [Bar], barsB: [Bar], x: Int @resolver }
                type Bar implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext {
                    createEngineObjectData(schema.schema.getObjectType("Foo")!!, emptyMap())
                }
            }

            field("Foo" to "barsA") {
                valueFromContext { ctx ->
                    val barType = schema.schema.getObjectType("Bar")!!
                    listOf(
                        ctx.createNodeReference("1", barType),
                        ctx.createNodeReference("2", barType),
                    )
                }
            }
            field("Foo" to "barsB") {
                valueFromContext { ctx ->
                    val barType = schema.schema.getObjectType("Bar")!!
                    listOf(
                        ctx.createNodeReference("1", barType),
                        ctx.createNodeReference("2", barType),
                    )
                }
            }
            field("Foo" to "x") {
                resolver {
                    objectSelections("barsA { x } barsB { y }")
                    fn { _, obj, _, _, _ ->
                        @Suppress("UNCHECKED_CAST")
                        val barsA = obj.fetchAs<List<EngineObjectData>>("barsA")
                        @Suppress("UNCHECKED_CAST")
                        val barsB = obj.fetchAs<List<EngineObjectData>>("barsB")

                        barsA[0].fetchAs<Int>("x") *
                            barsA[1].fetchAs<Int>("x") *
                            barsB[0].fetchAs<Int>("y") *
                            barsB[1].fetchAs<Int>("y")
                    }
                }
            }

            type("Bar") {
                nodeUnbatchedExecutor(selective = true) { id, sels, _ ->
                    val values = buildMap {
                        if (sels!!.containsField("Bar", "x")) {
                            put("x", if (id == "1") 2 else 3)
                        }
                        if (sels.containsField("Bar", "y")) {
                            put("y", if (id == "1") 5 else 7)
                        }
                    }

                    createEngineObjectData(objectType, values)
                }
            }
        }.toEngineTestModule().runContractTest(withoutDefaultQueryNodeResolvers = true) {
            withTimeout("{ foo { x } }")
                .assertJson("{ data: { foo: { x: 210 } } }")
        }
    }

    @Test
    fun `selective field can return selective node`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, ctx ->
                            ctx.createNodeReference("1", schema.schema.getObjectType("Foo")!!)
                        }
                    )
                }
            }
            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) {
                                put("x", 2)
                            }
                        }
                    )
                }
            }
            field("Foo" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { y } }")
                .assertJson("{data: {foo: {y: 6}}}")
        }
    }

    @Test
    fun `selective node materialization does not repeat type checker`() {
        val fooCheckerCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { ctx ->
                    ctx.createNodeReference("foo", schema.schema.getObjectType("Foo"))
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") + 1 }
                }
            }

            type("Foo") {
                checker {
                    fn { _, _ ->
                        fooCheckerCalls.incrementAndGet()
                    }
                }
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "y")) {
                                put("y", 1)
                            }
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(withoutDefaultQueryNodeResolvers = true) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 2}}}")
        }

        assertEquals(1, fooCheckerCalls.get())
    }

    @Test
    fun `skipped node plan does not shadow rss`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo, y: Int }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Query" to "y") {
                resolver {
                    objectSelections("foo { x y }")
                    fn { _, obj, _, _, _ ->
                        obj.fetch("foo")
                        2
                    }
                }
            }

            field("Foo" to "x") {
                resolver {
                    querySelections("foo { y }")
                    fn { _, _, query, _, _ ->
                        query.fetchAs<EngineObjectData>("foo").fetchAs<Int>("y") * 5
                    }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "y")) {
                                put("y", 3)
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout(
                """
                    query(${'$'}skipY: Boolean! = true) {
                      y @skip(if: ${'$'}skipY)
                      foo { x }
                    }
                """.trimIndent()
            ).assertJson("{data: {foo: {x: 15}}}")
        }
    }

    @Test
    fun `statically skipped fragment does not shadow active fragment during node materialization`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo, x: Int }
                type Foo implements Node { id: ID!, x: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Query" to "x") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS(
                            "Query",
                            """
                                fragment Main on Query {
                                  foo {
                                    ...Frag @include(if: false)
                                    ... on Foo { ...Frag }
                                  }
                                }

                                fragment Frag on Foo {
                                  x @include(if: true)
                                }
                            """.trimIndent()
                        ),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("foo").fetchAs<Int>("x")
                            1
                        }
                    )
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) put("x", 2)
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ x }")
                .assertJson("{data: {x: 1}}")
        }
    }

    @Test
    fun `type checker aliases field from selective node list`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foos:[Foo] }
                type Foo implements Node { id:ID!, x:Int }
            """.trimIndent()
        ) {
            field("Query" to "foos") {
                valueFromContext { ctx ->
                    listOf(
                        ctx.createNodeReference("1", schema.schema.getObjectType("Foo")!!),
                        ctx.createNodeReference("2", schema.schema.getObjectType("Foo")!!),
                    )
                }
            }

            type("Foo") {
                checker {
                    objectSelections("fields", "x, checked:x")
                    fn { _, objects ->
                        val fields = objects.getValue("fields")
                        assertEquals(
                            fields.fetchAs<Int>("x"),
                            fields.fetchAs<Int>("checked"),
                        )
                    }
                }
                nodeUnbatchedExecutor(selective = true) { id, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) {
                                put("x", id.toInt())
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foos { x } }")
                .assertJson("{data: {foos: [{x: 1}, {x: 2}]}}")
        }
    }

    @Test
    fun `type checker failure after selective node materialization is reported`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { ctx ->
                    ctx.createNodeReference("foo", schema.schema.getObjectType("Foo"))
                }
            }

            type("Foo") {
                checker {
                    fn { _, _ -> throw SecurityException("foo denied") }
                }
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "y")) {
                                put("y", 1)
                            }
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(withoutDefaultQueryNodeResolvers = true) {
            withTimeout("{ foo { y } }").assertMatches {
                "data" to {
                    "foo" to null
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*foo denied.*"
                        "path" to listOf("foo")
                    }
                )
            }
        }
    }

    @Test
    fun `type checker query rss does not rematerialize node recursively`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo implements Node { id:ID!, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            fieldWithValue("Foo" to "y", 1)

            type("Foo") {
                checker {
                    querySelections("query", "foo { y }")
                    querySelections("typename", "foo { __typename }")
                    fn { _, _ -> }
                }
                nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                    createEngineObjectData(objectType, emptyMap())
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { __typename } }")
                .assertJson("{data: {foo: {__typename: \"Foo\"}}}")
        }
    }

    @Test
    fun `type checker reads multiple node fields`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            type("Foo") {
                checker {
                    objectSelections("fields", "x y")
                    fn { _, objects ->
                        val fields = objects.getValue("fields")
                        check(fields.fetchAs<Int>("x") + fields.fetchAs<Int>("y") == 3)
                    }
                }
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val requested = sels!!
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (requested.containsField("Foo", "x")) {
                                put("x", 1)
                            }
                            if (requested.containsField("Foo", "y")) {
                                put("y", 2)
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { y } }")
                .assertJson("{data: {foo: {y: 2}}}")
        }
    }

    @Test
    fun `initial materialization resolves node reference`() {
        // This test captures the original node reference returned by a field resolver
        // and then later trying to read fields off of it after the node resolver has run
        // This isn't possible in a pure-Modern system, but is representative of how Classic
        // works.
        lateinit var nodeReference: NodeEngineObjectData

        MockTenantModuleBootstrapper(
            """
                | extend type Query { foo: Foo }
                | type Foo implements Node { id: ID!, x: Int }
            """.trimMargin()
        ) {
            field("Query" to "foo") {
                valueFromContext {
                    it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!)
                        .also { reference -> nodeReference = reference as NodeEngineObjectData }
                }
            }
            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                    createEngineObjectData(objectType, mapOf("x" to 2))
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 2}}}")
        }

        runTest {
            assertEquals("foo", nodeReference.fetchAs<String>("id"))
            assertEquals(2, withTimeout(1.seconds) { nodeReference.fetchAs<Int>("x") })
        }
    }

    @Test
    fun `reused node reference resolves newly selected fields`() {
        var nodeReference: NodeEngineObjectData? = null

        MockTenantModuleBootstrapper(
            """
                extend type Query { x:Int, foo2:Foo, foo1:Foo }
                type Foo implements Node { id:ID!, x:Int!, y:Int! }
            """.trimIndent()
        ) {
            field("Query" to "foo1") {
                valueFromContext { ctx ->
                    nodeReference ?: (ctx.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) as NodeEngineObjectData)
                        .also { nodeReference = it }
                }
            }

            field("Query" to "foo2") {
                resolver {
                    // This materializes y, then returns the same reference through a second node ledger.
                    objectSelections("foo1 { y }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("foo1").fetchAs<Int>("y")
                        checkNotNull(nodeReference)
                    }
                }
            }

            field("Query" to "x") {
                resolver {
                    objectSelections("foo2 { x }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("foo2").fetchAs<Int>("x")
                    }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) {
                                put("x", 2)
                            }
                            if (sels.containsField("Foo", "y")) {
                                put("y", 3)
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest(withoutDefaultQueryNodeResolvers = true) {
            withTimeout("{ x }")
                .assertJson("{data: {x: 2}}")
        }

        runTest {
            assertEquals(3, checkNotNull(nodeReference).fetchAs<Int>("y"))
            assertEquals(2, checkNotNull(nodeReference).fetchAs<Int>("x"))
        }
    }

    @Test
    fun `node selected in rss is not shadowed by skipped fragment in another rss`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { b: Int, foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int, z: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Query" to "b") {
                resolver {
                    objectSelections("foo { y x }")
                    fn { _, obj, _, _, _ ->
                        val foo = obj.fetchAs<EngineObjectData>("foo")
                        foo.fetchAs<Int>("y") * foo.fetchAs<Int>("x")
                    }
                }
            }

            field("Foo" to "x") {
                resolver {
                    querySelections("foo { z }")
                    fn { _, _, query, _, _ ->
                        query.fetchAs<EngineObjectData>("foo").fetchAs<Int>("z") * 5
                    }
                }
            }

            field("Foo" to "z") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }

            field("Foo" to "y") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        querySelectionSet = createRSS(
                            "Query",
                            """
                                fragment Main on Query {
                                  foo { ...Frag @skip(if: true) }
                                }

                                fragment Frag on Foo { z }
                            """.trimIndent()
                        ),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, query, _, _ ->
                            query.fetchAs<EngineObjectData>("foo")
                            2
                        }
                    )
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                    createEngineObjectData(objectType, emptyMap())
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ b }")
                .assertJson("{data: {b: 60}}")
        }
    }

    @Test
    fun `missing nested rss rematerializes node source`() {
        val fooCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, bar: Bar }
                type Bar { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Bar" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    fooCalls.incrementAndGet()
                    val barSelections =
                        sels!!.selectionSetForField("Foo", "bar")
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "bar" to
                                createEngineObjectData(
                                    schema.schema.getObjectType("Bar")!!,
                                    buildMap {
                                        if (barSelections.containsField("Bar", "x")) {
                                            put("x", 2)
                                        }
                                    },
                                )
                        ),
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { bar { y } } }")
                .assertJson("{data: {foo: {bar: {y: 6}}}}")
        }

        assertEquals(2, fooCalls.get())
    }

    @Test
    fun `surplus coverage uses values from the first covering result`() {
        val resultNumber = AtomicInteger()
        val firstResultConsumed = CompletableDeferred<Unit>()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id:ID!, x:Int, y:Int, z:Int, w:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("z")
                    fn { _, obj, _, _, _ ->
                        val z = obj.fetchAs<Int>("z")
                        firstResultConsumed.complete(Unit)
                        z * 5
                    }
                }
            }

            field("Foo" to "y") {
                resolver {
                    objectSelections("w")
                    fn { _, obj, _, _, _ ->
                        val w = obj.fetchAs<Int>("w")
                        firstResultConsumed.complete(Unit)
                        w * 7
                    }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                    createEngineObjectData(
                        objectType,
                        when (resultNumber.getAndIncrement()) {
                            0 -> emptyMap()
                            1 -> mapOf("z" to 2, "w" to 3)
                            else -> {
                                firstResultConsumed.await()
                                mapOf("z" to 4, "w" to 5)
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x y } }")
                .assertJson("{data: {foo: {x: 10, y: 21}}}")
        }
    }

    @Test
    fun `changed embedded list size leaves unmatched items unresolved`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo implements Node { id:ID!, bars:[Bar] }
                type Bar { x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val includeY = sels!!
                        .selectionSetForField("Foo", "bars")
                        .containsField("Bar", "y")
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "bars" to if (includeY) {
                                listOf(createEngineObjectData(schema.schema.getObjectType("Bar")!!, mapOf("y" to 2)))
                            } else {
                                listOf(
                                    createEngineObjectData(schema.schema.getObjectType("Bar")!!, emptyMap()),
                                    createEngineObjectData(schema.schema.getObjectType("Bar")!!, emptyMap()),
                                )
                            }
                        ),
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { bars { x } } }").assertMatches {
                "data" to {
                    "foo" to {
                        "bars" to arrayOf(
                            { "x" to "6" },
                            { "x" to null },
                        )
                    }
                }
                "errors" to arrayOf(
                    {
                        "path" to listOf("foo", "bars", "1", "x")
                    }
                )
            }
        }
    }

    @Test
    fun `embedded list item type changes during refetch report a field error`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, bars: [Bar] }
                union Bar = Baz | Qux
                type Baz { x: Int, y: Int }
                type Qux { y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Baz" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val ySelected = sels!!
                        .selectionSetForField("Foo", "bars")
                        .containsField("Baz", "y")
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "bars" to listOf(
                                if (ySelected) {
                                    createEngineObjectData(schema.schema.getObjectType("Qux")!!, mapOf("y" to 2))
                                } else {
                                    createEngineObjectData(schema.schema.getObjectType("Baz")!!, emptyMap())
                                }
                            )
                        ),
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout(
                "{ foo { bars { ... on Baz { x } } } }"
            ).assertMatches {
                "data" to {
                    "foo" to {
                        "bars" to arrayOf(
                            { "x" to null },
                        )
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*expected object of type `Baz`, found `Qux`.*"
                        "path" to listOf("foo", "bars", "0", "x")
                    }
                )
            }
        }
    }

    @Test
    fun `client and rss arguments remain isolated`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y(z: Int!): Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y(z: 2)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val ySelection = sels!!
                        .selections()
                        .single { it.fieldName == "y" }
                    val z = sels
                        .argumentsOfSelection("Foo", ySelection.selectionName)
                        ?.get("z") as Int
                    createEngineObjectData(objectType, mapOf("y" to z))
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout(
                "query(${'$'}z: Int!) { foo { y(z: ${'$'}z) x } }",
                variables = mapOf("z" to 1),
            ).assertJson("{data: {foo: {y: 1, x: 6}}}")
        }
    }

    @Test
    fun `rss variable failure reports a field error`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y(z: Int!): Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y(z: ${'$'}z)") {
                        variables("z") { _, _ ->
                            error("rss variables resolver failed")
                        }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "y")) {
                                put("y", 2)
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x } }").assertMatches {
                "data" to {
                    "foo" to {
                        "x" to null
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*rss variables resolver failed.*"
                        "path" to listOf("foo", "x")
                    }
                )
            }
        }
    }

    @Test
    fun `embedded node materialization preserves ancestor argument variables`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo implements Node { id:ID!, bar(y:Int!): Bar }
                type Bar { x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val requested = sels!!
                    val barSelections = requested.selectionSetForField("Foo", "bar")
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "bar" to createEngineObjectData(
                                schema.schema.getObjectType("Bar")!!,
                                buildMap {
                                    if (barSelections.containsField("Bar", "y")) {
                                        put(
                                            "y",
                                            requested
                                                .argumentsOfSelection("Foo", "bar")
                                                ?.get("y") as Int,
                                        )
                                    }
                                },
                            )
                        ),
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout(
                "query(\$y: Int!) { foo { bar(y: \$y) { x } } }",
                variables = mapOf("y" to 2),
            ).assertJson("{data: {foo: {bar: {x: 10}}}}")
        }
    }

    @Test
    fun `node owned sibling supplies required rss variable`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo implements Node { id:ID!, bar:Bar }
                type Bar { x:Int, y(z:Int!): Int, z:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y(z:\$z)") {
                        variables(
                            "z",
                            rss = createRSS("Bar", "z"),
                        ) { ctx, _ ->
                            mapOf("z" to ctx.objectData.fetchAs<Int>("z"))
                        }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val barSelections =
                        sels!!.selectionSetForField("Foo", "bar")
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "bar" to createEngineObjectData(
                                schema.schema.getObjectType("Bar")!!,
                                buildMap {
                                    if (barSelections.containsField("Bar", "z")) {
                                        put("z", 2)
                                    }
                                    if (barSelections.containsField("Bar", "y")) {
                                        val z = barSelections
                                            .argumentsOfSelection("Bar", "y")
                                            ?.get("z") as Int
                                        put("y", z * 3)
                                    }
                                },
                            )
                        ),
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { bar { x } } }")
                .assertJson("{data: {foo: {bar: {x: 30}}}}")
        }
    }

    @Test
    fun `embedded node materialization preserves fragment argument variables`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, bar(y: Int!): Bar }
                type Bar { x: Int, y(x: Int!): Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y(x: \$x)") {
                        variables("x") { _, _ -> mapOf("x" to 3) }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val requested = sels!!
                    val barSelections = requested.selectionSetForField("Foo", "bar")
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "bar" to createEngineObjectData(
                                schema.schema.getObjectType("Bar")!!,
                                buildMap {
                                    if (barSelections.containsField("Bar", "y")) {
                                        val y = requested
                                            .argumentsOfSelection("Foo", "bar")
                                            ?.get("y") as Int
                                        val x = barSelections
                                            .argumentsOfSelection("Bar", "y")
                                            ?.get("x") as Int
                                        put("y", x * y)
                                    }
                                },
                            )
                        ),
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout(
                """
                    query(${"$"}x: Int!) {
                      foo { ...FooFields }
                    }

                    fragment FooFields on Foo {
                      bar(y: ${"$"}x) { x }
                    }
                """.trimIndent(),
                variables = mapOf("x" to 2),
            ).assertJson("{data: {foo: {bar: {x: 30}}}}")
        }
    }

    @Test
    fun `type changes during refetch report a field error`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int, z: Int }
                type Bar { z: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "y") {
                resolver {
                    objectSelections("z")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("z") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    if (sels!!.containsField("Foo", "z")) {
                        createEngineObjectData(schema.schema.getObjectType("Bar")!!, mapOf("z" to 2))
                    } else {
                        createEngineObjectData(objectType, mapOf("x" to 1))
                    }
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x y } }").assertMatches {
                "data" to {
                    "foo" to {
                        "x" to "1"
                        "y" to null
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*expected object of type `Foo`, found `Bar`.*"
                        "path" to listOf("foo", "y")
                    }
                )
            }
        }
    }

    @Test
    fun `inconsistent resolver exceptions`() {
        val fooCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
            extend type Query { foo: Foo }
            type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { ctx ->
                    ctx.createNodeReference("foo", schema.schema.getObjectType("Foo"))
                }
            }
            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("y") + 1
                    }
                }
            }
            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    if (fooCalls.incrementAndGet() == 2) {
                        throw RuntimeException("foo node second materialization failed")
                    }
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "y")) {
                                put("y", 2)
                            }
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(withoutDefaultQueryNodeResolvers = true) {
            withTimeout("{ foo { x } }").assertMatches {
                "data" to {
                    "foo" to {
                        "x" to null
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*failed when materialized.*"
                        "path" to listOf("foo", "x")
                    }
                )
            }
        }

        assertEquals(2, fooCalls.get())
    }

    @Test
    fun `nested object type changes during refetch report a field error`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, bar: Bar }
                union Bar = Baz | Qux
                type Baz { x: Int, y: Int }
                type Qux { z: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Baz" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ ->
                        (obj.fetchOrNull("x") as? Int)?.times(3)
                    }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val barSelections =
                        sels!!.selectionSetForField("Foo", "bar")
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "bar" to if (barSelections.containsField("Baz", "x")) {
                                createEngineObjectData(schema.schema.getObjectType("Qux")!!, emptyMap())
                            } else {
                                createEngineObjectData(schema.schema.getObjectType("Baz")!!, emptyMap())
                            }
                        ),
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout(
                "{ foo { bar { ... on Baz { y } } } }"
            ).assertMatches {
                "data" to {
                    "foo" to {
                        "bar" to {
                            "y" to null
                        }
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*expected object of type `Baz`, found `Qux`.*"
                        "path" to listOf("foo", "bar", "y")
                    }
                )
            }
        }
    }

    @Test
    fun `malformed nested object during refetch reports a field error`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo implements Node { id:ID!, bar:Bar }
                type Bar { x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val barSelections =
                        sels!!.selectionSetForField("Foo", "bar")
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "bar" to if (barSelections.containsField("Bar", "y")) {
                                2
                            } else {
                                createEngineObjectData(schema.schema.getObjectType("Bar")!!, emptyMap())
                            }
                        ),
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { bar { x } } }").assertMatches {
                "data" to {
                    "foo" to {
                        "bar" to {
                            "x" to null
                        }
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*failed when materialized.*"
                        "path" to listOf("foo", "bar", "x")
                    }
                )
            }
        }
    }

    @Test
    fun `node fetch instrumentation covers required fields`() {
        val instrumentation = RecordingInstrumentation()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { ctx ->
                    ctx.createNodeReference("foo", schema.schema.getObjectType("Foo"))
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") + 1 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "y")) {
                                put("y", 1)
                            }
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(
            withoutDefaultQueryNodeResolvers = true,
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = instrumentation,
            )
        ) {
            withTimeout("query FetchFoo { foo { x } }")
                .assertJson("{data: {foo: {x: 2}}}")
        }

        val fooNodeFetchingContexts = instrumentation.nodeFetchingContexts
            .filter {
                (it.parameters as InstrumentNodeFetchingParameters).resolverMetadata?.name == "Node:Foo"
            }

        assertEquals(2, fooNodeFetchingContexts.size)
        assertTrue(fooNodeFetchingContexts.all { it.onDispatchedCalled.get() }) {
            "Expected every Foo node fetching context to be dispatched"
        }
        assertTrue(fooNodeFetchingContexts.all { it.onCompletedCalled.get() }) {
            "Expected every Foo node fetching context to be completed"
        }

        assertEquals(
            setOf(ExecutionAttribution.Type.OPERATION, ExecutionAttribution.Type.RESOLVER),
            fooNodeFetchingContexts
                .map { (it.parameters as InstrumentNodeFetchingParameters).requiredBy?.type }
                .toSet(),
        )
    }

    @Test
    fun `mat backed traversal keeps the node reference as its source`() {
        val instrumentation = RecordingInstrumentation()
        lateinit var nodeReference: Any

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { ctx ->
                    ctx.createNodeReference("foo", schema.schema.getObjectType("Foo")!!)
                        .also { nodeReference = it }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                    createEngineObjectData(objectType, mapOf("x" to 2))
                }
            }
        }.toEngineTestModule().runContractTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = instrumentation,
            )
        ) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 2}}}")
        }

        val xEnvironment = instrumentation.dataFetchingEnvironments.single {
            it.executionStepInfo.path.toString() == "/foo/x"
        }
        assertSame(nodeReference, xEnvironment.getSource<Any>())
    }

    @Test
    fun `node refetch failure completes instrumentation with error`() {
        val instrumentation = RecordingInstrumentation()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    if (sels!!.containsField("Foo", "y")) {
                        error("node refetch failed")
                    }
                    createEngineObjectData(objectType, emptyMap())
                }
            }
        }.toEngineTestModule().runContractTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = instrumentation,
            )
        ) {
            withTimeout("{ foo { x } }").assertMatches {
                "data" to {
                    "foo" to {
                        "x" to null
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*node refetch failed.*"
                        "path" to listOf("foo", "x")
                    }
                )
            }
        }

        val fooContexts = instrumentation.nodeFetchingContexts.filter {
            (it.parameters as InstrumentNodeFetchingParameters).resolverMetadata?.name == "Node:Foo"
        }
        assertEquals(2, fooContexts.size)
        assertEquals(1, fooContexts.count { it.completedException != null })
        assertTrue(
            fooContexts
                .mapNotNull { it.completedException?.message }
                .any { it.contains("node refetch failed") }
        )
    }

    @Test
    fun `whole batch exception is reported for every selector`() {
        val batches = mutableListOf<List<String>>()

        MockTenantModuleBootstrapper(
            """
                extend type Query { a: Foo, b: Foo }
                type Foo implements Node { id: ID!, x: Int, foo: Foo }
            """.trimIndent()
        ) {
            field("Query" to "a") {
                valueFromContext { it.createNodeReference("a", schema.schema.getObjectType("Foo")!!) }
            }

            field("Query" to "b") {
                valueFromContext { it.createNodeReference("b", schema.schema.getObjectType("Foo")!!) }
            }

            type("Foo") {
                nodeBatchedExecutor(selective = true) { selectors, ctx ->
                    batches += selectors.map { it.id }.sorted()
                    if (selectors.any { it.id.startsWith("nested-") }) {
                        throw IllegalStateException("batch failed")
                    }

                    selectors.associateWith { selector ->
                        Result.success(
                            createEngineObjectData(
                                objectType,
                                buildMap {
                                    if (selector.selections.containsField("Foo", "foo")) {
                                        put(
                                            "foo",
                                            ctx.createNodeReference("nested-${selector.id}", objectType)
                                        )
                                    }
                                }
                            )
                        )
                    }
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ a { foo { x } } b { foo { x } } }").assertMatches {
                "data" to {
                    "a" to { "foo" to null }
                    "b" to { "foo" to null }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*batch failed.*"
                        "path" to listOf("a", "foo")
                    },
                    {
                        "message" to ".*batch failed.*"
                        "path" to listOf("b", "foo")
                    }
                )
            }
        }

        assertEquals(
            listOf(
                listOf("a", "b"),
                listOf("nested-a", "nested-b"),
            ),
            batches,
        )
    }

    @Test
    fun `node resolver can query during refetch`() {
        val nodeCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { a: Int, foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "a", 2)

            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, ctx ->
                    nodeCalls.incrementAndGet()
                    val querySelections = ctx.engineSelectionSetFactory
                        .engineSelectionSet("Query", "a", emptyMap())
                    val a = ctx.query(querySelections).fetchAs<Int>("a")
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "y")) {
                                put("y", a)
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }

        assertEquals(2, nodeCalls.get())
    }

    @Test
    fun `rss preserves resolver directives`() {
        MockTenantModuleBootstrapper(
            """
                directive @matMarker on FIELD
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("foo", schema.schema.getObjectType("Foo")!!) }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y @matMarker")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.printAsFieldSet().contains("@matMarker")) {
                                put("y", 2)
                            }
                        },
                    )
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }
    }
}
