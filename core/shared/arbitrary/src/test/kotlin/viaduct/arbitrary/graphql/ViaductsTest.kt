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
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.apiannotations.VisibleForTest
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.CompoundingWeight.Companion.Never
import viaduct.arbitrary.common.CompoundingWeight.Companion.Once
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.asSequence
import viaduct.arbitrary.common.mapNotNull
import viaduct.arbitrary.common.withCheck
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.api.Viaduct
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct
import viaduct.service.runtime.globalid.DefaultGlobalIDCodec

class ViaductsTest : KotestPropertyBase(iterations = 100) {
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

    @Nested
    inner class FieldResolverTests {
        @Test
        fun `simple`(): Unit =
            runBlocking {
                Arb.viaduct(
                    "extend type Query { x:Int! @resolver }".asViaductSchema,
                    cfg,
                ).forAll { viaduct ->
                    val result = viaduct.execute(
                        ExecutionInput.create("{x}")
                    )
                    result.getData()?.get("x") is Int
                }
            }

        @Test
        fun `ExplicitNullValueWeight`(): Unit =
            runBlocking {
                val schema = "extend type Query { x:Int @resolver }".asViaductSchema
                val input = ExecutionInput.create("{x}")

                // disabled
                Arb.viaduct(schema, cfg + (ExplicitNullValueWeight to 0.0))
                    .forAll { viaduct ->
                        val result = viaduct.execute(input)
                        result.getData()!!.get("x") != null
                    }

                // enabled
                Arb.viaduct(schema, cfg + (ExplicitNullValueWeight to 1.0))
                    .forAll { viaduct ->
                        val result = viaduct.execute(input)
                        result.getData()!!.get("x") == null
                    }
            }

        @Test
        fun `RequiredSelectionSetWeight`(): Unit =
            runBlocking {
                val schema = "extend type Query { x:Int @resolver, y:Int @resolver }".asViaductSchema

                val arb = arbitrary {
                    val instr = FieldResolver.Factory.Instrumented()
                    val weight = Arb.of(Never, Once).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldResolverFactory to instr) +
                            (RequiredSelectionSetWeight to weight)
                    ).bind()
                    viaduct.execute(ExecutionInput.create("{ x }"))

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

        @Test
        fun `ExerciseRequiredSelectionsWeight`(): Unit =
            runBlocking {
                Arb.viaduct(
                    "extend type Query { x:Int @resolver }".asViaductSchema,
                    cfg +
                        (RequiredSelectionSetWeight to Once) +
                        (ExerciseRequiredSelectionsWeight to 1.0)
                ).forAll { viaduct ->
                    val result = viaduct.execute(ExecutionInput.create("{x}"))
                    result.errors.isEmpty() && result.getData()!!["x"] is Int
                }
            }

        @Test
        fun `ResolverLatencyMillis`(): Unit =
            runBlocking {
                val arb = arbitrary {
                    val instr = FieldResolver.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        "extend type Query { x:Int! @resolver }".asViaductSchema,
                        cfg +
                            (ResolverLatencyMillis to 100.asLongRange()) +
                            (FieldResolverFactory to instr)
                    ).bind()
                    viaduct.execute(ExecutionInput.create("{x}"))

                    instr.resolver("Query" to "x")
                }

                arb.forAll(3) { resolver ->
                    resolver.recorder.time > 100.milliseconds
                }
            }

        @Test
        fun `FieldResolverExceptionWeight`(): Unit =
            runBlocking {
                val schema = "extend type Query { x:Int! @resolver }".asViaductSchema
                val input = ExecutionInput.create("{x}")

                // disabled
                Arb.viaduct(schema, cfg + (FieldResolverExceptionWeight to 0.0))
                    .forAll {
                        it.execute(input).errors.isEmpty()
                    }

                // enabled
                Arb.viaduct(schema, cfg + (FieldResolverExceptionWeight to 1.0))
                    .forAll {
                        it.execute(input).errors.size == 1
                    }
            }

        @Test
        fun `SelectiveResolverWeight`(): Unit =
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
                            (SelectiveResolverWeight to selectiveResolverWeight)
                    ).bind()
                    viaduct.execute(ExecutionInput.create("{ obj { x } }"))

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

        @Test
        fun `DeterministicResolveWeight`(): Unit =
            runBlocking {
                val schema = "extend type Query { x: Int @resolver }".asViaductSchema

                val arb = arbitrary {
                    val instr = FieldResolver.Factory.Instrumented()
                    val deterministicResolveWeight = Arb.of(0.0, 1.0).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldResolverFactory to instr) +
                            (DeterministicResolveWeight to deterministicResolveWeight)
                    ).bind()

                    repeat(50) {
                        viaduct.execute(ExecutionInput.create("{ x }"))
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

        @Test
        fun `field resolver -- returns its output selection set`(): Unit =
            runBlocking {
                val schema = """
                extend type Query { obj: Obj @resolver }
                type Obj { x:Int @resolver y:Int }
            """.asViaductSchema
                val input = ExecutionInput.create("{ obj { x y } }")
                val instr = FieldResolver.Factory.Instrumented()

                Arb.viaduct(schema, cfg + (FieldResolverFactory to instr))
                    .forAll { viaduct ->
                        viaduct.execute(input)
                        val result = instr.resolver("Query" to "obj").recorder.result.getOrThrow() as ResolvedEngineObjectData
                        result.getSelections().toSet() == setOf("y")
                    }
            }

        @Test
        fun `field resolver values are stable when resolver scheduling changes`(): Unit =
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
                                (ListValueSize to 1.asIntRange())
                        ).next(RandomSource.seeded(rs.seed))

                        val result = viaduct.execute(ExecutionInput.create("{ a b }"))
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
        @Test
        fun `simple`(): Unit =
            runBlocking {
                Arb.viaduct(
                    "type Foo implements Node @resolver { id:ID! x:Int! }".asViaductSchema,
                    cfg
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

        @Test
        fun `NodeResolverExceptionWeight`() {
            fun arbExecutionResult(exceptionWeight: Double): Arb<ExecutionResult> {
                val schema = "type Foo implements Node @resolver { id:ID! x:Int! }".asViaductSchema
                val cfg = cfg + (NodeResolverExceptionWeight to exceptionWeight)
                return arbitrary {
                    val viaduct = Arb.viaduct(schema, cfg).bind()
                    viaduct.execute(
                        ExecutionInput.create(
                            "query (\$id:ID!) { node(id:\$id) { __typename } }",
                            variables = mapOf("id" to arbId().bind())
                        )
                    )
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

        @Test
        fun `ResolverLatencyMillis`(): Unit =
            runBlocking {
                val arb = arbitrary {
                    val instr = NodeResolver.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        "type Foo implements Node @resolver { id:ID! x:Int! }".asViaductSchema,
                        cfg +
                            (ResolverLatencyMillis to 100.asLongRange()) +
                            (NodeResolverFactory to instr)
                    ).bind()

                    viaduct.execute(
                        ExecutionInput.create(
                            "query (\$id:ID!) { node(id:\$id) { __typename } }",
                            variables = mapOf("id" to arbId().bind())
                        )
                    )

                    instr.resolver("Foo")
                }

                arb.forAll(3) { resolver ->
                    resolver.recorder.time >= 100.milliseconds
                }
            }

        @Test
        fun `returns its output selection set`(): Unit =
            runBlocking {
                val schema = "type Foo implements Node @resolver { id:ID!, x:Int @resolver y:Int }".asViaductSchema

                val arb = arbitrary {
                    val instr = NodeResolver.Factory.Instrumented()
                    val viaduct = Arb.viaduct(schema, cfg + (NodeResolverFactory to instr)).bind()
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

        @Test
        fun `SelectiveResolverWeight`(): Unit =
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
                            (SelectiveResolverWeight to selectiveResolverWeight)
                    ).bind()
                    viaduct.execute(
                        ExecutionInput.create(
                            "query (\$id:ID!) { node(id:\$id) { ... on Foo { x } } }",
                            variables = mapOf("id" to arbId().bind())
                        )
                    )
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

        @Test
        fun `DeterministicResolveWeight`(): Unit =
            runBlocking {
                val schema = "type Foo implements Node @resolver { id:ID!, x:Int }".asViaductSchema

                val arb = arbitrary {
                    val instr = NodeResolver.Factory.Instrumented()
                    val deterministicResolveWeight = Arb.of(0.0, 1.0).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (NodeResolverFactory to instr) +
                            (DeterministicResolveWeight to deterministicResolveWeight)
                    ).bind()

                    repeat(50) {
                        viaduct.execute(
                            ExecutionInput.create(
                                "query (\$id:ID!) { node(id:\$id) { ... on Foo { x } } }",
                                variables = mapOf("id" to arbId().bind())
                            )
                        )
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
        @Test
        fun `simple`(): Unit =
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
                            (VariablesResolverFactory to instr)
                    ).bind()
                    instr to viaduct
                }

                // The configuration used in this test encourages the execution of VariablesResolvers,
                // but it cannot guarantee that they will be run.
                // Instead of checking that a variables resolver was executed on every request, check
                // that it was executed at least once.
                val wasExecuted = arb.asSequence(randomSource)
                    .map { (instr, viaduct) ->
                        viaduct.execute(ExecutionInput.create("{ x }"))
                        instr
                    }
                    .take(iterations)
                    .any { instr ->
                        instr.allResolvers.any { it.recorder.log.isNotEmpty() }
                    }

                assertTrue(wasExecuted)
            }

        @Test
        fun `VariablesResolverExceptionWeight`(): Unit =
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
                            (VariablesResolverExceptionWeight to 1.0)
                    ).bind()
                    instr to viaduct
                }

                arb
                    .mapNotNull { (instr, viaduct) ->
                        viaduct.execute(ExecutionInput.create("{ x }"))
                        instr.takeIf {
                            it.allResolvers.any { it.recorder.log.isNotEmpty() }
                        }
                    }
                    .forAll { instr ->
                        instr.allResolvers.all { it.recorder.log.all { it.result.isFailure } }
                    }
            }

        @Test
        fun `ResolverLatencyMillis`(): Unit =
            runBlocking {
                val instr = VariablesResolver.Factory.Instrumented()
                val viaduct = Arb.viaduct(
                    "extend type Query { x:Int @resolver, y(a:Int!):Int }".asViaductSchema,
                    cfg +
                        (IncludeRequiredResolvers to false) +
                        (RequiredSelectionSetWeight to Once) +
                        (VariableWeight to 1.0) +
                        (ResolverLatencyMillis to 100.asLongRange()) +
                        (VariablesResolverFactory to instr) +
                        (FieldSelectionWeight to Once) +
                        (InlineFragmentWeight to Never) +
                        (FragmentSpreadWeight to Never) +
                        (BanSelectionCoordinates to setOf("Query" to "x", "Query" to "__typename"))
                ).next(randomSource)

                viaduct.execute(ExecutionInput.create("{x}"))

                val logs = instr.allResolvers.flatMap { it.recorder.log }
                assertTrue(logs.isNotEmpty())
                assertTrue(logs.all { it.time >= 100.milliseconds })
            }

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
                    viaduct.execute(ExecutionInput.create("{x}"))
                }

                arb.forAll { result ->
                    result.errors.isEmpty()
                }
            }

        @Test
        fun `DeterministicResolveWeight`(): Unit =
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
                            (VariableWeight to 1.0)
                    ).bind()

                    repeat(50) {
                        viaduct.execute(
                            ExecutionInput.create("{x}")
                        )
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
        @Test
        fun FieldCheckerWeight(): Unit =
            runBlocking {
                val schema = "extend type Query { x:Int @resolver }".asViaductSchema
                val arb = arbitrary {
                    val instr = CheckerExecutor.Factory.Instrumented()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldCheckerWeight to 1.0) +
                            (CheckerExecutorFactory to instr)
                    ).bind()

                    viaduct.execute(ExecutionInput.create("{ x }"))
                    instr
                }

                arb.forAll { instr ->
                    instr.fieldChecker("Query" to "x").recorder.log.size == 1
                }
            }

        @Test
        fun TypeCheckerWeight(): Unit =
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
                            (CheckerExecutorFactory to instr)
                    ).bind()

                    viaduct.execute(ExecutionInput.create("{obj { x } }"))
                    instr
                }

                arb.forAll { instr ->
                    instr.typeChecker("Obj").recorder.log.size == 1
                }
            }

        @Test
        fun CheckerErrorWeight(): Unit =
            runBlocking {
                val schema = "extend type Query { x:Int! @resolver }".asViaductSchema
                val input = ExecutionInput.create("{x}")

                val arb = arbitrary {
                    val weight = Arb.of(0.0, 1.0).bind()
                    val viaduct = Arb.viaduct(
                        schema,
                        cfg +
                            (FieldCheckerWeight to 1.0) +
                            (CheckerErrorWeight to weight)
                    ).bind()
                    val result = viaduct.execute(input)
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

        @Test
        fun CheckerExceptionWeight(): Unit =
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
                            (CheckerExecutorFactory to instr)
                    ).bind()
                    val result = viaduct.execute(input)
                    Triple(weight, instr, result)
                }

                arb.forAll { (weight, instr, result) ->
                    val checkers = listOfNotNull(
                        instr.checkers["Obj" to null],
                        instr.checkers["Query" to "obj"],
                        instr.checkers["Obj" to "x"],
                    )

                    if (weight == 1.0 && checkers.isNotEmpty()) {
                        checkers.all { it.recorder.result.isFailure } &&
                            result.errors.isNotEmpty() &&
                            result.getData() == null
                    } else {
                        result.errors.isEmpty() && "obj" in result.getData()!!
                    }
                }
            }

        @Test
        fun ExerciseRequiredSelectionsWeight(): Unit =
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
                            (ExerciseRequiredSelectionsWeight to exerciseWeight)
                    ).bind()

                    viaduct.execute(ExecutionInput.create("{obj { x }}"))
                }

                arb.forAll { result -> result.errors.isEmpty() }
            }

        @Test
        fun RequiredSelectionSetWeight(): Unit =
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
                            (RequiredSelectionSetWeight to Once)
                    ).bind()

                    viaduct.execute(ExecutionInput.create("{ obj { x } }"))
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

        @Test
        fun DeterministicResolveWeight(): Unit =
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
                            (DeterministicResolveWeight to deterministicResolveWeight)
                    ).bind()

                    repeat(50) {
                        viaduct.execute(ExecutionInput.create("{ x }"))
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

        @Test
        fun ResolverLatencyMillis(): Unit =
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
                            (ResolverLatencyMillis to 100.asLongRange())
                    ).bind()

                    viaduct.execute(ExecutionInput.create("{ obj { x } }"))

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
        val codec = DefaultGlobalIDCodec()

        fun arbId(typeName: String = "Foo"): Arb<String> =
            arbitrary {
                codec.serialize(typeName, Arb.string().bind())
            }
    }
}
