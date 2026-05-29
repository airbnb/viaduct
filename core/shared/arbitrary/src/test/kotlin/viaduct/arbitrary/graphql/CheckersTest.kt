@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.Exhaustive
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.of
import io.kotest.property.exhaustive.of
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CompoundingWeight.Companion.Never
import viaduct.arbitrary.common.CompoundingWeight.Companion.Once
import viaduct.arbitrary.common.Config

class CheckersTest {
    val cfg = Config.default +
        (RequiredSelectionSetWeight to Never) +
        (VariableWeight to 0.0)

    @Test
    fun `Arb_checkerExecutor -- field RequiredSelectionSetWeight`(): Unit =
        runBlocking {
            val arb = arbitrary {
                val weight = Arb.of(Never, Once).bind()
                val checker = Arb.checkerExecutor(
                    "extend type Query { x:Int, y:Int }".asViaductSchema,
                    "Query" to "x",
                    cfg + (RequiredSelectionSetWeight to weight)
                ).bind()

                weight to checker
            }

            arb.forAll { (weight, checker) ->
                checker.requiredSelectionSets.size == weight.max
            }
        }

    @Test
    fun `Arb_checkerExecutor -- type RequiredSelectionSetWeight`(): Unit =
        runBlocking {
            val arb = arbitrary {
                val weight = Arb.of(Never, Once).bind()
                val checker = Arb.checkerExecutor(
                    """
                    extend type Query { obj:Obj, x:Int }
                    type Obj { a:Int, b:Int }
                """.asViaductSchema,
                    "Obj" to null,
                    cfg + (RequiredSelectionSetWeight to weight)
                ).bind()

                weight to checker
            }

            arb.forAll { (weight, checker) ->
                checker.requiredSelectionSets.size == weight.max
            }
        }

    @Test
    fun `Arb_checkerExecutor -- ExerciseRequiredSelectionsWeight`(): Unit =
        runBlocking {
            val arb = arbitrary {
                val weight = Arb.of(0.0, 1.0).bind()
                val instr = CheckerExecutor.Factory.Instrumented()
                Arb.checkerExecutor(
                    "extend type Query { x:Int }".asViaductSchema,
                    "Query" to "x",
                    cfg +
                        (ExerciseRequiredSelectionsWeight to weight) +
                        (CheckerExecutorFactory to instr)
                ).bind()
                weight to instr
            }

            arb.forAll { (weight, instr) ->
                instr.recorder.arg.exerciseRequiredSelections == (weight == 1.0)
            }
        }

    @Test
    fun `Arb_checkerExecutor -- RequiredSelectionSetWeight`(): Unit =
        runBlocking {
            Exhaustive.of(Never, Once).forAll { weight ->
                val instr = CheckerExecutor.Factory.Instrumented()
                Arb.checkerExecutor(
                    "extend type Query { x:Int }".asViaductSchema,
                    "Query" to "x",
                    cfg +
                        (TypeCheckerWeight to 1.0) +
                        (FieldCheckerWeight to 1.0) +
                        (RequiredSelectionSetWeight to weight) +
                        (CheckerExecutorFactory to instr)
                ).bind()

                instr.recorder.arg.requiredSelectionSets.size == weight.max
            }
        }
}
