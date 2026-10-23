package viaduct.engine.runtime2.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.VariableFromFieldDefinitions
import viaduct.engine.runtime.execution.RequiredSelectionsContract
import viaduct.engine.runtime.tenantloading.InvalidVariableException
import viaduct.engine.runtime2.execution.testing.runQPlanFeatureTest
import viaduct.graphql.test.assertJson

@ExperimentalCoroutinesApi
class Engine2RequiredSelectionsContractTest : RequiredSelectionsContract, Engine2QueryExecutionContract {
    @Test
    @Disabled("ALT: Production resolves client and object-RSS shapes separately; qplan intentionally coalesces them into one selective resolver application")
    override fun `selective required selection is resolved independently from client query selection`() {
        super<RequiredSelectionsContract>.`selective required selection is resolved independently from client query selection`()
    }

    @Test
    @Disabled("TODO: ParentFld")
    override fun `parent field with resolver argument variables runs child plan`() {
        super<RequiredSelectionsContract>.`parent field with resolver argument variables runs child plan`()
    }

    @Test
    @Disabled("N/A: Requires a child-produced variable beneath @parent to parameterize ancestor work; qplan deliberately rejects that dependency direction")
    override fun `parent field with child object field variables runs child plan`() {
        super<RequiredSelectionsContract>.`parent field with child object field variables runs child plan`()
    }

    @Test
    @Disabled("N/A: Requires a variable read through @parent to parameterize ancestor work; qplan deliberately rejects variables beneath parent selections")
    override fun `parent field in variable resolver required selection is available to variables resolver`() {
        super<RequiredSelectionsContract>.`parent field in variable resolver required selection is available to variables resolver`()
    }

    @Test
    @Disabled("ALT: Uses a callback-owned child variable to condition ancestor demand; qplan preserves one-shot execution by lifting the bounded ancestor demand unconditionally")
    override fun `parent field selections honor conditional directives`() {
        super<RequiredSelectionsContract>.`parent field selections honor conditional directives`()
    }

    @Test
    @Disabled("TODO: MechAdapt ErrorData")
    override fun `plain OER keys cause required selections to reuse client selection shape`() {
        super<RequiredSelectionsContract>.`plain OER keys cause required selections to reuse client selection shape`()
    }

    @Test
    @Disabled("ALT: Production exposes only client demand to the shared non-selective application; qplan intentionally exposes its coalesced one-shot demand")
    override fun `non-selective required selection is shared across client query and dependency selections`() {
        super<RequiredSelectionsContract>.`non-selective required selection is shared across client query and dependency selections`()
    }

    @Test
    @Disabled("ALT: Production resolves distinct object-RSS shapes separately; qplan intentionally coalesces them into one selective resolver application")
    override fun `selective required selection is resolved independently across resolver rss variants`() {
        super<RequiredSelectionsContract>.`selective required selection is resolved independently across resolver rss variants`()
    }

    @Test
    @Disabled("ALT: Uses a callback-owned Query RSS only to produce a constant gate; qplan uses its supported no-RSS function provider while preserving the impossible sibling dependency")
    override fun `required selection with impossible sibling implementation dependency can be resolved`() {
        super<RequiredSelectionsContract>.`required selection with impossible sibling implementation dependency can be resolved`()
    }

    @Test
    @Disabled("N/A: Inspects old-engine ExecutionParameters and QueryPlan child plans from the execution handle")
    override fun `sibling cyclic required selections keep direct materializations during execution`() {
        super<RequiredSelectionsContract>.`sibling cyclic required selections keep direct materializations during execution`()
    }

    @Test
    @Disabled("TODO: AccessChk")
    override fun `selective query field resolves matching resolver and checker selections independently`() {
        super<RequiredSelectionsContract>.`selective query field resolves matching resolver and checker selections independently`()
    }

    @Test
    @Disabled("TODO: AccessChk")
    override fun `selective required selection resolves resolver and type checker selections independently`() {
        super<RequiredSelectionsContract>.`selective required selection resolves resolver and type checker selections independently`()
    }

    @Test
    @Disabled("ALT: Production expects alias-shaped required-selection demand to execute Query.bar twice; qplan intentionally coalesces it into one resolver application")
    override fun `resolve fields multiple mergeable requirements`() {
        super<RequiredSelectionsContract>.`resolve fields multiple mergeable requirements`()
    }

    @Test
    @Disabled("ALT: Production resolves nested client and object-RSS shapes separately; qplan intentionally coalesces them into one selective resolver application")
    override fun `proxy engine object data reads required selections through two nested client and rss merges`() {
        super<RequiredSelectionsContract>.`proxy engine object data reads required selections through two nested client and rss merges`()
    }

    @Test
    @Disabled("ALT: Uses a legacy callback-owned Query RSS; qplan expresses the provider as a declarative object-field path and coalesces client and provider demand")
    override fun `variable resolver rss reads through multiple selective fields including abstract hop`() {
        super<RequiredSelectionsContract>.`variable resolver rss reads through multiple selective fields including abstract hop`()
    }

    @Test
    @Disabled("ALT: Uses one shared callback-owned RSS across two resolvers; qplan gives each resolver its own declarative occurrence-local provider path")
    override fun `two resolvers with structurally-equivalent variable-resolver RSSes both resolve correctly`() {
        super<RequiredSelectionsContract>.`two resolvers with structurally-equivalent variable-resolver RSSes both resolve correctly`()
    }

    @Test
    @Disabled("ALT: Uses a callback-owned RSS for an item-local condition; qplan expresses the same occurrence-local dependency as a declarative object-field provider")
    override fun `objectSelections conditional directives honor per-item variables`() {
        super<RequiredSelectionsContract>.`objectSelections conditional directives honor per-item variables`()
    }

    @Test
    fun `ALTERNATIVE objectSelections conditional directives reject per-item from-field variables`() {
        val err = assertThrows<IllegalArgumentException> {
            EngineTestModule(
                """
                extend type Query {
                    items: [Item!]!
                }

                type Item {
                    includeSelectedValue: Boolean!
                    selectedValue: String
                    summary: String
                }
                """.trimIndent()
            ) {
                field("Item" to "summary") {
                    resolverExecutor {
                        val objectSelections = SelectionsParser.parse(
                            "Item",
                            "includeSelectedValue selectedValue @include(if: \$includeSelectedValue)",
                        )
                        val variablesResolvers = VariablesResolver.fromSelectionSetVariables(
                            objectSelections = objectSelections,
                            querySelections = null,
                            variables = listOf(
                                FromObjectFieldVariable(
                                    "includeSelectedValue",
                                    "includeSelectedValue",
                                )
                            ),
                            forChecker = false,
                        )
                        MockFieldUnbatchedResolverExecutor(
                            objectFieldVariables = VariableFromFieldDefinitions(mapOf("includeSelectedValue" to "includeSelectedValue")),
                            objectSelectionSet = RequiredSelectionSet(
                                selections = objectSelections,
                                variablesResolvers = variablesResolvers,
                                forChecker = false,
                            ),
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ -> error("should not execute") },
                        )
                    }
                }
            }.runQPlanFeatureTest { }
        }

        val cause = generateSequence<Throwable>(err) { it.cause }.filterIsInstance<InvalidVariableException>().first()
        assertEquals("includeSelectedValue", cause.variableName)
    }

    @Test
    @Disabled("TODO: Mutation")
    override fun `resolve mutation with queryValueFragment`() {
        super<RequiredSelectionsContract>.`resolve mutation with queryValueFragment`()
    }

    @Test
    @Disabled("ALT: Uses a callback-owned RSS for a runtime directive; qplan uses its supported no-RSS function provider and coalesces the repeated fragment demand")
    override fun `query rss variable resolver is planned when repeated fragment spread has runtime directive`() {
        super<RequiredSelectionsContract>.`query rss variable resolver is planned when repeated fragment spread has runtime directive`()
    }

    @Test
    fun `ALTERNATIVE selective required selection is resolved independently from client query selection`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        runQueryTest(
            EngineTestModule(
                """
            extend type Query { container: Container }
            type Container { details: Details, summary: Int }
            type Details { a: Int, b: Int }
                """.trimIndent()
            ) {
                field("Query" to "container") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData(requireNotNull(schema.schema.getObjectType("Container")), emptyMap())
                        }
                    }
                }
                field("Container" to "details") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, selections, _ ->
                                val requestedSelections = selections
                                    ?.selections()
                                    ?.map { it.selectionName }
                                    ?.toSortedSet()
                                    .orEmpty()
                                detailsCount.incrementAndGet()
                                detailsSelections.add(requestedSelections.joinToString(" "))
                                createEngineObjectData(
                                    requireNotNull(schema.schema.getObjectType("Details")),
                                    buildMap {
                                        if ("a" in requestedSelections) put("a", 1)
                                        if ("b" in requestedSelections) put("b", 2)
                                    }
                                )
                            }
                        )
                    }
                }
                field("Container" to "summary") {
                    resolver {
                        objectSelections("details { b }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b") * 10
                        }
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ container { details { a } summary } }")
                .assertJson("""{"data": {"container": {"details": {"a": 1}, "summary": 20}}}""")
        }

        assertEquals(1, detailsCount.get())
        assertEquals(setOf("a b"), detailsSelections)
    }

    @Test
    fun `ALTERNATIVE parent field selections honor conditional directives`() {
        val companyNameCount = AtomicInteger()

        runQueryTest(
            EngineTestModule(
                """
            extend type Query { company: Company }
            type Company { companyName: String, users: [User] }
            type User { includeParentName: Boolean!, parent: Company @parent, parentCompanyName: String }
                """.trimIndent()
            ) {
                field("Query" to "company") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData(requireNotNull(schema.schema.getObjectType("Company")), emptyMap())
                        }
                    }
                }
                field("Company" to "companyName") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            companyNameCount.incrementAndGet()
                            "Airbnb"
                        }
                    }
                }
                field("Company" to "users") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            listOf(
                                createEngineObjectData(
                                    requireNotNull(schema.schema.getObjectType("User")),
                                    mapOf("includeParentName" to true)
                                ),
                                createEngineObjectData(
                                    requireNotNull(schema.schema.getObjectType("User")),
                                    mapOf("includeParentName" to false)
                                )
                            )
                        }
                    }
                }
                field("User" to "parentCompanyName") {
                    resolver {
                        objectSelections("includeParentName parent { companyName }")
                        fn { _, obj, _, _, _ ->
                            val parent = obj.fetchAs<EngineObjectData>("parent")
                            if (obj.fetchAs<Boolean>("includeParentName")) {
                                parent.fetchAs<String>("companyName")
                            } else {
                                "skipped"
                            }
                        }
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ company { users { parentCompanyName } } }")
                .assertJson("""{"data": {"company": {"users": [{"parentCompanyName": "Airbnb"}, {"parentCompanyName": "skipped"}]}}}""")
        }

        assertEquals(1, companyNameCount.get())
    }

    @Test
    fun `ALTERNATIVE non-selective required selection is shared across client query and dependency selections`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        runQueryTest(
            EngineTestModule(
                """
            extend type Query { container: Container }
            type Container { details: Details, summary: Int }
            type Details { a: Int, b: Int }
                """.trimIndent()
            ) {
                field("Query" to "container") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData(requireNotNull(schema.schema.getObjectType("Container")), emptyMap())
                        }
                    }
                }
                field("Container" to "details") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, selections, _ ->
                                val requestedSelections = selections
                                    ?.selections()
                                    ?.map { it.selectionName }
                                    ?.toSortedSet()
                                    .orEmpty()
                                detailsCount.incrementAndGet()
                                detailsSelections.add(requestedSelections.joinToString(" "))
                                createEngineObjectData(
                                    requireNotNull(schema.schema.getObjectType("Details")),
                                    mapOf("a" to 1, "b" to 2)
                                )
                            }
                        )
                    }
                }
                field("Container" to "summary") {
                    resolver {
                        objectSelections("details { b }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b") * 10
                        }
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ container { details { a } summary } }")
                .assertJson("""{"data": {"container": {"details": {"a": 1}, "summary": 20}}}""")
        }

        assertEquals(1, detailsCount.get())
        assertEquals(setOf("a b"), detailsSelections)
    }

    @Test
    fun `ALTERNATIVE selective required selection is resolved independently across resolver rss variants`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        runQueryTest(
            EngineTestModule(
                """
            extend type Query { container: Container }
            type Container { details: Details, fromA: Int, fromB: Int }
            type Details { a: Int, b: Int }
                """.trimIndent()
            ) {
                field("Query" to "container") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData(requireNotNull(schema.schema.getObjectType("Container")), emptyMap())
                        }
                    }
                }
                field("Container" to "details") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, selections, _ ->
                                val requestedSelections = selections
                                    ?.selections()
                                    ?.map { it.selectionName }
                                    ?.toSortedSet()
                                    .orEmpty()
                                detailsCount.incrementAndGet()
                                detailsSelections.add(requestedSelections.joinToString(" "))
                                createEngineObjectData(
                                    requireNotNull(schema.schema.getObjectType("Details")),
                                    buildMap {
                                        if ("a" in requestedSelections) put("a", 1)
                                        if ("b" in requestedSelections) put("b", 2)
                                    }
                                )
                            }
                        )
                    }
                }
                field("Container" to "fromA") {
                    resolver {
                        objectSelections("details { a }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("a")
                        }
                    }
                }
                field("Container" to "fromB") {
                    resolver {
                        objectSelections("details { b }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                        }
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ container { fromA fromB } }")
                .assertJson("""{"data": {"container": {"fromA": 1, "fromB": 2}}}""")
        }

        assertEquals(1, detailsCount.get())
        assertEquals(setOf("a b"), detailsSelections)
    }

    @Test
    fun `ALTERNATIVE required selection with impossible sibling implementation dependency can be resolved`() {
        // Foo.x has an object RSS rooted at Foo. The outer `... on Node` branch can match Foo,
        // but after that refinement the nested `... on Bar` branch is impossible because Foo and Bar
        // are sibling implementations of Node.
        runQueryTest(
            EngineTestModule(
                """
                extend type Query {
                  trigger: Int
                  bar1: Node
                }

                extend interface Node {
                  x: Int
                }

                type Foo implements Node {
                  id: ID!
                  x: Int
                }

                type Bar implements Node {
                  id: ID!
                  x: Int
                  y: Int
                  foo: Foo
                }
                """.trimIndent()
            ) {
                field("Query" to "trigger") {
                    resolver {
                        querySelections("bar1 {x, ... on Bar { y } }")
                        fn { _, _, query, _, _ ->
                            query.fetchAs<EngineObjectData>("bar1").fetchAs<Int>("y")
                        }
                    }
                }

                field("Query" to "bar1") {
                    valueFromContext { ctx ->
                        ctx.createNodeReference(
                            ctx.globalIDCodec.serialize("Bar", "1"),
                            schema.schema.getObjectType("Bar")!!
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections(
                            """
                            ... on Node @include(if: ${'$'}gate) {
                              ... on Bar {
                                y
                              }
                            }
                            """.trimIndent()
                        ) {
                            variables("gate") { _, _ -> mapOf("gate" to true) }
                        }
                        fn { _, _, _, _, _ -> 1 }
                    }
                }

                field("Bar" to "y") {
                    resolver {
                        objectSelections("foo { x }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("foo")
                                .fetchAs<Int>("x")
                        }
                    }
                }

                field("Bar" to "foo") {
                    valueFromContext {
                        createEngineObjectData(schema.schema.getObjectType("Foo")!!, emptyMap())
                    }
                }

                type("Bar") {
                    nodeUnbatchedExecutor { _, _, _ ->
                        createEngineObjectData(objectType, emptyMap())
                    }
                }
            },
            withoutDefaultQueryNodeResolvers = true
        ) { runQuery ->
            runQuery.withTimeout("{ trigger }")
                .assertJson("{data: {trigger: 1}}")
        }
    }

    @Test
    fun `ALTERNATIVE resolve fields multiple mergeable requirements`() {
        val barCount = AtomicInteger()
        runQueryTest(
            EngineTestModule("extend type Query { foo: Int, bar: Int }") {
                field("Query" to "bar") {
                    resolver {
                        fn { _, _, _, _, _ -> 3.also { barCount.incrementAndGet() } }
                    }
                }
                field("Query" to "foo") {
                    resolver {
                        objectSelections(
                            """
                        fragment F on Query { bar }
                        fragment Main on Query {
                          bar
                          aliasedBar: bar
                          ... {
                            bar
                            ... {
                              bar
                              ... F
                            }
                          }
                          ... on Query {
                            bar
                            ... on Query {
                              bar
                              ... F
                            }
                          }
                          ... F
                        }
                            """.trimIndent(),
                        )
                        fn { _, obj, _, _, _ ->
                            // make sure we wait for aliasedBar
                            obj.fetchAs<Int>("aliasedBar")

                            // but ultimately just return 2 * "bar"
                            obj.fetchAs<Int>("bar") * 2
                        }
                    }
                }
            }
        ) { runQuery ->
            runQuery("{foo bar}")
                .assertJson("""{"data": {"foo": 6, "bar": 3}}""")
                .also { assertEquals(1, barCount.get()) }
        }
    }

    @Test
    fun `ALTERNATIVE proxy engine object data reads required selections through two nested client and rss merges`() {
        val outerCount = AtomicInteger()
        val middleCount = AtomicInteger()
        val innerCount = AtomicInteger()
        val innerSelections = ConcurrentHashMap.newKeySet<String>()

        runQueryTest(
            EngineTestModule(
                """
            extend type Query { summary: Int, outer: Outer }
            type Outer { middle: Middle }
            type Middle { inner: Inner }
            type Inner { a: Int, b: Int }
                """.trimIndent()
            ) {
                field("Query" to "outer") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            outerCount.incrementAndGet()
                            createEngineObjectData(requireNotNull(schema.schema.getObjectType("Outer")), emptyMap())
                        }
                    }
                }
                field("Outer" to "middle") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            middleCount.incrementAndGet()
                            createEngineObjectData(requireNotNull(schema.schema.getObjectType("Middle")), emptyMap())
                        }
                    }
                }
                field("Middle" to "inner") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, selections, _ ->
                                val requestedSelections = selections
                                    ?.selectionSetForType("Inner")
                                    ?.selections()
                                    ?.map { it.selectionName }
                                    ?.toSortedSet()
                                    .orEmpty()
                                innerCount.incrementAndGet()
                                innerSelections.add(requestedSelections.joinToString(" "))
                                createEngineObjectData(
                                    requireNotNull(schema.schema.getObjectType("Inner")),
                                    buildMap {
                                        if ("a" in requestedSelections) put("a", 1)
                                        if ("b" in requestedSelections) put("b", 2)
                                    }
                                )
                            }
                        )
                    }
                }
                field("Query" to "summary") {
                    resolver {
                        objectSelections("outer { middle { inner { b } } }")
                        fn { _, obj, _, _, _ ->
                            withTimeout(200) {
                                obj.fetchAs<EngineObjectData>("outer")
                                    .fetchAs<EngineObjectData>("middle")
                                    .fetchAs<EngineObjectData>("inner")
                                    .fetchAs<Int>("b")
                            }
                        }
                    }
                }
            }
        ) { runQuery ->
            runQuery(
                """
                query {
                  outer {
                    middle {
                      inner {
                        a
                      }
                    }
                  }
                  summary
                }
                """.trimIndent()
            ).assertJson("""{"data": {"outer": {"middle": {"inner": {"a": 1}}}, "summary": 2}}""")
        }

        assertEquals(1, outerCount.get())
        assertEquals(1, middleCount.get())
        assertEquals(1, innerCount.get())
        assertEquals(setOf("a b"), innerSelections)
    }

    @Test
    fun `ALTERNATIVE variable resolver rss reads through multiple selective fields including abstract hop`() {
        val middleCount = AtomicInteger()
        val nodeCount = AtomicInteger()

        runQueryTest(
            EngineTestModule(
                """
            extend type Query { outer: Outer, compute(x: Int): Int!, result: Int! }
            type Outer { middle: Middle }
            type Middle { node: AbstractNode }
            interface AbstractNode { id: ID!, value: Int! }
            type ConcreteNode implements AbstractNode { id: ID!, value: Int! }
                """.trimIndent()
            ) {
                field("Query" to "outer") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData(
                                requireNotNull(schema.schema.getObjectType("Outer")),
                                emptyMap(),
                            )
                        }
                    }
                }
                field("Outer" to "middle") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = "Outer.middle",
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                middleCount.incrementAndGet()
                                createEngineObjectData(
                                    requireNotNull(schema.schema.getObjectType("Middle")),
                                    emptyMap(),
                                )
                            }
                        )
                    }
                }
                field("Middle" to "node") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = "Middle.node",
                            unbatchedResolveFn = { _, _, _, selections, _ ->
                                val requestedSelections = selections
                                    ?.selectionSetForType("ConcreteNode")
                                    ?.selections()
                                    ?.map { it.selectionName }
                                    ?.toSet()
                                    .orEmpty()
                                nodeCount.incrementAndGet()
                                createEngineObjectData(
                                    requireNotNull(schema.schema.getObjectType("ConcreteNode")),
                                    buildMap {
                                        if ("id" in requestedSelections) put("id", "n1")
                                        if ("value" in requestedSelections) put("value", 7)
                                    },
                                )
                            }
                        )
                    }
                }
                field("Query" to "compute") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<Int>("x") + 1 }
                    }
                }
                field("Query" to "result") {
                    resolverExecutor {
                        val objectSelections = SelectionsParser.parse(
                            "Query",
                            """
                        compute(x: ${'$'}value)
                        outer { middle { node { value } } }
                            """.trimIndent(),
                        )
                        val variablesResolvers = VariablesResolver.fromSelectionSetVariables(
                            objectSelections = objectSelections,
                            querySelections = null,
                            variables = listOf(
                                FromObjectFieldVariable("value", "outer.middle.node.value"),
                            ),
                            forChecker = false,
                        )
                        MockFieldUnbatchedResolverExecutor(
                            objectFieldVariables = VariableFromFieldDefinitions(mapOf("value" to "outer.middle.node.value")),
                            objectSelectionSet = RequiredSelectionSet(
                                selections = objectSelections,
                                variablesResolvers = variablesResolvers,
                                forChecker = false,
                            ),
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("compute") },
                        )
                    }
                }
            }
        ) { runQuery ->
            runQuery(
                """
                query {
                  outer {
                    middle {
                        node {
                        ... on ConcreteNode {
                          id
                        }
                      }
                    }
                  }
                  result
                }
                """.trimIndent()
            ).assertJson(
                """
                {"data": {
                  "outer": {"middle": {"node": {"id": "n1"}}},
                  "result": 8
                }}
                """.trimIndent()
            )
        }

        assertEquals(1, middleCount.get())
        assertEquals(1, nodeCount.get())
    }

    @Test
    fun `ALTERNATIVE two resolvers with structurally-equivalent variable-resolver RSSes both resolve correctly`() {
        runQueryTest(
            EngineTestModule(
                "extend type Query { foo1: Int, foo2: Int, y(a:Int): Int, z: Int }"
            ) {
                fun declarativeObjectSelections(): RequiredSelectionSet {
                    val selections = SelectionsParser.parse("Query", "y(a:\$vara), z")
                    val variablesResolvers = VariablesResolver.fromSelectionSetVariables(
                        objectSelections = selections,
                        querySelections = null,
                        variables = listOf(FromObjectFieldVariable("vara", "z")),
                        forChecker = false,
                    )
                    return RequiredSelectionSet(
                        selections = selections,
                        variablesResolvers = variablesResolvers,
                        forChecker = false,
                    )
                }

                fieldWithValue("Query" to "z", 7)
                field("Query" to "y") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<Int>("a") }
                    }
                }
                field("Query" to "foo1") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            objectSelectionSet = declarativeObjectSelections(),
                            objectFieldVariables = VariableFromFieldDefinitions(mapOf("vara" to "z")),
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("y") },
                        )
                    }
                }
                field("Query" to "foo2") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            objectSelectionSet = declarativeObjectSelections(),
                            objectFieldVariables = VariableFromFieldDefinitions(mapOf("vara" to "z")),
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("y") },
                        )
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ foo1 foo2 }")
                .assertJson("""{"data": {"foo1": 7, "foo2": 7}}""")
        }
    }

    @Test
    fun `ALTERNATIVE query rss variable resolver is planned when repeated fragment spread has runtime directive`() {
        runQueryTest(
            EngineTestModule(
                "extend type Query { a: Int, b: Int }"
            ) {
                field("Query" to "a") {
                    resolver {
                        querySelections(
                            """
                            fragment Main on Query {
                              ...Fragment_B @skip(if: ${"$"}skipB)
                              ...Fragment_B
                            }

                            fragment Fragment_B on Query {
                              b
                            }
                            """.trimIndent()
                        ) {
                            variables("skipB") { _, _ ->
                                mapOf("skipB" to false)
                            }
                        }
                        fn { _, _, _, _, _ -> 1 }
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ a }").assertJson("{data: {a: 1}}")
        }
    }
}
