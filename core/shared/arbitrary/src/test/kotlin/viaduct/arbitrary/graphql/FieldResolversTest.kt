@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.Exhaustive
import io.kotest.property.RandomSource
import io.kotest.property.exhaustive.of
import io.kotest.property.forAll
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.CompoundingWeight.Companion.Never
import viaduct.arbitrary.common.CompoundingWeight.Companion.Once
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.VariablesResolver as EngineVariablesResolver
import viaduct.graphql.utils.collectVariableReferences

class FieldResolversTest : KotestPropertyBase() {
    private val schema = """
        extend type Query { x:Int @resolver }
        interface Interface { x:Int }
    """.asViaductSchema

    @Test
    fun `Arb_fieldResolverExecutor -- simple`(): Unit =
        runBlocking {
            // without coord
            Arb.fieldResolverExecutor(schema).forAll {
                it.resolverId == "Query.x"
            }

            // with coord
            Arb.fieldResolverExecutor(schema, "Query" to "x").forAll {
                it.resolverId == "Query.x"
            }
        }

    @Test
    fun `function provider preserves independent object and query variables with the same generated name`(): Unit =
        runBlocking {
            val schema = "extend type Query { x: Int @resolver, v(v: Int): Int @resolver }".asViaductSchema
            var nextValue = 0
            val factory = object : VariablesResolver.Factory {
                override fun createVariablesResolver(params: VariablesResolver.Factory.Params): EngineVariablesResolver = EngineVariablesResolver.const(mapOf(params.def.name to ++nextValue))
            }
            val cfg = Config.default +
                (RequiredSelectionSetWeight to Once) +
                (VariableWeight to 1.0) +
                (ImplicitNullValueWeight to 0.0) +
                (AppliedDirectiveWeight to Never) +
                (FieldSelectionWeight to Once) +
                (InlineFragmentWeight to Never) +
                (FragmentSpreadWeight to Never) +
                (FieldNameLength to 1..1) +
                (BanFieldNames to (('a'..'z').filter { it != 'v' }.map { it.toString() }.toSet() + setOf("_", "node", "nodes", "__typename"))) +
                (VariablesResolverFactory to factory)
            val executor = Arb.fieldResolverExecutor(schema, "Query" to "x", cfg)
                .generate(RandomSource.seeded(0))
                .map { it.value }
                .take(100)
                .first {
                    it.objectSelectionSet?.variablesResolvers?.size == 1 &&
                        it.querySelectionSet?.variablesResolvers?.size == 1
                }
            val provider = requireNotNull(executor.variablesFromFunctionProvider)
            val values = provider.provideVariables(
                ResolvedEngineObjectData(schema.schema.queryType, emptyMap()),
                emptyMap(),
                mockk<EngineExecutionContext>(),
            )

            assertEquals(2, values.size)
            assertEquals(2, values.values.toSet().size)
            assertEquals(provider.variableNames, values.keys)
            val objectSelections = requireNotNull(executor.objectSelectionSet)
            val querySelections = requireNotNull(executor.querySelectionSet)
            val objectReferences = objectSelections.selections.collectVariableReferences()
            val queryReferences = querySelections.selections.collectVariableReferences()
            assertEquals(objectSelections.variablesResolvers.flatMap { it.variableNames }.toSet(), objectReferences)
            assertEquals(querySelections.variablesResolvers.flatMap { it.variableNames }.toSet(), queryReferences)
            assertEquals(emptySet<String>(), objectReferences.intersect(queryReferences))
            assertEquals(provider.variableNames, objectReferences + queryReferences)
        }

    @Test
    fun `Arb_fieldResolverExecutor -- undefined field`() {
        assertThrows<IllegalArgumentException> {
            Arb.fieldResolverExecutor(schema, "Query" to "missing")
        }
    }

    @Test
    fun `Arb_fieldResolverExecutor -- introspection field`() {
        assertThrows<IllegalArgumentException> {
            Arb.fieldResolverExecutor(schema, "Query" to "__typename")
        }
    }

    @Test
    fun `Arb_fieldResolverExecutor -- type is not an object`() {
        assertThrows<IllegalArgumentException> {
            Arb.fieldResolverExecutor(schema, "Interface" to "x")
        }
    }

    @Test
    fun `Arb_fieldResolverExecutor -- undefined type`() {
        assertThrows<IllegalArgumentException> {
            Arb.fieldResolverExecutor(schema, "Missing" to "x")
        }
    }

    @Test
    fun `Arb_fieldResolverExecutor -- declared resolver selectivity`(): Unit =
        runBlocking {
            Exhaustive.of(
                "extend type Query { x:Int @resolver }".asViaductSchema to false,
                EngineSchema(
                    """
                    directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION | OBJECT
                    type Query { x:Int @resolver(isSelective: true) }
                    """.asSchema
                ) to true
            )
                .forAll { (schema, expectedSelective) ->
                    val instr = FieldResolver.Factory.Instrumented()
                    Arb.fieldResolverExecutor(
                        schema,
                        Config.default +
                            (FieldResolverFactory to instr)
                    ).bind()

                    instr.recorder.arg.selective == expectedSelective
                }
        }

    @Test
    fun `Arb_fieldResolverExecutor -- declared resolver batching`(): Unit =
        runBlocking {
            val gen = Exhaustive.of(
                "extend type Query { x:Int @resolver }".asViaductSchema to false,
                "extend type Query { x:Int @resolver(isBatching: false) }".asViaductSchema to false,
                "extend type Query { x:Int @resolver(isBatching: true) }".asViaductSchema to true
            )
            gen.forAll { (schema, expectedBatching) ->
                Arb.fieldResolverExecutor(schema).bind().isBatching == expectedBatching
            }
        }

    @Test
    fun `Arb_fieldResolverExecutor -- ExerciseRequiredSelectionsWeight`(): Unit =
        runBlocking {
            Exhaustive.of(0.0, 1.0)
                .forAll { weight ->
                    val instr = FieldResolver.Factory.Instrumented()
                    Arb.fieldResolverExecutor(
                        schema,
                        Config.default +
                            (ExerciseRequiredSelectionsWeight to weight) +
                            (FieldResolverFactory to instr)
                    ).bind()

                    instr.recorder.arg.exerciseRequiredSelections == (weight == 1.0)
                }
        }

    @Test
    fun `Arb_fieldResolverExecutor -- RequiredSelectionSetWeight`(): Unit =
        runBlocking {
            Exhaustive.of(Never, Once).forAll { weight ->
                val instr = FieldResolver.Factory.Instrumented()
                Arb.fieldResolverExecutor(
                    schema,
                    Config.default +
                        (RequiredSelectionSetWeight to weight) +
                        (FieldResolverFactory to instr)
                ).bind()

                val rsses = listOfNotNull(
                    instr.recorder.arg.querySelectionSet,
                    instr.recorder.arg.objectSelectionSet
                )
                rsses.isEmpty() == (weight.max == 0)
            }
        }

    @Test
    fun `Arb_fieldResolverExecutor -- BatchingResolverWeight`(): Unit =
        runBlocking {
            val schema = "extend type Query { x:Int }".asViaductSchema
            Exhaustive.of(0.0, 1.0).forAll { weight ->
                val resolver = Arb.fieldResolverExecutor(
                    schema,
                    "Query" to "x",
                    Config.default + (BatchingResolverWeight to weight)
                ).bind()

                resolver.isBatching == (weight == 1.0)
            }
        }
}
