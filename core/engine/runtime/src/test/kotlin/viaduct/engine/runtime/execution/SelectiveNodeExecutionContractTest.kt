@file:Suppress("DEPRECATION")

package viaduct.engine.runtime.execution

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import viaduct.arbitrary.common.CheckedArb
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.DeepArbSuite
import viaduct.arbitrary.common.withCheck
import viaduct.arbitrary.graphql.BatchingResolverWeight
import viaduct.arbitrary.graphql.CheckerErrorWeight
import viaduct.arbitrary.graphql.CheckerExceptionWeight
import viaduct.arbitrary.graphql.DeterministicResolveWeight
import viaduct.arbitrary.graphql.FieldCheckerWeight
import viaduct.arbitrary.graphql.FieldResolverExceptionWeight
import viaduct.arbitrary.graphql.NodeResolverExceptionWeight
import viaduct.arbitrary.graphql.ResolverFieldRefWeight
import viaduct.arbitrary.graphql.SelectedTypeBias
import viaduct.arbitrary.graphql.SelectiveResolverWeight
import viaduct.arbitrary.graphql.TypeCheckerWeight
import viaduct.arbitrary.graphql.UndeclaredFieldResolverWeight
import viaduct.arbitrary.graphql.UndeclaredNodeResolverWeight
import viaduct.arbitrary.graphql.VariableWeight
import viaduct.arbitrary.graphql.VariablesResolverExceptionWeight
import viaduct.arbitrary.graphql.asViaductSchema
import viaduct.arbitrary.graphql.viaduct
import viaduct.arbitrary.graphql.viaductExecutionInput
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.Caller
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.invocationContextFor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.api.spi.MaterializedFieldValueReader
import viaduct.engine.runtime.Engine1QueryExecutionContract
import viaduct.errors.TenantException
import viaduct.errors.TenantResolverException
import viaduct.graphql.test.assertMatches
import viaduct.service.api.ExecutionInput
import viaduct.service.api.GraphQLError
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.service.api.spi.mocks.MockFlagManager

class SelectiveNodeExecutionContractTest : SelectiveNodeExecutionContract, Engine1QueryExecutionContract {
    private val barWithY = MaterializedFieldValueReader { source, fieldName, responseKey ->
        val read = MaterializedFieldValueReader.Default.read(source, fieldName, responseKey)
        val bar = read.value
        if (source.type.name == "Foo" && fieldName == "bar" && bar is EngineObjectData) {
            read.copy(value = ResolvedEngineObjectData(bar.type, mapOf("y" to 5)))
        } else {
            read
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `abstract fields launch required selections only for their concrete type`(originFilteringKillSwitch: Boolean) {
        val barVariableCalls = AtomicInteger()
        val fooId = GlobalIDCodecDefault.serialize("Foo", "foo")
        val barId = GlobalIDCodecDefault.serialize("Bar", "bar")

        MockTenantModuleBootstrapper(
            """
                type Foo implements Node { id: ID! }
                type Bar implements Node { id: ID! }
            """.trimIndent()
        ) {
            for (typeName in listOf("Foo", "Bar")) {
                type(typeName) {
                    nodeUnbatchedExecutor { _, _, _ -> createEngineObjectData(objectType, emptyMap()) }
                }
            }
            field("Foo" to "id") {
                resolver {
                    querySelections("__typename")
                    fn { _, _, _, _, _ -> "foo" }
                }
            }
            field("Bar" to "id") {
                resolver {
                    querySelections("__typename @include(if: ${'$'}include)") {
                        variables("include") { ctx, _ ->
                            barVariableCalls.incrementAndGet()
                            assertEquals("Bar", ctx.objectData.type.name)
                            mapOf("include" to true)
                        }
                    }
                    fn { _, _, _, _, _ -> "bar" }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                flagManager = if (originFilteringKillSwitch) {
                    MockFlagManager.create(FlagManager.Flags.KILLSWITCH_FIELD_RSS_ORIGIN_FILTERING)
                } else {
                    MockFlagManager.Disabled
                },
            ),
        ) {
            runQueryWithTimeout("""{ node(id: "$fooId") { id } }""")
                .assertJson("""{data: {node: {id: "foo"}}}""")
            assertEquals(0, barVariableCalls.get())

            runQueryWithTimeout("""{ node(id: "$barId") { id } }""")
                .assertJson("""{data: {node: {id: "bar"}}}""")
            assertTrue(barVariableCalls.get() > 0)
        }
    }

    @Nested
    inner class ErrorTests {
        @ParameterizedTest
        @CsvSource("false, x", "true, x", "false, id __typename", "true, id __typename")
        fun `selective node failures preserve embedder error classification`(
            matEnabled: Boolean,
            fields: String
        ) {
            val failure = MissingNodeException()
            val reported = mutableListOf<Throwable>()
            val handler = ViaductDataFetcherExceptionHandler(
                ErrorReporter { error, _, _ -> reported.add(error) },
                ResolverErrorBuilder { error, _ ->
                    if (error is MissingNodeException) {
                        listOf(GraphQLError(message = error.message!!, extensions = mapOf("errorType" to "NOT_FOUND", "fatal" to false)))
                    } else {
                        null
                    }
                },
            )

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID!, x: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }
                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        throw TenantResolverException(failure, "Node:Foo")
                    }
                }
            }.runFeatureTest(
                engineConfig = EngineConfiguration.featureTestDefault.copy(
                    flagManager = if (matEnabled) MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION) else MockFlagManager.Disabled,
                    dataFetcherExceptionHandler = handler,
                )
            ) {
                val result = runQueryWithTimeout("{ foo { $fields } }")
                assertEquals(mapOf("foo" to null), result.getData())
                val error = result.errors.single()
                assertEquals(listOf("foo"), error.path)
                assertEquals("NOT_FOUND", error.extensions["errorType"])
                assertEquals(false, error.extensions["fatal"])
            }

            assertSame(failure, reported.single())
        }

        @Test
        fun `materialization invariant failures remain internal errors`() {
            val reported = mutableListOf<Throwable>()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID!, bar: Bar }
                    type Bar { x: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }
                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        createEngineObjectData(
                            objectType,
                            mapOf("bar" to createEngineObjectData("Bar", mapOf("x" to 1))),
                        )
                    }
                }
            }.runFeatureTest(
                engineConfig = EngineConfiguration.featureTestDefault.copy(
                    dataFetcherExceptionHandler = ViaductDataFetcherExceptionHandler(
                        ErrorReporter { error, _, _ -> reported.add(error) },
                        ResolverErrorBuilder.NOOP,
                    ),
                    materializedFieldValueReader = MaterializedFieldValueReader { _, _, _ -> error("broken materialization") },
                )
            ) {
                val result = runQueryWithTimeout("{ foo { bar { x } } }")
                assertEquals(mapOf("foo" to null), result.getData())
                assertEquals(listOf("foo"), result.errors.single().path)
            }

            assertInstanceOf(InternalEngineException::class.java, reported.single())
            assertTrue(reported.single().message!!.contains("broken materialization"))
        }
    }

    private class MissingNodeException : RuntimeException("node not found"), TenantException

    @Nested
    inner class BasicExecutionTests {
        @Test
        fun `engine-managed node fields still resolve the node reference`() {
            val nodeCalls = AtomicInteger(0)
            lateinit var nodeReference: NodeEngineObjectData
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID!, x: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext {
                        it.createNodeReference("Foo:1", objectType("Foo"))
                            .also { reference -> nodeReference = reference as NodeEngineObjectData }
                    }
                }
                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        nodeCalls.incrementAndGet()
                        createEngineObjectData(objectType, mapOf("x" to 2))
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { id __typename } }")
                    .assertJson("{data: {foo: {id: \"Foo:1\", __typename: \"Foo\"}}}")
            }
            assertEquals(1, nodeCalls.get())

            runTest {
                assertEquals(2, withTimeout(1.seconds) { nodeReference.fetchAs<Int>("x") })
            }
        }

        @Test
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

                fieldWithValue("Impl1" to "y", -1)

                fieldWithValue("Impl2" to "x", -1)
            }.runFeatureTest {
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

        @Test
        fun `fully skipped selection still resolves node reference`() {
            lateinit var nodeReference: NodeEngineObjectData

            MockTenantModuleBootstrapper(
                """
                    | extend type Query { foo: Foo }
                    | type Foo implements Node { id: ID!, x: Int }
                """.trimMargin()
            ) {
                field("Query" to "foo") {
                    valueFromContext {
                        it.createNodeReference("foo", objectType("Foo"))
                            .also { reference -> nodeReference = reference as NodeEngineObjectData }
                    }
                }
                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        createEngineObjectData(objectType, mapOf("x" to 2))
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { id @skip(if: true) } }")
                    .assertJson("{data: {foo: {}}}")
            }

            runTest {
                assertEquals(2, withTimeout(1.seconds) { nodeReference.fetchAs<Int>("x") })
            }
        }

        @Nested
        inner class ArbitraryTests :
            SelectiveNodeArbTest(
                """
                    | type Foo implements Node { id:ID!, x:Int!, bar:Bar }
                    | type Bar implements Node { id:ID!, y:Int!, foo:Foo }
                    | extend type Query { foo:Foo, bar:Bar }
                """.trimMargin()
            )
    }

    @Nested
    inner class ParentFields {
        @Test
        fun `resolver reads its selective node parent`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID!, x: Int, bar: Bar }
                    type Bar { parent: Foo @parent, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("1", objectType("Foo")) }
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
                field("Foo" to "bar") {
                    resolver {
                        fn { _, _, _, _, _ -> createEngineObjectData("Bar") }
                    }
                }
                field("Bar" to "y") {
                    resolver {
                        objectSelections("parent { x }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("parent").fetchAs<Int>("x") + 1
                        }
                    }
                }
            }.runFeatureTest {
                runQuery("{ foo { bar { y } } }")
                    .assertJson("{data: {foo: {bar: {y: 3}}}}")
            }
        }

        @Test
        fun `parent traversal does not re-invoke the selective node resolver for already-resolved data`() {
            val nodeCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID!, x: Int, bar: Bar }
                    type Bar { parent: Foo @parent, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("1", objectType("Foo")) }
                }
                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                        nodeCalls.incrementAndGet()
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
                field("Foo" to "bar") {
                    resolver {
                        fn { _, _, _, _, _ -> createEngineObjectData("Bar") }
                    }
                }
                field("Bar" to "y") {
                    resolver {
                        objectSelections("parent { x }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("parent").fetchAs<Int>("x") + 1
                        }
                    }
                }
            }.runFeatureTest {
                runQuery("{ foo { x bar { y } } }")
                    .assertJson("{data: {foo: {x: 2, bar: {y: 3}}}}")
            }

            assertEquals(1, nodeCalls.get())
        }

        @Test
        fun `each list item resolver reads its own selective node parent`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foos: [Foo] }
                    type Foo implements Node { id: ID!, x: Int, bar: Bar }
                    type Bar { parent: Foo @parent, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foos") {
                    valueFromContext {
                        listOf(
                            it.createNodeReference("1", objectType("Foo")),
                            it.createNodeReference("2", objectType("Foo")),
                        )
                    }
                }
                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { id, sels, _ ->
                        createEngineObjectData(
                            objectType,
                            buildMap {
                                if (sels!!.containsField("Foo", "x")) {
                                    put("x", id.toInt())
                                }
                            }
                        )
                    }
                }
                field("Foo" to "bar") {
                    resolver {
                        fn { _, _, _, _, _ -> createEngineObjectData("Bar") }
                    }
                }
                field("Bar" to "y") {
                    resolver {
                        objectSelections("parent { x }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("parent").fetchAs<Int>("x") * 2
                        }
                    }
                }
            }.runFeatureTest {
                runQuery("{ foos { bar { y } } }")
                    .assertJson("{data: {foos: [{bar: {y: 2}}, {bar: {y: 4}}]}}")
            }
        }

        @Test
        fun `aliased selections of a selective node preserve parent traversal`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo(id: Int): Foo }
                    type Foo implements Node { id: ID!, x: Int, bar: Bar }
                    type Bar { parent: Foo @parent, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolver {
                        fn { args, _, _, _, ctx ->
                            ctx.createNodeReference(args.getAs<Int>("id").toString(), objectType("Foo"))
                        }
                    }
                }
                type("Foo") {
                    nodeUnbatchedExecutor(selective = true) { id, sels, _ ->
                        createEngineObjectData(
                            objectType,
                            buildMap {
                                if (sels!!.containsField("Foo", "x")) {
                                    put("x", id.toInt() * 10)
                                }
                            }
                        )
                    }
                }
                field("Foo" to "bar") {
                    resolver {
                        fn { _, _, _, _, _ -> createEngineObjectData("Bar") }
                    }
                }
                field("Bar" to "y") {
                    resolver {
                        objectSelections("parent { x }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("parent").fetchAs<Int>("x") + 1
                        }
                    }
                }
            }.runFeatureTest {
                runQuery("{ a: foo(id: 1) { bar { y } } b: foo(id: 2) { bar { y } } }")
                    .assertJson("{data: {a: {bar: {y: 11}}, b: {bar: {y: 21}}}}")
            }
        }

        @Nested
        inner class ArbitraryTests :
            SelectiveNodeArbTest(
                """
                    | extend type Query { foo:Foo! }
                    | type Foo implements Node { id:ID!, x:Int!, bar:Bar!, bars:[Bar!]! }
                    | type Bar { parent:Foo! @parent, x:Int! }
                """.trimMargin()
            )
    }

    @Nested
    inner class AbstractTypeTests {
        @Nested
        inner class ArbitraryTests :
            SelectiveNodeArbTest(
                """
                    | type Foo implements Node { id:ID!, x:Int, bar:Bar }
                    | type Bar implements Node { id:ID!, y:Int, foo:Foo }
                """.trimMargin()
            )
    }

    @Nested
    inner class CoverageTests {
        @Test
        fun `configured reader supplies returned coverage and path reads`() {
            val fooCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo implements Node { id: ID!, x: Int, bar: Bar }
                    type Bar { y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
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
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        fooCalls.incrementAndGet()
                        createEngineObjectData(objectType, mapOf("bar" to createEngineObjectData("Bar", emptyMap())))
                    }
                }
            }.runFeatureTest(engineConfig = EngineConfiguration.featureTestDefault.copy(materializedFieldValueReader = barWithY)) {
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
            SelectiveNodeArbTest(
                """
                    | extend type Query { foo:Foo }
                    | type Foo implements Node { id:ID!, foo:Foo, bars:[Bar] }
                    | type Bar implements Node { id:ID!, foo:Foo, bars:[Bar] }
                """.trimMargin()
            )
    }

    @Nested
    inner class ListTests {
        @Nested
        inner class ArbitraryTests :
            SelectiveNodeArbTest(
                """
                    | extend type Query { foo:Foo, foos:[Foo] }
                    | type Foo implements Node { id:ID!, x:Int, bars:[Bar] }
                    | type Bar { y:Int, foos:[Foo] }
                """.trimMargin()
            )
    }

    @Nested
    inner class VariablesTests {
        @Test
        fun `variable rss does not use skipped child object plan`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { a: Int, b: Int, foo: Foo }
                    type Foo implements Node { id: ID!, y: Int, z: Boolean }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    valueFromContext { it.createNodeReference("foo", objectType("Foo")) }
                }

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
                    nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                        createEngineObjectData(
                            objectType,
                            buildMap {
                                if (sels!!.containsField("Foo", "y")) {
                                    put("y", 4)
                                }
                            },
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

        @Nested
        inner class ArbitraryTests :
            SelectiveNodeArbTest(
                """
                    | extend type Query { foo(x:Int!):Foo }
                    | enum E { A, B }
                    | input Inp { a:Int, b:String, c:[[Int]], d:Inp, e:E }
                    | type Foo implements Node {
                    |   id:ID!, x:Int, y(a:Int!):Int, bar(inp:Inp = {a: 2}):Bar
                    | }
                    | type Bar implements Node { id:ID!, x:Int, y:Int }
                """.trimMargin()
            )
    }

    @Nested
    inner class CheckerTests {
        @Nested
        inner class ArbitraryTests :
            SelectiveNodeArbTest(
                """
                    | extend type Query { foo:Foo bars:[Bar] }
                    | type Foo implements Node { id:ID!, x:Int, y:Int, bar:Bar }
                    | type Bar implements Node { id:ID!, x:Int, y:Int }
                """.trimMargin(),
                cfg = defaultCfg + (FieldCheckerWeight to .8) + (TypeCheckerWeight to .8),
                minViolationIterations = 100_000
            )
    }

    @Nested
    inner class BatchedTests {
        @Test
        fun `batched node refetches preserve selection shapes`() {
            val batches = mutableListOf<List<Pair<String, Set<String>>>>()
            val callers =
                mutableListOf<Pair<Pair<String, Set<String>>, Caller?>>()

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
                    nodeBatchedExecutor(selective = true) { selectors, context ->
                        batches += selectors.map { selector ->
                            val shape = selector.id to
                                selector.selections
                                    .selections()
                                    .map { it.fieldName }
                                    .toSet()
                            val caller =
                                context.invocationContextFor(selector).fieldScope.caller
                            callers += shape to caller
                            shape
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
            }.runFeatureTest {
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
            assertEquals(
                setOf(
                    ("2" to setOf("x")) to Caller(null, "Query", "foo"),
                    ("3" to setOf("y")) to Caller(null, "Query", "bar"),
                    ("2" to setOf("z")) to Caller(null, "Query", "foo"),
                    ("3" to setOf("w")) to Caller(null, "Query", "bar"),
                ),
                callers.toSet(),
            )
        }

        @Test
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
                        argumentVariables("a" to "a")
                        objectSelections("y(a:\$a)")
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
            }.runFeatureTest {
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

        @Nested
        inner class ArbitraryTests :
            SelectiveNodeArbTest(
                """
                    | extend type Query {
                    |   foo(x:Int!):Foo
                    |   foos(x:Int!):[Foo!]!
                    |   bars:[Bar!]!
                    | }
                    | type Foo implements Node {
                    |   id:ID!, x:Int, y(z:Int!):Int, bar(z:Int!):Bar, bars:[Bar!]!
                    | }
                    | type Bar implements Node { id:ID!, x:Int, y(x:Int!):Int }
                """.trimMargin(),
                cfg = defaultCfg + (BatchingResolverWeight to .8)
            )
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
            }.runFeatureTest(
                engineConfig = EngineConfiguration.featureTestDefault.copy(
                    flagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION),
                )
            ) {
                runQueryWithTimeout("{ foo { y x } }")
                    .assertJson("{data: {foo: {y: null, x: 6}}}")
            }

            assertEquals(1, nodeCalls.get())
        }
    }

    /** Helper class for managing deep Arb tests */
    abstract class SelectiveNodeArbTest(
        val sdl: String,
        val cfg: Config = defaultCfg,
        seed: Long = Random.nextLong(),
        iterations: Int = 100,
        minViolationIterations: Int = 10_000
    ) : DeepArbSuite<Pair<Viaduct, ExecutionInput>>(
            seed,
            iterations,
            minViolationIterations
        ) {
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
                (UndeclaredNodeResolverWeight to 1.0) +
                (DeterministicResolveWeight to 1.0) +
                (SelectedTypeBias to 0.0) +
                (ResolverFieldRefWeight to 0.0) +
                (VariableWeight to .25) +
                (SelectiveResolverWeight to .5) +
                (VariablesResolverExceptionWeight to 0.0) +
                (FieldCheckerWeight to 0.0) +
                (TypeCheckerWeight to 0.0) +
                (CheckerErrorWeight to 0.0) +
                (CheckerExceptionWeight to 0.0) +
                (FieldResolverExceptionWeight to 0.0) +
                (NodeResolverExceptionWeight to 0.0)
        }
    }
}
