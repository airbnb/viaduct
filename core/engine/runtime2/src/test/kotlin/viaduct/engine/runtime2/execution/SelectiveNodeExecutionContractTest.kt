package viaduct.engine.runtime2.execution

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.VariableFromFieldDefinitions
import viaduct.engine.runtime.execution.SelectiveNodeExecutionContract
import viaduct.engine.runtime.execution.query
import viaduct.engine.runtime2.execution.testing.runRuntime2FeatureTest
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.graphql.test.assertMatches

class SelectiveNodeExecutionContractTest : SelectiveNodeExecutionContract, Engine2QueryExecutionContract {
    @Test
    fun `selective node resolvers receive each input's requested fields and dependencies`() {
        val receivedNodeOwnedSelections = ConcurrentLinkedQueue<Pair<String, Set<String>>>()
        val module = selectiveProductNodes(
            // The node resolver supplies url and caption; the separate label resolver requires caption.
            nodeOwnedFields = setOf("url", "caption"),
            resolveProduct = { id, requestedNodeOwnedFields ->
                receivedNodeOwnedSelections += id to requestedNodeOwnedFields
                mapOf("url" to "url-$id", "caption" to "caption-$id")
                    .filterKeys { it in requestedNodeOwnedFields }
            },
            labelRequiredSelections = "caption",
            resolveLabel = { product -> "label:${product.get("caption")}" },
        )

        module.runRuntime2FeatureTest {
            val result = runQueryWithTimeout(
                """
                {
                  first: product(id: "1") { url label }
                  second: product(id: "2") { url }
                }
                """.trimIndent(),
            )

            result.assertJson(
                """
                {
                  "data": {
                    "first": {"url": "url-1", "label": "label:caption-1"},
                    "second": {"url": "url-2"}
                  }
                }
                """.trimIndent(),
            )
            assertEquals(
                listOf(
                    "1" to setOf("url", "caption"),
                    "2" to setOf("url"),
                ),
                receivedNodeOwnedSelections.sortedBy { it.first },
            )
        }
    }

    private fun selectiveProductNodes(
        nodeOwnedFields: Set<String>,
        resolveProduct: suspend (String, Set<String>) -> Map<String, Any?>,
        labelRequiredSelections: String,
        resolveLabel: suspend (EngineObjectData.Sync) -> String,
    ): EngineTestModule =
        EngineTestModule(
            """
            extend type Query {
                product(id: ID!): Product @resolver
            }
            type Product implements Node {
                id: ID!
                url: String
                caption: String
                label: String @resolver
            }
            """.trimIndent(),
        ) {
            field("Query" to "product") {
                resolver {
                    fn { arguments, _, _, _, context ->
                        context.createNodeReference(arguments.getValue("id") as String, schema.schema.getObjectType("Product"))
                    }
                }
            }
            type("Product") {
                nodeBatchedExecutor(selective = true) { selectors, _ ->
                    selectors.reversed().associateWith { selector ->
                        val requestedNodeOwnedFields = selector.selections.selections().map { it.fieldName }.toSet().intersect(nodeOwnedFields)
                        Result.success(createEngineObjectData("Product", resolveProduct(selector.id, requestedNodeOwnedFields)))
                    }
                }
            }
            field("Product" to "label") {
                resolver {
                    objectSelections(labelRequiredSelections)
                    fn { _, product, _, _, _ -> resolveLabel(product) }
                }
            }
        }

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "The runtime engine reserves selective node surplus for standard field resolvers; Runtime2 uses source-sensitive ownership for argumentless fields"
    )
    override fun `node surplus does not override field resolver ownership`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production mutates the original node reference after fetching; runtime2 publishes the same response from a closed one-shot occurrence"
    )
    override fun `initial materialization resolves node reference`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production accumulates fields on one reused mutable node reference; runtime2 resolves fresh closed reference occurrences"
    )
    override fun `reused node reference resolves newly selected fields`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "Registry rejects structural resolver cycles even when an RSS edge is statically skipped")
    override fun `node selected in rss is not shadowed by skipped fragment in another rss`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production refetches after discovering nested RSS demand; runtime2 supplies the complete nested demand in one node call"
    )
    override fun `missing nested rss rematerializes node source`() {}

    @Engine2Disabled(Engine2DisabledKind.ALT, "Production selects values from successive cache-covering results; runtime2 publishes one stable producer result")
    override fun `surplus coverage uses values from the first covering result`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production reconciles different list sizes across refetches; runtime2 publishes one stable list with the same item results"
    )
    override fun `changed embedded list size leaves unmatched items unresolved`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production detects embedded-list type drift between node fetches; one-shot runtime2 can preserve the same consumer error but has no second type"
    )
    override fun `embedded list item type changes during refetch report a field error`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production asks a selective node owner for argument-bearing fields in separate materializations; runtime2 resolves those fields actively by key"
    )
    override fun `client and rss arguments remain isolated`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production asks the selective node owner to supply an argument-bearing RSS field; runtime2 resolves that field actively"
    )
    override fun `rss variable failure reports a field error`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production asks selective node output to own an argument-bearing embedded field; runtime2 resolves that field actively"
    )
    override fun `embedded node materialization preserves ancestor argument variables`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Uses a callback-owned RSS and selective node execution for a conditional dependency chain; runtime2 uses a declarative provider and resolves the node once through its supported non-selective executor"
    )
    override fun `variable rss does not use skipped child object plan`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Uses a callback-owned RSS and asks a selective node owner to supply an argument-bearing field; runtime2 uses a declarative provider, an active argument-bearing field, and one non-selective node application"
    )
    override fun `node owned sibling supplies required rss variable`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production asks selective node output to own nested argument-bearing fields; runtime2 resolves those fields actively"
    )
    override fun `embedded node materialization preserves fragment argument variables`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production detects top-level type drift between node fetches; one-shot runtime2 preserves the same consumer error from one stable type"
    )
    override fun `type changes during refetch report a field error`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production compares exceptions across node refetches; one-shot runtime2 preserves the same consumer failure from one stable producer"
    )
    override fun `inconsistent resolver exceptions`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production detects nested type drift between node fetches; one-shot runtime2 preserves the same consumer error from one stable nested type"
    )
    override fun `nested object type changes during refetch report a field error`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production detects malformed nested replacement data during refetch; one-shot runtime2 preserves the same consumer failure from stable data"
    )
    override fun `malformed nested object during refetch reports a field error`() {}

    @Engine2Disabled(Engine2DisabledKind.NA, "Production node-fetch instrumentation is outside the runtime2 resolver-correctness boundary")
    override fun `node fetch instrumentation covers required fields`() {}

    @Engine2Disabled(Engine2DisabledKind.NA, "Production instrumentation source identity is outside the runtime2 resolver-correctness boundary")
    override fun `mat backed traversal keeps the node reference as its source`() {}

    @Engine2Disabled(Engine2DisabledKind.NA, "Production node-fetch instrumentation is outside the runtime2 resolver-correctness boundary")
    override fun `node refetch failure completes instrumentation with error`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "Batching")
    override fun `whole batch exception is reported for every selector`() {}

    @Engine2Disabled(Engine2DisabledKind.ALT, "Resolution invokes one closed selective-node producer rather than production refetch")
    override fun `node resolver can query during refetch`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "Directive: Canonical demand conversion does not preserve custom resolver-directive spelling")
    override fun `rss preserves resolver directives`() {}

    @Nested
    inner class BasicExecutionTests {
        @Engine2Alternate(original = "node surplus does not override field resolver ownership")
        fun `selective node output owns supplied argumentless registered fields`() {
            val standardCalls = AtomicInteger()
            MockTenantModuleBootstrapper(
                "extend type Query { foo: Foo } type Foo implements Node { id: ID!, x: Int, y: Int }",
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }
                field("Foo" to "x") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            standardCalls.incrementAndGet()
                            3
                        }
                    }
                }
                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        createEngineObjectData(objectType, mapOf("x" to 2, "y" to 1))
                    }
                }
            }.runRuntime2FeatureTest {
                runQuery("{ foo { x y } }").assertJson("{data: {foo: {x: 2, y: 1}}}")
            }
            assertEquals(0, standardCalls.get())
        }

        @Test
        fun `engine-managed node fields do not invoke selective resolver`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID! }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("Foo:1", objectType("Foo")) }
                }
                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ -> error("Node resolver should not run") }
                }
            }.runRuntime2FeatureTest {
                runQuery("{ foo { id __typename } }")
                    .assertJson("{data: {foo: {id: \"Foo:1\", __typename: \"Foo\"}}}")
            }
        }

        @Engine2Disabled(
            Engine2DisabledKind.ALT,
            "Production performs an initial fetch and a path-specific refetch; runtime2 supplies their concrete path union once"
        )
        fun `materialization output selections preserve path-specific concrete ownership`() {
            // The Root node resolver owns different leaves below foo and bar based on the concrete
            // Abstract implementation, so flat field coordinates cannot represent its selections.
            val rootCalls = AtomicInteger()
            var materializationSelections: EngineSelectionSet? = null

            MockTenantModuleBootstrapper(
                """
                    extend type Query { root: Root }
                    type Root implements Node { id: ID!, value: Int @resolver, foo: Abstract, bar: Abstract }
                    interface Abstract { x: Int, y: Int }
                    type Impl1 implements Abstract { x: Int, y: Int @resolver }
                    type Impl2 implements Abstract { x: Int @resolver, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "root") {
                    valueFromContext { ctx ->
                        ctx.createNodeReference("root", schema.schema.getObjectType("Root")!!)
                    }
                }

                type("Root") {
                    nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                        rootCalls.incrementAndGet()
                        val sel = sels!!
                        val values = buildMap {
                            if (sel.containsField("Root", "foo")) {
                                put(
                                    "foo",
                                    createEngineObjectData("Impl1", mapOf("x" to 2))
                                )
                            }

                            if (sel.containsField("Root", "bar")) {
                                put(
                                    "bar",
                                    createEngineObjectData("Impl2", mapOf("y" to 3))
                                )
                            }
                        }
                        if (values.isNotEmpty()) {
                            materializationSelections = sel
                        }
                        createEngineObjectData(objectType, values)
                    }
                }

                field("Root" to "value") {
                    resolver {
                        objectSelections(
                            """
                                foo { x }
                                bar { y }
                            """.trimIndent()
                        )
                        fn { _, obj, _, _, _ ->
                            val fooAbstract = obj.fetchAs<EngineObjectData>("foo")
                            val barAbstract = obj.fetchAs<EngineObjectData>("bar")
                            fooAbstract.fetchAs<Int>("x") * barAbstract.fetchAs<Int>("y")
                        }
                    }
                }

                field("Impl1" to "y") {
                    resolver { fn { _, _, _, _, _ -> -1 } }
                }

                field("Impl2" to "x") {
                    resolver { fn { _, _, _, _, _ -> -1 } }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ root { value } }")
                    .assertJson("{data: {root: {value: 6}}}")
            }

            assertEquals(2, rootCalls.get())
            val selections = checkNotNull(materializationSelections)

            assertEquals(
                mapOf(
                    "foo" to setOf(EngineSelection("Impl1", "x", "x")),
                    "bar" to setOf(EngineSelection("Impl2", "y", "y")),
                ),
                mapOf(
                    "foo" to
                        selections
                            .selectionSetForField("Root", "foo")
                            .selections().toSet(),
                    "bar" to
                        selections
                            .selectionSetForField("Root", "bar")
                            .selections().toSet(),
                ),
            )
        }

        @Engine2Alternate(original = "materialization output selections preserve path-specific concrete ownership")
        fun `ALTERNATIVE materialization output selections preserve path-specific concrete ownership`() {
            val rootCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { root: Root }
                    type Root implements Node { id: ID!, value: Int @resolver, foo: Abstract, bar: Abstract }
                    interface Abstract { x: Int, y: Int }
                    type Impl1 implements Abstract { x: Int, y: Int @resolver }
                    type Impl2 implements Abstract { x: Int @resolver, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "root") {
                    valueFromContext { ctx ->
                        ctx.createNodeReference("root", schema.schema.getObjectType("Root")!!)
                    }
                }

                type("Root") {
                    nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                        rootCalls.incrementAndGet()
                        val sel = sels!!
                        createEngineObjectData(
                            objectType,
                            buildMap {
                                if (sel.containsField("Root", "foo")) {
                                    put("foo", createEngineObjectData("Impl1", mapOf("x" to 2)))
                                }
                                if (sel.containsField("Root", "bar")) {
                                    put("bar", createEngineObjectData("Impl2", mapOf("y" to 3)))
                                }
                            },
                        )
                    }
                }

                field("Root" to "value") {
                    resolver {
                        objectSelections(
                            """
                                foo { x }
                                bar { y }
                            """.trimIndent()
                        )
                        fn { _, obj, _, _, _ ->
                            val fooAbstract = obj.fetchAs<EngineObjectData>("foo")
                            val barAbstract = obj.fetchAs<EngineObjectData>("bar")
                            fooAbstract.fetchAs<Int>("x") * barAbstract.fetchAs<Int>("y")
                        }
                    }
                }

                field("Impl1" to "y") {
                    resolver { fn { _, _, _, _, _ -> -1 } }
                }

                field("Impl2" to "x") {
                    resolver { fn { _, _, _, _, _ -> -1 } }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ root { value } }")
                    .assertJson("{data: {root: {value: 6}}}")
            }

            assertEquals(1, rootCalls.get())
        }

        @Engine2Alternate(original = "initial materialization resolves node reference")
        fun `ALTERNATIVE initial materialization resolves node reference`() {
            MockTenantModuleBootstrapper(
                """
                    | extend type Query { foo: Foo }
                    | type Foo implements Node { id: ID!, x: Int }
                """.trimMargin()
            ) {
                field("Query" to "foo") {
                    valueFromContext {
                        it.createNodeReference("foo", objectType("Foo"))
                    }
                }
                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        createEngineObjectData(objectType, mapOf("x" to 2))
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 2}}}")
            }
        }

        @Engine2Alternate(original = "reused node reference resolves newly selected fields")
        fun `ALTERNATIVE reused node reference resolves newly selected fields`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { x:Int, foo2:Foo, foo1:Foo }
                    type Foo implements Node { id:ID!, x:Int!, y:Int! }
                """.trimIndent()
            ) {
                field("Query" to "foo1") {
                    valueFromContext { ctx ->
                        ctx.createNodeReference("foo", objectType("Foo"))
                    }
                }

                field("Query" to "foo2") {
                    resolver {
                        objectSelections("foo1 { y }")
                        fn { _, obj, _, _, ctx ->
                            obj.fetchAs<EngineObjectData>("foo1").fetchAs<Int>("y")
                            ctx.createNodeReference("foo", schema.schema.getObjectType("Foo"))
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
            }.runRuntime2FeatureTest(withoutDefaultQueryNodeResolvers = true) {
                runQueryWithTimeout("{ x }")
                    .assertJson("{data: {x: 2}}")
            }
        }
    }

    @Nested
    inner class CoverageTests {
        @Engine2Alternate(original = "missing nested rss rematerializes node source")
        fun `ALTERNATIVE missing nested rss rematerializes node source`() {
            val fooCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID!, bar: Bar }
                    type Bar { x: Int, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
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
                                        "Bar",
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { bar { y } } }")
                    .assertJson("{data: {foo: {bar: {y: 6}}}}")
            }

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "surplus coverage uses values from the first covering result")
        fun `ALTERNATIVE surplus coverage uses values from the first covering result`() {
            val nodeCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id:ID!, x:Int, y:Int, z:Int, w:Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("z")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("z") * 5 }
                    }
                }

                field("Foo" to "y") {
                    resolver {
                        objectSelections("w")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("w") * 7 }
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                        nodeCalls.incrementAndGet()
                        createEngineObjectData(
                            objectType,
                            buildMap {
                                if (sels!!.containsField("Foo", "z")) put("z", 2)
                                if (sels.containsField("Foo", "w")) put("w", 3)
                            },
                        )
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x y } }")
                    .assertJson("{data: {foo: {x: 10, y: 21}}}")
            }

            assertEquals(1, nodeCalls.get())
        }
    }

    @Nested
    inner class ListTests {
        @Engine2Alternate(original = "changed embedded list size leaves unmatched items unresolved")
        fun `ALTERNATIVE changed embedded list size leaves unmatched items unresolved`() {
            val nodeCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo:Foo }
                    type Foo implements Node { id:ID!, bars:[Bar] }
                    type Bar { x:Int, y:Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Bar" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        nodeCalls.incrementAndGet()
                        createEngineObjectData(
                            objectType,
                            mapOf(
                                "bars" to
                                    listOf(
                                        createEngineObjectData("Bar", mapOf("y" to 2)),
                                        createEngineObjectData(
                                            "Bar",
                                            mapOf(
                                                "y" to
                                                    EngineErrorData.of(
                                                        IllegalStateException("unmatched list item"),
                                                    )
                                            ),
                                        ),
                                    )
                            ),
                        )
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { bars { x } } }").assertMatches {
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

            assertEquals(1, nodeCalls.get())
        }

        @Engine2Alternate(original = "embedded list item type changes during refetch report a field error")
        fun `ALTERNATIVE embedded list item type changes during refetch report a field error`() {
            val nodeCalls = AtomicInteger()

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
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Baz" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, _, _, _, _ ->
                            error("expected object of type `Baz`, found `Qux`")
                        }
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        nodeCalls.incrementAndGet()
                        createEngineObjectData(
                            objectType,
                            mapOf(
                                "bars" to
                                    listOf(
                                        createEngineObjectData("Baz", mapOf("y" to 2))
                                    )
                            ),
                        )
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
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

            assertEquals(1, nodeCalls.get())
        }
    }

    @Nested
    inner class VariablesTests {
        @Engine2Alternate(original = "client and rss arguments remain isolated")
        fun `ALTERNATIVE client and rss arguments remain isolated`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID!, x: Int, y(z: Int!): Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("y(z: 2)")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                    }
                }

                field("Foo" to "y") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<Int>("z") }
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        createEngineObjectData(objectType, emptyMap())
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
                    "query(${'$'}z: Int!) { foo { y(z: ${'$'}z) x } }",
                    variables = mapOf("z" to 1),
                ).assertJson("{data: {foo: {y: 1, x: 6}}}")
            }
        }

        @Engine2Alternate(original = "rss variable failure reports a field error")
        fun `ALTERNATIVE rss variable failure reports a field error`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID!, x: Int, y(z: Int!): Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
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

                field("Foo" to "y") {
                    resolver {
                        fn { _, _, _, _, _ -> 2 }
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        createEngineObjectData(objectType, emptyMap())
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x } }").assertMatches {
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

        @Engine2Alternate(original = "embedded node materialization preserves ancestor argument variables")
        fun `ALTERNATIVE embedded node materialization preserves ancestor argument variables`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo:Foo }
                    type Foo implements Node { id:ID!, bar(y:Int!): Bar }
                    type Bar { x:Int, y:Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Foo" to "bar") {
                    resolver {
                        fn { args, _, _, _, _ ->
                            createEngineObjectData("Bar", mapOf("y" to args.getAs<Int>("y")))
                        }
                    }
                }

                field("Bar" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        createEngineObjectData(objectType, emptyMap())
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
                    "query(\$y: Int!) { foo { bar(y: \$y) { x } } }",
                    variables = mapOf("y" to 2),
                ).assertJson("{data: {foo: {bar: {x: 10}}}}")
            }
        }

        @Engine2Alternate(original = "node owned sibling supplies required rss variable")
        fun `ALTERNATIVE node owned sibling supplies required rss variable`() {
            val nodeCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo:Foo }
                    type Foo implements Node { id:ID!, bar:Bar }
                    type Bar { x:Int, y(z:Int!): Int, z:Int! }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Bar" to "y") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<Int>("z") * 3 }
                    }
                }

                field("Bar" to "x") {
                    resolverExecutor {
                        val objectSelections = SelectionsParser.parse("Bar", "y(z: \$z), z")
                        val variablesResolvers = VariablesResolver.fromSelectionSetVariables(
                            objectSelections = objectSelections,
                            querySelections = null,
                            variables = listOf(FromObjectFieldVariable("z", "z")),
                            forChecker = false,
                        )
                        MockFieldUnbatchedResolverExecutor(
                            objectFieldVariables = VariableFromFieldDefinitions(mapOf("z" to "z")),
                            objectSelectionSet = RequiredSelectionSet(
                                selections = objectSelections,
                                variablesResolvers = variablesResolvers,
                                forChecker = false,
                            ),
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 },
                        )
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor { _, _, _ ->
                        nodeCalls.incrementAndGet()
                        createEngineObjectData(
                            objectType,
                            mapOf(
                                "bar" to createEngineObjectData("Bar", mapOf("z" to 2)),
                            ),
                        )
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { bar { x } } }")
                    .assertJson("{data: {foo: {bar: {x: 30}}}}")
            }

            assertEquals(1, nodeCalls.get())
        }

        @Engine2Alternate(original = "embedded node materialization preserves fragment argument variables")
        fun `ALTERNATIVE embedded node materialization preserves fragment argument variables`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID!, bar(y: Int!): Bar }
                    type Bar { x: Int, y(x: Int!): Int, parentY: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Foo" to "bar") {
                    resolver {
                        fn { args, _, _, _, _ ->
                            createEngineObjectData(
                                "Bar",
                                mapOf("parentY" to args.getAs<Int>("y")),
                            )
                        }
                    }
                }

                field("Bar" to "y") {
                    resolver {
                        objectSelections("parentY")
                        fn { args, obj, _, _, _ ->
                            args.getAs<Int>("x") * obj.fetchAs<Int>("parentY")
                        }
                    }
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
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        createEngineObjectData(objectType, emptyMap())
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
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

        @Engine2Alternate(original = "variable rss does not use skipped child object plan")
        fun `ALTERNATIVE variable rss does not use skipped child object plan`() {
            val nodeCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { a: Int, b: Int, foo: Foo!, sink(x: Boolean): Int }
                    type Foo implements Node { id: ID!, y: Int, z: Boolean! }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Query" to "b") {
                    resolverExecutor {
                        val objectSelections = SelectionsParser.parse(
                            "Query",
                            "sink(x: \$includeFoo), foo { z y }",
                        )
                        val variablesResolvers = VariablesResolver.fromSelectionSetVariables(
                            objectSelections = objectSelections,
                            querySelections = null,
                            variables = listOf(
                                FromObjectFieldVariable("includeFoo", "foo.z"),
                            ),
                            forChecker = false,
                        )
                        MockFieldUnbatchedResolverExecutor(
                            objectFieldVariables = VariableFromFieldDefinitions(mapOf("includeFoo" to "foo.z")),
                            objectSelectionSet = RequiredSelectionSet(
                                selections = objectSelections,
                                variablesResolvers = variablesResolvers,
                                forChecker = false,
                            ),
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ -> 2 },
                        )
                    }
                }

                field("Query" to "sink") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ -> 0 },
                        )
                    }
                }

                field("Query" to "a") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            objectSelectionSet = createRSS("Query", "b, foo { z y }"),
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, obj, _, _, _ ->
                                obj.fetch("b")
                                val foo = obj.fetchAs<EngineObjectData>("foo")
                                foo.fetch("y")
                                foo.fetch("z")
                                1
                            }
                        )
                    }
                }

                field("Foo" to "z") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            objectSelectionSet = createRSS("Foo", "y"),
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, obj, _, _, _ ->
                                obj.fetchAs<Int>("y")
                                false
                            }
                        )
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor { _, _, _ ->
                        nodeCalls.incrementAndGet()
                        createEngineObjectData(objectType, mapOf("y" to 4))
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
                    """
                        query (${"$"}includeA: Boolean! = false) {
                          b
                          a @include(if: ${"$"}includeA)
                        }
                    """.trimIndent()
                ).assertJson("{data: {b: 2}}")
            }

            assertEquals(1, nodeCalls.get())
        }
    }

    @Nested
    inner class ConsistencyTests {
        @Engine2Alternate(original = "type changes during refetch report a field error")
        fun `ALTERNATIVE type changes during refetch report a field error`() {
            val nodeCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID!, x: Int, y: Int, z: Int }
                    type Bar { z: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Foo" to "y") {
                    resolver {
                        objectSelections("z")
                        fn { _, _, _, _, _ ->
                            error("expected object of type `Foo`, found `Bar`")
                        }
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        nodeCalls.incrementAndGet()
                        createEngineObjectData(objectType, mapOf("x" to 1, "z" to 2))
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x y } }").assertMatches {
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

            assertEquals(1, nodeCalls.get())
        }

        @Engine2Alternate(original = "inconsistent resolver exceptions")
        fun `ALTERNATIVE inconsistent resolver exceptions`() {
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
                        fn { _, _, _, _, _ ->
                            error("foo node failed when materialized")
                        }
                    }
                }
                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        fooCalls.incrementAndGet()
                        createEngineObjectData(objectType, mapOf("y" to 2))
                    }
                }
            }.runRuntime2FeatureTest(withoutDefaultQueryNodeResolvers = true) {
                runQueryWithTimeout("{ foo { x } }").assertMatches {
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

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "nested object type changes during refetch report a field error")
        fun `ALTERNATIVE nested object type changes during refetch report a field error`() {
            val nodeCalls = AtomicInteger()

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
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Baz" to "y") {
                    resolver {
                        objectSelections("x")
                        fn { _, _, _, _, _ ->
                            error("expected object of type `Baz`, found `Qux`")
                        }
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        nodeCalls.incrementAndGet()
                        createEngineObjectData(
                            objectType,
                            mapOf(
                                "bar" to createEngineObjectData("Baz", mapOf("x" to 2))
                            ),
                        )
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
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

            assertEquals(1, nodeCalls.get())
        }

        @Engine2Alternate(original = "malformed nested object during refetch reports a field error")
        fun `ALTERNATIVE malformed nested object during refetch reports a field error`() {
            val nodeCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo:Foo }
                    type Foo implements Node { id:ID!, bar:Bar }
                    type Bar { x:Int, y:Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Bar" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, _, _, _, _ ->
                            error("nested object failed when materialized")
                        }
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        nodeCalls.incrementAndGet()
                        createEngineObjectData(
                            objectType,
                            mapOf(
                                "bar" to createEngineObjectData("Bar", mapOf("y" to 2))
                            ),
                        )
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { bar { x } } }").assertMatches {
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

            assertEquals(1, nodeCalls.get())
        }
    }

    @Nested
    inner class BatchedTests {
        @Engine2Disabled(Engine2DisabledKind.TODO, "Batching")
        fun `batched node refetches preserve selection shapes`() {
            val batches = mutableListOf<List<Pair<String, Set<String>>>>()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo, bar: Foo }
                    type Foo implements Node { id: ID!, x: Int, y: Int, z: Int, w: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("2", objectType("Foo")) }
                }

                field("Query" to "bar") {
                    valueFromContext { it.createNodeReference("3", objectType("Foo")) }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("z")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("z") * 11 }
                    }
                }

                field("Foo" to "y") {
                    resolver {
                        objectSelections("w")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("w") * 13 }
                    }
                }

                type("Foo") {
                    nodeBatchedExecutor(selective = true) { selectors, _ ->
                        batches += selectors.map { selector ->
                            selector.id to
                                selector.selections
                                    .selections()
                                    .map { it.fieldName }
                                    .toSet()
                        }.sortedBy { it.first }

                        selectors.associateWith { selector ->
                            val id = selector.id.toInt()
                            Result.success(
                                createEngineObjectData(
                                    objectType,
                                    buildMap {
                                        if (selector.selections.containsField("Foo", "z")) {
                                            put("z", id * 5)
                                        }
                                        if (selector.selections.containsField("Foo", "w")) {
                                            put("w", id * 7)
                                        }
                                    },
                                )
                            )
                        }
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x } bar { y } }")
                    .assertJson("{data: {foo: {x: 110}, bar: {y: 273}}}")
            }

            assertEquals(
                listOf(
                    listOf("2" to setOf("x"), "3" to setOf("y")),
                    listOf("2" to setOf("z"), "3" to setOf("w")),
                ),
                batches,
            )
        }

        @Engine2Disabled(Engine2DisabledKind.TODO, "Batching")
        fun `batched node refetch isolates same id selector failure`() {
            val refetchArguments = mutableSetOf<Int>()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { a:Foo, b:Foo }
                    type Foo implements Node { id:ID!, x(a:Int!):Int, y(a:Int!):Int }
                """.trimIndent()
            ) {
                field("Query" to "a") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Query" to "b") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("y(a:\$a)") {
                            variables("a") { ctx, _ ->
                                mapOf("a" to ctx.arguments.getAs<Int>("a"))
                            }
                        }
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") }
                    }
                }

                type("Foo") {
                    nodeBatchedExecutor(selective = true) { selectors, _ ->
                        selectors.associateWith { selector ->
                            val ySelection = selector.selections
                                .selections()
                                .singleOrNull { it.fieldName == "y" }
                            if (ySelection == null) {
                                Result.success(createEngineObjectData(objectType, emptyMap()))
                            } else {
                                val a = selector.selections
                                    .argumentsOfSelection("Foo", ySelection.selectionName)
                                    ?.get("a") as Int
                                refetchArguments += a
                                if (a == 2) {
                                    Result.failure(IllegalStateException("selector 2 failed"))
                                } else {
                                    Result.success(
                                        createEngineObjectData(objectType, mapOf("y" to a * 10))
                                    )
                                }
                            }
                        }
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ a { x(a: 1) } b { x(a: 2) } }").assertMatches {
                    "data" to {
                        "a" to {
                            "x" to "10"
                        }
                        "b" to {
                            "x" to null
                        }
                    }
                    "errors" to arrayOf(
                        {
                            "message" to ".*selector 2 failed.*"
                            "path" to listOf("b", "x")
                        }
                    )
                }
            }

            assertEquals(setOf(1, 2), refetchArguments)
        }
    }

    @Nested
    inner class SubqueryTests {
        @Engine2Alternate(original = "node resolver can query during refetch")
        fun `ALTERNATIVE node resolver can query during refetch`() {
            val nodeCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { a: Int, foo: Foo }
                    type Foo implements Node { id: ID!, x: Int, y: Int }
                """.trimIndent()
            ) {
                fieldWithValue("Query" to "a", 2)

                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 6}}}")
            }

            assertEquals(1, nodeCalls.get())
        }
    }

    @Nested
    inner class SelectionSetTests {
        @Test
        fun `selected omitted node field is treated as covered`() {
            val nodeCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo:Foo }
                    type Foo implements Node { id:ID!, x:Int, y:Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, obj, _, _, _ ->
                            ((obj.fetchOrNull("y") as? Int) ?: 2) * 3
                        }
                    }
                }

                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        nodeCalls.incrementAndGet()
                        createEngineObjectData(objectType, emptyMap())
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { y x } }")
                    .assertJson("{data: {foo: {y: null, x: 6}}}")
            }

            assertEquals(1, nodeCalls.get())
        }
    }
}
