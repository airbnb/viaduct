@file:Suppress("ForbiddenImport")
@file:OptIn(VisibleForTest::class, ExperimentalTime::class)

package viaduct.arbitrary.graphql

import graphql.schema.idl.SchemaPrinter
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.apiannotations.VisibleForTest
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.CompoundingWeight.Companion.Never
import viaduct.arbitrary.common.CompoundingWeight.Companion.Once
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.asIntRange
import viaduct.arbitrary.common.asLongRange
import viaduct.arbitrary.common.asSequence
import viaduct.arbitrary.common.mapNotNull
import viaduct.arbitrary.common.withCheck
import viaduct.engine.EngineImpl
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.VariablesResolver as EngineVariablesResolver
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime2.Engine2
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct
import viaduct.service.runtime.globalid.DefaultGlobalIDCodec

class ViaductsTest : KotestPropertyBase(seed = paritySeed, iterations = 100) {
    // set up a base cfg with most features disabled
    private val cfg = Config.default +
        (ExplicitNullValueWeight to 0.0) +
        (VariableWeight to 0.0) +
        (FieldResolverExceptionWeight to 0.0) +
        (NodeResolverExceptionWeight to 0.0) +
        (VariablesResolverExceptionWeight to 0.0) +
        (AppliedDirectiveWeight to Never) +
        (RequiredSelectionSetWeight to Never) +
        (ExerciseRequiredSelectionsWeight to 0.0) +
        (ResolverLatencyMillis to 0.asLongRange()) +
        (FieldCheckerWeight to 0.0) +
        (TypeCheckerWeight to 0.0) +
        (CheckerExceptionWeight to 0.0) +
        (CheckerErrorWeight to 0.0)

    @ParameterizedTest(name = "generated service engine2={0}")
    @ValueSource(booleans = [false, true])
    fun `generated service constructs and executes the selected engine`(engine2Enabled: Boolean): Unit =
        runBlocking {
            val viaduct = Arb.viaduct(
                "extend type Query { x: Int! @resolver }".asViaductSchema,
                cfg,
                engine2Enabled,
            ).next(RandomSource.seeded(paritySeed))
            val service = assertInstanceOf(StandardViaduct::class.java, (viaduct as DescribedViaduct).delegate)
            assertInstanceOf(if (engine2Enabled) Engine2::class.java else EngineImpl::class.java, service.engineRegistry.getEngine(SchemaId.Base))

            val result = viaduct.execute(ExecutionInput.create("{ x }"))
            assertTrue(result.errors.isEmpty())
            assertTrue(result.getData()?.get("x") is Int)
        }

    @Test
    fun `runtime2 rejects unsupported generation configuration before sampling`() {
        val schema = "extend type Query { x: Int @resolver, y(a: Int): Int @resolver, z(a: Int): Int @resolver }".asViaductSchema
        val variables = VariablesResolver.Factory.Instrumented()
        val config = cfg +
            (VariableWeight to 1.0) +
            (RequiredSelectionSetWeight to CompoundingWeight(1.0, 3)) +
            (VariablesResolverFactory to variables)
        Arb.viaduct(schema, config, engine2Enabled = false).next(RandomSource.seeded(0))
        assertTrue(variables.allResolvers.any { it.requiredSelectionSet != null })

        val failure = assertThrows<IllegalArgumentException> {
            Arb.viaduct(schema, config, engine2Enabled = true)
        }
        assertTrue(failure.message.orEmpty().contains("Arb.viaduct(engine2Enabled = true)"))
        assertTrue(failure.message.orEmpty().contains("variables callbacks with their own required selection sets"))
        assertTrue(failure.message.orEmpty().contains("RequiredSelectionSetWeight.max"))
    }

    @Test
    fun `runtime2 default generation constructs engines without nested variables callbacks`(): Unit =
        runBlocking {
            val schema = "extend type Query { x: Int @resolver, y(a: Int): Int @resolver, z(a: Int): Int @resolver }".asViaductSchema
            Arb.viaduct(schema, engine2Enabled = true).checkAll(20) { viaduct ->
                val service = (viaduct as DescribedViaduct).delegate as StandardViaduct
                val constructed = runCatching { service.engineRegistry.getEngine(SchemaId.Base) }
                assertTrue(constructed.isSuccess, "${constructed.exceptionOrNull()}\n${viaduct.dump()}")
                assertInstanceOf(Engine2::class.java, constructed.getOrThrow())
            }
        }

    @Test
    fun `explicit portable configuration generates identical fixtures for both engines`() {
        val schema = "extend type Query { x: Int @resolver, y(a: Int): Int @resolver, z(a: Int): Int @resolver }".asViaductSchema
        val config = cfg +
            (VariableWeight to 1.0) +
            (RequiredSelectionSetWeight to CompoundingWeight(.5, 1)) +
            (FieldCheckerWeight to 1.0) +
            (TypeCheckerWeight to 1.0)
        for (seed in 0L until 10L) {
            val fixtures = listOf(false, true).map { engine2Enabled ->
                Arb.viaduct(schema, config, engine2Enabled).next(RandomSource.seeded(seed)).dump()
            }
            assertEquals(fixtures[0], fixtures[1], "seed=$seed")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `custom checker callbacks cannot bypass runtime2 configuration validation`(typeChecker: Boolean) {
        val schema = "extend type Query { value: Int @resolver, policy(value: Int): Int @resolver, source: Int @resolver }".asViaductSchema
        val nested = RequiredSelectionSet(SelectionsParser.parse("Query", "source"), emptyList(), forChecker = true)
        val variables = object : EngineVariablesResolver {
            override val variableNames = setOf("provided")
            override val requiredSelectionSet = nested

            override suspend fun resolve(
                ctx: EngineVariablesResolver.ResolveCtx,
                context: EngineExecutionContext,
            ): Map<String, Any?> = mapOf("provided" to 37)
        }
        val required = RequiredSelectionSet(
            SelectionsParser.parse("Query", "policy(value: \$provided)"),
            listOf(variables),
            forChecker = true,
        )
        val key = "Query" to if (typeChecker) null else "value"
        val checkers = CheckerExecutor.Factory { params ->
            CheckerExecutor.Factory.Arbitrary.createCheckerExecutor(
                params.copy(requiredSelectionSets = if (params.key == key) mapOf("input" to required) else emptyMap()),
            )
        }
        val config = cfg +
            (FieldCheckerWeight to if (typeChecker) 0.0 else 1.0) +
            (TypeCheckerWeight to if (typeChecker) 1.0 else 0.0) +
            (CheckerExecutorFactory to checkers)

        val failure = assertThrows<IllegalArgumentException> {
            Arb.viaduct(schema, config, engine2Enabled = true).next(RandomSource.seeded(0))
        }
        val location = if (typeChecker) "type checker Query" else "field checker Query.value"
        assertTrue(failure.message.orEmpty().contains(location), failure.message)
        assertTrue(failure.message.orEmpty().contains("variables callbacks with their own required selection sets"), failure.message)
    }

    @Nested
    inner class FieldResolverTests {
        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `simple`(engine2Enabled: Boolean): Unit =
            runBlocking {
                Arb.viaduct(
                    "extend type Query { x:Int! @resolver }".asViaductSchema,
                    cfg,
                    engine2Enabled = engine2Enabled,
                ).forAll { viaduct ->
                    val result = viaduct.execute(
                        ExecutionInput.create("{x}")
                    )
                    result.getData()?.get("x") is Int
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `ExplicitNullValueWeight`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = "extend type Query { x:Int @resolver }".asViaductSchema
                val input = ExecutionInput.create("{x}")

                // disabled
                Arb.viaduct(schema, cfg + (ExplicitNullValueWeight to 0.0), engine2Enabled = engine2Enabled)
                    .forAll { viaduct ->
                        val result = viaduct.execute(input)
                        result.getData()!!.get("x") != null
                    }

                // enabled
                Arb.viaduct(schema, cfg + (ExplicitNullValueWeight to 1.0), engine2Enabled = engine2Enabled)
                    .forAll { viaduct ->
                        val result = viaduct.execute(input)
                        result.getData()!!.get("x") == null
                    }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `RequiredSelectionSetWeight`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = "extend type Query { x:Int @resolver, y:Int @resolver }".asViaductSchema

                val arb = arbitrary {
                    val instr = FieldResolver.Factory.Instrumented()
                    val weight = Arb.of(Never, Once).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldResolverFactory to instr) +
                            (RequiredSelectionSetWeight to weight),
                        engine2Enabled = engine2Enabled,
                    ).bind()
                    viaduct.executeAsync(ExecutionInput.create("{ x }")).join()

                    weight to instr
                }

                arb.forAll { (weight, instr) ->
                    val params = instr.recorder.log
                        .first { it.arg.coordinate == ("Query" to "x") }
                        .arg

                    val hasRequiredSelections = params.objectSelectionSet != null || params.querySelectionSet != null

                    (weight.weight == 1.0) == hasRequiredSelections
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `ExerciseRequiredSelectionsWeight`(engine2Enabled: Boolean): Unit =
            runBlocking {
                Arb.viaduct(
                    "extend type Query { x:Int @resolver }".asViaductSchema,
                    cfg +
                        (RequiredSelectionSetWeight to Once) +
                        (ExerciseRequiredSelectionsWeight to 1.0),
                    engine2Enabled = engine2Enabled,
                ).forAll { viaduct ->
                    val result = viaduct.execute(ExecutionInput.create("{x}"))
                    result.errors.isEmpty() && result.getData()!!["x"] is Int
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `ResolverLatencyMillis`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val arb = arbitrary {
                    val instr = FieldResolver.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        "extend type Query { x:Int! @resolver }".asViaductSchema,
                        cfg +
                            (ResolverLatencyMillis to 100.asLongRange()) +
                            (FieldResolverFactory to instr),
                        engine2Enabled = engine2Enabled,
                    ).bind()
                    viaduct.executeAsync(ExecutionInput.create("{x}")).join()

                    instr.resolver("Query" to "x")
                }

                arb.forAll(3) { resolver ->
                    resolver.recorder.time > 100.milliseconds
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `FieldResolverExceptionWeight`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = "extend type Query { x:Int! @resolver }".asViaductSchema
                val input = ExecutionInput.create("{x}")

                // disabled
                Arb.viaduct(schema, cfg + (FieldResolverExceptionWeight to 0.0), engine2Enabled = engine2Enabled)
                    .forAll {
                        it.execute(input).errors.isEmpty()
                    }

                // enabled
                Arb.viaduct(schema, cfg + (FieldResolverExceptionWeight to 1.0), engine2Enabled = engine2Enabled)
                    .forAll {
                        it.execute(input).errors.size == 1
                    }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `SelectiveResolverWeight`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = """
                extend type Query { obj: Obj }
                type Obj { x:Int y:Int }
            """.asViaductSchema

                val arb = arbitrary {
                    val instr = FieldResolver.Factory.Instrumented()
                    val selectiveResolverWeight = Arb.of(0.0, 1.0).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldResolverFactory to instr) +
                            (SelectiveResolverWeight to selectiveResolverWeight),
                        engine2Enabled = engine2Enabled,
                    ).bind()
                    viaduct.executeAsync(ExecutionInput.create("{ obj { x } }")).join()

                    selectiveResolverWeight to instr
                }

                arb.forAll { (selectiveResolverWeight, instr) ->
                    val result = instr.resolver("Query" to "obj").recorder.result.getOrThrow() as ResolvedEngineObjectData
                    val resolvedSelections = result.getSelections().toSet()

                    if (selectiveResolverWeight == 0.0) {
                        resolvedSelections == setOf("x", "y")
                    } else {
                        resolvedSelections == setOf("x")
                    }
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `DeterministicResolveWeight`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = "extend type Query { x: Int @resolver }".asViaductSchema

                val arb = arbitrary {
                    val instr = FieldResolver.Factory.Instrumented()
                    val deterministicResolveWeight = Arb.of(0.0, 1.0).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldResolverFactory to instr) +
                            (DeterministicResolveWeight to deterministicResolveWeight),
                        engine2Enabled = engine2Enabled,
                    ).bind()

                    repeat(50) {
                        viaduct.executeAsync(ExecutionInput.create("{ x }")).join()
                    }

                    deterministicResolveWeight to instr
                }

                arb.forAll(iterations / 50) { (deterministicResolveWeight, instr) ->
                    val results = instr.resolver("Query" to "x").recorder.log
                        .map { it.result.getOrNull() as Int? }
                        .distinct()

                    if (deterministicResolveWeight == 0.0) {
                        results.size > 1
                    } else {
                        results.size == 1
                    }
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `ResolverFieldRefWeight`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = """
                    | extend type Query { foo1:Foo! @resolver, foo2:Foo! @resolver }
                    | type Foo { x:Int }
                """.trimMargin().asViaductSchema

                val arb = arbitrary {
                    val instr = FieldResolver.Factory.Instrumented()
                    val v = Arb.viaduct(
                        schema,
                        cfg +
                            (ResolverFieldRefWeight to 1.0) +
                            (FieldResolverFactory to instr),
                        engine2Enabled = engine2Enabled,
                    ).bind()
                    val inp = ExecutionInput.create("{ foo1 { x } foo2 { x } }")

                    val result = v.executeAsync(inp).join()
                    instr to result
                }

                arb.checkAll { (instr, result) ->
                    val resolverResults = listOf("foo1", "foo2")
                        .flatMap { field ->
                            instr.resolver("Query" to field).recorder.log
                                .map { it.result.getOrThrow() }
                        }
                    assertEquals(1, resolverResults.count { it is RootFieldReference })
                    assertTrue(resolverResults.any { it is EngineObjectData })

                    assertTrue(result.errors.isEmpty())
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `root field refs do not form cycles with required selections`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = """
                    | extend type Query { foo1:Foo, foo2:Foo }
                    | type Foo { x:Int }
                """.trimMargin().asViaductSchema
                val input = ExecutionInput.create("{ foo1 { x } }")

                Arb.viaduct(
                    schema,
                    cfg +
                        (RequiredSelectionSetWeight to Once) +
                        (ExerciseRequiredSelectionsWeight to 1.0) +
                        (ResolverFieldRefWeight to 1.0),
                    engine2Enabled = engine2Enabled,
                ).checkAll { viaduct ->
                    withTimeout(2.seconds) {
                        viaduct.execute(input).errors.isEmpty()
                    }
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `field resolver -- returns its output selection set`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = """
                extend type Query { obj: Obj @resolver }
                type Obj { x:Int @resolver y:Int }
            """.asViaductSchema
                val input = ExecutionInput.create("{ obj { x y } }")
                val instr = FieldResolver.Factory.Instrumented()

                Arb.viaduct(schema, cfg + (FieldResolverFactory to instr), engine2Enabled = engine2Enabled)
                    .forAll { viaduct ->
                        viaduct.execute(input)
                        val result = instr.resolver("Query" to "obj").recorder.result.getOrThrow() as ResolvedEngineObjectData
                        result.getSelections().toSet() == setOf("y")
                    }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `field resolver values are stable when resolver scheduling changes`(engine2Enabled: Boolean): Unit =
            runBlocking {
                // test that field resolvers are deterministic and independent of each other,
                // producing the same value for the same seed even if called in different order

                val schema = "extend type Query { a:[String!]! @resolver b:[String!]! @resolver }".asViaductSchema

                val arb = arbitrary { rs ->
                    val delayedCoordinate = Arb.of(
                        "Query" to "a",
                        "Query" to "b"
                    ).bind()

                    val factory = object : FieldResolver.Factory {
                        override fun createFieldResolver(params: FieldResolver.Factory.Params): FieldResolver {
                            val delegate = FieldResolver.Factory.Arbitrary.createFieldResolver(params)
                            return FieldResolver { selector, ctx ->
                                if (params.coordinate == delayedCoordinate) {
                                    delay(5)
                                }
                                delegate(selector, ctx)
                            }
                        }
                    }

                    fun buildAndExecute(): Map<String, Any?> {
                        val viaduct = Arb.viaduct(
                            schema,
                            cfg +
                                (FieldResolverFactory to factory) +
                                (ListValueSize to 1.asIntRange()),
                            engine2Enabled = engine2Enabled,
                        ).next(RandomSource.seeded(rs.seed))

                        val result = viaduct.executeAsync(ExecutionInput.create("{ a b }")).join()
                        assertTrue(result.errors.isEmpty())
                        return requireNotNull(result.getData())
                    }

                    val resp1 = buildAndExecute()
                    val resp2 = buildAndExecute()

                    resp1 to resp2
                }

                arb.checkAll(10) { (resp1, resp2) ->
                    assertEquals(resp1, resp2)
                }
            }
    }

    @Nested
    inner class NodeResolverTests {
        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `simple`(engine2Enabled: Boolean): Unit =
            runBlocking {
                Arb.viaduct(
                    "type Foo implements Node @resolver { id:ID! x:Int! }".asViaductSchema,
                    cfg,
                    engine2Enabled = engine2Enabled,
                ).forAll { viaduct ->
                    val id = arbId().bind()
                    val result = viaduct.execute(
                        ExecutionInput.create(
                            "query (\$id:ID!) { node(id:\$id) { ... on Foo { id, x } } }",
                            variables = mapOf("id" to id)
                        )
                    )
                    @Suppress("UNCHECKED_CAST")
                    val node = result.getData()?.get("node") as? Map<String, Any?>
                    node?.get("id") == id &&
                        node.get("x") is Int &&
                        result.errors.isEmpty()
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `NodeResolverExceptionWeight`(engine2Enabled: Boolean) {
            fun arbExecutionResult(exceptionWeight: Double): Arb<ExecutionResult.Standard> {
                val schema = "type Foo implements Node @resolver { id:ID! x:Int! }".asViaductSchema
                val cfg = cfg + (NodeResolverExceptionWeight to exceptionWeight)
                return arbitrary {
                    val viaduct = Arb.viaduct(schema, cfg, engine2Enabled = engine2Enabled).bind()
                    viaduct.executeAsync(
                        ExecutionInput.create(
                            "query (\$id:ID!) { node(id:\$id) { ... on Foo { x } } }",
                            variables = mapOf("id" to arbId().bind())
                        )
                    ).join()
                }
            }

            runBlocking {
                // disabled
                arbExecutionResult(exceptionWeight = 0.0)
                    .forAll { result ->
                        result.errors.isEmpty()
                    }

                // enabled
                arbExecutionResult(exceptionWeight = 1.0)
                    .forAll { result ->
                        result.errors.size == 1
                    }
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `ResolverLatencyMillis`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val arb = arbitrary {
                    val instr = NodeResolver.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        "type Foo implements Node @resolver { id:ID! x:Int! }".asViaductSchema,
                        cfg +
                            (ResolverLatencyMillis to 100.asLongRange()) +
                            (NodeResolverFactory to instr),
                        engine2Enabled = engine2Enabled,
                    ).bind()

                    viaduct.executeAsync(
                        ExecutionInput.create(
                            "query (\$id:ID!) { node(id:\$id) { ... on Foo { x } } }",
                            variables = mapOf("id" to arbId().bind())
                        )
                    ).join()

                    instr.resolver("Foo")
                }

                arb.forAll(3) { resolver ->
                    resolver.recorder.time >= 100.milliseconds
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `returns its output selection set`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = "type Foo implements Node @resolver { id:ID!, x:Int @resolver y:Int }".asViaductSchema

                val arb = arbitrary {
                    val instr = NodeResolver.Factory.Instrumented()
                    val viaduct = Arb.viaduct(schema, cfg + (NodeResolverFactory to instr), engine2Enabled = engine2Enabled).bind()
                    viaduct to instr
                }

                arb.forAll { (viaduct, instr) ->
                    viaduct.execute(
                        ExecutionInput.create(
                            "query (\$id:ID!) { node(id:\$id) { ... on Foo { id, x, y } } }",
                            variables = mapOf("id" to arbId().bind())
                        )
                    )

                    val result = instr.resolver("Foo").recorder.result.getOrThrow()
                    // generated node resolvers omit `id` (it is answerable from the node
                    // reference) and `x` (it has its own resolver)
                    result.fetchSelections().toSet() == setOf("y")
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `SelectiveResolverWeight`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = "type Foo implements Node { id:ID!, x:Int, y:Int }".asViaductSchema

                val arb = arbitrary {
                    val instr = NodeResolver.Factory.Instrumented()
                    val selectiveResolverWeight = Arb.of(0.0, 1.0).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (NodeResolverFactory to instr) +
                            (UndeclaredNodeResolverWeight to 1.0) +
                            (SelectiveResolverWeight to selectiveResolverWeight),
                        engine2Enabled = engine2Enabled,
                    ).bind()
                    viaduct.executeAsync(
                        ExecutionInput.create(
                            "query (\$id:ID!) { node(id:\$id) { ... on Foo { x } } }",
                            variables = mapOf("id" to arbId().bind())
                        )
                    ).join()
                    selectiveResolverWeight to instr
                }

                arb.forAll { (selectiveResolverWeight, instr) ->
                    val result = instr.resolver("Foo").recorder.result.getOrThrow()
                    val resolvedSelections = result.fetchSelections().toSet()

                    if (selectiveResolverWeight == 0.0) {
                        resolvedSelections == setOf("x", "y")
                    } else {
                        resolvedSelections == setOf("x")
                    }
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `DeterministicResolveWeight`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = "type Foo implements Node @resolver { id:ID!, x:Int }".asViaductSchema

                val arb = arbitrary {
                    val instr = NodeResolver.Factory.Instrumented()
                    val deterministicResolveWeight = Arb.of(0.0, 1.0).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (NodeResolverFactory to instr) +
                            (DeterministicResolveWeight to deterministicResolveWeight),
                        engine2Enabled = engine2Enabled,
                    ).bind()

                    repeat(50) {
                        viaduct.executeAsync(
                            ExecutionInput.create(
                                "query (\$id:ID!) { node(id:\$id) { ... on Foo { x } } }",
                                variables = mapOf("id" to arbId().bind())
                            )
                        ).join()
                    }
                    deterministicResolveWeight to instr
                }

                arb.forAll(iterations / 50) { (deterministicResolveWeight, instr) ->
                    val results = instr.resolver("Foo").recorder.log
                        .map {
                            val data = it.result.getOrThrow() as ResolvedEngineObjectData
                            data.get("x") as Int
                        }.distinct()

                    if (deterministicResolveWeight == 0.0) {
                        results.size > 1
                    } else {
                        results.size == 1
                    }
                }
            }
    }

    @Nested
    inner class VariablesResolverTests {
        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `simple`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val arb = arbitrary {
                    val instr = VariablesResolver.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        """
                    extend type Query { x:Int @resolver, y(a:Int):Int @resolver }
                """.asViaductSchema,
                        cfg +
                            (RequiredSelectionSetWeight to Once) +
                            (VariableWeight to 1.0) +
                            (VariablesResolverFactory to instr),
                        engine2Enabled = engine2Enabled,
                    ).bind()
                    instr to viaduct
                }

                // The configuration used in this test encourages the execution of VariablesResolvers,
                // but it cannot guarantee that they will be run.
                // Instead of checking that a variables resolver was executed on every request, check
                // that it was executed at least once.
                val wasExecuted = arb.asSequence(randomSource)
                    .map { (instr, viaduct) ->
                        viaduct.executeAsync(ExecutionInput.create("{ x }")).join()
                        instr
                    }
                    .take(iterations)
                    .any { instr ->
                        instr.allResolvers.any { it.recorder.log.isNotEmpty() }
                    }

                assertTrue(wasExecuted)
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `VariablesResolverExceptionWeight`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val arb = arbitrary {
                    val instr = VariablesResolver.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        """
                        extend type Query { x:Int @resolver, y(a:Int):Int @resolver }
                    """.asViaductSchema,
                        cfg +
                            (RequiredSelectionSetWeight to Once) +
                            (VariableWeight to 1.0) +
                            (VariablesResolverFactory to instr) +
                            (VariablesResolverExceptionWeight to 1.0),
                        engine2Enabled = engine2Enabled,
                    ).bind()
                    instr to viaduct
                }

                arb
                    .mapNotNull { (instr, viaduct) ->
                        viaduct.executeAsync(ExecutionInput.create("{ x }")).join()
                        instr.takeIf {
                            it.allResolvers.any { it.recorder.log.isNotEmpty() }
                        }
                    }
                    .forAll { instr ->
                        instr.allResolvers.all { it.recorder.log.all { it.result.isFailure } }
                    }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `ResolverLatencyMillis`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val arb = arbitrary {
                    val instr = VariablesResolver.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        "extend type Query { x:Int @resolver, y(a:Int!):Int @resolver }".asViaductSchema,
                        cfg +
                            (IncludeRequiredResolvers to false) +
                            (RequiredSelectionSetWeight to Once) +
                            (VariableWeight to 1.0) +
                            (ResolverLatencyMillis to 100.asLongRange()) +
                            (VariablesResolverFactory to instr) +
                            (FieldSelectionWeight to Once) +
                            (InlineFragmentWeight to Never) +
                            (FragmentSpreadWeight to Never) +
                            (BanSelectionCoordinates to setOf("Query" to "x")),
                        engine2Enabled = engine2Enabled,
                    ).bind()
                    instr to viaduct
                }

                val logs = arb.asSequence(randomSource)
                    .map { (instr, viaduct) ->
                        viaduct.executeAsync(ExecutionInput.create("{x}")).join()
                        instr.allResolvers.flatMap { it.recorder.log }
                    }
                    .take(iterations)
                    .firstOrNull { it.isNotEmpty() }

                requireNotNull(logs)
                assertTrue(logs.all { it.time >= 100.milliseconds })
            }

        // Opaque variable callbacks with their own RSS remain a legacy-runtime API.
        @Test
        fun `ExerciseRequiredSelectionsWeight`(): Unit =
            runBlocking {
                val arb = arbitrary {
                    val exercise = Arb.of(0.0, 1.0).bind()
                    Arb.viaduct(
                        "extend type Query { x:Int @resolver, y(a:Int):Int @resolver }".asViaductSchema,
                        cfg +
                            (RequiredSelectionSetWeight to CompoundingWeight(1.0, 3)) +
                            (ExerciseRequiredSelectionsWeight to exercise) +
                            (VariableWeight to 1.0)
                    ).bind()
                }.map { viaduct ->
                    viaduct.executeAsync(ExecutionInput.create("{x}")).join()
                }

                arb.forAll { result ->
                    result.errors.isEmpty()
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `DeterministicResolveWeight`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val arb = arbitrary {
                    val deterministicResolveWeight = Arb.of(0.0, 1.0).bind()
                    val instr = VariablesResolver.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        "extend type Query { x:Int @resolver, y(a:Int):Int @resolver }".asViaductSchema,
                        cfg +
                            (RequiredSelectionSetWeight to CompoundingWeight(1.0, 1)) +
                            (DeterministicResolveWeight to deterministicResolveWeight) +
                            (VariablesResolverFactory to instr) +
                            (VariableWeight to 1.0),
                        engine2Enabled = engine2Enabled,
                    ).bind()

                    repeat(50) {
                        viaduct.executeAsync(
                            ExecutionInput.create("{x}")
                        ).join()
                    }
                    deterministicResolveWeight to instr
                }

                arb.forAll(iterations / 50) { (deterministicResolveWeight, instr) ->
                    instr.resolvers.all { (_, resolver) ->
                        val results = resolver.recorder.log
                            .map { it.result.getOrThrow() }
                            .distinct()

                        if (deterministicResolveWeight == 0.0) {
                            results.size > 1
                        } else {
                            results.size == 1
                        }
                    }
                }
            }
    }

    @Nested
    inner class CheckerExecutorTest {
        @ParameterizedTest(name = "checker variables engine2={0}")
        @ValueSource(booleans = [false, true])
        fun `checker variables without required selections execute through generated services`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val variables = VariablesResolver.Instrumented(EngineVariablesResolver.const(mapOf("provided" to 37)))
                val required = RequiredSelectionSet(
                    selections = SelectionsParser.parse("Query", "policy(value: \$provided)"),
                    variablesResolvers = listOf(variables),
                    forChecker = true,
                )
                val checkers = CheckerExecutor.Factory.Instrumented { params ->
                    CheckerExecutor.Factory.Arbitrary.createCheckerExecutor(
                        params.copy(
                            requiredSelectionSets = if (params.key == ("Query" to "value")) mapOf("input" to required) else emptyMap(),
                            exerciseRequiredSelections = true,
                        ),
                    )
                }
                val fields = FieldResolver.Factory.Instrumented(object : FieldResolver.Factory {
                    override fun createFieldResolver(params: FieldResolver.Factory.Params): FieldResolver =
                        FieldResolver { selector, _ ->
                            if (params.coordinate.second == "policy") selector.arguments.getValue("value") else 7
                        }
                })
                val viaduct = Arb.viaduct(
                    "extend type Query { value: Int! @resolver, policy(value: Int!): Int! @resolver }".asViaductSchema,
                    cfg + (FieldCheckerWeight to 1.0) + (CheckerExecutorFactory to checkers) + (FieldResolverFactory to fields),
                    engine2Enabled,
                ).next(RandomSource.seeded(0))

                val result = viaduct.execute(ExecutionInput.create("{ value }"))

                assertTrue(result.errors.isEmpty(), result.toSpecification().toString())
                assertEquals(mapOf("value" to 7), result.getData())
                assertEquals(setOf(mapOf("provided" to 37)), variables.recorder.log.map { it.result.getOrThrow() }.toSet())
                assertEquals(setOf(mapOf("value" to 37)), fields.resolver("Query" to "policy").recorder.log.map { it.arg.selector.arguments }.toSet())
                val checkerInput = checkers.fieldChecker("Query" to "value").recorder.arg.objectDataMap.getValue("input")
                assertEquals(37, checkerInput.fetch("policy"))
            }

        @ParameterizedTest(name = "checker variables with required selections engine2={0}")
        @ValueSource(booleans = [false, true])
        fun `checker variables with required selections retain runtime support and receive a generator diagnostic`(engine2Enabled: Boolean) {
            val nested = RequiredSelectionSet(SelectionsParser.parse("Query", "source"), emptyList(), forChecker = true)
            val variables = VariablesResolver.Instrumented(object : EngineVariablesResolver {
                override val variableNames: Set<String> = setOf("provided")
                override val requiredSelectionSet: RequiredSelectionSet = nested

                override suspend fun resolve(
                    ctx: EngineVariablesResolver.ResolveCtx,
                    context: EngineExecutionContext,
                ): Map<String, Any?> = mapOf("provided" to ctx.objectData.fetch("source"))
            })
            val required = RequiredSelectionSet(
                selections = SelectionsParser.parse("Query", "policy(value: \$provided)"),
                variablesResolvers = listOf(variables),
                forChecker = true,
            )
            val checkers = CheckerExecutor.Factory.Instrumented { params ->
                CheckerExecutor.Factory.Arbitrary.createCheckerExecutor(
                    params.copy(
                        requiredSelectionSets = if (params.key == ("Query" to "value")) mapOf("input" to required) else emptyMap(),
                        exerciseRequiredSelections = true,
                    ),
                )
            }
            val fields = FieldResolver.Factory.Instrumented(object : FieldResolver.Factory {
                override fun createFieldResolver(params: FieldResolver.Factory.Params): FieldResolver =
                    FieldResolver { selector, _ ->
                        when (params.coordinate.second) {
                            "policy" -> selector.arguments.getValue("value")
                            "source" -> 37
                            else -> 7
                        }
                    }
            })
            val generate = {
                Arb.viaduct(
                    "extend type Query { value: Int! @resolver, policy(value: Int!): Int! @resolver, source: Int! @resolver }".asViaductSchema,
                    cfg + (FieldCheckerWeight to 1.0) + (CheckerExecutorFactory to checkers) + (FieldResolverFactory to fields),
                    engine2Enabled,
                ).next(RandomSource.seeded(0))
            }

            if (engine2Enabled) {
                val failure = assertThrows<IllegalArgumentException> {
                    generate()
                }
                assertTrue(failure.message.orEmpty().contains("field checker Query.value"))
                assertTrue(failure.message.orEmpty().contains("variables callbacks with their own required selection sets"))
            } else {
                val result = runBlocking { generate().execute(ExecutionInput.create("{ value }")) }
                assertTrue(result.errors.isEmpty(), result.toSpecification().toString())
                assertEquals(mapOf("value" to 7), result.getData())
                assertEquals(setOf(mapOf("provided" to 37)), variables.recorder.log.map { it.result.getOrThrow() }.toSet())
                assertEquals(setOf(mapOf("value" to 37)), fields.resolver("Query" to "policy").recorder.log.map { it.arg.selector.arguments }.toSet())
            }
        }

        @ParameterizedTest(name = "reachable checker failure engine2={0}")
        @ValueSource(booleans = [false, true])
        fun `each reachable checker reports its exception without relying on downstream execution`(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = "extend type Query { obj: Obj! @resolver } type Obj { x: Int! }".asViaductSchema
                for (failingChecker in listOf("Query" to "obj", "Obj" to null, "Obj" to "x")) {
                    val checkers = CheckerExecutor.Factory.Instrumented { params ->
                        CheckerExecutor.Factory.Arbitrary.createCheckerExecutor(
                            params.copy(cfg = params.cfg + (CheckerExceptionWeight to if (params.key == failingChecker) 1.0 else 0.0)),
                        )
                    }
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg + (FieldCheckerWeight to 1.0) + (TypeCheckerWeight to 1.0) + (CheckerExecutorFactory to checkers),
                        engine2Enabled,
                    ).next(RandomSource.seeded(paritySeed))

                    val result = viaduct.execute(ExecutionInput.create("{ obj { x } }"))

                    val calls = checkers.checkers.getValue(failingChecker).recorder.log
                    assertEquals(1, calls.size, failingChecker.toString())
                    assertTrue(calls.single().result.isFailure, failingChecker.toString())
                    assertTrue(checkers.checkers.filterKeys { it != failingChecker }.values.flatMap { it.recorder.log }.all { it.result.isSuccess })
                    assertTrue(result.errors.isNotEmpty())
                    assertEquals(null, result.getData())
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun FieldCheckerWeight(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = "extend type Query { x:Int @resolver }".asViaductSchema
                val arb = arbitrary {
                    val instr = CheckerExecutor.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldCheckerWeight to 1.0) +
                            (CheckerExecutorFactory to instr),
                        engine2Enabled = engine2Enabled,
                    ).bind()

                    viaduct.executeAsync(ExecutionInput.create("{ x }")).join()
                    instr
                }

                arb.forAll { instr ->
                    instr.fieldChecker("Query" to "x").recorder.log.size == 1
                }
            }

        @Test
        fun `FieldCheckerWeight skips parent fields`(): Unit =
            runBlocking {
                val schema = """
                    extend type Query { foo:Foo @resolver }
                    type Foo { bar:Bar }
                    type Bar { parent:Foo @parent, x:Int }
                """.asViaductSchema
                val arb = arbitrary {
                    val instr = CheckerExecutor.Factory.Instrumented()
                    Arb.viaduct(schema, cfg + (FieldCheckerWeight to 1.0) + (CheckerExecutorFactory to instr)).bind()
                    instr
                }

                arb.forAll { instr ->
                    ("Bar" to "parent") !in instr.checkers && ("Bar" to "x") in instr.checkers
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun TypeCheckerWeight(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = """
                    extend type Query { obj:Obj @resolver }
                    type Obj { x:Int }
                """.asViaductSchema

                val arb = arbitrary {
                    val instr = CheckerExecutor.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (TypeCheckerWeight to 1.0) +
                            (CheckerExecutorFactory to instr),
                        engine2Enabled = engine2Enabled,
                    ).bind()

                    viaduct.executeAsync(ExecutionInput.create("{obj { x } }")).join()
                    instr
                }

                arb.forAll { instr ->
                    instr.typeChecker("Obj").recorder.log.size == 1
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun CheckerErrorWeight(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = "extend type Query { x:Int! @resolver }".asViaductSchema
                val input = ExecutionInput.create("{x}")

                val arb = arbitrary {
                    val weight = Arb.of(0.0, 1.0).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldCheckerWeight to 1.0) +
                            (CheckerErrorWeight to weight),
                        engine2Enabled = engine2Enabled,
                    ).bind()
                    val result = viaduct.executeAsync(input).join()
                    weight to result
                }

                arb.forAll { (weight, result) ->
                    if (weight == 0.0) {
                        result.errors.isEmpty() && "x" in result.getData()!!
                    } else {
                        result.errors.isNotEmpty() && result.getData() == null
                    }
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun CheckerExceptionWeight(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = """
                extend type Query { obj:Obj! @resolver }
                type Obj { x:Int! }
            """.asViaductSchema
                val input = ExecutionInput.create("{ obj { x } }")

                val arb = arbitrary {
                    val instr = CheckerExecutor.Factory.Instrumented()
                    val weight = Arb.of(0.0, 1.0).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldCheckerWeight to .25) +
                            (TypeCheckerWeight to .25) +
                            (CheckerExceptionWeight to weight) +
                            (CheckerExecutorFactory to instr),
                        engine2Enabled = engine2Enabled,
                    ).bind()
                    val result = viaduct.executeAsync(input).join()
                    Triple(weight, instr, result)
                }

                arb.forAll { (weight, instr, result) ->
                    val checkers = listOfNotNull(
                        instr.checkers["Obj" to null],
                        instr.checkers["Query" to "obj"],
                        instr.checkers["Obj" to "x"],
                    )

                    if (weight == 1.0 && checkers.isNotEmpty()) {
                        // An upstream denial can prevent downstream checkers from being invoked.
                        val calls = checkers.flatMap { it.recorder.log }
                        calls.isNotEmpty() && calls.all { it.result.isFailure } &&
                            result.errors.isNotEmpty() &&
                            result.getData() == null
                    } else {
                        result.errors.isEmpty() && "obj" in result.getData()!!
                    }
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun ExerciseRequiredSelectionsWeight(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = """
                    extend type Query { obj:Obj! @resolver }
                    type Obj { x:Int! }
                """.asViaductSchema

                val arb = arbitrary {
                    val exerciseWeight = Arb.of(0.0, 1.0).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldCheckerWeight to 1.0) +
                            (TypeCheckerWeight to 1.0) +
                            (RequiredSelectionSetWeight to Once) +
                            (ExerciseRequiredSelectionsWeight to exerciseWeight),
                        engine2Enabled = engine2Enabled,
                    ).bind()

                    viaduct.executeAsync(ExecutionInput.create("{obj { x }}")).join()
                }

                arb.forAll { result -> result.errors.isEmpty() }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun RequiredSelectionSetWeight(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = """
                    extend type Query { obj:Obj! @resolver }
                    type Obj { x:Int! }
                """.asViaductSchema

                val arb = arbitrary {
                    val instr = CheckerExecutor.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldCheckerWeight to 1.0) +
                            (TypeCheckerWeight to 1.0) +
                            (CheckerExecutorFactory to instr) +
                            (RequiredSelectionSetWeight to Once),
                        engine2Enabled = engine2Enabled,
                    ).bind()

                    viaduct.executeAsync(ExecutionInput.create("{ obj { x } }")).join()
                    instr
                }

                arb.forAll { instr ->
                    instr.checkers.all { (_, checker) ->
                        checker.requiredSelectionSets.isNotEmpty() &&
                            checker.recorder.log.all { entry ->
                                checker.requiredSelectionSets.keys.all { rssKey ->
                                    entry.arg.objectDataMap[rssKey] != null
                                }
                            }
                    }
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun DeterministicResolveWeight(engine2Enabled: Boolean): Unit =
            runBlocking {
                val schema = "extend type Query { x:Int @resolver }".asViaductSchema

                val arb = arbitrary {
                    val instr = CheckerExecutor.Factory.Instrumented()
                    val deterministicResolveWeight = Arb.of(0.0, 1.0).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldCheckerWeight to 1.0) +
                            (CheckerExceptionWeight to .5) +
                            (CheckerErrorWeight to .5) +
                            (CheckerExecutorFactory to instr) +
                            (DeterministicResolveWeight to deterministicResolveWeight),
                        engine2Enabled = engine2Enabled,
                    ).bind()

                    repeat(50) {
                        viaduct.executeAsync(ExecutionInput.create("{ x }")).join()
                    }
                    deterministicResolveWeight to instr
                }

                arb.forAll(iterations / 50) { (deterministicResolveWeight, instr) ->
                    val checker = instr.fieldChecker("Query" to "x")
                    val results = checker.recorder.log
                        .map { it.result.isSuccess }
                        .distinct()

                    if (deterministicResolveWeight == 0.0) {
                        results.size > 1
                    } else {
                        results.size == 1
                    }
                }
            }

        @Test
        fun `Instrumented -- checker delegates requiredSelectionSets`(): Unit =
            runBlocking {
                val schema = """
                    extend type Query { obj:Obj! @resolver }
                    type Obj { x:Int! }
                """.asViaductSchema

                val arb = arbitrary {
                    val instr = CheckerExecutor.Factory.Instrumented()
                    Arb.viaduct(
                        schema,
                        cfg +
                            (FieldCheckerWeight to 1.0) +
                            (CheckerExecutorFactory to instr) +
                            (RequiredSelectionSetWeight to Once)
                    ).bind()
                    instr
                }

                arb.forAll { instr ->
                    instr.checkers.isNotEmpty() &&
                        instr.checkers.values.all { it.requiredSelectionSets.isNotEmpty() }
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun ResolverLatencyMillis(engine2Enabled: Boolean): Unit =
            runBlocking {
                val arb = arbitrary {
                    val instr = CheckerExecutor.Factory.Instrumented()

                    val viaduct = Arb.viaduct(
                        """
                            extend type Query { obj:Obj @resolver, x:Int @resolver }
                            type Obj { x:Int }
                        """.asViaductSchema,
                        cfg +
                            (FieldCheckerWeight to 1.0) +
                            (TypeCheckerWeight to 1.0) +
                            (CheckerExecutorFactory to instr) +
                            (ResolverLatencyMillis to 100.asLongRange()),
                        engine2Enabled = engine2Enabled,
                    ).bind()

                    viaduct.executeAsync(ExecutionInput.create("{ obj { x } }")).join()

                    instr
                }

                arb.forAll(3) { instr ->
                    instr.fieldChecker("Query" to "obj").recorder.time >= 100.milliseconds &&
                        instr.typeChecker("Obj").recorder.time >= 100.milliseconds
                }
            }
    }

    @Test
    fun `Arb_viaduct -- generates valid wiring with required selections and variables`(): Unit =
        runBlocking {
            Arb.viaduct(
                "extend type Query { x:Int @resolver, y(a:Int):Int! }".asViaductSchema,
                Config.default +
                    (RequiredSelectionSetWeight to Once) +
                    (VariableWeight to 1.0)
            ).forAll { true }
        }

    @Test
    fun `Arb_viaduct -- generates valid wiring with high RSS weights`(): Unit =
        runBlocking {
            Arb.viaduct(
                """
                extend type Query { x:Int @resolver, y(a:Int):Int @resolver, obj:Obj @resolver }
                type Obj implements Node @resolver { id:ID!, a:Int, b:Int, obj:Obj @resolver }
            """.asViaductSchema,
                Config.default +
                    (RequiredSelectionSetWeight to CompoundingWeight(.5, 3)) +
                    (FieldCheckerWeight to .5) +
                    (TypeCheckerWeight to .5) +
                    (VariableWeight to .5)
            ).forAll { true }
        }

    @Test
    fun `Arb_viaduct -- generates valid wiring for arbitrary schemas`(): Unit =
        runBlocking {
            val cfg = Config.default +
                (GenInterfaceStubsIfNeeded to true) +
                (UndeclaredNodeResolverWeight to .5) +
                (UndeclaredFieldResolverWeight to .5)
            val arb = arbitrary {
                val schema = Arb.viaductSchema(cfg).bind()
                val viaduct = try {
                    Result.success(Arb.viaduct(schema, cfg).bind())
                } catch (e: Exception) {
                    Result.failure(e)
                }
                val sdl = SchemaPrinter().print(schema.schema)
                sdl to viaduct
            }.withCheck { (sdl, v) ->
                assertTrue(v.isSuccess) {
                    sdl
                }
            }

            val comparator = Comparator<Pair<String, Result<Viaduct>>> { a, b ->
                a.first.length.compareTo(b.first.length)
            }
            arb.minViolation(comparator, randomSource, iterations)?.let { v -> fail(v.err) }
        }

    @Test
    @Suppress("DEPRECATION")
    fun `dump -- rejects viaducts not generated by Arb_viaduct`() {
        val viaduct = StandardViaduct.Builder()
            .withSchemaConfiguration(
                SchemaConfiguration.fromSchema("type Empty implements Node { id:ID! }".asViaductSchema)
            )
            .build()

        val err = assertThrows<UnsupportedOperationException> { viaduct.dump() }

        assertEquals(
            "Unsupported operation: only a Viaduct created by Arb.viaduct may be dumped",
            err.message
        )
    }

    companion object {
        private val paritySeed = defaultSeed()
        val codec = DefaultGlobalIDCodec()

        fun arbId(typeName: String = "Foo"): Arb<String> =
            arbitrary {
                codec.serialize(typeName, Arb.string().bind())
            }
    }
}
