@file:Suppress("ForbiddenImport")

package semantics.resolver26

import semantics.shared.ResolverInvocationObservation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.Fragment
import model.fragmentFrom
import org.junit.jupiter.api.Test
import semantics.arbitrary.ResolverTestRun
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.contract.validateFromFieldBindings
import semantics.correctresolution.correctResolution
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import semantics.shared.SharedOperationContext
import semantics.correctresolution.CorrectnessResolverObserver

class ResolverMultithreadedStressTest {
    @Test
    fun `one request executes Resolver26 coroutines on the configured dispatcher`(): Unit =
        runBlocking {
            val threadCount: Int = configuredThreadCount()
            val configuredCounts: TestCaseCount? = configuredCounts()
            val campaignRounds: List<Resolver26BroadStressCampaignRound> =
                configuredRounds().map(Resolver26BroadStressCampaign::round)
            val campaignRuns: List<Resolver26BroadStressCampaignRun> =
                campaignRounds.flatMap(Resolver26BroadStressCampaignRound::runs)
            ResolutionDispatcherFactory.create(threadCount).use { executorDispatcher ->
                val dispatcher = RecordingCoroutineDispatcher(executorDispatcher)
                val completedCases: Int =
                    campaignRuns.sumOf { run ->
                        runResolver26MultithreadedStress(
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
                        "Expected concurrent Resolver26 continuations; maximum=" +
                            dispatcher.maximumConcurrentContinuations.get(),
                    )
                    assertTrue(
                        dispatcher.threadNames.size > 1,
                        "Expected multiple resolver threads; observed=" +
                            dispatcher.threadNames,
                    )
                }
                println(
                    "Resolver26 multithreaded stress: " +
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
    private fun configuredThreadCount(): Int =
        configuredResolutionThreadCount()

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
        const val SIZE_PROPERTY = "resolver26.multithreaded.size"
        const val ROUNDS_PROPERTY = "resolver26.multithreaded.rounds"
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
private suspend fun runResolver26MultithreadedStress(
    campaignRun: Resolver26BroadStressCampaignRun,
    counts: TestCaseCount,
    dispatcher: CoroutineDispatcher,
): Int {
    val startedAt: Long = System.nanoTime()
    var completedCases = 0
    val run: ResolverTestRun =
        checkResolverTestCases(
            counts = counts,
            config = campaignRun.config,
            profile = campaignRun.propertyProfile,
            seed = campaignRun.seed,
        ) { testWorld, testCase ->
            val world: Assumptions =
                testWorld.newAssumptions(selectiveResolvers = true)
            val fragment: Fragment = world.fragmentFrom(testCase.query.source)

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
        "Resolver26 multithreaded profile: profile=${campaignRun.propertyProfile}, " +
            "seed=${campaignRun.seed}, size=${counts.summary()}, " +
            "completedCases=$completedCases, " +
            "elapsedMillis=${(System.nanoTime() - startedAt) / 1_000_000}",
    )
    return completedCases
}

// Returns compact S:R:Q dimensions for diagnostics.
private fun TestCaseCount.summary(): String =
    "$schemas:$registriesPerSchema:$queriesPerSchema"
