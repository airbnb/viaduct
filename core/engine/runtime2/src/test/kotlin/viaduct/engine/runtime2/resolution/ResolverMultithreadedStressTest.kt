@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ResolverTestExecution
import viaduct.engine.runtime2.arbitrary.ResolverTestRun
import viaduct.engine.runtime2.arbitrary.ResolverVariableSingletonCoercionEnabled
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.executeResolverTestCases
import viaduct.engine.runtime2.contract.GeneratedTypeCheckerContract
import viaduct.engine.runtime2.contract.validateFromFieldBindings
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.Assumptions
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class ResolverMultithreadedStressTest : GeneratedTypeCheckerContract, ResolutionDispatcherResource {
    override val typeCheckerProfilePrefix = "resolution"
    override val runtimeTypeCheckerVariables = true
    override val selectiveResolvers = true
    override val generatedResolverConfigOverrides = Config.default + (ResolverVariableSingletonCoercionEnabled to true)
    private var checkerDispatcher: RecordingCoroutineDispatcher? = null

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult {
        val dispatcher = checkerDispatcher ?: RecordingCoroutineDispatcher(resolverDispatcher).also { checkerDispatcher = it }
        return operation.resolve(selections, coroutineContext = dispatcher)
    }

    @AfterEach
    fun verifyTypeCheckerConcurrency() {
        val dispatcher = checkerDispatcher ?: return
        if (configuredThreadCount() == 1) {
            assertEquals(1, dispatcher.maximumConcurrentContinuations.get())
            assertEquals(1, dispatcher.threadNames.size)
        } else {
            assertTrue(dispatcher.maximumConcurrentContinuations.get() > 1, "Expected concurrent type-checker profile continuations")
            assertTrue(dispatcher.threadNames.size > 1, "Expected multiple threads in the type-checker profile")
        }
        println(
            "Resolution type-checker concurrency: threads=${configuredThreadCount()}, " +
                "maximumConcurrentContinuations=${dispatcher.maximumConcurrentContinuations.get()}, " +
                "observedThreads=${dispatcher.threadNames.size}",
        )
    }

    @Test
    fun `one request executes Resolution coroutines on the configured dispatcher`(): Unit =
        runBlocking {
            val threadCount: Int = configuredThreadCount()
            val configuredCounts: TestCaseCount? = configuredCounts()
            val campaignRounds: List<ResolutionBroadStressCampaignRound> =
                configuredRounds().map(ResolutionBroadStressCampaign::round)
            val campaignRuns: List<ResolutionBroadStressCampaignRun> =
                campaignRounds.flatMap(ResolutionBroadStressCampaignRound::runs)
            ResolutionDispatcherFactory.create(threadCount).use { executorDispatcher ->
                val dispatcher = RecordingCoroutineDispatcher(executorDispatcher)
                val completedCases: Int =
                    campaignRuns.sumOf { run ->
                        runResolutionMultithreadedStress(
                            campaignRun = run,
                            counts = configuredCounts ?: run.counts,
                            dispatcher = dispatcher,
                        )
                    }
                val expectedCases: Int =
                    campaignRuns.sumOf { run ->
                        val counts: TestCaseCount = configuredCounts ?: run.counts
                        counts.schemas *
                            counts.registriesPerSchema *
                            counts.queriesPerSchema
                    }

                assertEquals(expectedCases, completedCases)
                if (threadCount == 1) {
                    assertEquals(1, dispatcher.maximumConcurrentContinuations.get())
                    assertEquals(1, dispatcher.threadNames.size)
                } else {
                    assertTrue(
                        dispatcher.maximumConcurrentContinuations.get() > 1,
                        "Expected concurrent Resolution continuations; maximum=" +
                            dispatcher.maximumConcurrentContinuations.get(),
                    )
                    assertTrue(
                        dispatcher.threadNames.size > 1,
                        "Expected multiple resolver threads; observed=" +
                            dispatcher.threadNames,
                    )
                }
                println(
                    "Resolution multithreaded stress: " +
                        "rounds=${campaignRounds.map { round -> round.number }}, " +
                        "size=${configuredCounts?.summary() ?: "campaign"}, " +
                        "threads=$threadCount, " +
                        "maximumConcurrentContinuations=" +
                        "${dispatcher.maximumConcurrentContinuations.get()}, " +
                        "threadNames=${dispatcher.threadNames.sorted()}, " +
                        "completedCases=$completedCases",
                )
            }
        }

    // Returns the fixed dispatcher size selected for this run.
    private fun configuredThreadCount(): Int = configuredResolutionThreadCount()

    // Returns fixed S:R:Q dimensions, or null to retain each campaign profile's dimensions.
    private fun configuredCounts(): TestCaseCount? {
        val configured: String =
            System.getProperty(SIZE_PROPERTY)
                ?: DEFAULT_SIZE
        if (configured == CAMPAIGN_SIZE) return null
        val dimensions: List<Int> =
            configured.split(':').map { value ->
                value.toIntOrNull()
                    ?.takeIf { dimension -> dimension > 0 }
                    ?: error("$SIZE_PROPERTY must have positive S:R:Q dimensions")
            }
        require(dimensions.size == 3) {
            "$SIZE_PROPERTY must have S:R:Q form"
        }
        return TestCaseCount(
            schemas = dimensions[0],
            registriesPerSchema = dimensions[1],
            queriesPerSchema = dimensions[2],
        )
    }

    // Returns the distinct persisted campaign rounds supplying seeds and configurations.
    private fun configuredRounds(): List<Int> {
        val configured: String =
            System.getProperty(ROUNDS_PROPERTY)
                ?: DEFAULT_ROUNDS
        val rounds: List<Int> =
            configured.split(',').map { value ->
                value.toIntOrNull()
                    ?.takeIf { round -> round in 1..100 }
                    ?: error("$ROUNDS_PROPERTY must contain comma-separated rounds in 1..100")
            }
        require(rounds.isNotEmpty() && rounds.distinct().size == rounds.size) {
            "$ROUNDS_PROPERTY must contain distinct campaign rounds"
        }
        return rounds
    }

    private companion object {
        const val SIZE_PROPERTY = "resolution.multithreaded.size"
        const val ROUNDS_PROPERTY = "resolution.multithreaded.rounds"
        const val CAMPAIGN_SIZE = "campaign"
        const val DEFAULT_SIZE = CAMPAIGN_SIZE
        const val DEFAULT_ROUNDS = "1"
    }
}

// Records actual continuation overlap while delegating execution to the fixed thread pool.
private class RecordingCoroutineDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    private val activeContinuations = AtomicInteger()
    val maximumConcurrentContinuations = AtomicInteger()
    val threadNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Dispatches one continuation and records the thread and overlap during its execution.
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        delegate.dispatch(context) {
            val active: Int = activeContinuations.incrementAndGet()
            maximumConcurrentContinuations.accumulateAndGet(active, ::maxOf)
            threadNames += Thread.currentThread().name
            try {
                block.run()
            } finally {
                activeContinuations.decrementAndGet()
            }
        }
    }
}

// Generates one profile serially while each resolution uses the shared multithreaded dispatcher.
private suspend fun runResolutionMultithreadedStress(
    campaignRun: ResolutionBroadStressCampaignRun,
    counts: TestCaseCount,
    dispatcher: CoroutineDispatcher,
): Int {
    val startedAt: Long = System.nanoTime()
    var completedCases = 0
    val run: ResolverTestRun =
        executeResolverTestCases(
            execution = ResolverTestExecution(counts),
            config = campaignRun.config,
            profile = campaignRun.propertyProfile,
            seed = campaignRun.seed,
        ) { testWorld, testCase ->
            val world: Assumptions =
                testWorld.newAssumptions(selectiveResolvers = true)
            val fragment: Fragment = testWorld.schemas.fragmentFrom(testCase.query.source)

            val appliedResolverOccurrences =
                ConcurrentHashMap.newKeySet<ResolverOccurrenceId>()

            val recordingObserver = object : CorrectnessResolverObserver() {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    super.onResolverInvocation(observation)
                    appliedResolverOccurrences += observation.resolverOccurrenceId
                }
            }
            val operation =
                SharedOperationContext.create(world, resolverObserver = recordingObserver)
            val result: ObjectEngineResult =
                operation.resolve(
                    selections = fragment.subselections,
                    coroutineContext = dispatcher,
                )
            // Resolution has quiesced; all post-resolution oracle work remains serial here.
            assertTrue(
                result.correctResolution(operation, fragment),
            )
            result.validateFromFieldBindings(operation, appliedResolverOccurrences)
            completedCases += 1
        }

    assertEquals(run.expectedCases, run.attemptedCases)
    assertEquals(run.expectedCases, completedCases)
    println(
        "Resolution multithreaded profile: profile=${campaignRun.propertyProfile}, " +
            "seed=${campaignRun.seed}, size=${counts.summary()}, " +
            "completedCases=$completedCases, " +
            "elapsedMillis=${(System.nanoTime() - startedAt) / 1_000_000}",
    )
    return completedCases
}

// Returns compact S:R:Q dimensions for diagnostics.
private fun TestCaseCount.summary(): String = "$schemas:$registriesPerSchema:$queriesPerSchema"
