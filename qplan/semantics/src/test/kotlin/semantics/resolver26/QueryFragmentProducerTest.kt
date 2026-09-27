package semantics.resolver26

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Arguments
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.VariableBinding
import model.VariableInstanceId
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.operationSelectionsFrom
import model.requireObjectField
import model.schemaType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromQueryField
import semantics.contract.selectionValues
import semantics.shared.SharedOperationContext
import semantics.shared.ResolverObserver
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import semantics.shared.OEROccurrence

class QueryFragmentProducerTest : Resolver26DispatcherResource {
    @Test
    fun `Query producer failure publishes a field error and terminates its bindings`() =
        runBlocking {
            val failure = IllegalStateException("Query producer failed")
            val consumerInvoked = AtomicBoolean()
            var consumerOccurrence: ResolverOccurrenceId? = null
            val observer =
                object : ResolverObserver {
                    override fun onQueryFragmentPrepared(
                        resolverOccurrenceId: ResolverOccurrenceId,
                        result: ObjectEngineResult,
                    ): Nothing {
                        consumerOccurrence = resolverOccurrenceId
                        throw failure
                    }
                }
            val requestJob = Job()
            val requestScope = CoroutineScope(resolverDispatcher + requestJob)

            try {
                val resolution =
                    startQueryFragmentResolution(requestScope, observer, useReference = true) {
                        consumerInvoked.set(true)
                    }
                val fieldValue =
                    assertIs<ErrorEngineResult>(
                        withTimeout(5_000) {
                            resolution.root
                                .getCell(resolution.key)
                                .getValue()
                                .await()
                        },
                    )
                assertSame(
                    VariableBinding.Error,
                    resolution.operation.variableBindings.fetchBinding(
                        resolution.variableId(requireNotNull(consumerOccurrence)),
                    ),
                )

                assertSame(failure, fieldValue.errorData.cause)
                assertFalse(consumerInvoked.get())
                assertTrue(requestJob.isActive)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `cancellation before or during field entry cancels provider bindings`() =
        runBlocking {
            for (cancelBeforeFieldEntry in listOf(true, false)) {
                val dispatcher = QueuedDispatcher()
                val requestJob = Job()
                val requestScope = CoroutineScope(dispatcher + requestJob)
                try {
                    val resolution =
                        startQueryFragmentResolution(requestScope, ResolverObserver.NOP) {}

                    // Optionally enter the field task so it launches its provider read.
                    if (!cancelBeforeFieldEntry) dispatcher.runNext()
                    requestJob.cancel(CancellationException("cancelled before coroutine entry"))
                    dispatcher.runNext()
                    dispatcher.runUntilIdle()
                    requestJob.join()

                    kotlin.test.assertFailsWith<CancellationException> {
                        resolution.operation.variableBindings.getBinding(resolution.variableId())
                    }
                } finally {
                    requestJob.cancel()
                    dispatcher.runUntilIdle()
                    requestJob.join()
                }
            }
        }

    @Test
    fun `field task cancellation terminates bindings created by a reference hop`() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val requestJob = Job()
        val requestScope = CoroutineScope(dispatcher + requestJob)
        val queryOccurrences = mutableListOf<ResolverOccurrenceId>()
        val observer = object : ResolverObserver {
            override fun onQueryFragmentPrepared(resolverOccurrenceId: ResolverOccurrenceId, result: ObjectEngineResult) {
                queryOccurrences += resolverOccurrenceId
                requestJob.cancel(CancellationException("cancelled during reference Query production"))
            }
        }
        try {
            val resolution = startQueryFragmentResolution(
                requestScope, observer, useReference = true, suspendVariablesProvider = true,
            ) {
                error("Cancelled reference resolver must not be invoked")
            }
            dispatcher.runUntilIdle()
            requestJob.join()

            for (name in listOf("provided", "local")) {
                val variableId = resolution.variableId(queryOccurrences.single(), name)
                kotlin.test.assertFailsWith<CancellationException> {
                    resolution.operation.variableBindings.getBinding(variableId)
                }
            }
        } finally {
            requestJob.cancel()
            dispatcher.runUntilIdle()
            requestJob.join()
        }
    }

    private fun startQueryFragmentResolution(
        requestScope: CoroutineScope,
        observer: ResolverObserver,
        useReference: Boolean = false,
        suspendVariablesProvider: Boolean = false,
        onConsumerInvocation: () -> Unit,
    ): StartedQueryFragmentResolution {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      reference: Int!
                      consumer: Int!
                      dependency: Int!
                      consume(value: Int!, extra: Int! = 0): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val dependency = schema.requireObjectField("Query", "dependency")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        schema.requireObjectField("Query", "reference") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                RootFieldReferenceData.of(listOf(consumer), emptyMap())
                            },
                        dependency to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        schema.requireObjectField("Query", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ConsumerQuery on Query { dependency consume(value: ${'$'}provided, extra: " +
                                            (if (suspendVariablesProvider) "${'$'}local" else "0") + ") }",
                                        variableField = consumer,
                                    ),
                            ) { _, queryValue, _ ->
                                onConsumerInvocation()
                                queryValue.selectionValues().getValue("consume")
                            }.let { resolver ->
                                if (suspendVariablesProvider) {
                                    resolver.withVariablesProvider(setOf("local")) { awaitCancellation() }
                                } else resolver
                            },
                    )
                },
                variableProviders = { schema ->
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "provided") to schema.fromQueryField(
                            queryFragmentSource = "fragment ConsumerQuery on Query { dependency }",
                            responsePath = listOf("dependency"),
                            variableField = consumer,
                        ),
                    )
                },
            )
        val selections = world.assumptions.operationSelectionsFrom(
            if (useReference) "query { reference }" else "query { consumer }",
        )
        val baseOperation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
        val operation =
            OperationContext.create(
                base = baseOperation,
                requestScope = requestScope,
            )
        val source = operation.world.resolverRegistry.createRootQueryInput()
        val root =
            ObjectEngineResult.of(
                type = source.schemaType,
                mutable = true,
            )
        val key = selections.merge(root.type).byKey().keys.single()
        val orchestration =
            OrchestrationTask.create(
                operation = operation,
                occurrence =
                    OEROccurrence(
                        root = root,
                        path = emptyList(),
                        target = root,
                    ),
                source = source,
                constructionDemand = selections,
            )
        operation.dispatcher.dispatchOrchestration(orchestration)
        return StartedQueryFragmentResolution(operation, root, key)
    }

    private data class StartedQueryFragmentResolution(
        val operation: OperationContext,
        val root: ObjectEngineResult,
        val key: ObjectEngineResult.ObjectKey,
    ) {
        fun variableId(
            occurrence: ResolverOccurrenceId = ResolverOccurrenceId.at(root, listOf(key)),
            name: String = "provided",
        ): VariableInstanceId = VariableInstanceId.of(
            occurrence, operation.world.schema.requireObjectField("Query", "consumer"), name,
        )
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        fun runNext() {
            check(tasks.isNotEmpty()) { "No queued coroutine to run" }
            tasks.removeFirst().run()
        }

        fun runUntilIdle() {
            while (tasks.isNotEmpty()) runNext()
        }
    }
}
