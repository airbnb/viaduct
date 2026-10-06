@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.propertytest

import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ResolverTestCaseCoordinate
import viaduct.engine.runtime2.arbitrary.ResolverTestExecution
import viaduct.engine.runtime2.resolution.ResolutionDispatcherFactory
import viaduct.engine.runtime2.resolution.ResolutionStructuralSignature
import viaduct.engine.runtime2.resolution.configuredResolutionThreadCount
import viaduct.engine.runtime2.resolution.runResolutionBroadStress

const val GENERATOR_CONFIG_INDEX_RESOURCE =
    "/viaduct/engine/runtime2/property-tests/generator-configs/index.json"
const val RESOLUTION_BROAD_CORRECTNESS_SUBJECT_ID =
    "resolution-broad-correctness"

data class PropertyTestRoundResult(
    val roundId: String,
    val completedCases: Int,
    val elapsedMillis: Long,
)

data class PropertyTestRoundExecution(
    val selectedTestInputProfileId: String? = null,
    val selectedCase: ResolverTestCaseCoordinate? = null,
) {
    init {
        require(selectedCase == null || selectedTestInputProfileId != null) {
            "A selected case requires a selected test-input profile"
        }
    }
}

object PropertyTestRoundRunner {
    private val generatorConfigs: GeneratorConfigRegistry by lazy {
        GeneratorConfigRegistry.load(GENERATOR_CONFIG_INDEX_RESOURCE)
    }

    suspend fun run(
        round: PropertyTestRoundConfigFile,
        execution: PropertyTestRoundExecution = PropertyTestRoundExecution(),
    ): PropertyTestRoundResult {
        validate(round)
        val startedAt = System.nanoTime()
        var completedCases = 0
        val selectedRuns =
            round.runs.withIndex().filter { (_, run) ->
                execution.selectedTestInputProfileId == null ||
                    execution.selectedTestInputProfileId == run.testInputProfileId
            }
        require(selectedRuns.isNotEmpty()) {
            "Round ${round.id} does not contain test-input profile " +
                execution.selectedTestInputProfileId
        }
        val resolverDispatcher =
            ResolutionDispatcherFactory.create(configuredResolutionThreadCount())
        try {
            selectedRuns.forEach { (index, run) ->
                val propertyProfile =
                    "${round.id}-run-${(index + 1).toString().padStart(2, '0')}-" +
                        run.testInputProfileId
                val config = generatorConfigs[run.testInputProfileId].toConfig()
                val resolverExecution =
                    ResolverTestExecution(
                        counts = run.counts,
                        selectedCase = execution.selectedCase,
                    )
                completedCases +=
                    subject(run.subjectProfileId).execute(
                        run = run,
                        propertyProfile = propertyProfile,
                        config = config,
                        execution = resolverExecution,
                        resolverCoroutineContext = resolverDispatcher,
                    )
            }
        } finally {
            resolverDispatcher.close()
        }
        val result =
            PropertyTestRoundResult(
                roundId = round.id,
                completedCases = completedCases,
                elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
            )
        println(
            "Property test round: id=${result.roundId}, runs=${selectedRuns.size}, " +
                "completedCases=${result.completedCases}, elapsedMillis=${result.elapsedMillis}",
        )
        return result
    }

    private fun validate(round: PropertyTestRoundConfigFile) {
        require(round.formatVersion == PROPERTY_TEST_ROUND_FORMAT_VERSION) {
            "Unsupported property-test round formatVersion ${round.formatVersion}"
        }
        require(round.id.isNotBlank()) { "Property-test round id must not be blank" }
        require(round.runs.isNotEmpty()) {
            "Property-test round ${round.id} must contain at least one run"
        }
        round.runs.forEach { run ->
            require(run.subjectProfileId.isNotBlank())
            require(run.testInputProfileId.isNotBlank())
            generatorConfigs[run.testInputProfileId]
            subject(run.subjectProfileId)
        }
    }
}

object PropertyTestRoundLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 3 && args[0] == "--campaign" && args[2] == "--list-rounds") {
            loadConfig<PropertyTestCampaignConfigFile>(args[1])
                .roundNumbers()
                .forEach(::println)
            return
        }
        val round =
            when {
                args.size == 1 ->
                    loadConfig<PropertyTestRoundConfigFile>(args.single())
                args.size == 4 && args[0] == "--campaign" && args[2] == "--round" -> {
                    val roundNumber =
                        args[3].toIntOrNull()
                            ?: error("Campaign round must be an integer: ${args[3]}")
                    loadConfig<PropertyTestCampaignConfigFile>(args[1])
                        .roundConfig(roundNumber)
                }
                else ->
                    error(
                        "Usage: property-test-round <round-config.json|classpath:/resource.json> " +
                            "or --campaign <campaign.json|classpath:/resource.json> " +
                            "<--round <number>|--list-rounds>",
                    )
            }
        runBlocking {
            PropertyTestRoundRunner.run(round)
        }
    }
}

private inline fun <reified T> loadConfig(reference: String): T =
    if (reference.startsWith("classpath:")) {
        PropertyTestJson.readResource(reference.removePrefix("classpath:"))
    } else {
        File(reference).inputStream().use(PropertyTestJson::read)
    }

private fun subject(id: String): PropertyTestSubject =
    when (id) {
        ResolutionBroadCorrectnessSubject.id -> ResolutionBroadCorrectnessSubject
        else ->
            error(
                "Unknown property-test subject profile $id; profiles=" +
                    listOf(ResolutionBroadCorrectnessSubject.id),
            )
    }

private fun interface PropertyTestSubject {
    suspend fun execute(
        run: PropertyTestRunConfig,
        propertyProfile: String,
        config: Config,
        execution: ResolverTestExecution,
        resolverCoroutineContext: CoroutineContext,
    ): Int
}

private object ResolutionBroadCorrectnessSubject : PropertyTestSubject {
    const val id = RESOLUTION_BROAD_CORRECTNESS_SUBJECT_ID

    override suspend fun execute(
        run: PropertyTestRunConfig,
        propertyProfile: String,
        config: Config,
        execution: ResolverTestExecution,
        resolverCoroutineContext: CoroutineContext,
    ): Int =
        runResolutionBroadStress(
            resolverCoroutineContext = resolverCoroutineContext,
            requiredSignatures =
                run.requiredCoverage.mapTo(linkedSetOf(), ::structuralSignature),
            propertyProfile = propertyProfile,
            counts = run.counts,
            config = config,
            seed = run.seed,
            execution = execution,
        )
}

internal fun ResolutionStructuralSignature.wireId(): String = name.lowercase().replace('_', '-')

private fun structuralSignature(id: String): ResolutionStructuralSignature =
    ResolutionStructuralSignature.entries.singleOrNull { signature ->
        signature.wireId() == id
    } ?: error(
        "Unknown Resolution coverage signature $id; signatures=" +
            ResolutionStructuralSignature.entries.map(ResolutionStructuralSignature::wireId),
    )
