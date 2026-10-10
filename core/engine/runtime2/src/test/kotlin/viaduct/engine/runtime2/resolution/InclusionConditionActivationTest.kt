@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld

class InclusionConditionActivationTest {
    private val schema =
        TestWorld.fromSDL("type Query { value: Boolean! }").schema
    private val field = schema.requireObjectField("Query", "value")
    private val x = Arguments.Variable.of(field, "x")
    private val y = Arguments.Variable.of(field, "y")

    @Test
    fun `ready true alternative does not await suspended sibling`() =
        runBlocking {
            val suspended = CompletableDeferred<Boolean>()
            val cancelled = CompletableDeferred<Unit>()
            val condition =
                InclusionCondition.requires(mapOf(x to true))
                    .or(InclusionCondition.requires(mapOf(y to true)))

            assertTrue(
                withTimeout(1_000) {
                    condition.includeAnyReadyAlternative { variable ->
                        if (variable == x) {
                            try {
                                suspended.await()
                            } finally {
                                cancelled.complete(Unit)
                            }
                        } else {
                            true
                        }
                    }
                },
            )
            withTimeout(1_000) { cancelled.await() }
        }

    @Test
    fun `ready true alternative masks sibling failure`() =
        runBlocking {
            val condition =
                InclusionCondition.requires(mapOf(x to true))
                    .or(InclusionCondition.requires(mapOf(y to true)))

            assertTrue(
                condition.includeAnyReadyAlternative { variable ->
                    if (variable == x) error("failed binding") else true
                },
            )
        }

    @Test
    fun `activation returns false only when every alternative is false`(): Unit =
        runBlocking {
            val condition =
                InclusionCondition.requires(mapOf(x to true))
                    .or(InclusionCondition.requires(mapOf(y to true)))

            assertFalse(condition.includeAnyReadyAlternative { false })
            assertFailsWith<IllegalStateException> {
                condition.includeAnyReadyAlternative { variable ->
                    if (variable == x) false else error("failed binding")
                }
            }
        }

    @Test
    fun `compact activation preserves distributed conjunction failure`(): Unit =
        runBlocking {
            val z = Arguments.Variable.of(field, "z")
            val condition =
                InclusionCondition.requires(mapOf(x to true))
                    .or(InclusionCondition.requires(mapOf(y to true)))
                    .and(InclusionCondition.requires(mapOf(z to true)))

            assertFailsWith<IllegalStateException> {
                condition.includeAnyReadyAlternative { variable ->
                    when (variable) {
                        x -> error("failed binding")
                        y -> true
                        z -> false
                        else -> error("unexpected variable")
                    }
                }
            }
        }

    @Test
    fun `contradictory alternative does not propagate a binding failure`() =
        runBlocking {
            val condition =
                InclusionCondition.requires(mapOf(x to true))
                    .or(InclusionCondition.requires(mapOf(y to true)))
                    .and(InclusionCondition.requires(mapOf(x to false)))

            assertFalse(
                condition.includeAnyReadyAlternative { variable ->
                    if (variable == x) error("failed binding") else false
                },
            )
        }

    @Test
    fun `contradictory alternative does not await an irrelevant binding`() =
        runBlocking {
            val suspended = CompletableDeferred<Boolean>()
            val condition =
                InclusionCondition.requires(mapOf(x to true))
                    .or(InclusionCondition.requires(mapOf(y to true)))
                    .and(InclusionCondition.requires(mapOf(x to false)))

            assertFalse(
                withTimeout(1_000) {
                    condition.includeAnyReadyAlternative { variable ->
                        if (variable == x) suspended.await() else false
                    }
                },
            )
        }

    @Test
    fun `wide activation is stack safe`() =
        runBlocking {
            val condition =
                InclusionCondition.anyOf(
                    (0 until 10_000).map { index ->
                        InclusionCondition.requires(mapOf(Arguments.Variable.of(field, "x$index") to true))
                    },
                )

            assertFalse(condition.includeAnyReadyAlternative { false })
        }

    @Test
    fun `nested contradictory alternatives do not propagate failed bindings`(): Unit =
        runBlocking {
            for (condition in nestedContradictions()) {
                assertFalse(
                    condition.includeAnyReadyAlternative { variable ->
                        if (variable == x) error("irrelevant failed binding") else false
                    }
                )
            }
        }

    @Test
    fun `nested contradictory alternatives do not await irrelevant bindings`(): Unit =
        runBlocking {
            val unresolved = CompletableDeferred<Boolean>()
            for (condition in nestedContradictions()) {
                assertFalse(
                    withTimeout(1_000) {
                        condition.includeAnyReadyAlternative { variable ->
                            if (variable == x) unresolved.await() else false
                        }
                    }
                )
            }
        }

    @Test
    fun `nested pruning retains failures from an explicit left guard`(): Unit =
        runBlocking {
            val alternatives = InclusionCondition.requires(mapOf(x to true))
                .or(InclusionCondition.requires(mapOf(y to true)))
                .or(InclusionCondition.requires(mapOf(Arguments.Variable.of(field, "z") to true)))
            val condition = InclusionCondition.requires(mapOf(x to false)).and(alternatives)
            assertFailsWith<IllegalStateException> {
                condition.includeAnyReadyAlternative { variable ->
                    if (variable == x) error("required left guard failed") else false
                }
            }
        }

    private fun nestedContradictions(): List<InclusionCondition> {
        val xRequired = InclusionCondition.requires(mapOf(x to true))
        val yRequired = InclusionCondition.requires(mapOf(y to true))
        val zRequired = InclusionCondition.requires(mapOf(Arguments.Variable.of(field, "z") to true))
        val wRequired = InclusionCondition.requires(mapOf(Arguments.Variable.of(field, "w") to true))
        val guard = InclusionCondition.requires(mapOf(x to false))
        return listOf(
            xRequired.or(yRequired).or(zRequired),
            yRequired.or(zRequired.or(xRequired)),
            xRequired.and(wRequired).or(yRequired).or(zRequired),
            xRequired.or(yRequired).or(zRequired).and(wRequired.or(yRequired)),
        ).map { it.and(guard) }
    }

    @Test
    fun `equal guarded alternatives exclude demand without reading a failed binding`() =
        runBlocking {
            val z = Arguments.Variable.of(field, "z")
            val xRequired = InclusionCondition.requires(mapOf(x to true))
            val zRequired = InclusionCondition.requires(mapOf(z to true))
            val condition = zRequired.and(xRequired).or(xRequired).and(zRequired)
            assertFalse(
                condition.includeAnyReadyAlternative { variable ->
                    check(variable == z) { "Excluded demand read a failed binding" }
                    false
                },
            )
        }

    @Test
    fun `equal guarded alternatives exclude demand without awaiting a pending binding`() =
        runBlocking {
            val z = Arguments.Variable.of(field, "z")
            val xRequired = InclusionCondition.requires(mapOf(x to true))
            val zRequired = InclusionCondition.requires(mapOf(z to true))
            val condition = zRequired.and(xRequired).or(xRequired).and(zRequired)
            val unresolved = CompletableDeferred<Boolean>()
            assertFalse(
                withTimeout(1_000) {
                    condition.includeAnyReadyAlternative { if (it == z) false else unresolved.await() }
                },
            )
        }

    @Test
    fun `cancelling activation cancels binding evaluation`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val condition = InclusionCondition.requires(mapOf(x to true))
            val evaluation =
                async {
                    condition.includeAnyReadyAlternative {
                        started.complete(Unit)
                        suspendCancellableCoroutine<Boolean> { }
                    }
                }
            started.await()
            evaluation.cancelAndJoin()
            assertTrue(evaluation.isCancelled)
        }
}
