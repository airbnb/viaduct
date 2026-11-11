@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_PROPERTY
import viaduct.engine.runtime2.arbitrary.ResolutionWitness
import viaduct.engine.runtime2.arbitrary.checkResolverTestCases
import viaduct.engine.runtime2.arbitrary.encodeResolverBenchmarkCorpus
import viaduct.engine.runtime2.contract.registeredResolverApplicationIdentityCounts
import viaduct.engine.runtime2.contract.validateFromFieldBindings
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

object PropertyTestBenchmarkCorpusWriter {
    private const val CAMPAIGN_ROUND = 46

    // S:R:Q generation produced the frozen corpus case (S=10 R=4 Q=3); S:Q generation cannot reproduce it.
    private val SELECTED_CASE: String? = null
    private const val EXPECTED_RESOLVER_APPLICATIONS = 12_763

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) {
            "Expected arguments: <output-directory>"
        }
        val selectedCase =
            checkNotNull(SELECTED_CASE) {
                "The frozen property-test benchmark corpus predates S:Q generation; search campaign round $CAMPAIGN_ROUND for a new S:Q case and set SELECTED_CASE and EXPECTED_RESOLVER_APPLICATIONS"
            }
        val outputDirectory = Path.of(arguments.single())
        val campaignRun =
            ResolutionBroadStressCampaign
                .round(CAMPAIGN_ROUND)
                .runs
                .single { run ->
                    run.profile == ResolutionBroadStressProfile.SYMBOLIC_IDENTITY
                }
        val previousCase = System.getProperty(RESOLVER_TEST_CASE_PROPERTY)
        System.setProperty(RESOLVER_TEST_CASE_PROPERTY, selectedCase)
        try {
            ResolutionDispatcherFactory.create(configuredResolutionThreadCount()).use { dispatcher ->
                runBlocking {
                    var captured = false
                    checkResolverTestCases(
                        counts = campaignRun.counts,
                        config = campaignRun.config,
                        profile = campaignRun.propertyProfile,
                        seed = campaignRun.seed,
                    ) { testWorld, testCase ->
                        val coordinates = requireNotNull(testCase.coordinates)
                        val world: Assumptions =
                            testWorld.newAssumptions()
                        val fragment: Fragment = testWorld.schemas.fragmentFrom(testCase.query.source)

                        testCase.registry.clearResolutionWitness()
                        val appliedResolverOccurrences =
                            ConcurrentHashMap.newKeySet<ResolverOccurrenceId>()

                        val witnessObserver = testCase.registry.resolutionObserver()

                        val recordingObserver = object : CorrectnessResolverObserver() {
                            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                                super.onResolverInvocation(observation)
                                witnessObserver.onResolverInvocation(observation)
                                appliedResolverOccurrences += observation.resolverOccurrenceId
                            }
                        }
                        val operation =
                            SharedOperationContext.create(
                                world,
                                resolutionObserver = recordingObserver,
                            )
                        val result: ObjectEngineResult =
                            operation.resolve(
                                selections = fragment.subselections,
                                coroutineContext = dispatcher,
                            )
                        val witness: ResolutionWitness = testCase.registry.resolutionWitness()
                        check(witness.applications.size == EXPECTED_RESOLVER_APPLICATIONS)
                        check(
                            result.registeredResolverApplicationIdentityCounts(operation) ==
                                witness.applicationIdentityCounts(),
                        )
                        check(result.correctResolution(operation, fragment))
                        result.validateFromFieldBindings(
                            operation,
                            appliedResolverOccurrences,
                        )

                        Files.createDirectories(outputDirectory)
                        Files.writeString(
                            outputDirectory.resolve("schema.graphqls"),
                            testCase.schema.sdl,
                        )
                        Files.writeString(
                            outputDirectory.resolve("registry.json"),
                            testCase.registry.encodeResolverBenchmarkCorpus(
                                schema = testCase.schema,
                                metrics =
                                    mapOf(
                                        "campaignBaseSeed" to
                                            ResolutionBroadStressCampaign
                                                .round(CAMPAIGN_ROUND)
                                                .baseSeed,
                                        "campaignRound" to CAMPAIGN_ROUND.toLong(),
                                        "propertySeed" to campaignRun.seed,
                                        "queryIndex" to
                                            coordinates.queryIndex.toLong(),
                                        "resolverApplications" to
                                            witness.applications.size.toLong(),
                                        "schemaIndex" to
                                            coordinates.schemaIndex.toLong(),
                                    ),
                            ),
                        )
                        Files.writeString(
                            outputDirectory.resolve("query.graphql"),
                            testCase.query.source + System.lineSeparator(),
                        )
                        Files.writeString(
                            outputDirectory.resolve("provenance.txt"),
                            buildString {
                                appendLine("campaignRound=$CAMPAIGN_ROUND")
                                appendLine("profile=${campaignRun.propertyProfile}")
                                appendLine("propertySeed=${campaignRun.seed}")
                                appendLine("campaignSize=${campaignRun.counts.summary()}")
                                appendLine("selectedCase=$selectedCase")
                                appendLine(
                                    "resolverApplications=${witness.applications.size}",
                                )
                            },
                        )
                        captured = true
                    }
                    check(captured) {
                        "Property-test benchmark case $selectedCase was not captured"
                    }
                }
            }
        } finally {
            if (previousCase == null) {
                System.clearProperty(RESOLVER_TEST_CASE_PROPERTY)
            } else {
                System.setProperty(RESOLVER_TEST_CASE_PROPERTY, previousCase)
            }
        }
        println("Wrote frozen property-test benchmark corpus to $outputDirectory")
    }
}
