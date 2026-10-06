@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.jvm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException

class NodeBatchExecutionTest {
    @Test
    fun `repeated decoded IDs run concurrently and returned map order does not change attribution`() =
        runBlocking {
            val selectors = listOf("A1", "B1", "A2")
            val started = CompletableDeferred<Unit>()
            val invocations = mutableListOf<List<String>>()
            val itemError = IllegalStateException("item failed")

            val results = withTimeout(5_000) {
                executeNodeBatch(
                    selectors = selectors,
                    typeName = "Node",
                    inputFor = { NodeBatchContext(it, it.take(1)) },
                    invoke = { contexts ->
                        invocations += contexts
                        if (invocations.size == 2) started.complete(Unit)
                        started.await()
                        contexts.reversed().associateWith { it.lowercase() }
                    },
                    unwrap = { selector, value ->
                        if (selector == "B1") Result.failure(itemError) else Result.success(value)
                    },
                )
            }

            assertEquals(setOf(listOf("A1", "B1"), listOf("A2")), invocations.toSet())
            assertEquals("a1", results.getValue("A1").getOrThrow())
            assertSame(itemError, results.getValue("B1").exceptionOrNull())
            assertEquals("a2", results.getValue("A2").getOrThrow())
        }

    @Test
    fun `missing or foreign context fails every selector in its group`() =
        runBlocking {
            val selectors = listOf("A", "B")
            val malformed = listOf<(List<String>) -> Map<String, String>>(
                { contexts -> mapOf(contexts.first() to "value") },
                { contexts -> mapOf(contexts.first() to "value", "foreign" to "value") },
            )

            malformed.forEach { returnMap ->
                val results = executeNodeBatch(
                    selectors = selectors,
                    typeName = "Node",
                    inputFor = { NodeBatchContext(it, it) },
                    invoke = { returnMap(it) },
                    unwrap = { _, value -> Result.success(value) },
                )

                val firstError = assertInstanceOf(TenantResolverException::class.java, results.getValue("A").exceptionOrNull())
                val secondError = assertInstanceOf(TenantResolverException::class.java, results.getValue("B").exceptionOrNull())
                assertEquals("Node", firstError.resolver)
                assertEquals("Node", secondError.resolver)
                assertInstanceOf(TenantUsageException::class.java, firstError.cause)
                assertInstanceOf(TenantUsageException::class.java, secondError.cause)
            }
        }

    @Test
    fun `group failure is attributed without discarding another group's results`() =
        runBlocking {
            val selectors = listOf("A1", "B1", "A2", "B2")
            val failure = IllegalStateException("group failed")

            val results = executeNodeBatch(
                selectors = selectors,
                typeName = "Node",
                inputFor = { NodeBatchContext(it, it.take(1)) },
                invoke = { contexts ->
                    if ("A2" in contexts) throw failure
                    contexts.associateWith { it.lowercase() }
                },
                unwrap = { _, value -> Result.success(value) },
            )

            assertEquals("a1", results.getValue("A1").getOrThrow())
            assertEquals("b1", results.getValue("B1").getOrThrow())
            val firstError = assertInstanceOf(TenantResolverException::class.java, results.getValue("A2").exceptionOrNull())
            val secondError = assertInstanceOf(TenantResolverException::class.java, results.getValue("B2").exceptionOrNull())
            assertSame(failure, firstError.cause)
            assertSame(failure, secondError.cause)
        }

    @Test
    fun `independent cancellation is attributed while request stays active`() =
        runBlocking {
            val cancellation = CancellationException("resolver cancelled")

            val results = executeNodeBatch(
                selectors = listOf("A"),
                typeName = "Node",
                inputFor = { NodeBatchContext(it, it) },
                invoke = { _: List<String> -> throw cancellation },
                unwrap = { _: String, value: String -> Result.success(value) },
            )

            val error = assertInstanceOf(TenantResolverException::class.java, results.getValue("A").exceptionOrNull())
            assertSame(cancellation, error.cause)
        }

    @Test
    fun `request cancellation stops a pending group`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val pending = CompletableDeferred<Unit>()
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                executeNodeBatch(
                    selectors = listOf("A"),
                    typeName = "Node",
                    inputFor = { NodeBatchContext(it, it) },
                    invoke = { contexts ->
                        started.complete(Unit)
                        try {
                            pending.await()
                        } finally {
                            stopped.complete(Unit)
                        }
                        contexts.associateWith { it }
                    },
                    unwrap = { _, value -> Result.success(value) },
                )
            }
            started.await()
            request.cancel()

            assertThrows<CancellationException> { request.await() }
            withTimeout(5_000) { stopped.await() }
        }

    @Test
    fun `empty input does not invoke the resolver`() =
        runBlocking {
            val results = executeNodeBatch<String, String, String, String>(
                selectors = emptyList(),
                typeName = "Node",
                inputFor = { error("unexpected input") },
                invoke = { error("unexpected invocation") },
                unwrap = { _, _ -> error("unexpected result") },
            )

            assertTrue(results.isEmpty())
        }
}
