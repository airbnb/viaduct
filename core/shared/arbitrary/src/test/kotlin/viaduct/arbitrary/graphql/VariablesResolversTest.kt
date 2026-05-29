@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.language.TypeName
import graphql.language.VariableDefinition
import io.kotest.property.Exhaustive
import io.kotest.property.arbitrary.of
import io.kotest.property.exhaustive.of
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.CompoundingWeight.Companion.Never
import viaduct.arbitrary.common.CompoundingWeight.Companion.Once
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase

class VariablesResolversTest : KotestPropertyBase() {
    private val schema = "extend type Query { x:Int }".asViaductSchema
    private val variableDef = VariableDefinition
        .newVariableDefinition("var", TypeName("Int"))
        .build()

    @Test
    fun `VariablesResolverGen -- instrumented`() {
        val instr = VariablesResolver.Factory.Instrumented()

        val cfg = Config.default +
            (VariablesResolverFactory to instr) +
            (RequiredSelectionSetWeight to Never)
        val resolver = ViaductGenEnv(schema, cfg, randomSource)
            .variablesResolverGen
            .gen("Query" to "x", variableDef, forChecker = false, 0)

        assertEquals("Query" to "x", instr.recorder.arg.tfc)
        assertSame(resolver, instr.resolver("Query" to "x", variableDef.name))

        // lookup missing variable
        assertThrows<IllegalArgumentException> {
            instr.resolver("Query" to "x", "unknown")
        }

        assertTrue(resolver is VariablesResolver.Instrumented)
    }

    @Test
    fun `VariablesResolverGen -- ExerciseRequiredSelectionsWeight`(): Unit =
        runBlocking {
            Exhaustive.of(0.0, 1.0)
                .forAll { weight ->
                    val instr = VariablesResolver.Factory.Instrumented()
                    val cfg = Config.default +
                        (VariablesResolverFactory to instr) +
                        (ExerciseRequiredSelectionsWeight to weight) +
                        (RequiredSelectionSetWeight to Never)

                    ViaductGenEnv(schema, cfg, randomSource)
                        .variablesResolverGen
                        .gen("Query" to "x", variableDef, forChecker = false, 0)

                    instr.recorder.arg.exerciseRequiredSelections == (weight == 1.0)
                }
        }

    @Test
    fun `VariablesResolverGen -- RequiredSelectionSetWeight`(): Unit =
        runBlocking {
            Exhaustive.of(Never, Once).forAll { weight ->
                val instr = VariablesResolver.Factory.Instrumented()
                val cfg = Config.default +
                    (VariablesResolverFactory to instr) +
                    (RequiredSelectionSetWeight to weight)

                ViaductGenEnv(schema, cfg, randomSource)
                    .variablesResolverGen
                    .gen("Query" to "x", variableDef, forChecker = false, 0)

                val rsses = instr.recorder.log.mapNotNull { it.arg.requiredSelectionSet }
                rsses.size > 0 == (weight.max != 0)
            }
        }
}
