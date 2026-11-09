package viaduct.engine.runtime2.resolution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.coverage.ArgumentState
import viaduct.engine.runtime2.coverage.CoverageExecution
import viaduct.engine.runtime2.coverage.CoverageSupport
import viaduct.engine.runtime2.coverage.DeclaredOutputShape
import viaduct.engine.runtime2.coverage.OccurrenceCoverageObligation
import viaduct.engine.runtime2.coverage.OutputKind
import viaduct.engine.runtime2.coverage.RESOLVER_COVERAGE_MODEL_VERSION
import viaduct.engine.runtime2.coverage.ResolutionRole
import viaduct.engine.runtime2.coverage.ResolverBindingSource
import viaduct.engine.runtime2.coverage.ResolverCoverageCsv
import viaduct.engine.runtime2.coverage.ResolverCoverageMeasurement
import viaduct.engine.runtime2.coverage.ResolverCoverageRow
import viaduct.engine.runtime2.coverage.ResolverDependencyOccurrence
import viaduct.engine.runtime2.coverage.ResolverDependencyRow
import viaduct.engine.runtime2.coverage.ResolverLocus
import viaduct.engine.runtime2.coverage.StampClass
import viaduct.engine.runtime2.resolution.framework.ProviderCellOutcome

class ResolverCoverageMeasurementTest {
    @Test
    fun `worked example counts BOTH on the same occurrence`() {
        assertEquals(CoverageSupport(2, 1, 1, 1, 1, 1), measurement(workedExampleRows()).bothSourcesOnOneOccurrence())
    }

    @Test
    fun `separate occurrences do not satisfy a local source combination`() {
        val argumentOnly = row("E1", "A", ARGUMENT_ONLY)
        val objectFieldOnly = row("E1", "B", OBJECT_FIELD_ONLY)

        assertEquals(CoverageSupport(0, 0, 0, 0, 0, 0), measurement(listOf(argumentOnly, objectFieldOnly)).bothSourcesOnOneOccurrence())
    }

    @Test
    fun `worked example counts source coexistence across occurrences`() {
        assertEquals(CoverageSupport(4, 2, 2, 1, 1, 1), measurement(workedExampleRows()).bothSourcesWithinOneExecution())
    }

    @Test
    fun `source occurrences in separate executions do not count as coexistence`() {
        val argumentOnly = row("E1", "A", ARGUMENT_ONLY)
        val objectFieldOnly = row("E2", "B", OBJECT_FIELD_ONLY)

        assertEquals(CoverageSupport(0, 0, 0, 0, 0, 0), measurement(listOf(argumentOnly, objectFieldOnly)).bothSourcesWithinOneExecution())
    }

    @Test
    fun `reruns of one test count as separate executions`() {
        val first = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        val rerun = first.copy(runId = "second-run", occurrenceId = "B")
        assertEquals(CoverageSupport(2, 2, 1, 1, 1, 1), measurement(listOf(first, rerun)).bothSourcesOnOneOccurrence())
    }

    @Test
    fun `schema support counts schemas instead of batches`() {
        val firstBatch = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        val sameSchemaInAnotherBatch =
            firstBatch.copy(
                batchId = "another-batch",
                executionId = "execution-2",
                occurrenceId = "B",
            )
        assertEquals(1, measurement(listOf(firstBatch, sameSchemaInAnotherBatch)).bothSourcesOnOneOccurrence().schemas)

        val anotherSchema =
            sameSchemaInAnotherBatch.copy(
                schemaId = "another-schema",
                executionId = "execution-3",
                occurrenceId = "C",
            )
        assertEquals(2, measurement(listOf(firstBatch, sameSchemaInAnotherBatch, anotherSchema)).bothSourcesOnOneOccurrence().schemas)
    }

    @Test
    fun `only invoked occurrences support the initial metrics`() {
        val invoked = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        val sourceSupplied = invoked.copy(occurrenceId = "B", resolutionRole = ResolutionRole.SOURCE_SUPPLIED)
        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), measurement(listOf(invoked, sourceSupplied)).bothSourcesOnOneOccurrence())
    }

    @Test
    fun `missing higher order obligation has zero support`() {
        val obligation = listAndBothSourcesAndError()
        assertEquals(CoverageSupport(0, 0, 0, 0, 0, 0), measurement(higherOrderRows()).support(obligation))
    }

    @Test
    fun `higher order obligation counts a matching occurrence`() {
        val matching = row("execution", "D", ARGUMENT_AND_OBJECT_FIELD).copy(
            listAncestorCount = 1,
            outputKind = OutputKind.ERROR,
        )
        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), measurement(listOf(matching)).support(listAndBothSourcesAndError()))
    }

    @Test
    fun `empty measurement has zero support`() {
        assertEquals(CoverageSupport(0, 0, 0, 0, 0, 0), ResolverCoverageMeasurement(emptyList(), emptyList()).bothSourcesOnOneOccurrence())
    }

    @Test
    fun `fan out score uses distinct recorded consumer occurrences`() {
        val provider = row("E1", "P", emptySet(), coordinate = "Source.value")
        val firstConsumer = row("E1", "C1", emptySet(), coordinate = "Item.first")
        val secondConsumer = row("E1", "C2", emptySet(), coordinate = "Item.second")
        val edges = listOf(
            dependency(provider.execution, "P", "Source.value", "C1", "Item.first", "one"),
            dependency(provider.execution, "P", "Source.value", "C1", "Item.first", "sameConsumerAgain"),
            dependency(provider.execution, "P", "Source.value", "C2", "Item.second", "two"),
        )

        val scored = measurement(listOf(provider, firstConsumer, secondConsumer), edges)

        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), scored.sharedProviderFanOut())
        assertEquals(CoverageSupport(0, 0, 0, 0, 0, 0), scored.multipleProviderFanIn())
    }

    @Test
    fun `fan in counts distinct provider cells even when their field coordinate matches`() {
        val firstProvider = row("E1", "P1", emptySet(), coordinate = "Source.value")
        val secondProvider = row("E1", "P2", emptySet(), coordinate = "Source.value")
        val consumer = row("E1", "C", emptySet(), coordinate = "Item.computed")
        val edges = listOf(
            dependency(firstProvider.execution, "P1", "Source.value", "C", "Item.computed", "first"),
            dependency(firstProvider.execution, "P1", "Source.value", "C", "Item.computed", "firstAgain"),
            dependency(firstProvider.execution, "P2", "Source.value", "C", "Item.computed", "second"),
        )

        val scored = measurement(listOf(firstProvider, secondProvider, consumer), edges)

        assertEquals(CoverageSupport(0, 0, 0, 0, 0, 0), scored.sharedProviderFanOut())
        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), scored.multipleProviderFanIn())
    }

    @Test
    fun `coexisting occurrences without dependency records score neither relationship`() {
        val provider = row("E1", "P", emptySet(), coordinate = "Source.value")
        val firstConsumer = row("E1", "C1", emptySet(), coordinate = "Item.first")
        val secondConsumer = row("E1", "C2", emptySet(), coordinate = "Item.second")
        val scored = measurement(listOf(provider, firstConsumer, secondConsumer))

        assertEquals(CoverageSupport(0, 0, 0, 0, 0, 0), scored.sharedProviderFanOut())
        assertEquals(CoverageSupport(0, 0, 0, 0, 0, 0), scored.multipleProviderFanIn())
    }

    @Test
    fun `success and failure score only when invoked branches share an execution`() {
        val failed = row("E1", "failed", emptySet(), coordinate = "Item.failed").copy(outputKind = OutputKind.ERROR)
        val succeeded = row("E1", "succeeded", emptySet(), coordinate = "Item.succeeded")
        val isolatedFailure = row("E2", "isolatedFailure", emptySet(), coordinate = "Item.failed").copy(outputKind = OutputKind.ERROR)
        val notInvokedSuccess = succeeded.copy(
            executionId = "E2",
            queryId = "E2",
            occurrenceId = "notInvokedSuccess",
            resolutionRole = ResolutionRole.SOURCE_SUPPLIED,
        )

        assertEquals(
            CoverageSupport(2, 1, 1, 1, 1, 1),
            measurement(listOf(failed, succeeded, isolatedFailure, notInvokedSuccess))
                .successfulAndFailingBranchesWithinOneExecution(),
        )
    }

    @Test
    fun `scores count batches separately and repeated executions do not inflate test support`() {
        val firstProvider = row("E1", "P1", emptySet(), coordinate = "Source.value")
        val firstConsumer = row("E1", "C1", emptySet(), coordinate = "Item.first")
        val secondConsumer = row("E1", "C2", emptySet(), coordinate = "Item.second")
        val repeatedProvider = firstProvider.copy(runId = "run-2")
        val repeatedFirstConsumer = firstConsumer.copy(runId = "run-2")
        val repeatedSecondConsumer = secondConsumer.copy(runId = "run-2")
        val firstRunEdges = listOf(
            dependency(firstProvider.execution, "P1", "Source.value", "C1", "Item.first", "one"),
            dependency(firstProvider.execution, "P1", "Source.value", "C2", "Item.second", "two"),
        )
        val repeatedRunEdges = firstRunEdges.map { it.copy(execution = repeatedProvider.execution) }
        val sameTestSupport = measurement(
            listOf(firstProvider, firstConsumer, secondConsumer, repeatedProvider, repeatedFirstConsumer, repeatedSecondConsumer),
            firstRunEdges + repeatedRunEdges,
        ).sharedProviderFanOut()
        assertEquals(CoverageSupport(2, 2, 1, 1, 1, 1), sameTestSupport)

        val secondBatchRows = listOf(
            firstProvider.copy(batchId = "batch-2", executionId = "E2", queryId = "E2", occurrenceId = "P2"),
            firstConsumer.copy(batchId = "batch-2", executionId = "E2", queryId = "E2", occurrenceId = "C3"),
            secondConsumer.copy(batchId = "batch-2", executionId = "E2", queryId = "E2", occurrenceId = "C4"),
        )
        val secondBatchExecution = secondBatchRows.first().execution
        val secondBatchEdges = listOf(
            dependency(secondBatchExecution, "P2", "Source.value", "C3", "Item.first", "one"),
            dependency(secondBatchExecution, "P2", "Source.value", "C4", "Item.second", "two"),
        )
        val acrossBatches = measurement(
            listOf(firstProvider, firstConsumer, secondConsumer) + secondBatchRows,
            firstRunEdges + secondBatchEdges,
        ).sharedProviderFanOut()
        assertEquals(CoverageSupport(2, 2, 2, 2, 2, 1), acrossBatches)
    }

    @Test
    fun `measurement rejects rows without a completed execution`() {
        val first = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        assertThrows(IllegalArgumentException::class.java) { ResolverCoverageMeasurement(emptyList(), listOf(first)) }
    }

    @Test
    fun `measurement rejects duplicate occurrence IDs`() {
        val first = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        assertThrows(IllegalArgumentException::class.java) { measurement(listOf(first, first)) }
    }

    @Test
    fun `measurement rejects dependency endpoints that do not match recorded occurrences`() {
        val provider = row("E1", "P", emptySet(), coordinate = "Source.value")
        val consumer = row("E1", "C", emptySet(), coordinate = "Item.computed")
        val missingEndpoint = dependency(provider.execution, "P", "Source.value", "missing", "Item.computed", "value")

        assertThrows(IllegalArgumentException::class.java) {
            measurement(listOf(provider, consumer), listOf(missingEndpoint))
        }
    }

    @Test
    fun `measurement rejects duplicate execution IDs within one run`() {
        val first = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        assertThrows(IllegalArgumentException::class.java) {
            ResolverCoverageMeasurement(listOf(first.execution, first.execution), emptyList())
        }
    }

    @Test
    fun `measurement rejects an unsupported model version`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResolverCoverageMeasurement(emptyList(), emptyList(), modelVersion = RESOLVER_COVERAGE_MODEL_VERSION + 1)
        }
    }

    @Test
    fun `CSV round trip preserves every row classification`() {
        val row = row("execution", "A", ALL_BINDING_SOURCES).copy(
            resultPath = "[{\"argument\":\"comma, quote \\\" and newline\n\"}]",
            listAncestorCount = 3,
            maxProviderPathDepth = 2,
            argumentState = ArgumentState.CONTAINS_NULL,
            declaredOutputShape = DeclaredOutputShape.LIST,
            outputKind = OutputKind.ERROR,
        )
        assertEquals(listOf(row), ResolverCoverageCsv.read(writeCsv(listOf(row)).reader()))
    }

    @Test
    fun `CSV round trip handles no rows`() {
        assertEquals(emptyList<ResolverCoverageRow>(), ResolverCoverageCsv.read(writeCsv(emptyList()).reader()))
    }

    @Test
    fun `CSV reader rejects an unexpected header`() {
        assertThrows(IllegalArgumentException::class.java) { ResolverCoverageCsv.read("wrong,header\n".reader()) }
    }

    private fun workedExampleRows(): List<ResolverCoverageRow> =
        listOf(
            row("E1", "A", ARGUMENT_ONLY),
            row("E1", "B", OBJECT_FIELD_ONLY),
            row("E2", "C", ARGUMENT_AND_OBJECT_FIELD),
            row("E2", "D", ARGUMENT_AND_OBJECT_FIELD),
            row("E3", "E", emptySet()),
        )

    private fun higherOrderRows(): List<ResolverCoverageRow> =
        listOf(
            row("E1", "A", ARGUMENT_AND_OBJECT_FIELD).copy(listAncestorCount = 1),
            row("E1", "B", ARGUMENT_AND_OBJECT_FIELD).copy(outputKind = OutputKind.ERROR),
            row("E1", "C", emptySet()).copy(listAncestorCount = 1, outputKind = OutputKind.ERROR),
        )

    private fun listAndBothSourcesAndError() =
        OccurrenceCoverageObligation("list-both-error") {
            it.listAncestorCount > 0 &&
                it.bindingSources.containsAll(ARGUMENT_AND_OBJECT_FIELD) &&
                it.outputKind == OutputKind.ERROR
        }

    private fun measurement(
        rows: List<ResolverCoverageRow>,
        dependencies: List<ResolverDependencyRow> = emptyList(),
    ): ResolverCoverageMeasurement =
        ResolverCoverageMeasurement(
            executions = rows.map { it.execution }.distinct(),
            rows = rows,
            dependencies = dependencies,
        )

    private fun writeCsv(rows: List<ResolverCoverageRow>): String = StringBuilder().also { ResolverCoverageCsv.write(rows, it) }.toString()
}

private fun row(
    execution: String,
    occurrence: String,
    sources: Set<ResolverBindingSource>,
    coordinate: String = "Item.computed",
): ResolverCoverageRow =
    ResolverCoverageRow(
        runId = "run",
        executionId = execution,
        batchId = "batch",
        schemaId = "schema",
        registryId = "registry",
        queryId = execution,
        occurrenceId = occurrence,
        resultPath = "[]",
        fieldCoordinate = coordinate,
        resolutionRole = ResolutionRole.INVOKED,
        locus = ResolverLocus.OTHER_OBJECT,
        stampClass = StampClass.VARIABLE_FREE,
        listAncestorCount = 0,
        bindingSources = sources,
        maxProviderPathDepth = null,
        argumentState = ArgumentState.NONE,
        declaredOutputShape = DeclaredOutputShape.SCALAR,
        outputKind = OutputKind.SCALAR,
    )

private val ARGUMENT_ONLY = setOf(ResolverBindingSource.ARGUMENT)
private val OBJECT_FIELD_ONLY = setOf(ResolverBindingSource.OBJECT_FIELD)
private val ARGUMENT_AND_OBJECT_FIELD = setOf(ResolverBindingSource.ARGUMENT, ResolverBindingSource.OBJECT_FIELD)
private val ALL_BINDING_SOURCES = ResolverBindingSource.entries.toSet()

private fun dependency(
    execution: CoverageExecution,
    providerId: String,
    providerCoordinate: String,
    consumerId: String,
    consumerCoordinate: String,
    variableName: String,
): ResolverDependencyRow =
    ResolverDependencyRow(
        execution = execution,
        provider = ResolverDependencyOccurrence(providerId, providerCoordinate),
        providerSource = ResolverBindingSource.OBJECT_FIELD,
        providerOutcome = ProviderCellOutcome.VALUE,
        consumer = ResolverDependencyOccurrence(consumerId, consumerCoordinate),
        variableName = variableName,
    )
