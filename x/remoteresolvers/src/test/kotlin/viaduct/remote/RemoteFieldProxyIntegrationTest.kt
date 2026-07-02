@file:Suppress("ForbiddenImport")

package viaduct.remote

import graphql.schema.GraphQLObjectType
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineSelectionSet
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.remote.fixtures.ArgumentEchoFieldResolverExecutor
import viaduct.remote.fixtures.CallbackFieldResolverExecutor
import viaduct.remote.fixtures.SimpleFieldResolverExecutor
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.FieldExecutorRegistry
import viaduct.remote.registry.SelectionsRegistry

/**
 * End-to-end test for the remote *field* resolver flow using in-process gRPC channels.
 *
 * Mirrors `RemoteProxyIntegrationTest` (which covers the node path): registers a
 * [FieldResolverExecutor] in [FieldExecutorRegistry], stands up the
 * [RemoteResolverServiceImpl] and [EngineCallbackServiceImpl] servers, and asserts that a
 * field value round-trips through
 * [RemoteFieldProxyExecutor.batchResolve] → [RemoteResolverServiceImpl.batchResolveField]
 * and back, including batching and error propagation.
 */
class RemoteFieldProxyIntegrationTest {
    // The resolver id encodes the parent type ("Character"), which the remote side uses to
    // deserialize each selector's object value against the real schema type.
    private val testSchema = MockSchema.mk(
        """
        extend type Query { test: String }
        type Character {
            id: ID!
            name: String!
            age: Int!
            isAdult: Boolean
        }
        """.trimIndent()
    )

    private val characterType: GraphQLObjectType
        get() = testSchema.schema.getObjectType("Character")

    private val queryType: GraphQLObjectType
        get() = testSchema.schema.queryType

    /** Builds a `.Sync` object value carrying just the `age` field the resolver reads. */
    private fun characterObjectValue(age: Int): EngineObjectData.Sync =
        ResolvedEngineObjectData.Builder(characterType)
            .put(SimpleFieldResolverExecutor.AGE_FIELD, age)
            .build()

    /** Empty query value; the resolver declares no query selection set. */
    private fun emptyQueryValue(): EngineObjectData.Sync = ResolvedEngineObjectData.Builder(queryType).build()

    private fun selectorForAge(age: Int): FieldResolverExecutor.Selector = selectorWith(age = age, arguments = emptyMap())

    /**
     * Builds a selector carrying [arguments]; the object value only needs the `age` field that
     * [SimpleFieldResolverExecutor] reads (argument-reading resolvers ignore it).
     */
    private fun selectorWith(
        age: Int,
        arguments: Map<String, Any?>
    ): FieldResolverExecutor.Selector {
        val objectValue = characterObjectValue(age)
        val queryValue = emptyQueryValue()
        return FieldResolverExecutor.Selector(
            arguments = arguments,
            selections = null,
            syncObjectValueGetter = { objectValue },
            syncQueryValueGetter = { queryValue }
        )
    }

    private suspend inline fun withServers(block: (rrsChannel: io.grpc.ManagedChannel, callbackEndpoint: String, context: EngineExecutionContext) -> Unit) {
        FieldExecutorRegistry.clear()
        ContextRegistry.clear()
        SelectionsRegistry.clear()

        val rrsServerName = "test-rrs-field-${System.nanoTime()}"
        val rrsServer = InProcessServerBuilder
            .forName(rrsServerName)
            .directExecutor()
            .addService(RemoteResolverServiceImpl())
            .build()
            .start()

        val callbackEndpoint = "test-rrp-callback-field-${System.nanoTime()}"
        val callbackServer = InProcessServerBuilder
            .forName(callbackEndpoint)
            .directExecutor()
            .addService(EngineCallbackServiceImpl())
            .build()
            .start()

        val rrsChannel = InProcessChannelBuilder.forName(rrsServerName).directExecutor().build()
        try {
            block(rrsChannel, callbackEndpoint, ContextMocks(testSchema).engineExecutionContext)
        } finally {
            rrsChannel.shutdownNow()
            rrsServer.shutdownNow()
            callbackServer.shutdownNow()
            FieldExecutorRegistry.clear()
            ContextRegistry.clear()
            SelectionsRegistry.clear()
        }
    }

    @Test
    fun `field value round-trips through the full gRPC stack`() =
        runBlocking {
            withServers { rrsChannel, callbackEndpoint, context ->
                val executor = SimpleFieldResolverExecutor()
                val executorId = FieldExecutorRegistry.register(executor)

                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = executor,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                // age 25 -> isAdult == true
                val adultSelector = selectorForAge(25)
                val results = proxy.batchResolve(listOf(adultSelector), context)

                val result = results[adultSelector]
                assertNotNull(result, "Result should not be null")
                assertTrue(result!!.isSuccess, "Result should be success")
                assertEquals(true, result.getOrNull(), "age 25 should resolve isAdult=true")
            }
        }

    @Test
    fun `batch of two selectors each resolve independently`() =
        runBlocking {
            withServers { rrsChannel, callbackEndpoint, context ->
                val executor = SimpleFieldResolverExecutor()
                val executorId = FieldExecutorRegistry.register(executor)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = executor,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val adultSelector = selectorForAge(40)
                val minorSelector = selectorForAge(12)
                val results = proxy.batchResolve(listOf(adultSelector, minorSelector), context)

                assertEquals(2, results.size, "Should have one result per selector")
                assertEquals(true, results[adultSelector]?.getOrNull(), "age 40 should resolve isAdult=true")
                assertEquals(false, results[minorSelector]?.getOrNull(), "age 12 should resolve isAdult=false")
            }
        }

    @Test
    fun `resolver failure surfaces as a RemoteResolverException`() =
        runBlocking {
            withServers { rrsChannel, callbackEndpoint, context ->
                // This executor throws inside batchResolve so we can verify error propagation
                // across the wire.
                val failing = object : FieldResolverExecutor by SimpleFieldResolverExecutor() {
                    override val resolverId: String = "Character.isAdult"

                    override suspend fun batchResolve(
                        selectors: List<FieldResolverExecutor.Selector>,
                        context: EngineExecutionContext
                    ): Map<FieldResolverExecutor.Selector, Result<Any?>> = selectors.associateWith { Result.failure(IllegalStateException("boom from remote field")) }
                }
                val executorId = FieldExecutorRegistry.register(failing)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = failing,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val selector = selectorForAge(30)
                val results = proxy.batchResolve(listOf(selector), context)

                val result = results[selector]
                assertNotNull(result, "Error result should not be null")
                assertFalse(result!!.isSuccess, "Result should be a failure")
                val exception = result.exceptionOrNull()
                assertTrue(exception is RemoteResolverException, "Should be RemoteResolverException, got $exception")
                assertTrue(
                    exception!!.message?.contains("boom from remote field") == true,
                    "Error message should carry the original failure. Got: ${exception.message}"
                )
            }
        }

    @Test
    fun `a serialization failure mid-batch leaks no context or selection handles`() =
        runBlocking {
            // Regression test for a handle leak: batchResolve registers a context handle in
            // ContextRegistry and a selection handle in SelectionsRegistry up front, then
            // serializes each selector (arguments + value getters). If serialization throws,
            // those process-global handles must still be unregistered. withServers clears all
            // registries before this block, giving a clean baseline (size == 0).
            withServers { rrsChannel, callbackEndpoint, context ->
                val executor = SimpleFieldResolverExecutor()
                val executorId = FieldExecutorRegistry.register(executor)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = executor,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                // A non-null selections forces a selection handle to be registered *before* the
                // object-value getter is read, so the failure exercises both registries.
                val selections = createEngineSelectionSet(
                    SelectionsParser.parse("Character", "name"),
                    testSchema,
                    emptyMap()
                )
                val throwingSelector = FieldResolverExecutor.Selector(
                    arguments = emptyMap(),
                    selections = selections,
                    syncObjectValueGetter = { throw RuntimeException("boom") },
                    syncQueryValueGetter = { emptyQueryValue() }
                )

                val thrown = assertThrows<RuntimeException> {
                    runBlocking { proxy.batchResolve(listOf(throwingSelector), context) }
                }
                assertEquals("boom", thrown.message, "The serialization failure should propagate")

                // The fix's try/finally must have unregistered both handles; without it these
                // would each hold one leaked entry.
                assertEquals(0, ContextRegistry.size, "Context handle leaked after serialization failure")
                assertEquals(0, SelectionsRegistry.size, "Selection handle leaked after serialization failure")
            }
        }

    @Test
    fun `constructing a proxy for a selective resolver fails fast`() {
        // Selective field resolvers vary by sub-selection and aren't supported over the wire;
        // the proxy must reject them at construction rather than return a wrong result.
        val selective = object : FieldResolverExecutor by SimpleFieldResolverExecutor() {
            override val isSelective: Boolean = true
        }
        val channel = InProcessChannelBuilder.forName("rrs-selective-${System.nanoTime()}").build()
        try {
            assertThrows<IllegalArgumentException> {
                RemoteFieldProxyExecutor(
                    originalExecutor = selective,
                    executorId = "Character.isAdult",
                    rrsChannel = channel,
                    callbackEndpoint = "cb"
                )
            }
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `selector arguments round-trip to the remote resolver`() =
        runBlocking {
            withServers { rrsChannel, callbackEndpoint, context ->
                // The resolver branches on arguments, so the returned value proves the remote side
                // reconstructed the selector with the same arguments the proxy serialized.
                val executor = ArgumentEchoFieldResolverExecutor()
                val executorId = FieldExecutorRegistry.register(executor)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = executor,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val detailedSelector = selectorWith(
                    age = 25,
                    arguments = mapOf(
                        ArgumentEchoFieldResolverExecutor.INCLUDE_DETAILS_ARG to true,
                        ArgumentEchoFieldResolverExecutor.LIMIT_ARG to 3
                    )
                )
                val plainSelector = selectorWith(
                    age = 25,
                    arguments = mapOf(ArgumentEchoFieldResolverExecutor.INCLUDE_DETAILS_ARG to false)
                )
                val results = proxy.batchResolve(listOf(detailedSelector, plainSelector), context)

                assertEquals(
                    "details:limit=3",
                    results[detailedSelector]?.getOrNull(),
                    "includeDetails=true, limit=3 should be received intact on the remote side"
                )
                assertEquals(
                    "summary",
                    results[plainSelector]?.getOrNull(),
                    "includeDetails=false selector should branch differently"
                )
            }
        }

    @Test
    fun `non-serializable result fails only its own selector in a batch`() =
        runBlocking {
            withServers { rrsChannel, callbackEndpoint, context ->
                // age 7 -> the resolver returns an object value (EngineObjectData), which
                // FieldValueSerializer rejects; any other age returns a Boolean scalar. This drives
                // the per-selector try/catch in RemoteResolverServiceImpl.batchResolveField.
                val objectReturning = object : FieldResolverExecutor by SimpleFieldResolverExecutor() {
                    override suspend fun batchResolve(
                        selectors: List<FieldResolverExecutor.Selector>,
                        context: EngineExecutionContext
                    ): Map<FieldResolverExecutor.Selector, Result<Any?>> =
                        selectors.associateWith { selector ->
                            runCatching {
                                val age = (selector.syncObjectValueGetter().get(SimpleFieldResolverExecutor.AGE_FIELD) as Number).toInt()
                                if (age == OBJECT_RETURN_AGE) {
                                    // Non-JSON-friendly: an object value carries no type identity on the wire.
                                    characterObjectValue(age)
                                } else {
                                    age >= SimpleFieldResolverExecutor.ADULT_AGE
                                }
                            }
                        }
                }
                val executorId = FieldExecutorRegistry.register(objectReturning)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = objectReturning,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val badSelector = selectorForAge(OBJECT_RETURN_AGE)
                val goodSelector = selectorForAge(30)
                val results = proxy.batchResolve(listOf(badSelector, goodSelector), context)

                // The object-returning selector is isolated to a failure...
                val badResult = results[badSelector]
                assertNotNull(badResult, "Bad selector should have a result")
                assertFalse(badResult!!.isSuccess, "Object-returning selector should fail")
                assertTrue(
                    badResult.exceptionOrNull() is RemoteResolverException,
                    "Should be RemoteResolverException, got ${badResult.exceptionOrNull()}"
                )

                // ...while the scalar selector in the same batch still succeeds.
                assertEquals(
                    true,
                    results[goodSelector]?.getOrNull(),
                    "The other selector should resolve normally despite its batch-mate failing"
                )
            }
        }

    @Test
    fun `re-entrant query from a field resolver fires the callback path`() =
        runBlocking {
            // Mirrors the node `test callback flow with re-entrant calls`: ContextMocks runs over a
            // no-op engine, so the re-entrant query cannot complete. We assert the callback
            // mechanism fires (the failure originates engine-side, behind the gRPC callback
            // channel) rather than that the query succeeds.
            withServers { rrsChannel, callbackEndpoint, context ->
                val executor = CallbackFieldResolverExecutor()
                val executorId = FieldExecutorRegistry.register(executor)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = executor,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val selector = selectorForAge(25)
                val results = proxy.batchResolve(listOf(selector), context)

                // The re-entrant resolveSelectionSet routes Proxy → gRPC → service → resolver →
                // callback channel → engine. With the test context the engine-side query cannot
                // complete, so the selector comes back as a failure. Crucially, the failure
                // arrives as a gRPC StatusException: that error type can only originate from the
                // callback channel round-trip, proving the callback fired (a purely local failure
                // would carry a JVM exception type instead).
                val result = results[selector]
                assertNotNull(result, "Result should not be null")
                assertFalse(result!!.isSuccess, "Re-entrant query cannot complete under ContextMocks")
                val exception = result.exceptionOrNull()
                assertTrue(
                    exception is RemoteResolverException,
                    "Should surface as RemoteResolverException, got $exception"
                )
                assertTrue(
                    (exception as RemoteResolverException).errorType.startsWith("io.grpc.Status"),
                    "Failure should arrive over the gRPC callback channel (a grpc StatusException), " +
                        "confirming the callback fired. Got errorType=${exception.errorType}, message=${exception.message}"
                )
            }
        }

    private companion object {
        // Sentinel age that makes the object-returning fixture emit a non-serializable value.
        private const val OBJECT_RETURN_AGE = 7
    }
}
