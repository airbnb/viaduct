@file:Suppress("ForbiddenImport", "DEPRECATION")

package viaduct.engine.runtime.execution

import graphql.analysis.QueryTraverser
import graphql.execution.DataFetcherResult
import graphql.language.SelectionSet
import graphql.schema.GraphQLCompositeType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CheckedArb
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.DeepArbSuite
import viaduct.arbitrary.common.withCheck
import viaduct.arbitrary.graphql.BatchingResolverWeight
import viaduct.arbitrary.graphql.CheckerErrorWeight
import viaduct.arbitrary.graphql.CheckerExceptionWeight
import viaduct.arbitrary.graphql.CheckerExecutor
import viaduct.arbitrary.graphql.CheckerExecutorFactory
import viaduct.arbitrary.graphql.DeterministicResolveWeight
import viaduct.arbitrary.graphql.FieldCheckerWeight
import viaduct.arbitrary.graphql.FieldResolver
import viaduct.arbitrary.graphql.FieldResolverExceptionWeight
import viaduct.arbitrary.graphql.FieldResolverFactory
import viaduct.arbitrary.graphql.NodeResolverExceptionWeight
import viaduct.arbitrary.graphql.ResolverFieldRefWeight
import viaduct.arbitrary.graphql.SelectedTypeBias
import viaduct.arbitrary.graphql.SelectiveResolverWeight
import viaduct.arbitrary.graphql.TypeCheckerWeight
import viaduct.arbitrary.graphql.UndeclaredFieldResolverWeight
import viaduct.arbitrary.graphql.VariablesResolverExceptionWeight
import viaduct.arbitrary.graphql.asViaductSchema
import viaduct.arbitrary.graphql.viaduct
import viaduct.arbitrary.graphql.viaductExecutionInput
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ParentManagedValue
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.StandardResolutionValue
import viaduct.engine.api.mocks.FeatureTest
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest as runEngineFeatureTest
import viaduct.engine.api.spi.MaterializedFieldValueReader
import viaduct.engine.runtime.Engine1QueryExecutionContract
import viaduct.engine.runtime.dfe.engineExecutionContext
import viaduct.graphql.test.assertMatches
import viaduct.service.api.ExecutionInput
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

/**
 * # About This Test Suite
 *
 * This suite includes property tests that can be used to discover new regressions.
 * These tests follow a convention of being named 'ArbitraryTests', and are
 * modeled as a [Nested] class that implements [DeepArbSuite].
 *
 * See the docs on [DeepArbSuite] for more information on how to use each of its
 * run modes
 */
class SelectiveFieldExecutionContractTest : SelectiveFieldExecutionContract, Engine1QueryExecutionContract {
    override val selectiveFieldConfig: EngineConfiguration =
        EngineConfiguration.featureTestDefault.copy(
            flagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION)
        )

    @Nested
    inner class BasicExecutionTests {
        @Test
        fun `selective reruns reuse replacement children while resolving nested data`() {
            val depth = 6
            val providerCalls = AtomicInteger()
            val rerunSelectionSizes = ConcurrentLinkedQueue<Pair<Int, Int>>()
            var selections = "value"
            repeat(depth) { selections = "next { $selections }" }

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { next: Foo, value: Int, summary: Int }
                """.trimIndent(),
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, sels, context ->
                                providerCalls.incrementAndGet()
                                if (sels!!.containsField("Foo", "next")) {
                                    val parameters = context.executionHandle as ExecutionParameters
                                    val field = checkNotNull(parameters.field)
                                    rerunSelectionSizes += checkNotNull(field.occurrences.first().field.selectionSet).selections.size to
                                        checkNotNull(field.selectionSet).selections.size
                                    var data = createEngineObjectData("Foo", mapOf("value" to 42))
                                    repeat(depth) { data = createEngineObjectData("Foo", mapOf("next" to data)) }
                                    data
                                } else {
                                    createEngineObjectData("Foo")
                                }
                            },
                        )
                    }
                }
                field("Foo" to "summary") {
                    resolver {
                        objectSelections(selections)
                        fn { _, obj, _, _, _ ->
                            var current: EngineObjectData = obj
                            repeat(depth) { current = current.fetchAs<EngineObjectData>("next") }
                            current.fetchAs<Int>("value")
                        }
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { summary } foo { summary } }")
                    .assertJson("{data: {foo: {summary: 42}}}")
            }

            assertEquals(2, providerCalls.get())
            assertTrue(rerunSelectionSizes.isNotEmpty())
            rerunSelectionSizes.forEach { (requested, combined) -> assertEquals(requested, combined) }
        }

        @Test
        fun `selective resolver materialization rejects DataFetcherResult`() {
            val fooCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { x: Int, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, sels, _ ->
                                fooCalls.incrementAndGet()
                                if (sels!!.containsField("Foo", "y")) {
                                    DataFetcherResult.newResult<EngineObjectData>()
                                        .data(createEngineObjectData("Foo", mapOf("y" to 2)))
                                        .build()
                                } else {
                                    DataFetcherResult.newResult<EngineObjectData>()
                                        .data(createEngineObjectData("Foo"))
                                        .build()
                                }
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }").assertMatches {
                    "data" to {
                        "foo" to {
                            "x" to null
                        }
                    }
                    "errors" to arrayOf(
                        {
                            "message" to
                                ".*DataFetcherResult is not supported during selective field materialization.*"
                            "path" to listOf("foo", "x")
                        }
                    )
                }
            }

            assertEquals(2, fooCalls.get())
        }

        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { a:Int, b:Int!, foo:Foo }
                    | type Foo { x:Int, y:Int!, bar:Bar }
                    | type Bar { x:Int, y:Int! }
                """.trimMargin()
            )
    }

    @Nested
    inner class ParentFields {
        @Test
        fun `selective list item can read its non-selective parent`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { x: Int, bars: [Bar!]! }
                    type Bar { parent: Foo @parent, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData("Foo", mapOf("x" to 2))
                        }
                    }
                }

                field("Foo" to "bars") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                listOf(createEngineObjectData("Bar"))
                            }
                        )
                    }
                }

                field("Bar" to "y") {
                    resolver {
                        objectSelections("parent { x }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("parent").fetchAs<Int>("x")
                        }
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { bars { y } } }")
                    .assertJson("{data: {foo: {bars: [{y: 2}]}}}")
            }
        }

        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                sdl =
                    """
                        extend type Query {
                          foo: Foo!
                        }
                        type Foo {
                          bar: Bar!
                        }
                        type Bar {
                          x: Int!
                          bazs: [Baz!]!
                        }
                        type Baz {
                          parent: Bar! @parent
                          y: Int!
                        }
                    """.trimIndent(),
            )
    }

    @Nested
    inner class ParentManagedValueTests {
        @Test
        fun `missing descendant of a parent-managed selective result refetches the parent`() {
            assertParentManagedRefetch()
        }

        @Test
        fun `parent-managed refetch crosses a field with a suppressed resolver`() {
            assertParentManagedRefetch(nestedResolverSelective = false)
        }

        @Test
        fun `parent-managed refetch crosses a field with a suppressed selective resolver`() {
            assertParentManagedRefetch(nestedResolverSelective = true)
        }

        private fun assertParentManagedRefetch(nestedResolverSelective: Boolean? = null) {
            val parentSelections = ConcurrentLinkedQueue<Set<String>>()
            val descendantResolverCalls = AtomicInteger()
            val checkerValue = AtomicReference<String?>()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { bar: Bar }
                    type Bar { visible: String, missing: String }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, sels, _ ->
                                val selections = sels!!.selectionSetForField("Foo", "bar")
                                val fields = setOf("visible", "missing").filterTo(mutableSetOf()) {
                                    selections.containsField("Bar", it)
                                }
                                parentSelections += fields
                                ParentManagedValue(
                                    createEngineObjectData(
                                        "Foo",
                                        mapOf(
                                            "bar" to createEngineObjectData(
                                                "Bar",
                                                fields.associateWith { "parent-$it" },
                                            )
                                        ),
                                    )
                                )
                            }
                        )
                    }
                }

                if (nestedResolverSelective != null) {
                    field("Foo" to "bar") {
                        resolverExecutor {
                            MockFieldUnbatchedResolverExecutor(
                                isSelective = nestedResolverSelective,
                                resolverId = resolverId,
                                unbatchedResolveFn = { _, _, _, _, _ ->
                                    descendantResolverCalls.incrementAndGet()
                                    error("Foo.bar must be provided by Query.foo")
                                },
                            )
                        }
                    }
                }

                field("Bar" to "visible") {
                    checker {
                        objectSelections("required", "missing")
                        fn { _, objects ->
                            checkerValue.set(objects.getValue("required").fetchAs<String?>("missing"))
                        }
                    }
                }

                field("Bar" to "missing") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            descendantResolverCalls.incrementAndGet()
                            "child-value"
                        }
                    }
                }
            }.runFeatureTest {
                val result = runQueryWithTimeout("{ foo { bar { visible } } }")
                assertAll(
                    { result.assertJson("""{data: {foo: {bar: {visible: "parent-visible"}}}}""") },
                    { assertEquals(listOf(setOf("visible"), setOf("missing")), parentSelections.toList()) },
                    { assertEquals(0, descendantResolverCalls.get()) },
                    { assertEquals("parent-missing", checkerValue.get()) },
                )
            }
        }

        @Test
        fun `missing selections beyond a standard boundary do not refetch the parent`() {
            val parentCalls = AtomicInteger()
            val checkerValue = AtomicReference<String?>()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { visible: String, bar: Bar }
                    type Bar { initial: String, missing: String }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                parentCalls.incrementAndGet()
                                ParentManagedValue(
                                    ResolvedEngineObjectData(
                                        createEngineObjectData("Foo", emptyMap()).type,
                                        mapOf(
                                            "visible" to "parent-visible",
                                            "bar" to StandardResolutionValue(createEngineObjectData("Bar", emptyMap())),
                                        ),
                                    )
                                )
                            },
                        )
                    }
                }
                field("Foo" to "visible") {
                    checker {
                        objectSelections("required", "bar { missing }")
                        fn { _, objects ->
                            checkerValue.set(
                                objects.getValue("required").fetchAs<EngineObjectData>("bar").fetchAs<String>("missing")
                            )
                        }
                    }
                }
                field("Bar" to "initial") { resolver { fn { _, _, _, _, _ -> "child-initial" } } }
                field("Bar" to "missing") { resolver { fn { _, _, _, _, _ -> "child-missing" } } }
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { visible bar { initial } } }")
                    .assertJson("""{data: {foo: {visible: "parent-visible", bar: {initial: "child-initial"}}}}""")
            }

            assertEquals(1, parentCalls.get())
            assertEquals("child-missing", checkerValue.get())
        }
    }

    @Nested
    inner class CoverageTests {
        @Test
        fun `configured reader supplies returned coverage and path reads`() {
            val fooCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo:Foo }
                    type Foo { x:Int, bar:Bar }
                    type Bar { y:Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData("Foo", mapOf("bar" to emptyMap<String, Any?>()))
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("bar { y }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("bar").fetchAs<Int>("y") * 3
                        }
                    }
                }
            }.runFeatureTest(EngineConfiguration.featureTestDefault.copy(materializedFieldValueReader = barWithY)) {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 15}}}")
            }

            assertEquals(1, fooCalls.get())
        }
    }

    @Nested
    inner class RecursiveTests {
        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { foo:Foo }
                    | type Foo { x:Int, next:Foo, bar:Bar }
                    | type Bar { y:Int, foo:Foo }
                """.trimMargin()
            )
    }

    @Nested
    inner class ListTests {
        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { foos:[Foo] }
                    | type Foo { x:Int, bars:[Bar] }
                    | type Bar { y:Int }
                """.trimMargin()
            )
    }

    @Nested
    inner class AbstractTypeTests {
        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | interface Item { x:Int }
                    | type Foo implements Item { x:Int, y:Int }
                    | type Bar implements Item { x:Int, z:Int }
                    | union Result = Foo | Bar
                    | extend type Query { item:Item, results:[Result!]! }
                """.trimMargin()
            )
    }

    @Nested
    inner class VariablesTests {
        @Test
        fun `field AST arguments resolve with Mat enabled`() {
            assertFieldAstArguments(matEnabled = true)
        }

        @Test
        fun `field AST arguments resolve with Mat disabled`() {
            assertFieldAstArguments(matEnabled = false)
        }

        /**
         * Mat changes the variables available during a rerun. The original field arguments must still
         * resolve to the same values when a consumer, such as delegation, reads the GraphQL AST.
         */
        private fun assertFieldAstArguments(matEnabled: Boolean) {
            val instrumentation = RecordingInstrumentation()
            val expectedId = "listing-1"

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo(id: ID!): Foo }
                    type Foo { x: String, label(locale: String): String }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { arguments, _, _, _, _ ->
                                assertEquals(expectedId, arguments["id"])
                                createEngineObjectData(
                                    "Foo",
                                    mapOf("label" to "Listing 1"),
                                )
                            },
                        )
                    }
                }
                field("Foo" to "x") {
                    resolver {
                        // Argument-specific coverage requires a Mat rerun even when label is populated.
                        objectSelections("label(locale: \"en\")")
                        fn { _, obj, _, _, _ -> obj.fetchAs<String>("label") }
                    }
                }
            }.runEngineFeatureTest(
                engineConfig = EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation,
                    flagManager = if (matEnabled) {
                        MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION)
                    } else {
                        MockFlagManager.create()
                    },
                ),
            ) {
                runQueryWithTimeout(
                    "query(\$id: ID!) { foo(id: \$id) { x } }",
                    variables = mapOf("id" to "listing-1"),
                ).assertJson("{data: {foo: {x: \"Listing 1\"}}}")
            }

            val environments = instrumentation.dataFetchingEnvironments.filter {
                it.executionStepInfo.path.toString() == "/foo"
            }
            assertEquals(if (matEnabled) 2 else 1, environments.size)
            environments.forEach { env ->
                val context = env.engineExecutionContext
                val arguments = QueryTraverser.newQueryTraverser()
                    .schema(context.fullSchema.schema)
                    .root(SelectionSet(env.mergedField.fields))
                    .rootParentType(env.parentType as GraphQLCompositeType)
                    .fragmentsByName(context.fieldScope.fragments)
                    .variables(context.fieldScope.variables)
                    .build()
                    .reducePreOrder<Map<String, Any?>>(
                        { field, acc -> if (field.field.name == "foo") field.arguments else acc },
                        emptyMap(),
                    )
                assertEquals(expectedId, arguments["id"])
            }
        }

        @Test
        fun `selective field inputs survive rematerialization`() {
            var initialRequestContext: Any? = null

            MockTenantModuleBootstrapper(
                """
                    extend type Query { x: Int, foo(y: Int!): Foo }
                    type Foo { x: Int, y: Int }
                """.trimIndent()
            ) {
                fieldWithValue("Query" to "x", 2)

                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            objectSelectionSet = createRSS("Query", "x"),
                            resolverId = resolverId,
                            unbatchedResolveFn = { arguments, obj, _, sels, context ->
                                val isMaterialization = sels!!.containsField("Foo", "y")
                                if (isMaterialization) {
                                    check(context.requestContext === initialRequestContext)
                                } else {
                                    initialRequestContext = context.requestContext
                                }
                                createEngineObjectData(
                                    "Foo",
                                    buildMap {
                                        if (isMaterialization) {
                                            put(
                                                "y",
                                                obj.fetchAs<Int>("x") * arguments.getAs<Int>("y"),
                                            )
                                        }
                                    },
                                )
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout(
                    "query(\$y: Int!) { foo(y: \$y) { x } }",
                    variables = mapOf("y" to 3),
                ).assertJson("{data: {foo: {x: 30}}}")
            }
        }

        @Test
        fun `variable rss does not use skipped child object plan`() {
            // Query.b has a runtime-dependent object RSS field whose variable resolver needs
            // `foo { z y }`. Query.foo is selective, so it materializes scalar Foo fields only when
            // they are selected. The requested `z` field is itself a selective resolver, and Foo.z
            // needs parent object data from its own object RSS: `y`.
            //
            // A runtime-skipped Query.a branch also plans `b` and `foo { z y }`. That unexecuted
            // branch creates another Foo.z object-RSS plan with the same RSS id as the active
            // variable-RSS path. If runtime chooses the skipped branch's plan for Foo.z's object RSS,
            // Foo.z waits for a `y` value that will never be produced.

            MockTenantModuleBootstrapper(
                """
                    extend type Query { a: Int, b: Int, foo: Foo @resolver }
                    type Foo { y: Int, z: Boolean }
                """.trimIndent()
            ) {
                field("Query" to "b") {
                    resolver {
                        objectSelections("__typename @include(if: ${"$"}includeFoo)") {
                            variables(
                                "includeFoo",
                                rss = createRSS("Query", "foo { z y }")
                            ) { ctx, _ ->
                                val foo = ctx.objectData.getAs<EngineObjectData.Sync>("foo")
                                val includeFoo = foo.getAs<Boolean>("z")
                                mapOf("includeFoo" to includeFoo)
                            }
                        }
                        fn { _, _, _, _, _ -> 2 }
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

                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, sels, _ ->
                                val values = buildMap {
                                    if (sels!!.containsField("Foo", "y")) {
                                        put("y", 4)
                                    }
                                }
                                createEngineObjectData("Foo", values)
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
            }.runFeatureTest {
                runQueryWithTimeout(
                    """
                        query (${"$"}includeA: Boolean! = false) {
                          b
                          a @include(if: ${"$"}includeA)
                        }
                    """.trimIndent()
                ).assertJson("{data: {b: 2}}")
            }
        }

        @Test
        fun `embedded materialization preserves ancestor argument variables across child plans`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { bar(y: Int!): Bar }
                    type Bar { x: Int, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, sels, _ ->
                                val barSelections = sels!!.selectionSetForField("Foo", "bar")
                                createEngineObjectData(
                                    "Foo",
                                    mapOf(
                                        "bar" to createEngineObjectData(
                                            "Bar",
                                            buildMap {
                                                if (barSelections.containsField("Bar", "y")) {
                                                    val id = sels
                                                        .argumentsOfSelection("Foo", "bar")
                                                        ?.get("y") as Int
                                                    put("y", id)
                                                }
                                            },
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }

                field("Bar" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout(
                    "query(\$y: Int!) { foo { bar(y: \$y) { x } } }",
                    variables = mapOf("y" to 2),
                ).assertJson("{data: {foo: {bar: {x: 10}}}}")
            }
        }

        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { foo(x:Int!):Foo }
                    | enum E { A, B }
                    | input Inp { a:Int, b:String, c:[[Int]], d:Inp, e:E }
                    | type Foo { x:Int, y(a:Int!):Int, bar(inp:Inp = {a: 2}):Bar }
                    | type Bar { x:Int, y:Int }
                """.trimMargin()
            )
    }

    @Nested
    inner class BatchedTests {
        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { foo(z:Int!):Foo, foos(z:Int!):[Foo!]!, bars:[Bar!]! }
                    | type Foo { x:Int, y(z:Int!):Int, bar(z:Int!):Bar, bars:[Bar!]! }
                    | type Bar { x:Int, y(z:Int!):Int }
                """.trimMargin(),
                cfg = defaultCfg + (BatchingResolverWeight to .8)
            )
    }

    @Nested
    inner class CheckerTests {
        @Test
        fun `type checker reads conditional field from selective source`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { bar:Bar, foo:Foo }
                    type Foo { bar:Bar, includeBar:Boolean }
                    type Bar { y:Int }
                """.trimIndent()
            ) {
                field("Query" to "bar") {
                    resolver {
                        objectSelections("foo { bar @include(if: \$var) { y } }") {
                            variables(
                                "var",
                                rss = createRSS("Query", "foo { includeBar }"),
                            ) { ctx, _ ->
                                val includeBar = ctx.objectData
                                    .getAs<EngineObjectData.Sync>("foo")
                                    .getAs<Boolean>("includeBar")
                                mapOf("var" to includeBar)
                            }
                        }
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("foo")
                                .fetchAs<EngineObjectData>("bar")
                        }
                    }
                }

                fieldWithValue("Foo" to "includeBar", true)

                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, sels, _ ->
                                createEngineObjectData(
                                    "Foo",
                                    if (sels!!.containsField("Foo", "bar")) {
                                        mapOf(
                                            "bar" to createEngineObjectData("Bar", mapOf("y" to 2))
                                        )
                                    } else {
                                        emptyMap()
                                    }
                                )
                            }
                        )
                    }
                }

                type("Bar") {
                    checker {
                        objectSelections("fields", "checked:y")
                        fn { _, objects ->
                            objects.getValue("fields").fetchAs<Int>("checked")
                        }
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ bar { __typename } }")
                    .assertJson("{data: {bar: {__typename: \"Bar\"}}}")
            }
        }

        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { foo:Foo, bars:[Bar] }
                    | type Foo { x:Int, y:Int, bar:Bar }
                    | type Bar { x:Int, y:Int }
                """.trimMargin(),
                cfg = defaultCfg + (FieldCheckerWeight to .8) + (TypeCheckerWeight to .8),
            )
    }

    @Nested
    inner class SelectionSetTests {
        @Test
        fun `materialization output selections preserve path-specific concrete ownership`() {
            var materializationSelections: EngineSelectionSet? = null

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { x: Int @resolver, bar: Qux, baz: Qux }
                    interface Qux { x: Int, y: Int }
                    type QuxA implements Qux { x: Int, y: Int @resolver }
                    type QuxB implements Qux { x: Int @resolver, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, sels, _ ->
                                val values = buildMap {
                                    if (sels!!.containsField("Foo", "bar")) {
                                        put(
                                            "bar",
                                            createEngineObjectData("QuxA", mapOf("x" to 2))
                                        )
                                    }

                                    if (sels.containsField("Foo", "baz")) {
                                        put(
                                            "baz",
                                            createEngineObjectData("QuxB", mapOf("y" to 3))
                                        )
                                    }
                                }
                                if (values.isNotEmpty()) {
                                    materializationSelections = sels
                                }
                                createEngineObjectData("Foo", values)
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections(
                            """
                                bar { x }
                                baz { y }
                            """.trimIndent()
                        )
                        fn { _, obj, _, _, _ ->
                            val bar = obj.fetchAs<EngineObjectData>("bar")
                            val baz = obj.fetchAs<EngineObjectData>("baz")
                            bar.fetchAs<Int>("x") * baz.fetchAs<Int>("y")
                        }
                    }
                }

                fieldWithValue("QuxA" to "y", 5)

                fieldWithValue("QuxB" to "x", 7)
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 6}}}")
            }

            val selections = checkNotNull(materializationSelections)

            assertEquals(
                mapOf(
                    "bar" to setOf(EngineSelection("QuxA", "x", "x")),
                    "baz" to setOf(EngineSelection("QuxB", "y", "y")),
                ),
                mapOf(
                    "bar" to
                        selections
                            .selectionSetForField("Foo", "bar")
                            .selections().toSet(),
                    "baz" to
                        selections
                            .selectionSetForField("Foo", "baz")
                            .selections().toSet(),
                ),
            )
        }

        @Test
        fun `resolver hydrates unselected fields outside its output selection set`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { x: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                createEngineObjectData("Foo", mapOf("x" to 2))
                            }
                        )
                    }
                }

                fieldWithValue("Foo" to "x", 3)
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 3}}}")
            }
        }
    }

    private val barWithY = MaterializedFieldValueReader { source, fieldName, responseKey ->
        val read = MaterializedFieldValueReader.Default.read(source, fieldName, responseKey)
        val bar = read.value
        if (source.type.name == "Foo" && fieldName == "bar" && bar is EngineObjectData) {
            read.copy(value = ResolvedEngineObjectData(bar.type, mapOf("y" to 5)))
        } else {
            read
        }
    }

    private val matOnlyFlags =
        MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION)

    private fun MockTenantModuleBootstrapper.runFeatureTest(
        engineConfig: EngineConfiguration = EngineConfiguration.featureTestDefault,
        block: FeatureTest.() -> Unit,
    ) {
        runEngineFeatureTest(
            engineConfig = engineConfig.copy(flagManager = matOnlyFlags),
            block = block,
        )
    }

    /** Helper class for managing deep Arb tests */
    abstract class SelectiveFieldArbTest(
        val sdl: String,
        val cfg: Config = defaultCfg,
        seed: Long = Random.nextLong(),
        iterations: Int = 100
    ) : DeepArbSuite<Pair<Viaduct, ExecutionInput>>(seed, iterations) {
        override val comparator = ViaductAndInputComparator

        override val checkedArb: CheckedArb<Pair<Viaduct, ExecutionInput>>
            get() {
                val schema = sdl.asViaductSchema

                return arbitrary {
                    val viaduct = Arb.viaduct(schema, cfg).bind()
                    val input = Arb.viaductExecutionInput(schema, cfg).bind()
                    viaduct to input
                }.withCheck { (viaduct, input) ->
                    val result = runCatching { viaduct.runQueryWithTimeout(input) }
                    assertTrue(result.getOrNull()?.errors?.isEmpty() == true) {
                        dump(viaduct, input, result)
                    }
                }
            }

        companion object {
            val defaultCfg: Config = Config.default +
                (UndeclaredFieldResolverWeight to .25) +
                (SelectiveResolverWeight to .5) +
                (DeterministicResolveWeight to 1.0) +
                // SelectedTypeBias can override the behavior of DeterministicResolveWeight, causing
                //  re-executing fields to return different values when called with different selections.
                //  Disabling this feature allows for true deterministic resolver behavior
                (SelectedTypeBias to 0.0) +
                (ResolverFieldRefWeight to 0.0) +
                (VariablesResolverExceptionWeight to 0.0) +
                (CheckerErrorWeight to 0.0) +
                (CheckerExceptionWeight to 0.0) +
                (FieldCheckerWeight to 0.0) +
                (TypeCheckerWeight to 0.0) +
                (NodeResolverExceptionWeight to 0.0) +
                (FieldResolverExceptionWeight to 0.0) +
                (FieldResolverFactory to FieldResolver.Factory.Instrumented()) +
                (CheckerExecutorFactory to CheckerExecutor.Factory.Instrumented())
        }
    }
}
