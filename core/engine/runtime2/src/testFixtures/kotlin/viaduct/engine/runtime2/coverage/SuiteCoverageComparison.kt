package viaduct.engine.runtime2.coverage

/** One candidate's suite-wide report, with each batch's report computed over that batch's evidence alone. */
data class CandidateCoverage(
    val label: String,
    val elapsedMillis: Long,
    val report: ResolverCoverageReport,
    val batchReports: Map<String, ResolverCoverageReport>,
    val preparationMillis: Long = 0,
) {
    val cases: Int get() = report.executions.size
    val batches: Int get() = batchReports.size
    val schemas: Int get() = report.executions.map { it.test.schemaId }.distinct().size

    /** Batches that support no metric add replay cost without coverage. */
    val idleBatches: List<String>
        get() = batchReports.filterValues { report -> report.metrics.all { it.support.occurrences == 0 } }.keys.toList()

    companion object {
        fun of(
            label: String,
            elapsedMillis: Long,
            measurement: ResolverCoverageMeasurement,
            preparationMillis: Long = 0,
        ): CandidateCoverage =
            CandidateCoverage(
                label = label,
                elapsedMillis = elapsedMillis,
                preparationMillis = preparationMillis,
                report = measurement.report(),
                batchReports = measurement.executions.map { it.test.batchId }.distinct().associateWith { measurement.forBatch(it).report() },
            )
    }
}

fun ResolverCoverageMeasurement.forBatch(batchId: String): ResolverCoverageMeasurement =
    copy(
        executions = executions.filter { it.test.batchId == batchId },
        rows = rows.filter { it.batchId == batchId },
        dependencies = dependencies.filter { it.batchId == batchId },
    )

enum class CoverageVerdict { LEFT_DOMINATES, RIGHT_DOMINATES, EQUAL, MIXED }

data class MetricComparison(
    val id: String,
    val left: CoverageSupport,
    val right: CoverageSupport,
) {
    /** Distinct tests measure support, batches measure spread; occurrence volume alone never wins a metric. */
    val verdict: CoverageVerdict
        get() {
            val leftKey = listOf(left.tests, left.batches)
            val rightKey = listOf(right.tests, right.batches)
            return when {
                leftKey == rightKey -> CoverageVerdict.EQUAL
                leftKey.zip(rightKey).all { (l, r) -> l >= r } -> CoverageVerdict.LEFT_DOMINATES
                leftKey.zip(rightKey).all { (l, r) -> l <= r } -> CoverageVerdict.RIGHT_DOMINATES
                else -> CoverageVerdict.MIXED
            }
        }
}

data class SuiteCoverageComparison(
    val left: CandidateCoverage,
    val right: CandidateCoverage,
) {
    init {
        require(left.report.modelVersion == right.report.modelVersion) { "Candidates were measured with different coverage models" }
    }

    /** Support counts grow with suite size, so the verdict compares coverage fairly only when both sides cost the same. */
    val sameCost: Boolean
        get() = left.cases == right.cases && left.batches == right.batches

    val metrics: List<MetricComparison>
        get() = left.report.metrics.zip(right.report.metrics) { l, r -> MetricComparison(l.id, l.support, r.support) }

    val verdict: CoverageVerdict
        get() {
            val verdicts = metrics.map { it.verdict }.filter { it != CoverageVerdict.EQUAL }.toSet()
            return when {
                verdicts.isEmpty() -> CoverageVerdict.EQUAL
                verdicts.size == 1 -> verdicts.single()
                else -> CoverageVerdict.MIXED
            }
        }

    fun render(output: Appendable) {
        output.appendLine("A: ${left.label}")
        output.appendLine("B: ${right.label}")
        output.appendLine()
        val costRows =
            listOf(
                Triple("cases", left.cases, right.cases),
                Triple("batches", left.batches, right.batches),
                Triple("schemas", left.schemas, right.schemas),
                Triple("idle batches", left.idleBatches.size, right.idleBatches.size),
            )
        output.appendLine(row("", "A", "B", "Δ"))
        costRows.forEach { (name, a, b) -> output.appendLine(row(name, "$a", "$b", signed(b - a))) }
        output.appendLine(row("generation", "${left.preparationMillis}ms", "${right.preparationMillis}ms", ""))
        output.appendLine(row("replay (informational: A pays JVM warm-up)", "${left.elapsedMillis}ms", "${right.elapsedMillis}ms", ""))
        output.appendLine()
        output.appendLine(row("metric (tests/batches)", "A", "B", "verdict"))
        metrics.forEach { metric ->
            output.appendLine(row(metric.id, metric.left.summary(), metric.right.summary(), metric.verdict.label()))
        }
        output.appendLine()
        output.appendLine("verdict (raw coverage): ${verdict.label()}")
        if (!sameCost) {
            output.appendLine(
                "warning: A and B differ in cost (${left.cases} vs ${right.cases} cases, ${left.batches} vs ${right.batches} batches); " +
                    "raw coverage favors the larger candidate, so the verdict does not show which covers more for the same cost",
            )
        }
        output.appendLine()
        output.appendLine("batch columns: " + metrics.mapIndexed { index, metric -> "m${index + 1}=${metric.id}" }.joinToString())
        listOf("A" to left, "B" to right).forEach { (side, candidate) ->
            output.appendLine()
            output.appendLine("$side batch contributions (occurrences per metric):")
            output.appendLine("  batch " + metrics.indices.joinToString(" ") { "%6s".format("m${it + 1}") })
            candidate.batchReports.forEach { (batchId, report) ->
                output.appendLine("  ${batchId.substringAfterLast('/')}  " + report.metrics.joinToString(" ") { "%6d".format(it.support.occurrences) })
            }
            if (candidate.idleBatches.isNotEmpty()) {
                output.appendLine("  idle: " + candidate.idleBatches.joinToString { it.substringAfterLast('/') })
            }
        }
    }
}

private fun CoverageSupport.summary(): String = "$tests/$batches"

private fun CoverageVerdict.label(): String =
    when (this) {
        CoverageVerdict.LEFT_DOMINATES -> "A"
        CoverageVerdict.RIGHT_DOMINATES -> "B"
        CoverageVerdict.EQUAL -> "="
        CoverageVerdict.MIXED -> "mixed"
    }

private fun signed(value: Int): String = if (value > 0) "+$value" else "$value"

private fun row(vararg cells: String): String = "%-56s %12s %12s %8s".format(*cells).trimEnd()
