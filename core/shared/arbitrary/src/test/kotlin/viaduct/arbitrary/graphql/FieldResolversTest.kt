@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.Exhaustive
import io.kotest.property.exhaustive.of
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.CompoundingWeight.Companion.Never
import viaduct.arbitrary.common.CompoundingWeight.Companion.Once
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase

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
    fun `Arb_fieldResolverExecutor -- SelectiveResolverWeight`(): Unit =
        runBlocking {
            Exhaustive.of(0.0, 1.0)
                .forAll { weight ->
                    val instr = FieldResolver.Factory.Instrumented()
                    Arb.fieldResolverExecutor(
                        schema,
                        Config.default +
                            (SelectiveResolverWeight to weight) +
                            (FieldResolverFactory to instr)
                    ).bind()

                    instr.recorder.arg.selective == (weight == 1.0)
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
}
