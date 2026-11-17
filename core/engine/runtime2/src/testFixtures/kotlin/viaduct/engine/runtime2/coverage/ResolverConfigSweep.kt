package viaduct.engine.runtime2.coverage

import kotlin.time.measureTimedValue
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.contract.ResolverContract
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition

/** A named set of generator-config overrides, in the `name:value;...` form [CoverageCandidate.Generated] accepts. */
data class ConfigSweepVariant(val name: String, val overrides: String) {
    init {
        require(name.isNotBlank()) { "Sweep variant name must not be blank" }
    }

    companion object {
        /** Parses `name=overrides|name=overrides`. */
        fun parseAll(spec: String): List<ConfigSweepVariant> =
            spec.split('|').map(String::trim).filter(String::isNotEmpty).map { entry ->
                val parts = entry.split('=', limit = 2)
                require(parts.size == 2) { "Sweep variant must be name=overrides: $entry" }
                ConfigSweepVariant(parts[0].trim(), parts[1].trim())
            }
    }
}

/** One variant's distinct-test support; a sweep keeps only these summaries, not the measurements. */
data class ConfigSweepResult(
    val variant: ConfigSweepVariant,
    val cases: Int,
    val richness: Int,
    val obligationTests: Map<String, Int>,
    val attributeTests: Map<String, Int>,
    val elapsedMillis: Long,
)

/** Measures [definition] regenerated with each variant's config on a pool of [counts]. */
suspend fun ResolverContract.sweepConfigs(
    definition: ResolverTestSuiteDefinition,
    counts: TestCaseCount,
    variants: List<ConfigSweepVariant>,
): List<ConfigSweepResult> {
    require(variants.isNotEmpty()) { "A config sweep needs at least one variant" }
    require(variants.map { it.name }.distinct().size == variants.size) { "Sweep variant names must be unique" }
    return variants.map { variant ->
        val candidate = CoverageCandidate.Generated(definition, counts = counts, configOverrides = variant.overrides)
        val (report, elapsed) = measureTimedValue { measureCoverage(candidate).report() }
        ConfigSweepResult(
            variant = variant,
            cases = report.executions.size,
            richness = report.analysis.suite.diversity.d0,
            obligationTests = report.metrics.associate { it.id to it.support.tests },
            attributeTests = report.attributes.associate { it.id to it.support.tests },
            elapsedMillis = elapsed.inWholeMilliseconds,
        )
    }
}

/** One row per variant: cases, D0, distinct tests per obligation code, and how many attributes with [attributePrefix] it reaches. */
fun List<ConfigSweepResult>.render(
    output: Appendable,
    attributePrefix: String = "object_fragment",
) {
    val codes = resolverCoverageMetricCodes.entries.map { it.toPair() }
    val prefixed = flatMapTo(sortedSetOf()) { result -> result.attributeTests.keys.filter { it.startsWith(attributePrefix) } }
    val nameWidth = maxOf(7, maxOf { it.variant.name.length })
    output.appendLine(
        "%-${nameWidth}s %6s %4s ".format("variant", "cases", "D0") + codes.joinToString(" ") { (_, code) -> "%4s".format(code) } +
            "  $attributePrefix*",
    )
    forEach { result ->
        val reached = prefixed.count { (result.attributeTests[it] ?: 0) > 0 }
        output.appendLine(
            "%-${nameWidth}s %6d %4d ".format(result.variant.name, result.cases, result.richness) +
                codes.joinToString(" ") { (id, _) -> "%4d".format(result.obligationTests.getValue(id)) } +
                "  $reached/${prefixed.size}",
        )
    }
    output.appendLine()
    forEach { result -> output.appendLine("${result.variant.name} (${result.elapsedMillis}ms): ${result.variant.overrides.ifBlank { "(definition config)" }}") }
}
