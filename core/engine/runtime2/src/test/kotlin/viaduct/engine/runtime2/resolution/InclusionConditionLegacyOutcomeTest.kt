@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld

/** Expected alternatives are handwritten ordered requirement maps, not expanded production DAGs. */
class InclusionConditionLegacyOutcomeTest {
    private val field = TestWorld.fromSDL("type Query { value: Boolean }").schema.requireObjectField("Query", "value")
    private val x = Arguments.Variable.of(field, "x")
    private val y = Arguments.Variable.of(field, "y")
    private val z = Arguments.Variable.of(field, "z")
    private val w = Arguments.Variable.of(field, "w")
    private val variables = listOf(x, y, z, w)
    private val xr = InclusionCondition.requires(mapOf(x to true))
    private val yr = InclusionCondition.requires(mapOf(y to true))
    private val zr = InclusionCondition.requires(mapOf(z to true))
    private val wr = InclusionCondition.requires(mapOf(w to true))

    private enum class Outcome { TRUE, FALSE, FAILED, PENDING }

    private data class Case(val condition: InclusionCondition, val alternatives: List<Map<Arguments.Variable, Boolean>>)

    private fun cases(): List<Case> {
        val zx = zr.and(xr)
        val guard = InclusionCondition.requires(mapOf(w to true, z to true))
        return listOf(
            Case(zx.or(xr).and(zr), listOf(mapOf(z to true, x to true))),
            Case(zx.or(xr).or(yr).and(zr), listOf(mapOf(z to true, x to true), mapOf(y to true, z to true))),
            Case(yr.or(zx.or(xr)).and(zr), listOf(mapOf(y to true, z to true), mapOf(z to true, x to true))),
            Case(xr.or(zx).and(zr), listOf(mapOf(x to true, z to true))),
            Case(xr.or(yr).and(zr), listOf(mapOf(x to true, z to true), mapOf(y to true, z to true))),
            Case(xr.or(yr).and(zr.or(wr)), listOf(mapOf(x to true, z to true), mapOf(x to true, w to true), mapOf(y to true, z to true), mapOf(y to true, w to true))),
            Case(zx.or(xr).or(yr).and(guard), listOf(mapOf(z to true, x to true, w to true), mapOf(y to true, w to true, z to true))),
            Case(guard.and(zx.or(xr).or(yr)), listOf(mapOf(w to true, z to true, x to true), mapOf(w to true, z to true, y to true))),
            Case(
                zx.or(xr).or(yr).and(zr.or(zr.and(wr))),
                listOf(mapOf(z to true, x to true), mapOf(z to true, x to true, w to true), mapOf(y to true, z to true), mapOf(y to true, z to true, w to true))
            ),
            Case(zx.or(xr).or(yr).and(wr).and(zr), listOf(mapOf(z to true, x to true, w to true), mapOf(y to true, w to true, z to true))),
        )
    }

    private fun states(bits: Int): Map<Arguments.Variable, Outcome> = variables.mapIndexed { index, variable -> variable to Outcome.entries[(bits shr (2 * index)) and 3] }.toMap()

    private fun alternativeOutcome(
        alternative: Map<Arguments.Variable, Boolean>,
        states: Map<Arguments.Variable, Outcome>
    ): Outcome {
        for ((variable, required) in alternative) {
            when (val state = states.getValue(variable)) {
                Outcome.FAILED, Outcome.PENDING -> return state
                else -> if ((state == Outcome.TRUE) != required) return Outcome.FALSE
            }
        }
        return Outcome.TRUE
    }

    @Test
    fun `ordinary evaluators match normalized ordered alternatives with failed bindings`(): Unit =
        runBlocking {
            for ((caseIndex, case) in cases().withIndex()) {
                for (bits in 0 until 256) {
                    val states = states(bits)
                    if (states.values.any { it == Outcome.PENDING }) continue

                    fun binding(variable: Arguments.Variable): Boolean =
                        when (states.getValue(variable)) {
                            Outcome.TRUE -> true
                            Outcome.FALSE -> false
                            else -> error("failed ${variables.indexOf(variable)}")
                        }
                    val expected = runCatching { case.alternatives.any { alternative -> alternative.all { (variable, required) -> binding(variable) == required } } }
                    for (actual in listOf(runCatching { case.condition.includeWith(::binding) }, runCatching { case.condition.include { binding(it) } })) {
                        assertEquals(expected.getOrNull(), actual.getOrNull(), "case=$caseIndex bits=$bits")
                        assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message, "case=$caseIndex bits=$bits")
                    }
                }
            }
        }

    @Test
    fun `activation matches normalized alternative outcomes including pending bindings`(): Unit =
        runBlocking {
            for ((caseIndex, case) in cases().withIndex()) {
                for (bits in 0 until 256) {
                    val states = states(bits)
                    val outcomes = case.alternatives.map { alternativeOutcome(it, states) }
                    val expected = when {
                        Outcome.TRUE in outcomes -> Outcome.TRUE
                        Outcome.PENDING in outcomes -> Outcome.PENDING
                        Outcome.FAILED in outcomes -> Outcome.FAILED
                        else -> Outcome.FALSE
                    }
                    val unresolved = CompletableDeferred<Boolean>()
                    val evaluation = async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching {
                            case.condition.includeAnyReadyAlternative { variable ->
                                when (states.getValue(variable)) {
                                    Outcome.TRUE -> true
                                    Outcome.FALSE -> false
                                    Outcome.FAILED -> error("failed binding")
                                    Outcome.PENDING -> unresolved.await()
                                }
                            }
                        }
                    }
                    try {
                        if (expected == Outcome.PENDING) {
                            repeat(20) { yield() }
                            assertFalse(evaluation.isCompleted, "case=$caseIndex bits=$bits")
                        } else {
                            val result = withTimeout(1_000) { evaluation.await() }
                            val actual = if (result.isFailure) {
                                Outcome.FAILED
                            } else if (result.getOrThrow()) {
                                Outcome.TRUE
                            } else {
                                Outcome.FALSE
                            }
                            assertEquals(expected, actual, "case=$caseIndex bits=$bits")
                        }
                    } finally {
                        evaluation.cancelAndJoin()
                    }
                }
            }
        }
}
