package viaduct.engine.runtime2.execution

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.dataloader.NextTickDispatcher
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.FieldDataLoader
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.errors.TenantResolverException
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.internal.BaseBatchedFieldResolver
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.resolvers.FieldValue
import viaduct.java.api.types.Query
import viaduct.java.runtime.bridge.FieldBatchResolverExecutorImpl

class Runtime2JavaBatchScopeTest {
    class QueryResult(context: InternalContext?, data: EngineObjectData.Sync) : ObjectBase(context, data), Query

    private val schema = MockSchema.mk("extend type Query { empty: Int }")

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handling one subquery failure preserves the other batch item`(): Unit =
        runTest {
            val failure = TenantResolverException(IllegalStateException("query failed"), "Query.empty")
            val success = queryResult(empty = 42)

            val batch = runJavaFieldBatch(
                subqueryResults = mapOf(
                    "bad" to Result.failure(failure),
                    "good" to Result.success(success),
                ),
                resolveItem = ::queryReturningAnItemResult,
            )

            assertEquals(2, batch.callbackSize)
            assertHasCause(failure, batch.results.getValue("bad").exceptionOrNull())
            assertSame(success, batch.results.getValue("good").getOrThrow())
            assertTrue(batch.requestWasActive)
        }

    private fun queryReturningAnItemResult(context: FieldExecutionContext<*, *, *, *>) =
        context.query("empty", emptyMap(), QueryResult::class.java)
            .handle<FieldValue<*>> { value, error ->
                if (error == null) {
                    FieldValue.ofValue(value)
                } else {
                    FieldValue.ofError<QueryResult>(error as Exception)
                }
            }

    private fun queryResult(empty: Int) = ResolvedEngineObjectData(schema.schema.queryType, mapOf("empty" to empty))

    private fun assertHasCause(
        expected: Throwable,
        actual: Throwable?
    ) {
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected }, "Expected cause $expected in $actual")
    }

    private data class JavaFieldBatchResult(
        val callbackSize: Int,
        val results: Map<String, Result<Any?>>,
        val requestWasActive: Boolean,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.runJavaFieldBatch(
        subqueryResults: Map<String, Result<EngineObjectData.Sync>>,
        resolveItem: (FieldExecutionContext<*, *, *, *>) -> CompletableFuture<FieldValue<*>>,
    ): JavaFieldBatchResult {
        val handles = subqueryResults.keys.associateWith { object : EngineExecutionContext.ExecutionHandle {} }
        val resultsByHandle = handles.map { (name, handle) -> handle to subqueryResults.getValue(name) }.toMap()
        val engine = mockk<Engine> {
            every { supportsIncrementalExecution } returns false
            coEvery { resolveSelectionSet(any(), any(), any()) } coAnswers {
                yield()
                resultsByHandle.getValue(firstArg()).getOrThrow()
            }
        }
        val mocks = ContextMocks(myFullSchema = schema, myEngine = engine)
        var callbackSize = 0
        val resolver = object : BaseBatchedFieldResolver {
            override fun invokeFieldBatchResolver(contexts: List<FieldExecutionContext<*, *, *, *>>): CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, Any>> =
                error("Legacy adapter must not be called")

            override fun invokeFieldBatchResolverWithErrors(contexts: List<FieldExecutionContext<*, *, *, *>>): CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, FieldValue<*>?>?> {
                callbackSize = contexts.size
                val values = contexts.map(resolveItem)
                return CompletableFuture.allOf(*values.toTypedArray()).thenApply {
                    contexts.zip(values.map { it.join() }).toMap()
                }
            }
        }
        val executor = FieldBatchResolverExecutorImpl(Provider { resolver }, "Query.batch", "Query.batch")
        val loader = FieldDataLoader(Runtime2ScopedFieldBatchExecutor(executor))
        val requestJob = Job()
        val dispatcher = NextTickDispatcher(
            StandardTestDispatcher(testScheduler),
            UnconfinedTestDispatcher(testScheduler),
            parentJob = requestJob,
        )
        try {
            val results = withContext(dispatcher) {
                handles.mapValues { (_, handle) ->
                    val context = mocks.invocationContext(EngineExecutionContextImpl.FieldExecutionScopeImpl(), null, handle)
                    val selector = FieldResolverExecutor.Selector(emptyMap(), null, { error("No object input") }, { error("No Query input") })
                    async { loader.loadByKey(selector, context) }
                }.mapValues { (_, result) -> result.await() }
            }
            return JavaFieldBatchResult(callbackSize, results, requestJob.isActive)
        } finally {
            requestJob.cancelAndJoin()
        }
    }
}
