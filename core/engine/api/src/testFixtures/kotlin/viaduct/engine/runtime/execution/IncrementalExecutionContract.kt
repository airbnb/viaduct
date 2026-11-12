@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.Engine
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.runStandardViaductFeatureTest
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

interface IncrementalExecutionContract {
    fun runIncrementalTest(
        module: EngineTestModule,
        incrementalEnabled: Boolean = true,
        block: suspend CoroutineScope.(Engine) -> Unit,
    )

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `deferred fields arrive incrementally when supported and otherwise in a complete result`(incrementalExecution: Boolean) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val module = incrementalModule {
            calls++
            started.complete(Unit)
            release.await()
            "later"
        }
        runIncrementalTest(module) { engine ->
            val executionInput = input("{ __typename ... @defer(label: \"details\") { test } }")
            var initial: IncrementalExecutionResult? = null
            try {
                if (incrementalExecution && engine.supportsIncrementalExecution) {
                    initial = assertInstanceOf(IncrementalExecutionResult::class.java, engine.execute(executionInput, incrementalExecution))
                    assertEquals(mapOf("__typename" to "Query"), initial.getData())
                    assertTrue(initial.errors.isEmpty())
                    assertTrue(initial.hasNext)
                    val pending = initial.pending.single()
                    assertEquals("details", pending.label)
                    assertEquals(emptyList<Any>(), pending.path.toList())
                    assertFalse(release.isCompleted)
                    release.complete(Unit)
                    val update = initial.updates.toList().single()
                    assertEquals(
                        mapOf(
                            "incremental" to listOf(mapOf("id" to pending.id, "data" to mapOf("test" to "later"))),
                            "completed" to listOf(mapOf("id" to pending.id)),
                            "hasNext" to false,
                        ),
                        update.toSpecification(),
                    )
                } else {
                    val execution = async { engine.execute(executionInput, incrementalExecution) }
                    try {
                        started.await()
                        assertFalse(execution.isCompleted)
                        release.complete(Unit)
                        assertComplete(execution.await(), mapOf("__typename" to "Query", "test" to "later"))
                    } finally {
                        execution.cancelAndJoin()
                    }
                }
                assertEquals(1, calls)
            } finally {
                release.complete(Unit)
                initial?.cancel()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `multiple deferred groups preserve data labels and completion`(reverseCompletionOrder: Boolean) {
        val completionOrder = if (reverseCompletionOrder) listOf("second", "first") else listOf("first", "second")
        val releases = mapOf("first" to CompletableDeferred<Unit>(), "second" to CompletableDeferred<Unit>())
        val module = EngineTestModule("extend type Query { initial: String @resolver, first: String @resolver, second: String @resolver }") {
            for (name in listOf("initial", "first", "second")) {
                field("Query" to name) {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(resolverId = "Query.$name") { _, _, _, _, _ ->
                            releases[name]?.await()
                            name
                        }
                    }
                }
            }
        }
        runIncrementalTest(module) { engine ->
            var initial: IncrementalExecutionResult? = null
            try {
                val executionInput = input("{ initial ... @defer(label: \"first\") { first } ... @defer(label: \"second\") { second } }")
                if (engine.supportsIncrementalExecution) {
                    initial = assertInstanceOf(IncrementalExecutionResult::class.java, engine.execute(executionInput))
                    assertEquals(mapOf("initial" to "initial"), initial.getData())
                    assertTrue(initial.errors.isEmpty())
                    assertTrue(initial.hasNext)
                    val pending = initial.pending.associateBy { it.label }
                    assertEquals(setOf("first", "second"), pending.keys)
                    assertEquals(2, pending.values.map { it.id }.toSet().size)
                    pending.values.forEach { assertEquals(emptyList<Any>(), it.path.toList()) }
                    assertTrue(releases.values.none { it.isCompleted })
                    releases.getValue(completionOrder.first()).complete(Unit)
                    var received = 0
                    initial.updates.collect { update ->
                        val name = completionOrder[received++]
                        val id = pending.getValue(name).id
                        assertEquals(
                            mapOf(
                                "incremental" to listOf(mapOf("id" to id, "data" to mapOf(name to name))),
                                "completed" to listOf(mapOf("id" to id)),
                                "hasNext" to (received < completionOrder.size),
                            ),
                            update.toSpecification(),
                        )
                        if (received < completionOrder.size) releases.getValue(completionOrder[received]).complete(Unit)
                    }
                    assertEquals(2, received)
                } else {
                    releases.values.forEach { it.complete(Unit) }
                    assertComplete(engine.execute(executionInput), mapOf("initial" to "initial", "first" to "first", "second" to "second"))
                }
            } finally {
                releases.values.forEach { it.complete(Unit) }
                initial?.cancel()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `absent defer or a disabled feature gate returns a complete result`(incrementalEnabled: Boolean) {
        runIncrementalTest(incrementalModule { "value" }, incrementalEnabled) { engine ->
            val query = if (incrementalEnabled) "{ test }" else "{ ... @defer { test } }"
            assertComplete(engine.execute(input(query)), mapOf("test" to "value"))
            if (!incrementalEnabled) assertFalse(engine.supportsIncrementalExecution)
        }
    }

    @Test
    fun `false defer condition returns a complete result`() {
        runIncrementalTest(incrementalModule { "value" }) { engine ->
            assertComplete(
                engine.execute(input("{ __typename ... @defer(if: false) { test } }")),
                mapOf("__typename" to "Query", "test" to "value"),
            )
        }
    }

    @Test
    fun `deferred resolver errors survive incremental or complete delivery`() {
        runIncrementalTest(incrementalModule { error("deferred failure") }) { engine ->
            val result = engine.execute(input("{ __typename ... @defer { test } }"))
            if (engine.supportsIncrementalExecution) {
                val initial = assertInstanceOf(IncrementalExecutionResult::class.java, result)
                try {
                    assertTrue(initial.errors.isEmpty())
                    val updates = initial.updates.toList()
                    assertFalse(updates.last().hasNext)
                    val entries = updates.flatMap { it.incremental }
                    val errors = entries.flatMap { it.errors } + updates.flatMap { it.completed }.flatMap { it.errors }
                    assertEquals(listOf(listOf("test")), errors.map { it.path })
                    assertTrue(errors.single().message.contains("deferred failure"))
                    assertEquals(mapOf("test" to null), (entries.single() as viaduct.engine.api.IncrementalEntry.Object).data)
                } finally {
                    initial.cancel()
                }
            } else {
                assertFalse(result is IncrementalExecutionResult)
                assertEquals(mapOf("__typename" to "Query", "test" to null), result.getData())
                assertEquals(listOf(listOf("test")), result.errors.map { it.path })
                assertTrue(result.errors.single().message.contains("deferred failure"))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["{", "{ missingField }"])
    fun `invalid requests return complete errors without invoking resolvers`(query: String) {
        var calls = 0
        runIncrementalTest(
            incrementalModule {
                calls++
                "value"
            }
        ) { engine ->
            val result = engine.execute(input(query))
            assertFalse(result is IncrementalExecutionResult)
            assertTrue(result.errors.isNotEmpty())
            assertFalse(result.isDataPresent)
            assertEquals(0, calls)
        }
    }

    @Test
    fun `cancelling execution before a result stops resolver work`() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        runIncrementalTest(
            incrementalModule {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        ) { engine ->
            val execution = launch { engine.execute(input("{ test }")) }
            try {
                started.await()
                execution.cancelAndJoin()
                cancelled.await()
            } finally {
                execution.cancelAndJoin()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancelling an incremental result or its updates stops deferred work`(collectUpdates: Boolean) {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        runIncrementalTest(
            incrementalModule {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        ) { engine ->
            assumeTrue(engine.supportsIncrementalExecution)
            val result = assertInstanceOf(IncrementalExecutionResult::class.java, engine.execute(input("{ __typename ... @defer { test } }")))
            try {
                assertEquals(mapOf("__typename" to "Query"), result.getData())
                assertTrue(result.hasNext)
                started.await()
                if (collectUpdates) {
                    val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                        result.updates.collect()
                    }
                    collection.cancelAndJoin()
                } else {
                    result.cancel()
                }
                cancelled.await()
            } finally {
                result.cancel()
            }
        }
    }
}

fun EngineTestModule.runIncrementalEngineTest(
    engine2Enabled: Boolean,
    incrementalEnabled: Boolean,
    block: suspend CoroutineScope.(Engine) -> Unit,
) {
    val config = EngineConfiguration.default.copy(
        flagManager = MockFlagManager(if (incrementalEnabled) setOf(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION) else emptySet()),
    )
    runStandardViaductFeatureTest(engine2Enabled = engine2Enabled, engineConfig = config) {
        val selectedEngine = engine
        runBlocking {
            withTimeout(5.seconds) {
                config.coroutineInterop.enterThreadLocalCoroutineContext(coroutineContext) {
                    block(selectedEngine)
                }.await()
            }
        }
    }
}

private fun incrementalModule(resolve: suspend () -> String): EngineTestModule =
    EngineTestModule("extend type Query { test: String @resolver }") {
        field("Query" to "test") {
            resolverExecutor {
                MockFieldUnbatchedResolverExecutor(resolverId = "Query.test") { _, _, _, _, _ -> resolve() }
            }
        }
    }

private fun input(query: String) = ExecutionInput(query, requestContext = Any())

private fun assertComplete(
    result: ExecutionResult,
    data: Map<String, Any?>
) {
    assertFalse(result is IncrementalExecutionResult)
    assertTrue(result.errors.isEmpty(), result.errors.toString())
    assertEquals(mapOf("data" to data), result.toSpecification())
}
