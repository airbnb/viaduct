package viaduct.engine.runtime2.coverage

/** One candidate's suite-wide report, with each batch's report computed over that batch's evidence alone. */
data class CandidateCoverage(
    val label: String,
    val elapsedMillis: Long,
    val report: ResolverCoverageReport,
    val batchReports: Map<String, ResolverCoverageReport>,
    val preparationMillis: Long = 0,
    val inapplicableObligations: Set<String> = emptySet(),
    val vectorD0: Int = 0,
    val batchVectorD0: Map<String, Int> = emptyMap(),
) {
    init {
        require(report.metrics.map { it.id }.containsAll(inapplicableObligations)) { "Inapplicable obligations must name catalog metrics" }
    }

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
            inapplicableObligations: Set<String> = emptySet(),
        ): CandidateCoverage {
            val vectorObservations = measurement.interactionVectorObservations()
            val vectorsByBatch = vectorObservations.groupBy { it.test.batchId }
                .mapValues { (_, observations) -> observations.mapTo(mutableSetOf()) { it.vector } }
            val batchVectorD0 = measurement.executions.map { it.test.batchId }.distinct().associateWith { batchId ->
                vectorsByBatch[batchId]?.size ?: 0
            }
            return CandidateCoverage(
                label = label,
                elapsedMillis = elapsedMillis,
                preparationMillis = preparationMillis,
                report = measurement.report(),
                batchReports = measurement.executions.map { it.test.batchId }.distinct().associateWith { measurement.forBatch(it).report() },
                inapplicableObligations = inapplicableObligations,
                vectorD0 = vectorObservations.mapTo(mutableSetOf()) { it.vector }.size,
                batchVectorD0 = batchVectorD0,
            )
        }
    }
}

fun ResolverCoverageMeasurement.forBatch(batchId: String): ResolverCoverageMeasurement = retainingBatches(setOf(batchId))

enum class CoverageVerdict { LEFT_DOMINATES, RIGHT_DOMINATES, EQUAL, MIXED, INCOMPARABLE }

data class MetricComparison(
    val id: String,
    val left: CoverageSupport,
    val right: CoverageSupport,
    val leftApplicable: Boolean = true,
    val rightApplicable: Boolean = true,
) {
    /** Neither config can generate the evidence, so zero on both sides says nothing about either suite. */
    val inapplicable: Boolean get() = !leftApplicable && !rightApplicable

    /** Only one config can generate the evidence, so the other side's zero is not a loss. */
    val comparable: Boolean get() = leftApplicable == rightApplicable

    /** Distinct tests measure support, batches measure spread; occurrence volume alone never wins a metric. */
    val verdict: CoverageVerdict
        get() {
            if (!comparable) return CoverageVerdict.INCOMPARABLE
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

/** Descriptive resolver-attribute support; attributes are useful for inspection, not suite requirements. */
data class AttributeComparison(
    val id: String,
    val description: String,
    val left: CoverageSupport,
    val right: CoverageSupport,
)

data class SuiteCoverageComparison(
    val left: CandidateCoverage,
    val right: CandidateCoverage,
) {
    init {
        require(left.report.modelVersion == right.report.modelVersion) { "Candidates were measured with different coverage models" }
    }

    /** Support counts grow with suite size, so the verdict compares coverage fairly only when both sides cost the same. */
    val sameCost: Boolean
        get() = left.cases == right.cases && left.batches == right.batches && left.schemas == right.schemas

    val metrics: List<MetricComparison>
        get() =
            left.report.metrics.zip(right.report.metrics) { l, r ->
                MetricComparison(l.id, l.support, r.support, l.id !in left.inapplicableObligations, r.id !in right.inapplicableObligations)
            }

    /** Uses the union so attributes seen by only one candidate remain visible in the comparison. */
    val attributes: List<AttributeComparison>
        get() {
            val leftById = left.report.attributes.associateBy { it.id }
            val rightById = right.report.attributes.associateBy { it.id }
            val emptySupport = CoverageSupport(occurrences = 0, executions = 0, tests = 0, registries = 0, batches = 0, schemas = 0)
            return (leftById.keys + rightById.keys).sorted().map { id ->
                val leftAttribute = leftById[id]
                val rightAttribute = rightById[id]
                AttributeComparison(
                    id = id,
                    description = leftAttribute?.description ?: requireNotNull(rightAttribute).description,
                    left = leftAttribute?.support ?: emptySupport,
                    right = rightAttribute?.support ?: emptySupport,
                )
            }
        }

    val verdict: CoverageVerdict
        get() {
            val verdicts = metrics.map { it.verdict }.filter { it != CoverageVerdict.EQUAL && it != CoverageVerdict.INCOMPARABLE }.toSet()
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
        output.appendLine(row("distinct RFC-271 interaction vectors (D0)", "${left.vectorD0}", "${right.vectorD0}", signed(right.vectorD0 - left.vectorD0)))
        output.appendLine(row("batch vector D0 min/median/max", left.batchVectorD0.values.spread(), right.batchVectorD0.values.spread(), ""))
        output.appendLine(
            row(
                "resolver-fingerprint diversity (diagnostic; mean batch D0/D1/D2)",
                left.report.analysis.batchDiversity.diagnosticSummary(),
                right.report.analysis.batchDiversity.diagnosticSummary(),
                "",
            ),
        )
        output.appendLine()
        output.appendLine(row("metric (tests/batches; n/a = config cannot generate it; one n/a side = no verdict)", "A", "B", "verdict"))
        metrics.forEach { metric ->
            val a = if (metric.leftApplicable) metric.left.summary() else "n/a"
            val b = if (metric.rightApplicable) metric.right.summary() else "n/a"
            output.appendLine(row(metric.id, a, b, if (metric.inapplicable) "n/a" else metric.verdict.label()))
        }
        output.appendLine()
        output.appendLine(row("resolver attribute (cases/batches)", "A", "B", ""))
        attributes.forEach { attribute ->
            output.appendLine(row(attribute.id, attribute.left.summary(), attribute.right.summary(), ""))
        }
        output.appendLine()
        output.appendLine("verdict (raw coverage): ${verdict.label()}")
        if (!sameCost) {
            output.appendLine(
                "warning: A and B differ in cost (${left.cases} vs ${right.cases} cases, ${left.batches} vs ${right.batches} batches, " +
                    "${left.schemas} vs ${right.schemas} schemas); raw coverage is not compared at the same cost",
            )
        }
        output.appendLine()
        output.appendLine("batch columns: " + metrics.mapIndexed { index, metric -> "m${index + 1}=${metric.id}" }.joinToString())
        listOf("A" to left, "B" to right).forEach { (side, candidate) ->
            output.appendLine()
            output.appendLine("$side batch contributions (occurrences per metric):")
            output.appendLine("  batch vectorD0 " + metrics.indices.joinToString(" ") { "%6s".format("m${it + 1}") })
            candidate.batchReports.forEach { (batchId, report) ->
                output.appendLine(
                    "  ${batchId.substringAfterLast('/')}  %7d ".format(candidate.batchVectorD0[batchId] ?: 0) +
                        report.metrics.joinToString(" ") { "%6d".format(it.support.occurrences) },
                )
            }
            if (candidate.idleBatches.isNotEmpty()) {
                output.appendLine("  idle: " + candidate.idleBatches.joinToString { it.substringAfterLast('/') })
            }
        }
    }
}

private fun CoverageSupport.summary(): String = "$tests/$batches"

private fun Collection<Int>.spread(): String = if (isEmpty()) "-" else sorted().let { "${it.first()}/${it[(it.size - 1) / 2]}/${it.last()}" }

private fun List<ResolverBatchDiversity>.diagnosticSummary(): String = if (isEmpty()) "-" else "D0 %.2f / D1 %.2f / D2 %.2f".format(mean { it.d0.toDouble() }, mean { it.d1 }, mean { it.d2 })

private fun <T> Collection<T>.mean(value: (T) -> Double): Double = if (isEmpty()) 0.0 else sumOf(value) / size

private fun CoverageVerdict.label(): String =
    when (this) {
        CoverageVerdict.LEFT_DOMINATES -> "A"
        CoverageVerdict.RIGHT_DOMINATES -> "B"
        CoverageVerdict.EQUAL -> "="
        CoverageVerdict.MIXED -> "mixed"
        CoverageVerdict.INCOMPARABLE -> "n/a"
    }

private fun signed(value: Int): String = if (value > 0) "+$value" else "$value"

private fun row(vararg cells: String): String = "%-56s %12s %12s %8s".format(*cells).trimEnd()
