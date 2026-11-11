@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.coverage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.measureTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.ResolverContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.propertytest.PropertyTestJson
import viaduct.engine.runtime2.propertytest.workspaceRelativePath
import viaduct.engine.runtime2.resolution.ResolutionDispatcherFactory
import viaduct.engine.runtime2.resolution.configuredResolutionThreadCount
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.resolve

/** Measures two candidate suites with production Resolution and prints their coverage side by side. */
object ResolverTestSuiteComparer {
    private const val USAGE =
        "Usage: ResolverTestSuiteComparer <candidate-a> <candidate-b> [--json <path>] [--html <path>]\n" +
            "  candidate: <suite-id> replays the stored suite; <suite-id>@seed=<n>,size=<S:R:Q> regenerates it with either override"

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList().chunkedOptions()
        val specs = options.getValue(null)
        require(specs.size == 2 && options.keys.all { it == null || it in setOf("--json", "--html") }) { USAGE }
        val outputs = options.filterKeys { it != null }.mapValues { (_, values) -> workspaceRelativePath(requireNotNull(values.singleOrNull()) { USAGE }) }
        val left = CoverageCandidate.parse(specs[0])
        val right = CoverageCandidate.parse(specs[1])
        val comparison = compare(left, right)
        comparison.render(System.out)
        outputs["--json"]?.let { path -> writeOutput(path, PropertyTestJson.write(comparison)) }
        outputs["--html"]?.let { path -> writeOutput(path, runBlocking { SuiteCoverageComparisonPage.render(comparison, left.batches(), right.batches()) }) }
    }

    private fun writeOutput(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.toAbsolutePath().parent)
        Files.writeString(path, content)
        println("\nwrote $path")
    }

    // Arguments before the first option are candidates (key null); each option takes the arguments up to the next option.
    private fun List<String>.chunkedOptions(): Map<String?, List<String>> {
        val options = linkedMapOf<String?, MutableList<String>>(null to mutableListOf())
        var current: String? = null
        for (arg in this) {
            if (arg.startsWith("--")) {
                require(arg !in options) { "Option $arg given twice" }
                current = arg
                options[arg] = mutableListOf()
            } else {
                options.getValue(current) += arg
            }
        }
        return options
    }

    fun compare(
        left: CoverageCandidate,
        right: CoverageCandidate,
    ): SuiteCoverageComparison =
        ResolutionDispatcherFactory.create(configuredResolutionThreadCount()).use { dispatcher ->
            val contract = ResolutionContract(dispatcher)
            SuiteCoverageComparison(contract.measure(left), contract.measure(right))
        }

    private fun ResolverContract.measure(candidate: CoverageCandidate): CandidateCoverage {
        val preparation = measureTime { runBlocking { candidate.prepare() } }
        val (measurement, elapsed) = measureTimedValue { runBlocking { measureCoverage(candidate) } }
        return CandidateCoverage.of(candidate.label, elapsed.inWholeMilliseconds, measurement, preparation.inWholeMilliseconds)
    }
}

private class ResolutionContract(private val dispatcher: CoroutineContext) : ResolverContract {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections, dispatcher)
}
