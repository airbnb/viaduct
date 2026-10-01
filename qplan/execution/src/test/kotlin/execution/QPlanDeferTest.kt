@file:Suppress("ForbiddenImport")

package execution

import execution.testing.ExecutionTestFixture
import execution.testing.ExecutionTestFixtureResource
import graphql.incremental.DeferPayload
import graphql.incremental.DelayedIncrementalPartialResult
import graphql.incremental.IncrementalExecutionResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.EngineErrorData
import model.SelectionForest
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fragmentFrom
import model.merge
import model.outputValue
import model.registry.fieldResolverOf
import model.registry.selectiveFieldResolverOf
import model.requireObjectField
import model.requireType
import model.testing.TestWorld
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

class QPlanDeferTest : ExecutionTestFixtureResource {
    @Test
    fun `returns non-deferred data before a deferred qplan resolver completes`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val fixture = delayedFixture(providerStarted, gate)

            val initial =
                fixture
                    .runQueryAsync(DEFER_QUERY, incrementalSupport = true)
                    .get(5, TimeUnit.SECONDS)

            val incremental = assertIs<IncrementalExecutionResult>(initial)
            assertEquals(mapOf("fast" to 1), incremental.getData())
            assertTrue(incremental.errors.isEmpty())
            withTimeout(5_000) { providerStarted.await() }
            assertFalse(gate.isCompleted)

            val next = incremental.incrementalItemPublisher.nextIncrementalResult()
            assertFalse(next.isDone)
            gate.complete(Unit)

            val delayed = next.get(5, TimeUnit.SECONDS)
            val payload = assertIs<DeferPayload>(delayed.incremental.single())
            assertEquals(emptyList(), payload.path)
            assertEquals("slow-part", payload.label)
            assertEquals(mapOf("slow" to 2), payload.getData())
            assertFalse(delayed.hasNext())
        }

    @Test
    fun `deferred field waits for its delayed object fragment dependency`() =
        runBlocking {
            val abStarted = CompletableDeferred<Unit>()
            val abCompleted = CompletableDeferred<Unit>()
            val allowAbToComplete = CompletableDeferred<Unit>()
            val fixture =
                dependentDeferredFixture(
                    abStarted,
                    abCompleted,
                    allowAbToComplete,
                )

            val initial =
                assertIs<IncrementalExecutionResult>(
                    fixture
                        .runQueryAsync(DEPENDENT_DEFER_QUERY, incrementalSupport = true)
                        .get(5, TimeUnit.SECONDS),
                )
            assertEquals(mapOf("a" to mapOf("aa" to 42)), initial.getData())
            withTimeout(5_000) { abStarted.await() }
            assertFalse(abCompleted.isCompleted)

            val next = initial.incrementalItemPublisher.nextIncrementalResult()
            assertFalse(next.isDone)
            allowAbToComplete.complete(Unit)

            val delayed = next.get(5, TimeUnit.SECONDS)
            val payload = assertIs<DeferPayload>(delayed.incremental.single())
            assertTrue(abCompleted.isCompleted)
            assertEquals(listOf("a"), payload.path)
            assertEquals("waits", payload.label)
            assertEquals(mapOf("ac" to 7), payload.getData())
            assertFalse(delayed.hasNext())
        }

    @Test
    fun `defer if false completes through the ordinary response`() {
        val providerStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred(Unit)
        val fixture = delayedFixture(providerStarted, gate)

        val result =
            fixture
                .runQueryAsync(DEFER_IF_FALSE_QUERY, incrementalSupport = true)
                .get(5, TimeUnit.SECONDS)

        assertFalse(result is IncrementalExecutionResult)
        assertTrue(result.errors.isEmpty())
        assertEquals(mapOf("fast" to 1, "slow" to 2), result.getData())
    }

    @Test
    fun `publishes nested object shape before its deferred field completes`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val fixture = nestedDelayedFixture(providerStarted, gate)

            val initial =
                assertIs<IncrementalExecutionResult>(
                    fixture
                        .runQueryAsync(NESTED_DEFER_QUERY, incrementalSupport = true)
                        .get(5, TimeUnit.SECONDS),
                )
            assertEquals(mapOf("viewer" to mapOf("fast" to 1)), initial.getData())
            withTimeout(5_000) { providerStarted.await() }
            assertFalse(gate.isCompleted)

            val next = initial.incrementalItemPublisher.nextIncrementalResult()
            gate.complete(Unit)
            val payload =
                assertIs<DeferPayload>(
                    next.get(5, TimeUnit.SECONDS).incremental.single(),
                )
            assertEquals(listOf("viewer"), payload.path)
            assertEquals(mapOf("slow" to 2), payload.getData())
        }

    @Test
    fun `selective producer receives demand for initial and deferred fields`() {
        var suppliedDemand: SelectionForest? = null
        val observer = object : semantics.shared.ResolverObserver {
            override fun onResolverInvocation(observation: semantics.shared.ResolverInvocationObservation) {
                val field = observation.field
                val demand = observation.suppliedDemand
                if (field.name == "viewer") suppliedDemand = demand
            }
        }
        val world =
            TestWorld.fromSDL(
                schemaSDL = NESTED_SCHEMA,
                fieldResolvers = { schema ->
                    val viewerField = schema.loweredSchema.requireObjectField("Query", "viewer")
                    val viewerType =
                        schema.loweredSchema.requireType("Viewer") as
                            viaduct.graphql.schema.ViaductSchema.Object
                    mapOf(
                        viewerField to
                            selectiveFieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                            ) { _, _, _ ->
                                engineObjectDataOf(
                                    viewerType,
                                    mapOf("fast" to 1, "slow" to 2),
                                )
                            },
                    )
                },
            )
        val fixture = fixtureFromWorld(NESTED_SCHEMA, world)

        val initial =
            assertIs<IncrementalExecutionResult>(
                fixture
                    .runQueryAsync(NESTED_DEFER_QUERY, incrementalSupport = true, resolverObserver = observer)
                    .get(5, TimeUnit.SECONDS),
            )
        initial.incrementalItemPublisher.nextIncrementalResult().get(5, TimeUnit.SECONDS)

        val viewer = world.schema.requireType("Viewer") as viaduct.graphql.schema.ViaductSchema.Object
        assertEquals(
            setOf("fast", "slow"),
            requireNotNull(suppliedDemand)
                .merge(viewer)
                .byKey()
                .keys
                .mapTo(linkedSetOf()) { key -> key.field.name },
        )
    }

    @Test
    fun `places qplan field errors in the deferred payload`() {
        val providerStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred(Unit)
        val fixture = delayedFixture(providerStarted, gate, failSlow = true)

        val initial =
            fixture
                .runQueryAsync(DEFER_QUERY, incrementalSupport = true)
                .get(5, TimeUnit.SECONDS)

        val incremental = assertIs<IncrementalExecutionResult>(initial)
        assertTrue(incremental.errors.isEmpty())
        val payload =
            assertIs<DeferPayload>(
                incremental
                    .incrementalItemPublisher
                    .nextIncrementalResult()
                    .get(5, TimeUnit.SECONDS)
                    .incremental
                    .single(),
            )
        assertEquals(mapOf("slow" to null), payload.getData())
        assertTrue(payload.errors.single().message.contains("deferred boom"))
    }

    @Test
    fun `cancelling the incremental subscription cancels qplan resolver work`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val providerStopped = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val fixture = delayedFixture(providerStarted, gate, providerStopped = providerStopped)
            val initial =
                assertIs<IncrementalExecutionResult>(
                    fixture
                        .runQueryAsync(DEFER_QUERY, incrementalSupport = true)
                        .get(5, TimeUnit.SECONDS),
                )
            withTimeout(5_000) { providerStarted.await() }
            val subscription = CompletableFuture<Subscription>()
            initial.incrementalItemPublisher.subscribe(
                object : Subscriber<DelayedIncrementalPartialResult> {
                    override fun onSubscribe(value: Subscription) {
                        subscription.complete(value)
                        value.request(1)
                    }

                    override fun onNext(item: DelayedIncrementalPartialResult) = Unit

                    override fun onError(throwable: Throwable) = Unit

                    override fun onComplete() = Unit
                },
            )

            subscription.get(5, TimeUnit.SECONDS).cancel()

            withTimeout(5_000) { providerStopped.await() }
            assertFalse(gate.isCompleted)
        }

    @Test
    fun `fatal deferred resolver failure is reported with its original cause`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val allowProviderFailure = CompletableDeferred<Unit>()
            val fixture =
                delayedFixture(
                    providerStarted,
                    allowProviderFailure,
                    fatalProviderFailure = true,
                )
            val initial =
                assertIs<IncrementalExecutionResult>(
                    fixture
                        .runQueryAsync(DEFER_QUERY, incrementalSupport = true)
                        .get(5, TimeUnit.SECONDS),
                )
            assertEquals(mapOf("fast" to 1), initial.getData())
            withTimeout(5_000) { providerStarted.await() }
            val next = initial.incrementalItemPublisher.nextIncrementalResult()

            allowProviderFailure.complete(Unit)

            val delayed = next.get(5, TimeUnit.SECONDS)
            assertFalse(delayed.hasNext())
            val payload =
                assertIs<DeferPayload>(
                    delayed.incremental.single(),
                )
            assertTrue(
                payload.errors.single().message.contains(
                    "VariablesProvider returned invalid variables. Missing keys: ready",
                ),
            )
        }

    private fun delayedFixture(
        providerStarted: CompletableDeferred<Unit>,
        gate: CompletableDeferred<Unit>,
        providerStopped: CompletableDeferred<Unit>? = null,
        failSlow: Boolean = false,
        fatalProviderFailure: Boolean = false,
    ): ExecutionTestFixture {
        val world =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA,
                fieldResolvers = { schema ->
                    val fast = schema.loweredSchema.requireObjectField("Query", "fast")
                    val slow = schema.loweredSchema.requireObjectField("Query", "slow")
                    mapOf(
                        fast to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 1 },
                        slow to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Slow on Query { " +
                                        "dependency @include(if: ${'$'}ready) }",
                                    variableField = slow,
                                ),
                            ) { _, _ ->
                                if (failSlow) {
                                    EngineErrorData.of(IllegalStateException("deferred boom"))
                                } else {
                                    2
                                }
                            }.withVariablesProvider(setOf("ready")) {
                                providerStarted.complete(Unit)
                                try {
                                    gate.await()
                                    if (fatalProviderFailure) {
                                        emptyMap()
                                    } else {
                                        mapOf("ready" to false)
                                    }
                                } finally {
                                    providerStopped?.complete(Unit)
                                }
                            },
                    )
                },
            )
        return fixtureFromWorld(SCHEMA, world)
    }

    private fun nestedDelayedFixture(
        providerStarted: CompletableDeferred<Unit>,
        gate: CompletableDeferred<Unit>,
    ): ExecutionTestFixture {
        val world =
            TestWorld.fromSDL(
                schemaSDL = NESTED_SCHEMA,
                fieldResolvers = { schema ->
                    val viewer = schema.loweredSchema.requireObjectField("Query", "viewer")
                    val viewerType = schema.loweredSchema.requireType("Viewer") as viaduct.graphql.schema.ViaductSchema.Object
                    val fast = schema.loweredSchema.requireObjectField("Viewer", "fast")
                    val slow = schema.loweredSchema.requireObjectField("Viewer", "slow")
                    val dependency = schema.loweredSchema.requireObjectField("Viewer", "dependency")
                    mapOf(
                        viewer to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                engineObjectDataOf(viewerType)
                            },
                        fast to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Viewer")) { _, _ -> 1 },
                        slow to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Slow on Viewer { " +
                                        "dependency @include(if: ${'$'}ready) }",
                                    variableField = slow,
                                ),
                            ) { _, _ -> 2 }
                                .withVariablesProvider(setOf("ready")) {
                                    providerStarted.complete(Unit)
                                    gate.await()
                                    mapOf("ready" to false)
                                },
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Viewer")) { _, _ -> 3 },
                    )
                },
            )
        return fixtureFromWorld(NESTED_SCHEMA, world)
    }

    private fun dependentDeferredFixture(
        abStarted: CompletableDeferred<Unit>,
        abCompleted: CompletableDeferred<Unit>,
        allowAbToComplete: CompletableDeferred<Unit>,
    ): ExecutionTestFixture {
        val world =
            TestWorld.fromSDL(
                schemaSDL = DEPENDENT_SCHEMA,
                fieldResolvers = { schema ->
                    val a = schema.loweredSchema.requireObjectField("Query", "a")
                    val aType =
                        schema.loweredSchema.requireType("A") as
                            viaduct.graphql.schema.ViaductSchema.Object
                    val ab = schema.loweredSchema.requireObjectField("A", "ab")
                    val ac = schema.loweredSchema.requireObjectField("A", "ac")
                    mapOf(
                        a to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                engineObjectDataOf(aType, mapOf("aa" to 42))
                            },
                        ab to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment DelayedAb on A { " +
                                        "aa @include(if: ${'$'}ready) }",
                                    variableField = ab,
                                ),
                            ) { _, _ -> 7 }
                                .withVariablesProvider(setOf("ready")) {
                                    // Resolver functions are synchronous in qplan. Delay readiness
                                    // in its suspending provider so the single resolver worker is
                                    // free to publish the initial GraphQL result.
                                    abStarted.complete(Unit)
                                    delay(500)
                                    allowAbToComplete.await()
                                    abCompleted.complete(Unit)
                                    mapOf("ready" to false)
                                },
                        ac to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Ac on A { ab }"),
                            ) { input, _ ->
                                input.outputValue("ab")
                            },
                    )
                },
            )
        return fixtureFromWorld(DEPENDENT_SCHEMA, world)
    }

    private companion object {
        val SCHEMA =
            """
            type Query {
              fast: Int!
              slow: Int
              dependency: Int!
            }
            """.trimIndent()

        val DEFER_QUERY =
            """
            query {
              fast
              ... @defer(label: "slow-part") {
                slow
              }
            }
            """.trimIndent()

        val DEFER_IF_FALSE_QUERY =
            """
            query {
              fast
              ... @defer(if: false, label: "slow-part") {
                slow
              }
            }
            """.trimIndent()

        val NESTED_SCHEMA =
            """
            type Query {
              viewer: Viewer!
            }

            type Viewer {
              fast: Int!
              slow: Int
              dependency: Int!
            }
            """.trimIndent()

        val NESTED_DEFER_QUERY =
            """
            query {
              viewer {
                fast
                ... @defer(label: "nested-slow") {
                  slow
                }
              }
            }
            """.trimIndent()

        val DEPENDENT_SCHEMA =
            """
            type Query {
              a: A!
            }

            type A {
              aa: Int!
              ab: Int!
              ac: Int!
            }
            """.trimIndent()

        val DEPENDENT_DEFER_QUERY =
            """
            query {
              a {
                aa
                ... @defer(label: "waits") {
                  ac
                }
              }
            }
            """.trimIndent()
    }
}

private fun Publisher<DelayedIncrementalPartialResult>.nextIncrementalResult(): CompletableFuture<DelayedIncrementalPartialResult> =
    CompletableFuture<DelayedIncrementalPartialResult>().also { result ->
        subscribe(
            object : Subscriber<DelayedIncrementalPartialResult> {
                override fun onSubscribe(subscription: Subscription) {
                    subscription.request(1)
                }

                override fun onNext(item: DelayedIncrementalPartialResult) {
                    result.complete(item)
                }

                override fun onError(throwable: Throwable) {
                    result.completeExceptionally(throwable)
                }

                override fun onComplete() {
                    if (!result.isDone) {
                        result.completeExceptionally(
                            IllegalStateException("Incremental publisher completed without a result"),
                        )
                    }
                }
            },
        )
    }
