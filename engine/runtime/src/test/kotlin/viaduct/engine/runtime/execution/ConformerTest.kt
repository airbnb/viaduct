@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime.execution

import graphql.schema.DataFetcher
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createExecutionInput
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

class ConformerTest : KotestPropertyBase() {
    @Test
    fun `ctor -- Conformers with different seeds produce different results`() {
        runExecutionTest {
            val c1 = Conformer("type Query { x:Int }", seed = 1L)
            val c2 = Conformer("type Query { x:Int }", seed = 2L)

            val d1 = c1.modernGraphQL.executeAsync(createExecutionInput(c1.schema, "{x}"))
                .await()
                .getData<Map<String, Any?>>()
            val d2 = c2.modernGraphQL.executeAsync(createExecutionInput(c2.schema, "{x}"))
                .await()
                .getData<Map<String, Any?>>()

            assertNotEquals(d1, d2)
        }
    }

    @Test
    fun `ctor -- Conformers with the same seeds produce the same results`() {
        runExecutionTest {
            val c1 = Conformer("type Query { x:Int }", seed = 0L)
            val c2 = Conformer("type Query { x:Int }", seed = 0L)

            val d1 = c1.modernGraphQL.executeAsync(createExecutionInput(c1.schema, "{x}"))
                .await()
                .getData<Map<String, Any?>>()
            val d2 = c2.modernGraphQL.executeAsync(createExecutionInput(c2.schema, "{x}"))
                .await()
                .getData<Map<String, Any?>>()

            assertEquals(d1, d2)
        }
    }

    @Test
    fun `ctor -- can create fixed wiring`() {
        Conformer(
            "type Query { x:Int }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { 1 }))
        ) {
            check("{x}")
        }
    }

    @Test
    fun `check -- arb based wiring can succeed`() {
        assertDoesNotThrow {
            Conformer("type Query { x:Int }") {
                check("{x}")
            }
        }
    }

    @Test
    fun `check -- fixed wiring can succeed`() {
        Conformer(
            "type Query { x:Int }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { 1 }))
        ) {
            check("{x}")
        }
    }

    @Test
    fun `check -- checkNoModernErrors`() {
        Conformer(
            "type Query { x:Int }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { throw RuntimeException() }))
        ) {
            assertDoesNotThrow {
                check("{x}", checkNoModernErrors = false)
            }

            assertThrows<AssertionError> {
                check("{x}", checkNoModernErrors = true)
            }
        }
    }

    @Test
    fun `check -- checkResultsEqual`() {
        val int = AtomicInteger(0)
        Conformer(
            "type Query { x:Int }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { int.incrementAndGet() }))
        ) {
            assertDoesNotThrow {
                check("{x}", checkResultsEqual = false)
            }

            assertThrows<AssertionError> {
                check("{x}", checkResultsEqual = true)
            }
        }
    }

    @Test
    fun `check -- extraChecks`() {
        Conformer(
            "type Query { x:Int }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { 1 }))
        ) {
            assertDoesNotThrow {
                check("{x}")
            }

            assertThrows<AssertionError> {
                check("{x}") { _, _ ->
                    fail()
                }
            }
        }
    }

    @Test
    fun `checkAll -- succeeds`() {
        assertDoesNotThrow {
            Conformer("type Query { x:Int }") {
                val arb = Arb.constant(
                    createExecutionInput(schema, "{x}")
                )
                arb.checkAll(100)
            }
        }
    }

    @Test
    fun `checkAll -- failure includes correct seed value`() {
        val conformerSeed = 42L
        val assertionFailure = assertThrows<AssertionError> {
            Conformer("type Query { x:Int }", seed = conformerSeed) {
                val arb = Arb.constant(
                    createExecutionInput(schema, "{x}")
                )
                arb.checkAll { _, _ -> fail() }
            }
        }

        assertTrue(assertionFailure.message?.contains(conformerSeed.toString()) ?: false) {
            assertionFailure.message
        }
    }
}
