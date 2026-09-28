@file:Suppress("ForbiddenImport")

package semantics.resolver26

import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
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
import model.operationSelectionsFrom
import model.requireObjectField
import model.registry.ResolverTarget
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromQueryField
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import semantics.shared.SharedOperationContext
import semantics.shared.ResolverObserver
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Query tasks must finish after their consumer exits, without cancelling the request. */
class QueryFragmentBindingTerminationTest {
    @TestFactory
    fun `consumer exits do not strand Query binding readers`(): List<DynamicTest> =
        Exit.entries.flatMap { exit ->
            val references = if (exit == Exit.ARGUMENT_ERROR || exit == Exit.EXCLUDED) listOf(false) else listOf(false, true)
            references.map { reference ->
                DynamicTest.dynamicTest("${exit.description} through ${if (reference) "reference" else "ordinary field"}") {
                    runBlocking { withTimeout(5_000) { verifyTermination(exit, reference) } }
                }
            }
        }

    private suspend fun verifyTermination(exit: Exit, reference: Boolean) {
        val dispatcher = QueuedDispatcher()
        val requestJob = Job()
        val fixture = Fixture(exit, reference)
        try {
            val root = fixture.operation.startResolve(fixture.selections, CoroutineScope(dispatcher + requestJob))
            dispatcher.runUntilIdle()

            val query = requireNotNull(fixture.queryResult)
            val consumer = root.cell(if (reference) "reference" else "consumer")
            assertEquals(42, root.cell("healthy").getValue().get())
            assertFalse(fixture.consumerInvoked)
            if (exit != Exit.EXCLUDED) {
                // With the queue drained, these installed tasks are suspended on the
                // consumer's FromProvider / FromArgument / FromQueryField bindings.
                assertTrue(query.cell("source").fetchActivated())
                assertFalse(query.cell("source").getValue().isCompleted)
                assertTrue(query.cell("dependency").fetchActivated())
                assertFalse(query.cell("dependency").getValue().isCompleted)
                assertTrue(fixture.independentStarted)
            }

            if (exit == Exit.CANCEL) {
                requireNotNull(fixture.consumerJob).cancel(fixture.cancellation)
            } else {
                fixture.consumerGate.complete(Unit)
            }
            dispatcher.runUntilIdle()

            if (exit == Exit.CANCEL) {
                assertSame(fixture.cancellation, assertFailsWith<CancellationException> { consumer.getValue().get() })
            } else if (exit == Exit.EXCLUDED) {
                assertFalse(consumer.fetchActivated())
            } else {
                val error = assertIs<ErrorEngineResult>(consumer.getValue().get())
                if (exit == Exit.UNEXPECTED || exit == Exit.VARIABLES_PROVIDER_THROWS) {
                    assertSame(fixture.failure, error.errorData.cause)
                }
            }
            assertFalse(fixture.consumerInvoked)
            assertTrue(requestJob.isActive, "Only the consumer should terminate")

            // Independent Query work is allowed to continue after the consumer exits.
            // Its provider is finite and released explicitly, never cancelled to make the test pass.
            fixture.independentGate.complete(Unit)
            dispatcher.runUntilIdle()
            val pendingBindings = listOf("provided", "queryValue").filterNot {
                fixture.operation.variableBindings.isBound(fixture.variableId(it))
            }
            assertFalse(
                requestJob.children.any(),
                "Query work is still waiting after ${exit.description}; pending bindings: $pendingBindings",
            )

            if (exit == Exit.EXCLUDED) {
                assertFalse(fixture.consumerProviderInvoked)
                assertFalse(fixture.independentStarted)
                for (key in query.keys) assertFalse(query.getCell(key).fetchActivated())
                // Unused bindings may remain pending; no Query task may wait for them.
            } else {
                assertEquals(7, query.cell("independent").getValue().get())
                assertIs<ErrorEngineResult>(query.cell("source").getValue().get())
                assertIs<ErrorEngineResult>(query.cell("dependency").getValue().get())
                for (name in listOf("provided", "queryValue")) {
                    val variableId = fixture.variableId(name)
                    if (exit == Exit.CANCEL) {
                        assertFailsWith<CancellationException> { fixture.operation.variableBindings.getBinding(variableId) }
                    } else {
                        assertSame(VariableBinding.Error, fixture.operation.variableBindings.getBinding(variableId))
                    }
                }
                if (exit == Exit.ARGUMENT_ERROR) assertFalse(fixture.consumerProviderInvoked)
            }
        } finally {
            // Cleanup happens after the assertions, so request cancellation cannot hide stuck readers.
            requestJob.cancel()
            dispatcher.runUntilIdle()
            requestJob.join()
        }
    }

    private enum class Exit(val description: String) {
        CANCEL("consumer cancelled"),
        UNEXPECTED("unexpected exception reaches run"),
        VARIABLES_PROVIDER_THROWS("variables provider throws"),
        MISSING_VARIABLE("variable missing from variables provider map"),
        ARGUMENT_ERROR("consumer argument contains an error"),
        EXCLUDED("consumer negatively activated"),
    }

    private class Fixture(val exit: Exit, reference: Boolean) {
        val consumerGate = CompletableDeferred<Unit>()
        val independentGate = CompletableDeferred<Unit>()
        val failure = IllegalStateException("Unexpected consumer failure")
        val cancellation = CancellationException("Consumer cancelled")
        var consumerJob: Job? = null
        var consumerProviderInvoked = false
        var consumerInvoked = false
        var independentStarted = false
        var queryResult: ObjectEngineResult? = null
        var consumerOccurrence: ResolverOccurrenceId? = null

        val world = TestWorld.fromSDL(
            schemaSDL =
                """
                type Query {
                  driver(enabled: Boolean!): Int!
                  reference: Int!
                  consumer(seed: Int!): Int!
                  source(value: Int!): Int!
                  dependency(value: Int!, seed: Int!): Int!
                  independent: Int!
                  sink(value: Int!): Int!
                  healthy: Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val driver = schema.requireObjectField("Query", "driver")
                val consumer = schema.requireObjectField("Query", "consumer")
                val independent = schema.requireObjectField("Query", "independent")
                mapOf(
                    driver to fieldResolverOf(
                        schema.fragmentFrom(
                            "fragment Driver on Query { consumer(seed: ${'$'}seed) @include(if: ${'$'}enabled) }",
                            variableField = driver,
                        ),
                    ) { _, _ -> 1 }.withVariablesProvider(setOf("seed")) {
                        consumerGate.await()
                        if (exit == Exit.ARGUMENT_ERROR) throw failure
                        mapOf("seed" to 5)
                    },
                    schema.requireObjectField("Query", "reference") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                            RootFieldReferenceData.of(listOf(consumer), mapOf("seed" to 5))
                        },
                    consumer to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schema.fragmentFrom(
                            "fragment ConsumerQuery on Query { source(value: ${'$'}provided) " +
                                "dependency(value: ${'$'}queryValue, seed: ${'$'}seed) independent }",
                            variableField = consumer,
                        ),
                    ) { _, _, _ ->
                        consumerInvoked = true
                        error("Consumer resolver must not run")
                    }.withVariablesProvider(setOf("provided")) {
                        consumerProviderInvoked = true
                        consumerJob = currentCoroutineContext().job
                        consumerGate.await()
                        when (exit) {
                            Exit.VARIABLES_PROVIDER_THROWS -> throw failure
                            Exit.MISSING_VARIABLE -> emptyMap()
                            Exit.UNEXPECTED -> object : Map<String, Any?> by mapOf("provided" to 7) {
                                // Fail after the provider returns, outside its local catch, before
                                // completeBinding. This exception must reach FieldResolverTask.run.
                                override fun get(key: String): Any? = throw failure
                            }
                            else -> mapOf("provided" to 7)
                        }
                    },
                    independent to fieldResolverOf(
                        schema.fragmentFrom("fragment Independent on Query { sink(value: ${'$'}local) }", variableField = independent),
                    ) { _, _ -> 7 }.withVariablesProvider(setOf("local")) {
                        independentStarted = true
                        independentGate.await()
                        mapOf("local" to 7)
                    },
                    schema.requireObjectField("Query", "source") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    schema.requireObjectField("Query", "dependency") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> error("Failed binding must skip dependency") },
                    schema.requireObjectField("Query", "sink") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    schema.requireObjectField("Query", "healthy") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 42 },
                )
            },
            variableProviders = { schema ->
                val driver = schema.requireObjectField("Query", "driver")
                val consumer = schema.requireObjectField("Query", "consumer")
                mapOf(
                    Arguments.Variable.of(driver, "enabled") to schema.fromArgument(driver, "enabled"),
                    Arguments.Variable.of(consumer, "seed") to schema.fromArgument(consumer, "seed"),
                    Arguments.Variable.of(consumer, "queryValue") to schema.fromQueryField(
                        queryFragmentSource = "fragment Source on Query { source(value: ${'$'}provided) }",
                        responsePath = listOf("source"),
                        variableField = consumer,
                    ),
                )
            },
        ).assumptions

        val operation = SharedOperationContext.create(world, resolverObserver = object : ResolverObserver {
            override fun onQueryFragmentPrepared(resolverOccurrenceId: ResolverOccurrenceId, result: ObjectEngineResult) {
                check(queryResult == null) { "Only the consumer has a Query fragment" }
                queryResult = result
                consumerOccurrence = resolverOccurrenceId
            }
        })
        val selections = world.operationSelectionsFrom(
            when {
                exit == Exit.ARGUMENT_ERROR -> "query { driver(enabled: true) healthy }"
                exit == Exit.EXCLUDED -> "query { driver(enabled: false) healthy }"
                reference -> "query { reference healthy }"
                else -> "query { consumer(seed: 5) healthy }"
            },
        )

        fun variableId(name: String): VariableInstanceId = VariableInstanceId.of(
            requireNotNull(consumerOccurrence),
            ResolverTarget.FieldValueResolverTarget(
                world.schema.requireObjectField("Query", "consumer"),
            ),
            name,
        )
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runUntilIdle() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private fun ObjectEngineResult.cell(name: String) = getCell(keys.single { it.field.name == name })
}
