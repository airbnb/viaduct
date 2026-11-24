@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime2.execution

import graphql.GraphQL
import graphql.incremental.DeferPayload
import graphql.incremental.DelayedIncrementalPartialResult
import graphql.incremental.IncrementalExecutionResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import viaduct.dataloader.NextTickDispatcher
import viaduct.engine.EngineConfiguration
import viaduct.engine.EngineKind
import viaduct.engine.airbnb.EngineGraphQLJavaCompat
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.invocationContextFor
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.runStandardViaductFeatureTest
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.query
import viaduct.graphql.test.assertJson
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

class Engine2FieldBatchingTest {
    @Test
    fun `switching to IO before a nested query preserves its results`() {
        val resolvedKeys = mutableListOf<Int>()
        val module = EngineTestModule("extend type Query { outer: Int @resolver value(key: Int!): Int @resolver }") {
            field("Query" to "outer") {
                resolver {
                    fn { _, _, _, _, context ->
                        withContext(Dispatchers.IO) {
                            val selections = context.engineSelectionSetFactory.engineSelectionSet(
                                "Query",
                                "first: value(key: 1) second: value(key: 2)",
                                emptyMap(),
                            )
                            val result = context.query(selections)
                            (result.get("first") as Int) + (result.get("second") as Int)
                        }
                    }
                }
            }
            field("Query" to "value") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(resolverId = resolverId) { selectors, _ ->
                        resolvedKeys += selectors.map { it.arguments.getValue("key") as Int }
                        selectors.associateWith { Result.success((it.arguments.getValue("key") as Int) * 10) }
                    }
                }
            }
        }

        module.withBatchedEngine { service ->
            service.executeQuery("{ outer }").assertJson("""{data: {outer: 30}}""")
        }
        assertEquals(listOf(1, 2), resolvedKeys.sorted())
    }

    @Test
    fun `unhandled child failure stops other request work with batching off`() {
        val fail = CompletableDeferred<Unit>()
        val slowStarted = CompletableDeferred<Unit>()
        val slowStopped = CompletableDeferred<Unit>()
        val module = requestWithUnhandledChildFailure(fail, slowStarted, slowStopped)

        module.withEngine(batchingEnabled = false) { service ->
            supervisorScope {
                val response = async { service.executeQuery("{ broken slow }") }
                slowStarted.await()

                fail.complete(Unit)

                val failure = assertFailsWith<IllegalStateException> { response.await() }
                assertEquals("unhandled child failure", failure.message)
                slowStopped.await()
            }
        }
    }

    @Test
    fun `unhandled deferred child failure terminates the stream with batching off`() {
        val fail = CompletableDeferred<Unit>()
        val slowStarted = CompletableDeferred<Unit>()
        val slowStopped = CompletableDeferred<Unit>()
        val module = requestWithUnhandledChildFailure(fail, slowStarted, slowStopped)

        module.withEngine(batchingEnabled = false) { service ->
            val initial = service.executeIncrementally("{ fast ... @defer { broken slow } }")
            assertEquals(mapOf("fast" to 7), initial.getData())
            val subscription = initial.subscribeToNextPayload()
            slowStarted.await()

            fail.complete(Unit)

            slowStopped.await()
            val delayed = subscription.next.await()
            val payload = assertInstanceOf(DeferPayload::class.java, delayed.incremental.single())
            assertTrue(payload.errors.isNotEmpty(), "The deferred consumer must see the failure")
            assertFalse(delayed.hasNext())
            subscription.completed.await()
        }
    }

    private fun requestWithUnhandledChildFailure(
        fail: Deferred<Unit>,
        slowStarted: CompletableDeferred<Unit>,
        slowStopped: CompletableDeferred<Unit>,
    ) = EngineTestModule("extend type Query { fast: Int @resolver broken: Int @resolver slow: Int @resolver }") {
        fieldWithValue("Query" to "fast", 7)
        field("Query" to "broken") {
            resolver {
                fn { _, _, _, _, _ ->
                    supervisorScope {
                        slowStarted.await()
                        fail.await()
                        launch { error("unhandled child failure") }
                        awaitCancellation()
                    }
                }
            }
        }
        field("Query" to "slow") {
            resolver {
                fn { _, _, _, _, _ ->
                    slowStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        slowStopped.complete(Unit)
                    }
                }
            }
        }
    }

    @Test
    fun `enabled batching supports an IO resolver dispatcher`() {
        val module = EngineTestModule("extend type Query { value: String @resolver }") {
            field("Query" to "value") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(resolverId = resolverId) { selectors, _ ->
                        selectors.associateWith { Result.success("resolved") }
                    }
                }
            }
        }
        val interop = object : CoroutineInterop by DefaultCoroutineInterop {
            override fun <T> enterThreadLocalCoroutineContext(
                callerContext: CoroutineContext,
                block: suspend CoroutineScope.() -> T,
            ): CompletableFuture<T> =
                DefaultCoroutineInterop.enterThreadLocalCoroutineContext(callerContext) {
                    withContext(Dispatchers.IO, block)
                }
        }
        module.runStandardViaductFeatureTest(
            engine2Enabled = true,
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                coroutineInterop = interop,
                flagManager = object : FlagManager {
                    override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_BATCHING
                },
            ),
        ) {
            runQueryWithin("{ value }").assertJson("""{data: {value: "resolved"}}""")
        }
    }

    @ParameterizedTest(name = "ENGINE2_BATCHING={0}")
    @ValueSource(booleans = [false, true])
    fun `service batching flag preserves item demand and reports batch rounds`(batchingEnabled: Boolean) {
        val calls = mutableListOf<List<Pair<Int, Set<String>>>>()
        val module = EngineTestModule(
            """
            extend type Query { details(key: Int!): Details @resolver }
            type Details { url: String, caption: String }
            """.trimIndent(),
        ) {
            field("Query" to "details") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(resolverId = resolverId, isSelective = true) { selectors, _ ->
                        calls += selectors.map { selector ->
                            val key = selector.arguments.getValue("key") as Int
                            val requestedFields = requireNotNull(selector.selections).selections().map { it.fieldName }.toSet()
                            key to requestedFields
                        }
                        selectors.reversed().associateWith { selector ->
                            val key = selector.arguments.getValue("key") as Int
                            val selections = requireNotNull(selector.selections)
                            Result.success(
                                createEngineObjectData(
                                    "Details",
                                    buildMap {
                                        if (selections.containsField("Details", "url")) put("url", "url-$key")
                                        if (selections.containsField("Details", "caption")) put("caption", "caption-$key")
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
        val defaults = EngineConfiguration.featureTestDefault
        val flags = object : FlagManager by defaults.flagManager {
            override fun isEnabled(flag: FlagManager.Flag): Boolean = if (flag == FlagManager.Flags.ENGINE2_BATCHING) batchingEnabled else defaults.flagManager.isEnabled(flag)
        }

        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val serviceOwner = Job()
            val serviceDispatcher = NextTickDispatcher(dispatcher, metricsEnabled = true, parentJob = serviceOwner)
            val interop = object : CoroutineInterop by DefaultCoroutineInterop {
                override fun <T> enterThreadLocalCoroutineContext(
                    callerContext: CoroutineContext,
                    block: suspend CoroutineScope.() -> T,
                ): CompletableFuture<T> =
                    DefaultCoroutineInterop.enterThreadLocalCoroutineContext(dispatcher.executor, callerContext) {
                        withContext(serviceDispatcher, block)
                    }
            }
            try {
                module.runStandardViaductFeatureTest(
                    engine2Enabled = true,
                    engineConfig = defaults.copy(flagManager = flags, coroutineInterop = interop),
                ) {
                    runQueryWithin(
                        """
                        {
                          first: details(key: 1) { url caption }
                          second: details(key: 2) { url }
                        }
                        """.trimIndent(),
                    ).assertJson(
                        """
                        {data: {first: {url: "url-1", caption: "caption-1"}, second: {url: "url-2"}}}
                        """.trimIndent(),
                    )
                }
                assertTrue(serviceDispatcher.flushCount > 0)
                assertEquals(batchingEnabled, serviceDispatcher.batchRoundCount > 0, "Service metrics must include field batches")
            } finally {
                serviceOwner.cancel()
            }
        }

        assertEquals(
            listOf(1 to setOf("url", "caption"), 2 to setOf("url")),
            calls.flatten().sortedBy { it.first },
        )
        if (batchingEnabled) {
            assertTrue(calls.any { it.size > 1 }, "Enabled batching must call the executor with multiple items: $calls")
        } else {
            assertTrue(calls.all { it.size == 1 }, "Disabled batching must call the executor with single items: $calls")
        }
    }

    @Test
    fun `an item failure in a shared call preserves other items and unrelated fields`() {
        val calls = mutableListOf<List<Int>>()
        val module = EngineTestModule(
            "extend type Query { value(key: Int!): Int @resolver healthy: String @resolver }",
        ) {
            field("Query" to "value") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(resolverId = resolverId) { selectors, _ ->
                        calls += selectors.map { it.arguments.getValue("key") as Int }
                        selectors.reversed().associateWith { selector ->
                            val key = selector.arguments.getValue("key") as Int
                            if (key == 2) Result.failure(IllegalStateException("item 2 failed")) else Result.success(key * 10)
                        }
                    }
                }
            }
            fieldWithValue("Query" to "healthy", "healthy")
        }

        module.withBatchedEngine { service ->
            val result = service.executeQuery("{ first: value(key: 1) middle: value(key: 2) last: value(key: 3) healthy }")

            assertEquals(mapOf("first" to 10, "middle" to null, "last" to 30, "healthy" to "healthy"), result.getData())
            assertEquals(listOf(listOf("middle")), result.errors.map { it.path })
            assertTrue(result.errors.single().message.contains("item 2 failed"))
        }
        assertTrue(calls.any { 2 in it && it.size > 1 }, "The failing item must belong to a shared executor call: $calls")
    }

    @ParameterizedTest(name = "foreign cancellation={0}")
    @ValueSource(booleans = [false, true])
    fun `a thrown batch failure reaches each member while an unrelated field completes`(foreignCancellation: Boolean) {
        val failure = if (foreignCancellation) CancellationException("foreign batch cancellation") else IllegalStateException("batch failed")
        val calls = mutableListOf<List<Int>>()
        val module = EngineTestModule(
            "extend type Query { broken(key: Int!): Int @resolver healthy: String @resolver }",
        ) {
            field("Query" to "broken") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(resolverId = resolverId) { selectors, _ ->
                        calls += selectors.map { it.arguments.getValue("key") as Int }
                        throw failure
                    }
                }
            }
            fieldWithValue("Query" to "healthy", "healthy")
        }

        module.withBatchedEngine { service ->
            val result = service.executeQuery("{ first: broken(key: 1) second: broken(key: 2) healthy }")

            assertEquals(mapOf("first" to null, "second" to null, "healthy" to "healthy"), result.getData())
            assertEquals(setOf(listOf("first"), listOf("second")), result.errors.map { it.path }.toSet())
            assertEquals(2, result.errors.size)
            assertTrue(result.errors.all { it.message.contains(requireNotNull(failure.message)) })
        }
        assertTrue(calls.any { it.size > 1 }, "Failure fan-out must originate in a shared executor call: $calls")
    }

    @Test
    fun `batched items execute nested queries with their own owner in later dispatch rounds`() {
        val calls = mutableListOf<List<Int>>()
        val itemContexts = mutableMapOf<Int, EngineExecutionContext>()
        val module = EngineTestModule("extend type Query { value(key: Int!): Int @resolver }") {
            field("Query" to "value") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(resolverId = resolverId) { selectors, context ->
                        if (selectors.size > 1) assertNull(context.executionHandle, "A shared batch must not borrow one item's execution handle")
                        calls += selectors.map { it.arguments.getValue("key") as Int }
                        selectors.associateWith { selector ->
                            val key = selector.arguments.getValue("key") as Int
                            val itemContext = context.invocationContextFor(selector)
                            itemContexts[key] = itemContext
                            val value = if (key < 10) {
                                val selections = itemContext.engineSelectionSetFactory.engineSelectionSet("Query", "value(key: ${key + 10})", emptyMap())
                                (itemContext.query(selections).get("value") as Int) + key
                            } else {
                                key * 10
                            }
                            Result.success(value)
                        }
                    }
                }
            }
        }

        module.withBatchedEngine { service ->
            val result = service.executeQuery("{ first: value(key: 1) second: value(key: 2) }")

            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(mapOf("first" to 111, "second" to 122), result.getData())
        }
        assertTrue(calls.any { 1 in it && 2 in it }, "Both outer items must enter a shared call: $calls")
        assertEquals(listOf(1, 2, 11, 12), calls.flatten().sorted())
        assertNotSame(
            requireNotNull(itemContexts.getValue(1).executionHandle),
            requireNotNull(itemContexts.getValue(2).executionHandle),
        )
    }

    @Test
    fun `concurrent top-level executions never share tenant calls`() {
        val calls = mutableListOf<List<Pair<String, Int>>>()
        val enteredRequests = mutableSetOf<String>()
        val bothRequestsEntered = CompletableDeferred<Unit>()
        val module = EngineTestModule("extend type Query { value(key: Int!): String @resolver }") {
            field("Query" to "value") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(resolverId = resolverId) { selectors, context ->
                        val inputs = selectors.map { selector ->
                            (context.invocationContextFor(selector).requestContext as String) to (selector.arguments.getValue("key") as Int)
                        }
                        calls += inputs
                        enteredRequests += inputs.map { it.first }
                        if (enteredRequests.size == 2) bothRequestsEntered.complete(Unit)
                        bothRequestsEntered.await()
                        selectors.associateWith { selector ->
                            Result.success("${context.invocationContextFor(selector).requestContext}-${selector.arguments.getValue("key")}")
                        }
                    }
                }
            }
        }

        module.withBatchedEngine { service ->
            runBlocking {
                withTimeout(5_000) {
                    val first = async {
                        service.execute(ExecutionInput.create("{ one: value(key: 1) two: value(key: 2) }", requestContext = "A"), SchemaId.Base)
                    }
                    val second = async {
                        service.execute(ExecutionInput.create("{ one: value(key: 1) two: value(key: 2) }", requestContext = "B"), SchemaId.Base)
                    }
                    val firstResult = first.await()
                    val secondResult = second.await()
                    assertTrue(firstResult.errors.isEmpty(), firstResult.errors.toString())
                    assertTrue(secondResult.errors.isEmpty(), secondResult.errors.toString())
                    assertEquals(mapOf("one" to "A-1", "two" to "A-2"), firstResult.getData())
                    assertEquals(mapOf("one" to "B-1", "two" to "B-2"), secondResult.getData())
                }
            }
        }
        assertTrue(calls.all { call -> call.map { it.first }.toSet().size == 1 }, "Tenant calls must contain one request: $calls")
        assertTrue(calls.any { call -> call.size > 1 && call.all { it.first == "A" } }, "Request A must form a batch: $calls")
        assertTrue(calls.any { call -> call.size > 1 && call.all { it.first == "B" } }, "Request B must form a batch: $calls")
    }

    @Test
    fun `next mutation waits for the first two-item payload`() {
        var storedVersion = 0
        val writes = mutableListOf<Int>()
        val reading = CompletableDeferred<List<Int>>()
        val finishReading = CompletableDeferred<Unit>()
        val module = versionedItemMutations(
            keys = listOf(1, 2),
            writeVersion = { version ->
                storedVersion = version
                writes += version
            },
            readBatchVersion = { keys ->
                if (storedVersion == 1) {
                    reading.complete(keys)
                    finishReading.await()
                }
                storedVersion
            },
        )

        module.withBatchedEngine { service ->
            val result = async {
                service.executeQuery(
                    """mutation {
                        first: update(version: 1) { version }
                        second: update(version: 2) { version }
                    }""",
                )
            }
            assertEquals(setOf(1, 2), reading.await().toSet())
            assertEquals(listOf(1), writes)
            assertFalse(result.isCompleted)

            finishReading.complete(Unit)

            result.await().assertJson(
                """{data: {
                    first: [{version: 1}, {version: 1}],
                    second: [{version: 2}, {version: 2}]
                }}""",
            )
            assertEquals(listOf(1, 2), writes)
        }
    }

    private fun versionedItemMutations(
        keys: List<Int>,
        writeVersion: (Int) -> Unit,
        readBatchVersion: suspend (List<Int>) -> Int,
    ) = EngineTestModule(
        """
        extend type Mutation { update(version: Int!): [Item!]! @resolver }
        type Item { key: Int!, version: Int @resolver }
        """.trimIndent(),
    ) {
        fieldWithValue("Query" to "_", null)
        field("Mutation" to "update") {
            resolver {
                fn { arguments, _, _, _, _ ->
                    writeVersion(arguments.getValue("version") as Int)
                    keys.map { key -> createEngineObjectData("Item", mapOf("key" to key)) }
                }
            }
        }
        field("Item" to "version") {
            resolver {
                objectSelections("key")
                fn { selectors, _ ->
                    val version = readBatchVersion(selectors.map { it.syncObjectValueGetter().fetchAs<Int>("key") })
                    selectors.associateWith { Result.success(version) }
                }
            }
        }
    }

    @Test
    fun `deferred field batch finishes after the initial response`() {
        val batchStarted = CompletableDeferred<List<Int>>()
        val finishBatch = CompletableDeferred<Unit>()
        val batchStopped = CompletableDeferred<Unit>()
        val module = queryWithItems(keys = listOf(1, 2), fast = 7) { keys ->
            batchStarted.complete(keys)
            try {
                finishBatch.await()
                keys.map { it * 10 }
            } finally {
                batchStopped.complete(Unit)
            }
        }

        module.withBatchedEngine { service ->
            val initial = service.executeIncrementally("""{ fast ... @defer(label: "items") { items { value } } }""")
            assertTrue(initial.errors.isEmpty())
            assertEquals(mapOf("fast" to 7), initial.getData())
            assertEquals(setOf(1, 2), batchStarted.await().toSet())
            assertFalse(batchStopped.isCompleted)

            val subscription = initial.subscribeToNextPayload()
            assertFalse(subscription.next.isCompleted)

            finishBatch.complete(Unit)

            val delayed = subscription.next.await()
            val payload = assertInstanceOf(DeferPayload::class.java, delayed.incremental.single())
            assertTrue(payload.errors.isEmpty())
            assertEquals("items", payload.label)
            assertEquals(emptyList<Any>(), payload.path)
            assertEquals(mapOf("items" to listOf(mapOf("value" to 10), mapOf("value" to 20))), payload.getData())
            assertFalse(delayed.hasNext())
            batchStopped.await()
        }
    }

    @Test
    fun `cancelling the subscription stops its pending field batch`() {
        val batchStarted = CompletableDeferred<List<Int>>()
        val batchStopped = CompletableDeferred<Unit>()
        val module = queryWithItems(keys = listOf(1, 2), fast = 7) { keys ->
            batchStarted.complete(keys)
            try {
                awaitCancellation()
            } finally {
                batchStopped.complete(Unit)
            }
        }

        module.withBatchedEngine { service ->
            val initial = service.executeIncrementally("""{ fast ... @defer(label: "items") { items { value } } }""")
            assertTrue(initial.errors.isEmpty())
            assertEquals(mapOf("fast" to 7), initial.getData())
            assertEquals(setOf(1, 2), batchStarted.await().toSet())
            assertFalse(batchStopped.isCompleted)

            val subscription = initial.subscribeToNextPayload()
            assertFalse(subscription.next.isCompleted)

            subscription.cancel()

            batchStopped.await()
            assertFalse(subscription.next.isCompleted)
        }
    }

    private fun queryWithItems(
        keys: List<Int>,
        fast: Int,
        resolveValues: suspend (List<Int>) -> List<Int>,
    ) = EngineTestModule(
        """
        extend type Query { fast: Int @resolver items: [Item!]! @resolver }
        type Item { key: Int!, value: Int @resolver }
        """.trimIndent(),
    ) {
        fieldWithValue("Query" to "fast", fast)
        field("Query" to "items") {
            resolver {
                fn { _, _, _, _, _ -> keys.map { key -> createEngineObjectData("Item", mapOf("key" to key)) } }
            }
        }
        field("Item" to "value") {
            resolver {
                objectSelections("key")
                fn { selectors, _ ->
                    val values = resolveValues(selectors.map { it.syncObjectValueGetter().fetchAs<Int>("key") })
                    selectors.zip(values).associate { (selector, value) -> selector to Result.success(value) }
                }
            }
        }
    }

    private suspend fun StandardViaduct.executeIncrementally(query: String): IncrementalExecutionResult {
        val graphQL = (engineRegistry.getEngine(SchemaId.Base, EngineKind.ENGINE2) as EngineGraphQLJavaCompat).getGraphQL()
        val input = graphql.ExecutionInput.newExecutionInput().query(query)
        GraphQL.unusualConfiguration(input).incrementalSupport()
            .enableIncrementalSupport(true).enableEarlyIncrementalFieldExecution(true)
        return assertInstanceOf(
            IncrementalExecutionResult::class.java,
            DefaultCoroutineInterop.enterThreadLocalCoroutineContext(currentCoroutineContext()) {
                graphQL.executeAsync(input.build()).await()
            }.await(),
        )
    }

    private class PayloadSubscription(
        val next: Deferred<DelayedIncrementalPartialResult>,
        val completed: Deferred<Unit>,
        private val subscription: Subscription,
    ) {
        fun cancel() = subscription.cancel()
    }

    private suspend fun IncrementalExecutionResult.subscribeToNextPayload(): PayloadSubscription {
        val subscribed = CompletableDeferred<Subscription>()
        val next = CompletableDeferred<DelayedIncrementalPartialResult>()
        val completed = CompletableDeferred<Unit>()
        incrementalItemPublisher.subscribe(object : Subscriber<DelayedIncrementalPartialResult> {
            override fun onSubscribe(subscription: Subscription) {
                subscribed.complete(subscription)
                subscription.request(Long.MAX_VALUE)
            }

            override fun onNext(item: DelayedIncrementalPartialResult) {
                next.complete(item)
            }

            override fun onError(cause: Throwable) {
                next.completeExceptionally(cause)
                completed.completeExceptionally(cause)
            }

            override fun onComplete() {
                completed.complete(Unit)
            }
        })
        return PayloadSubscription(next, completed, subscribed.await())
    }
}

internal fun EngineTestModule.withBatchedEngine(block: suspend CoroutineScope.(StandardViaduct) -> Unit) = withEngine(batchingEnabled = true, block)

private fun EngineTestModule.withEngine(
    batchingEnabled: Boolean,
    block: suspend CoroutineScope.(StandardViaduct) -> Unit,
) {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
        val interop = object : CoroutineInterop by DefaultCoroutineInterop {
            override fun <T> enterThreadLocalCoroutineContext(
                callerContext: CoroutineContext,
                block: suspend CoroutineScope.() -> T,
            ): CompletableFuture<T> = DefaultCoroutineInterop.enterThreadLocalCoroutineContext(dispatcher.executor, callerContext, block)
        }
        val service = StandardViaduct.Builder()
            .withSchemaConfiguration(SchemaConfiguration.fromSchema(fullSchema))
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(toModuleConfigSource()))
            .withCoroutineInterop(interop)
            .withFlagManager(object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED || (batchingEnabled && flag == FlagManager.Flags.ENGINE2_BATCHING)
            })
            .build()
        runBlocking {
            withTimeout(5_000) { block(service) }
        }
    }
}

internal suspend fun StandardViaduct.executeQuery(query: String): ExecutionResult.Standard = execute(ExecutionInput.create(query), SchemaId.Base)
