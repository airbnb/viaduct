package viaduct.engine.runtime.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.deferred.currentRequestParentJobOrNull
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

@Timeout(30)
class ValueCompositionExecutionTest {
    private class Request {
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val parents = ConcurrentHashMap.newKeySet<Job>()
        val exits = AtomicInteger()
        private val starts = AtomicInteger()

        suspend fun pending(value: Int): Int {
            parents.add(requireNotNull(currentRequestParentJobOrNull()))
            if (starts.incrementAndGet() == 3) ready.complete(Unit)
            try {
                release.await()
                if (value == 1) error("controlled field failure")
                return value
            } finally {
                exits.incrementAndGet()
            }
        }

        suspend fun assertDrained() {
            parents.single().join()
            assertFalse(parents.single().children.any())
            assertEquals(3, exits.get())
        }
    }

    private fun fixture() =
        EngineTestModule(
            """
        extend type Query { instant: Int, delayed: Int, items: [CompositionItem] }
        type CompositionItem { index: Int, value: Int }
            """.trimIndent(),
        ) {
            field("Query" to "instant") { resolver { fn { _, _, _, _, _ -> 1 } } }
            field("Query" to "delayed") {
                resolver { fn { _, _, _, _, ctx -> (ctx.requestContext as Request).pending(1) } }
            }
            field("Query" to "items") {
                resolver {
                    fn { _, _, _, _, _ ->
                        List(2) { index -> createEngineObjectData(schema.schema.getObjectType("CompositionItem"), mapOf("index" to index)) }
                    }
                }
            }
            field("CompositionItem" to "value") {
                resolver {
                    objectSelections("index")
                    fn { _, obj, _, _, ctx -> (ctx.requestContext as Request).pending(obj.fetchAs("index")) }
                }
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancelling pending composition preserves another request's response and cleanup`(mat: Boolean) {
        val config = EngineConfiguration.featureTestDefault.copy(
            flagManager = if (mat) MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION) else MockFlagManager.Disabled,
        )
        fixture().runFeatureTest(engineConfig = config) {
            runExecutionTest<Unit> {
                withTimeout(5_000) {
                    val cancelled = Request()
                    val surviving = Request()
                    val query = "{ instant delayed items { index value } }"
                    val first = async { engine.execute(ExecutionInput(query, requestContext = cancelled)) }
                    val second = async { engine.execute(ExecutionInput(query, requestContext = surviving)) }
                    try {
                        cancelled.ready.await()
                        surviving.ready.await()
                        assertNotSame(cancelled.parents.single(), surviving.parents.single())
                        first.cancelAndJoin()
                        assertThrows<CancellationException> { first.await() }
                        cancelled.assertDrained()
                        assertTrue(cancelled.parents.single().isCancelled)
                        assertTrue(second.isActive)
                        assertTrue(surviving.parents.single().isActive)
                        surviving.release.complete(Unit)
                        val result = second.await()
                        assertEquals(
                            mapOf(
                                "instant" to 1,
                                "delayed" to null,
                                "items" to listOf(mapOf("index" to 0, "value" to 0), mapOf("index" to 1, "value" to null)),
                            ),
                            result.getData<Map<String, Any?>>(),
                        )
                        assertEquals(setOf(listOf("delayed"), listOf("items", 1, "value")), result.errors.map { it.path }.toSet())
                        assertEquals(2, result.errors.size)
                        surviving.assertDrained()
                    } finally {
                        first.cancel()
                        second.cancel()
                    }
                }
            }
        }
    }
}
