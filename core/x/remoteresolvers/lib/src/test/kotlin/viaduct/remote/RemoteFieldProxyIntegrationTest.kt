@file:Suppress("ForbiddenImport")

package viaduct.remote

import com.google.protobuf.ByteString
import graphql.schema.GraphQLObjectType
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineSelectionSet
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.remote.fixtures.ArgumentEchoFieldResolverExecutor
import viaduct.remote.fixtures.CallbackFieldResolverExecutor
import viaduct.remote.fixtures.SimpleFieldResolverExecutor
import viaduct.remote.grpc.BatchResolveFieldRequest
import viaduct.remote.grpc.FieldSelector
import viaduct.remote.grpc.SerializedSelectionSet
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.FieldExecutorRegistry
import viaduct.remote.registry.SchemaRegistry
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

    // The direct-batchResolveField tests register a schema in the process-global SchemaRegistry to
    // drive the NETWORK reconstruction path; withServers clears the other registries in its finally
    // but not this one. Clear it after every test so a registered schema can't leak into a later test.
    @AfterEach
    fun clearSchemaRegistry() {
        SchemaRegistry.clear()
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
    fun `a batch sharing one selection-set instance resolves every selector`() =
        runBlocking {
            // The proxy memoizes the shipped selection set by object identity (one toFragment()/
            // register per distinct instance). Drive a batch whose selectors all share ONE non-null
            // EngineSelectionSet instance and verify every selector still round-trips to its own value —
            // memoizing the shared selection set must not collapse or corrupt the per-selector results.
            withServers { rrsChannel, callbackEndpoint, context ->
                val executor = SimpleFieldResolverExecutor()
                val executorId = FieldExecutorRegistry.register(executor)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = executor,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                // One selection-set instance shared across all three selectors.
                val sharedSelections = createEngineSelectionSet(
                    SelectionsParser.parse("Character", "name"),
                    testSchema,
                    emptyMap()
                )

                // A fresh syncObjectValueGetter per call keeps the selectors distinct map keys (Selector
                // identity keys on that lambda) even though they share the same arguments and selections.
                fun selectorSharing(age: Int): FieldResolverExecutor.Selector {
                    val objectValue = characterObjectValue(age)
                    val queryValue = emptyQueryValue()
                    return FieldResolverExecutor.Selector(
                        arguments = emptyMap(),
                        selections = sharedSelections,
                        syncObjectValueGetter = { objectValue },
                        syncQueryValueGetter = { queryValue }
                    )
                }

                val adultSelector = selectorSharing(40)
                val minorSelector = selectorSharing(12)
                val secondAdultSelector = selectorSharing(21)
                val results = proxy.batchResolve(
                    listOf(adultSelector, minorSelector, secondAdultSelector),
                    context
                )

                assertEquals(3, results.size, "each selector sharing the selection set should resolve independently")
                assertEquals(true, results[adultSelector]?.getOrNull(), "age 40 should resolve isAdult=true")
                assertEquals(false, results[minorSelector]?.getOrNull(), "age 12 should resolve isAdult=false")
                assertEquals(true, results[secondAdultSelector]?.getOrNull(), "age 21 should resolve isAdult=true")
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
            // serializes each selector (arguments + value getters). A serialization failure is now
            // isolated to the offending selector's Result rather than propagated, but the
            // process-global handles must still be unregistered in the finally block regardless.
            // withServers clears all registries before this block, giving a clean baseline (size == 0).
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

                // batchResolve no longer propagates the failure; it isolates it to this selector's
                // Result (and, since it's the only selector, returns without making an RPC).
                val results = proxy.batchResolve(listOf(throwingSelector), context)

                val result = results[throwingSelector]
                assertNotNull(result, "Result should not be null")
                assertFalse(result!!.isSuccess, "Serialization failure should surface as a failure")
                assertTrue(
                    result.exceptionOrNull() is RemoteResolverException,
                    "Should be RemoteResolverException, got ${result.exceptionOrNull()}"
                )

                // The try/finally must have unregistered both handles despite the isolation; without
                // it these would each hold one leaked entry.
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
                // age 7 -> the resolver returns a value the wire format genuinely can't carry (an
                // arbitrary non-JSON, non-EngineObject type); any other age returns a Boolean scalar.
                // This drives the per-selector try/catch in RemoteResolverServiceImpl.batchResolveField.
                val unserializableReturning = object : FieldResolverExecutor by SimpleFieldResolverExecutor() {
                    override suspend fun batchResolve(
                        selectors: List<FieldResolverExecutor.Selector>,
                        context: EngineExecutionContext
                    ): Map<FieldResolverExecutor.Selector, Result<Any?>> =
                        selectors.associateWith { selector ->
                            runCatching {
                                val age = (selector.syncObjectValueGetter().get(SimpleFieldResolverExecutor.AGE_FIELD) as Number).toInt()
                                if (age == UNSERIALIZABLE_AGE) Unserializable() else age >= SimpleFieldResolverExecutor.ADULT_AGE
                            }
                        }
                }
                val executorId = FieldExecutorRegistry.register(unserializableReturning)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = unserializableReturning,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val badSelector = selectorForAge(UNSERIALIZABLE_AGE)
                val goodSelector = selectorForAge(30)
                val results = proxy.batchResolve(listOf(badSelector, goodSelector), context)

                // The non-serializable selector is isolated to a failure...
                val badResult = results[badSelector]
                assertNotNull(badResult, "Bad selector should have a result")
                assertFalse(badResult!!.isSuccess, "Non-serializable selector should fail")
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
    fun `a sender-side serialization failure fails only its own selector in a batch`() =
        runBlocking {
            withServers { rrsChannel, callbackEndpoint, context ->
                // The middle selector's object-value getter throws while the proxy is serializing the
                // batch, so it never reaches the wire. The two well-formed selectors around it must
                // still be sent to the remote and resolve normally. This is the sender-side mirror of
                // `non-serializable result fails only its own selector in a batch`: one bad selector
                // can't sink its batch-mates.
                val executor = SimpleFieldResolverExecutor()
                val executorId = FieldExecutorRegistry.register(executor)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = executor,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val adultSelector = selectorForAge(40)
                val throwingSelector = FieldResolverExecutor.Selector(
                    arguments = emptyMap(),
                    selections = null,
                    syncObjectValueGetter = { throw RuntimeException("boom mid-batch") },
                    syncQueryValueGetter = { emptyQueryValue() }
                )
                val minorSelector = selectorForAge(12)
                val results = proxy.batchResolve(
                    listOf(adultSelector, throwingSelector, minorSelector),
                    context
                )

                // The middle selector is isolated to a failure...
                val throwingResult = results[throwingSelector]
                assertNotNull(throwingResult, "Throwing selector should have a result")
                assertFalse(throwingResult!!.isSuccess, "The un-serializable middle selector should fail")
                assertTrue(
                    throwingResult.exceptionOrNull() is RemoteResolverException,
                    "Should be RemoteResolverException, got ${throwingResult.exceptionOrNull()}"
                )

                // ...while both well-formed selectors still round-trip through the real RPC.
                assertEquals(true, results[adultSelector]?.getOrNull(), "age 40 should resolve isAdult=true")
                assertEquals(false, results[minorSelector]?.getOrNull(), "age 12 should resolve isAdult=false")
            }
        }

    @Test
    fun `a node-reference field value round-trips as a NodeReference over the wire`() =
        runBlocking {
            withServers { rrsChannel, callbackEndpoint, context ->
                // The resolver returns a node reference (as `Character.species` would). It must ship
                // as {id, type} and be rebuilt into a NodeReference on the engine side.
                val refReturning = object : FieldResolverExecutor by SimpleFieldResolverExecutor() {
                    override suspend fun batchResolve(
                        selectors: List<FieldResolverExecutor.Selector>,
                        context: EngineExecutionContext
                    ): Map<FieldResolverExecutor.Selector, Result<Any?>> = selectors.associateWith { Result.success(context.createNodeReference("Character:7", characterType)) }
                }
                val executorId = FieldExecutorRegistry.register(refReturning)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = refReturning,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val selector = selectorForAge(25)
                val result = proxy.batchResolve(listOf(selector), context)[selector]
                assertNotNull(result, "Result should not be null")
                assertTrue(result!!.isSuccess, "Node-reference result should succeed")
                val value = result.getOrNull()
                assertTrue(value is NodeReference, "Expected a NodeReference, got $value")
                assertEquals("Character:7", (value as NodeReference).id)
                assertEquals("Character", value.type.name)
            }
        }

    @Test
    fun `an object field value round-trips as an EngineObjectData over the wire`() =
        runBlocking {
            withServers { rrsChannel, callbackEndpoint, context ->
                // The resolver returns a resolved object (as an object-typed field would). It must
                // ship as {type, field-map} and rebuild against the real type on the engine side.
                val objectReturning = object : FieldResolverExecutor by SimpleFieldResolverExecutor() {
                    override suspend fun batchResolve(
                        selectors: List<FieldResolverExecutor.Selector>,
                        context: EngineExecutionContext
                    ): Map<FieldResolverExecutor.Selector, Result<Any?>> =
                        selectors.associateWith {
                            Result.success(
                                ResolvedEngineObjectData.Builder(characterType).put("name", "Yoda").build()
                            )
                        }
                }
                val executorId = FieldExecutorRegistry.register(objectReturning)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = objectReturning,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val selector = selectorForAge(25)
                val result = proxy.batchResolve(listOf(selector), context)[selector]
                assertNotNull(result, "Result should not be null")
                assertTrue(result!!.isSuccess, "Object result should succeed")
                val value = result.getOrNull()
                assertTrue(value is EngineObjectData.Sync, "Expected an EngineObjectData.Sync, got $value")
                assertEquals("Character", (value as EngineObjectData.Sync).type.name)
                assertEquals("Yoda", value.get("name"))
            }
        }

    @Test
    fun `a list of objects field value round-trips element-by-element over the wire`() =
        runBlocking {
            withServers { rrsChannel, callbackEndpoint, context ->
                // Mirrors `allCharacters`: a list of resolved objects. Each element ships tagged and
                // rebuilds independently on the engine side.
                val listReturning = object : FieldResolverExecutor by SimpleFieldResolverExecutor() {
                    override suspend fun batchResolve(
                        selectors: List<FieldResolverExecutor.Selector>,
                        context: EngineExecutionContext
                    ): Map<FieldResolverExecutor.Selector, Result<Any?>> =
                        selectors.associateWith {
                            Result.success(
                                listOf(
                                    ResolvedEngineObjectData.Builder(characterType).put("name", "Luke").build(),
                                    ResolvedEngineObjectData.Builder(characterType).put("name", "Leia").build()
                                )
                            )
                        }
                }
                val executorId = FieldExecutorRegistry.register(listReturning)
                val proxy = RemoteFieldProxyExecutor(
                    originalExecutor = listReturning,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val selector = selectorForAge(25)
                val result = proxy.batchResolve(listOf(selector), context)[selector]
                assertNotNull(result, "Result should not be null")
                assertTrue(result!!.isSuccess, "List result should succeed")
                val list = result.getOrNull() as List<*>
                assertEquals(2, list.size)
                assertEquals("Luke", (list[0] as EngineObjectData.Sync).get("name"))
                assertEquals("Leia", (list[1] as EngineObjectData.Sync).get("name"))
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

    @Test
    fun `a serialized selection set is reconstructed on the remote when the handle is unresolvable`() =
        runBlocking {
            // Simulates NETWORK mode: the per-JVM selections handle isn't resolvable on the remote, so
            // the service must rebuild the field's sub-selection set from the serialized {type,
            // document, variables} shipped in the FieldSelector. We call batchResolveField directly with
            // an EMPTY handle + serialized selections and an UNREGISTERED context handle (forcing the
            // schema-only reconstruction path), and capture the selection set the resolver receives.
            FieldExecutorRegistry.clear()
            ContextRegistry.clear()
            SelectionsRegistry.clear()
            SchemaRegistry.register(testSchema)

            val received = AtomicReference<String?>()
            val recording = object : FieldResolverExecutor by SimpleFieldResolverExecutor(resolverId = "Character.friend") {
                override suspend fun batchResolve(
                    selectors: List<FieldResolverExecutor.Selector>,
                    context: EngineExecutionContext
                ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
                    received.set(selectors.first().selections?.printAsFieldSet())
                    return selectors.associateWith { Result.success(true) }
                }
            }
            val executorId = FieldExecutorRegistry.register(recording)

            // The selection set we "ship" — on Character (a testSchema type), fields name + age.
            val shipped = createEngineSelectionSet(SelectionsParser.parse("Character", "name age"), testSchema, emptyMap())
            val request = BatchResolveFieldRequest.newBuilder()
                .setExecutorId(executorId)
                .setContextHandle("net-${System.nanoTime()}") // unregistered → schema-only (network) context
                .setCallbackEndpoint("cb-${System.nanoTime()}")
                .addSelectors(
                    FieldSelector.newBuilder()
                        .setSelectorKey("0")
                        .setArgumentsJson(ByteString.copyFrom(FieldValueSerializer.serializeArguments(emptyMap())))
                        .setSelectionsHandle("") // force the serialized reconstruction path
                        .setObjectValueJson(ByteString.copyFrom(EngineObjectDataSerializer.serialize(characterObjectValue(25))))
                        .setQueryValueJson(ByteString.copyFrom(EngineObjectDataSerializer.serialize(emptyQueryValue())))
                        .setSelections(
                            SerializedSelectionSet.newBuilder()
                                .setType(shipped.type)
                                .setDocument(shipped.document)
                                .setVariablesJson(ByteString.copyFrom(FieldValueSerializer.serializeArguments(shipped.variables)))
                                .build()
                        )
                        .build()
                )
                .build()

            val response = RemoteResolverServiceImpl().batchResolveField(request)

            assertEquals(1, response.resultsCount, "one result expected")
            assertNotNull(received.get(), "resolver should receive a reconstructed (non-null) selection set")
            assertEquals(
                shipped.printAsFieldSet(),
                received.get(),
                "the reconstructed selection set should match what was shipped"
            )
        }

    @Test
    fun `an empty blank-document selection set reconstructs to a non-null empty set`() =
        runBlocking {
            // Regression: a composite field whose sub-selection is entirely @skip'd ships a blank
            // document. It must reconstruct to a NON-null (empty) selection set on the remote, or a
            // composite-returning resolver hits the "null selection set on a composite type" error.
            FieldExecutorRegistry.clear()
            ContextRegistry.clear()
            SelectionsRegistry.clear()
            SchemaRegistry.register(testSchema)

            val received = AtomicReference<Any?>(null)
            val recording = object : FieldResolverExecutor by SimpleFieldResolverExecutor(resolverId = "Character.friend") {
                override suspend fun batchResolve(
                    selectors: List<FieldResolverExecutor.Selector>,
                    context: EngineExecutionContext
                ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
                    received.set(selectors.first().selections)
                    return selectors.associateWith { Result.success(true) }
                }
            }
            val executorId = FieldExecutorRegistry.register(recording)

            val request = BatchResolveFieldRequest.newBuilder()
                .setExecutorId(executorId)
                .setContextHandle("net-${System.nanoTime()}")
                .setCallbackEndpoint("cb-${System.nanoTime()}")
                .addSelectors(
                    FieldSelector.newBuilder()
                        .setSelectorKey("0")
                        .setArgumentsJson(ByteString.copyFrom(FieldValueSerializer.serializeArguments(emptyMap())))
                        .setSelectionsHandle("")
                        .setObjectValueJson(ByteString.copyFrom(EngineObjectDataSerializer.serialize(characterObjectValue(25))))
                        .setQueryValueJson(ByteString.copyFrom(EngineObjectDataSerializer.serialize(emptyQueryValue())))
                        // Blank document = a fully-skipped (empty) selection set.
                        .setSelections(
                            SerializedSelectionSet.newBuilder()
                                .setType("Character")
                                .setDocument("")
                                .setVariablesJson(ByteString.copyFrom(FieldValueSerializer.serializeArguments(emptyMap())))
                                .build()
                        )
                        .build()
                )
                .build()

            RemoteResolverServiceImpl().batchResolveField(request)
            assertTrue(
                received.get() is EmptyEngineSelectionSet,
                "a blank-document selection set must reconstruct to a non-null empty set, got ${received.get()}"
            )
        }

    @Test
    fun `a malformed selection set fails only its own selector, not the batch`() =
        runBlocking {
            FieldExecutorRegistry.clear()
            ContextRegistry.clear()
            SelectionsRegistry.clear()
            SchemaRegistry.register(testSchema)

            val recording = object : FieldResolverExecutor by SimpleFieldResolverExecutor(resolverId = "Character.friend") {
                override suspend fun batchResolve(
                    selectors: List<FieldResolverExecutor.Selector>,
                    context: EngineExecutionContext
                ): Map<FieldResolverExecutor.Selector, Result<Any?>> = selectors.associateWith { Result.success(true) }
            }
            val executorId = FieldExecutorRegistry.register(recording)

            suspend fun selector(
                key: String,
                document: String
            ) = FieldSelector.newBuilder()
                .setSelectorKey(key)
                .setArgumentsJson(ByteString.copyFrom(FieldValueSerializer.serializeArguments(emptyMap())))
                .setSelectionsHandle("")
                .setObjectValueJson(ByteString.copyFrom(EngineObjectDataSerializer.serialize(characterObjectValue(25))))
                .setQueryValueJson(ByteString.copyFrom(EngineObjectDataSerializer.serialize(emptyQueryValue())))
                .setSelections(
                    SerializedSelectionSet.newBuilder()
                        .setType("Character")
                        .setDocument(document)
                        .setVariablesJson(ByteString.copyFrom(FieldValueSerializer.serializeArguments(emptyMap())))
                        .build()
                )
                .build()

            val request = BatchResolveFieldRequest.newBuilder()
                .setExecutorId(executorId)
                .setContextHandle("net-${System.nanoTime()}")
                .setCallbackEndpoint("cb-${System.nanoTime()}")
                .addSelectors(selector("good", "fragment _ on Character { name }"))
                .addSelectors(selector("bad", "}} not a valid document {{"))
                .build()

            val byKey = RemoteResolverServiceImpl().batchResolveField(request).resultsList.associateBy { it.selectorKey }
            assertEquals(2, byKey.size, "both selectors should be represented in the response")
            assertTrue(byKey.getValue("good").hasValueJson(), "the valid selector should still resolve")
            assertTrue(byKey.getValue("bad").hasError(), "the malformed selector should be an isolated per-selector error")
        }

    @Test
    fun `every selector failing deserialization returns errors without invoking the resolver`() =
        runBlocking {
            // Regression: when *every* selector fails to deserialize, batchResolveField returns their
            // per-selector errors WITHOUT calling the resolver. Invoking an unbatched built-in resolver
            // with the empty surviving-selector list would trip its `require(selectors.size == 1)` and
            // misattribute the deserialization failures to the resolver.
            FieldExecutorRegistry.clear()
            ContextRegistry.clear()
            SelectionsRegistry.clear()
            SchemaRegistry.register(testSchema)

            val batchResolveInvoked = AtomicReference(false)
            val spy = object : FieldResolverExecutor by SimpleFieldResolverExecutor() {
                override suspend fun batchResolve(
                    selectors: List<FieldResolverExecutor.Selector>,
                    context: EngineExecutionContext
                ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
                    batchResolveInvoked.set(true)
                    return selectors.associateWith { Result.success(true) }
                }
            }
            val executorId = FieldExecutorRegistry.register(spy)

            // Both selectors carry malformed object-value bytes, so both fail
            // EngineObjectDataSerializer.deserialize before the resolver would ever run.
            suspend fun malformedSelector(key: String) =
                FieldSelector.newBuilder()
                    .setSelectorKey(key)
                    .setArgumentsJson(ByteString.copyFrom(FieldValueSerializer.serializeArguments(emptyMap())))
                    .setSelectionsHandle("")
                    .setObjectValueJson(ByteString.copyFromUtf8("}} not valid json {{"))
                    .setQueryValueJson(ByteString.copyFrom(EngineObjectDataSerializer.serialize(emptyQueryValue())))
                    .build()

            val request = BatchResolveFieldRequest.newBuilder()
                .setExecutorId(executorId)
                .setContextHandle("net-${System.nanoTime()}")
                .setCallbackEndpoint("cb-${System.nanoTime()}")
                .addSelectors(malformedSelector("0"))
                .addSelectors(malformedSelector("1"))
                .build()

            val response = RemoteResolverServiceImpl().batchResolveField(request)

            assertFalse(
                batchResolveInvoked.get(),
                "resolver must not be invoked when every selector fails deserialization"
            )
            val byKey = response.resultsList.associateBy { it.selectorKey }
            assertEquals(2, byKey.size, "both selectors should be represented in the response")
            assertTrue(byKey.getValue("0").hasError(), "selector 0 should be an isolated per-selector error")
            assertTrue(byKey.getValue("1").hasError(), "selector 1 should be an isolated per-selector error")
        }

    // An arbitrary type the wire format can't carry (not a scalar, list, map, EngineObjectData, or
    // NodeReference), used to exercise the per-selector serialization-failure path.
    private class Unserializable

    private companion object {
        // Sentinel age that makes the fixture emit a genuinely non-serializable value.
        private const val UNSERIALIZABLE_AGE = 7
    }
}
