@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createDirectories
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.ArbitraryQuery
import viaduct.engine.runtime2.arbitrary.ArbitraryRegistry
import viaduct.engine.runtime2.arbitrary.ArbitrarySchema
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.ResolutionWitnessBoundExceededException
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkCorpus
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkQueryCorpus
import viaduct.engine.runtime2.arbitrary.ResolverTestCase
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.checkResolverTestCases
import viaduct.engine.runtime2.arbitrary.encodeResolverBenchmarkCorpus
import viaduct.engine.runtime2.arbitrary.resolverBenchmarkCorpusSearchConfig
import viaduct.engine.runtime2.arbitrary.resolverBenchmarkOverheadQueryConfig
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.ResolutionDispatcherFactory
import viaduct.engine.runtime2.resolution.configuredResolutionThreadCount
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.CheckerObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.resolve

object ResolverBenchmarkCorpusSearch {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 5) {
            "Expected arguments: <output-directory> <seed> <schemas:registries:queries> " +
                "<benchmark-query-count> <benchmark-query-seed>"
        }
        val outputDirectory = Path.of(arguments[0])
        val seed = arguments[1].toLong()
        val counts = parseCounts(arguments[2])
        val benchmarkQueryCount = arguments[3].toInt()
        val benchmarkQuerySeed = arguments[4].toLong()
        val winner =
            ResolutionDispatcherFactory.create(configuredResolutionThreadCount()).use { dispatcher ->
                runBlocking {
                    search(seed, counts, dispatcher)
                }
            }
        outputDirectory.createDirectories()
        Files.writeString(outputDirectory.resolve("schema.graphqls"), winner.schema.sdl)
        val registryJson =
            winner.registry.encodeResolverBenchmarkCorpus(
                schema = winner.schema,
                metrics = winner.metrics(seed, counts),
            )
        GZIPOutputStream(Files.newOutputStream(outputDirectory.resolve("registry.json.gz"))).use { output ->
            output.writer().use { writer -> writer.write(registryJson) }
        }
        val querySources =
            ResolverBenchmarkCorpus
                .decode(
                    schemaSDL = winner.schema.sdl,
                    registryJson = registryJson,
                ).generateQueries(
                    count = benchmarkQueryCount,
                    config = resolverBenchmarkOverheadQueryConfig(),
                    seed = benchmarkQuerySeed,
                ).map { query -> query.source }
        Files.writeString(
            outputDirectory.resolve("queries.json"),
            ResolverBenchmarkQueryCorpus
                .create(
                    generationSeed = benchmarkQuerySeed,
                    querySources = querySources,
                ).encode(),
        )
        println(winner.summary(seed, counts))
        println(
            "Wrote resolver benchmark corpus and $benchmarkQueryCount queries to " +
                outputDirectory,
        )
    }

    private suspend fun search(
        seed: Long,
        counts: TestCaseCount,
        resolverCoroutineContext: CoroutineContext,
    ): Candidate {
        val candidates = linkedMapOf<Pair<Int, Int>, Candidate>()
        checkResolverTestCases(
            counts = counts,
            config = resolverBenchmarkCorpusSearchConfig(),
            profile = "resolver-benchmark-corpus-search",
            seed = seed,
            fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_SUCCESS,
            typeCheckerMode = GeneratedTypeCheckerMode.RUNTIME_SUCCESS,
        ) { testWorld, testCase ->
            val coordinates = requireNotNull(testCase.coordinates)
            val key = coordinates.schemaIndex to coordinates.registryIndex
            val candidate =
                candidates.getOrPut(key) {
                    Candidate(testCase.schema, testCase.registry)
                }
            if (!candidate.disqualified) {
                try {
                    candidate.observe(
                        testWorld,
                        testCase,
                        resolverCoroutineContext,
                    )
                } catch (_: TimeoutCancellationException) {
                    candidate.disqualified = true
                } catch (_: ResolutionWitnessBoundExceededException) {
                    candidate.disqualified = true
                }
            }
        }
        require(candidates.isNotEmpty()) {
            "Resolver benchmark corpus search produced no candidates"
        }
        val shapeEligible = candidates.values.filter(Candidate::meetsRegistryShapeTargets)
        val workloadEligible = shapeEligible.filter(Candidate::meetsWorkloadTargets)
        return (
            workloadEligible.ifEmpty {
                shapeEligible.ifEmpty { candidates.values }
            }
        ).maxBy(Candidate::score)
    }

    private fun Candidate.observe(
        testWorld: TestWorld,
        testCase: ResolverTestCase,
        resolverCoroutineContext: CoroutineContext,
    ) {
        val world = testWorld.newAssumptions(selectiveResolvers = true)
        val fragment: Fragment = testWorld.schemas.fragmentFrom(testCase.query.source)
        registry.clearResolutionWitness()
        val applicationObservations =
            Collections.synchronizedList(
                mutableListOf<ResolverInvocationObservation>(),
            )
        val witnessObserver = registry.resolverObserver()
        val checkerKinds =
            Collections.synchronizedList(
                mutableListOf<CheckerKind>(),
            )
        val observer = object : viaduct.engine.runtime2.resolution.framework.ResolverObserver {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                witnessObserver.onResolverInvocation(observation)
                applicationObservations += observation
            }
        }
        val result =
            SharedOperationContext.create(
                world,
                resolverObserver = observer,
                checkerObserver =
                    object : CheckerObserver {
                        override fun onCheckerInvocation(observation: CheckerInvocationObservation) {
                            checkerKinds += observation.checkerKind
                        }
                    },
            ).resolve(
                selections = fragment.subselections,
                coroutineContext = resolverCoroutineContext,
            )
        val witness = registry.resolutionWitness()
        check(applicationObservations.size == witness.applications.size)
        val shape = result.shape()
        queryCount += 1
        totalResultFields += shape.fields
        maximumResultFields = maxOf(maximumResultFields, shape.fields)
        maximumNonListFields = maxOf(maximumNonListFields, shape.nonListFields)
        maximumListDerivedFields =
            maxOf(maximumListDerivedFields, shape.listDerivedFields)
        maximumResultDepth = maxOf(maximumResultDepth, shape.depth)
        maximumQueryDepth = maxOf(maximumQueryDepth, testCase.query.selectionDepth)
        resolverApplications += witness.applications.size
        fieldCheckerApplications += checkerKinds.count { kind -> kind == CheckerKind.FIELD }
        typeCheckerApplications += checkerKinds.count { kind -> kind == CheckerKind.TYPE }
        resolverApplicationsPerQuery += witness.applications.size.toLong()
        val variableBearingApplications =
            applicationObservations.filter { observation ->
                observation.variableArgumentCount > 0
            }
        variableBearingResolverApplicationsPerQuery +=
            variableBearingApplications.size.toLong()
        variableArgumentCounts +=
            variableBearingApplications.map { observation ->
                observation.variableArgumentCount.toLong()
            }
        maximumVariableStackDepth =
            maxOf(
                maximumVariableStackDepth,
                applicationObservations.maximumVariableStackDepth(),
            )
        distinctResolverFields +=
            witness.applications.mapTo(linkedSetOf()) { application ->
                application.key.field
            }
        activatedFromArgumentApplications +=
            witness.applications.count { application ->
                registry.sourceResolverHasFromArgumentVariables(application.key.field)
            }
        activatedFromPathApplications +=
            witness.applications.count { application ->
                registry.sourceResolverHasFromObjectFieldVariables(application.key.field)
            }
        activatedFromQueryPathApplications +=
            witness.applications.count { application ->
                registry.sourceResolverHasFromQueryFieldVariables(application.key.field)
            }
        activatedFromProviderApplications +=
            witness.applications.count { application ->
                registry.sourceResolverHasFromProviderVariables(application.key.field)
            }
        observeQueryFeatures(testCase.query)
    }

    private fun Candidate.observeQueryFeatures(query: ArbitraryQuery) {
        if (query.features.hasAliases) queriesWithAliases += 1
        if (query.features.hasDuplicateSelections) queriesWithDuplicates += 1
        if (query.features.hasDistinctArgumentSelections) {
            queriesWithDistinctArguments += 1
        }
        if (query.features.hasExactKeyAliasConvergence) {
            queriesWithAliasConvergence += 1
        }
    }

    private fun EngineResult?.shape(
        depth: Int = 0,
        beneathList: Boolean = false,
    ): ResultShape =
        when (this) {
            null, is ErrorEngineResult -> ResultShape()
            is ListEngineResult ->
                indices
                    .map { index ->
                        get(index).value.get().shape(depth, beneathList = true)
                    }
                    .fold(ResultShape(), ResultShape::plus)
            is ObjectEngineResult -> {
                val childShapes =
                    keys.map { key ->
                        val value = getCell(key).value.get()
                        val child =
                            if (key is ObjectEngineResult.ParentKey) {
                                ResultShape()
                            } else {
                                value.shape(depth + 1, beneathList)
                            }
                        child.copy(
                            fields = child.fields + 1,
                            nonListFields =
                                child.nonListFields +
                                    if (beneathList) 0 else 1,
                            listDerivedFields =
                                child.listDerivedFields +
                                    if (beneathList) 1 else 0,
                            depth = maxOf(child.depth, depth + 1),
                        )
                    }
                childShapes.fold(ResultShape(), ResultShape::plus)
            }
            else -> ResultShape()
        }

    private fun parseCounts(value: String): TestCaseCount {
        val dimensions = value.split(':').map(String::toInt)
        require(dimensions.size == 3 && dimensions.all { dimension -> dimension > 0 }) {
            "Corpus search size must have positive S:R:Q form: $value"
        }
        return TestCaseCount(
            schemas = dimensions[0],
            registriesPerSchema = dimensions[1],
            queriesPerSchema = dimensions[2],
        )
    }

    private data class ResultShape(
        val fields: Long = 0,
        val nonListFields: Long = 0,
        val listDerivedFields: Long = 0,
        val depth: Int = 0,
    ) {
        operator fun plus(other: ResultShape): ResultShape =
            ResultShape(
                fields = fields + other.fields,
                nonListFields = nonListFields + other.nonListFields,
                listDerivedFields =
                    listDerivedFields + other.listDerivedFields,
                depth = maxOf(depth, other.depth),
            )
    }

    private fun List<ResolverInvocationObservation>.maximumVariableStackDepth(): Long {
        val executedOccurrences =
            mapTo(linkedSetOf()) { observation -> observation.resolverOccurrenceId }
        val childrenBySource =
            buildMap<ResolverOccurrenceId, MutableSet<ResolverOccurrenceId>> {
                this@maximumVariableStackDepth.forEach { observation ->
                    observation.variableResolverOccurrenceIds
                        .filter(executedOccurrences::contains)
                        .forEach { sourceId ->
                            getOrPut(sourceId) { linkedSetOf() }
                                .add(observation.resolverOccurrenceId)
                        }
                }
            }
        val depthByOccurrence = mutableMapOf<ResolverOccurrenceId, Long>()
        val visiting = mutableSetOf<ResolverOccurrenceId>()

        fun depth(identity: ResolverOccurrenceId): Long {
            depthByOccurrence[identity]?.let { return it }
            check(visiting.add(identity)) {
                "Variable resolver dependency cycle at $identity"
            }
            val depth =
                childrenBySource[identity]
                    .orEmpty()
                    .maxOfOrNull { child -> 1L + depth(child) }
                    ?: 0
            visiting.remove(identity)
            depthByOccurrence[identity] = depth
            return depth
        }
        return executedOccurrences.maxOfOrNull(::depth) ?: 0
    }

    private class Candidate(
        val schema: ArbitrarySchema,
        val registry: ArbitraryRegistry,
    ) {
        var disqualified: Boolean = false
        private val objectFragmentSelectionCounts =
            registry.objectFragmentSelectionCounts().map(Int::toLong)
        private val objectFragmentDepths =
            registry.objectFragmentDepths().map(Int::toLong)
        private val activeFieldCount = registry.fieldResolverCoordinates.size.toLong()
        private val passiveFieldCount =
            (schema.sourceFieldCoordinates.size - registry.fieldResolverCoordinates.size)
                .toLong()
        private val passiveFieldsPerActiveFieldTimes100 =
            if (activeFieldCount == 0L) 0 else passiveFieldCount * 100 / activeFieldCount
        private val averageActiveFieldsPerObjectTimes100 =
            (
                schema.sourceFieldCoordinatesByObject.values
                    .map { fields -> fields.count(registry.fieldResolverCoordinates::contains) }
                    .average() * 100
            ).toLong()
        private val averagePassiveFieldsPerObjectTimes100 =
            (
                schema.sourceFieldCoordinatesByObject.values
                    .map { fields -> fields.count { field -> field !in registry.fieldResolverCoordinates } }
                    .average() * 100
            ).toLong()
        private val averageObjectFragmentSelectionsTimes100 =
            (objectFragmentSelectionCounts.average() * 100).toLong()
        private val p90ObjectFragmentSelections =
            objectFragmentSelectionCounts.percentile(0.9)
        private val maximumObjectFragmentSelections =
            objectFragmentSelectionCounts.maxOrNull() ?: 0

        fun meetsRegistryShapeTargets(): Boolean =
            !disqualified &&
                passiveFieldsPerActiveFieldTimes100 in 400..700 &&
                averageActiveFieldsPerObjectTimes100 in 150..250 &&
                averagePassiveFieldsPerObjectTimes100 in 1_200..1_600 &&
                averageObjectFragmentSelectionsTimes100 in 350..500 &&
                p90ObjectFragmentSelections >= 10 &&
                maximumObjectFragmentSelections >= 30 &&
                registry.features.fromArgumentVariableCount > 0 &&
                registry.features.fromObjectFieldVariableCount > 0 &&
                registry.features.fromQueryFieldVariableCount > 0 &&
                registry.features.fromProviderVariableCount > 0 &&
                registry.features.queryFragmentCount > 0

        var queryCount: Int = 0
        var totalResultFields: Long = 0
        var maximumResultFields: Long = 0
        var maximumNonListFields: Long = 0
        var maximumListDerivedFields: Long = 0
        var maximumResultDepth: Int = 0
        var maximumQueryDepth: Int = 0
        var resolverApplications: Int = 0
        var fieldCheckerApplications: Int = 0
        var typeCheckerApplications: Int = 0
        val resolverApplicationsPerQuery: MutableList<Long> = mutableListOf()
        val variableBearingResolverApplicationsPerQuery: MutableList<Long> =
            mutableListOf()
        val variableArgumentCounts: MutableList<Long> = mutableListOf()
        var maximumVariableStackDepth: Long = 0
        var activatedFromArgumentApplications: Int = 0
        var activatedFromPathApplications: Int = 0
        var activatedFromQueryPathApplications: Int = 0
        var activatedFromProviderApplications: Int = 0
        var queriesWithAliases: Int = 0
        var queriesWithDuplicates: Int = 0
        var queriesWithDistinctArguments: Int = 0
        var queriesWithAliasConvergence: Int = 0
        val distinctResolverFields: MutableSet<FieldCoordinate> = linkedSetOf()

        fun meetsWorkloadTargets(): Boolean =
            queryCount > 0 &&
                totalResultFields / queryCount >= 300 &&
                resolverApplicationsPerQuery.average() >= 100 &&
                activatedFromArgumentApplications > 0 &&
                activatedFromPathApplications > 0 &&
                activatedFromQueryPathApplications > 0 &&
                activatedFromProviderApplications > 0 &&
                fieldCheckerApplications > 0 &&
                typeCheckerApplications > 0 &&
                maximumVariableStackDepth > 0

        fun score(): Long {
            if (disqualified) return Long.MIN_VALUE
            val averageResultFields =
                if (queryCount == 0) 0 else totalResultFields / queryCount
            val averageResolverApplications = resolverApplicationsPerQuery.average().toLong()
            val medianVariableBearingApplications =
                variableBearingResolverApplicationsPerQuery.percentile(0.5)
            val workloadScore =
                closeness(averageResultFields, target = 1_000, radius = 2_000) * 100_000L +
                    closeness(maximumResultFields, target = 2_500, radius = 10_000) * 10_000L +
                    closeness(maximumNonListFields, target = 75, radius = 500) * 10_000L +
                    closeness(
                        averageResolverApplications,
                        target = 300,
                        radius = 1_000,
                    ) * 500_000L
            val variableWorkloadScore =
                medianVariableBearingApplications.coerceAtMost(10) * 40_000_000L +
                    (medianVariableBearingApplications - 10)
                        .coerceAtLeast(0) * 100_000L +
                    maximumVariableStackDepth * 100_000_000L +
                    (variableArgumentCounts.maxOrNull() ?: 0) * 1_000_000L
            val registryShapeScore =
                if (meetsRegistryShapeTargets()) {
                    0
                } else {
                    closeness(
                        passiveFieldsPerActiveFieldTimes100,
                        target = 500,
                        radius = 700,
                    ) * 2_000_000L +
                        closeness(
                            averageActiveFieldsPerObjectTimes100,
                            target = 200,
                            radius = 200,
                        ) * 1_000_000L +
                        closeness(
                            averagePassiveFieldsPerObjectTimes100,
                            target = 1_400,
                            radius = 1_000,
                        ) * 500_000L +
                        closeness(
                            averageObjectFragmentSelectionsTimes100,
                            target = 400,
                            radius = 400,
                        ) * 2_000_000L +
                        p90ObjectFragmentSelections.coerceAtMost(10) * 30_000_000L +
                        (p90ObjectFragmentSelections - 10).coerceAtLeast(0) * 1_000_000L +
                        closeness(
                            maximumObjectFragmentSelections,
                            target = 35,
                            radius = 35,
                        ) * 10_000_000L
                }
            val featureScore =
                registry.features.fromArgumentVariableCount * 50L +
                    registry.features.fromObjectFieldVariableCount * 100L +
                    registry.features.maximumFromObjectFieldPathLength * 500L +
                    registry.features.maximumFromObjectFieldVariableUseDepth * 500L +
                    registry.features.maximumVariablesPerOwner * 1_000L +
                    registry.fromObjectFieldVariableOwnerDependencies.size * 2_000L +
                    registry.nodeResolverTypes.size * 500L +
                    distinctResolverFields.size * 100L +
                    activatedFromArgumentApplications +
                    activatedFromPathApplications * 2L +
                    activatedFromQueryPathApplications * 2L +
                    activatedFromProviderApplications
            val diversityScore =
                queriesWithAliases * 10L +
                    queriesWithDuplicates * 10L +
                    queriesWithDistinctArguments * 20L +
                    queriesWithAliasConvergence * 20L
            return workloadScore +
                variableWorkloadScore +
                registryShapeScore +
                maximumQueryDepth * 100_000L +
                featureScore +
                diversityScore
        }

        fun metrics(
            seed: Long,
            counts: TestCaseCount,
        ): Map<String, Long> =
            sortedMapOf(
                "searchSeed" to seed,
                "searchSchemas" to counts.schemas.toLong(),
                "searchRegistriesPerSchema" to counts.registriesPerSchema.toLong(),
                "sampledQueries" to queryCount.toLong(),
                "score" to score(),
                "averageResultFields" to
                    if (queryCount == 0) 0 else totalResultFields / queryCount,
                "maximumResultFields" to maximumResultFields,
                "maximumNonListFields" to maximumNonListFields,
                "maximumListDerivedFields" to maximumListDerivedFields,
                "maximumResultDepth" to maximumResultDepth.toLong(),
                "maximumQueryDepth" to maximumQueryDepth.toLong(),
                "resolverApplications" to resolverApplications.toLong(),
                "fieldCheckerApplications" to fieldCheckerApplications.toLong(),
                "typeCheckerApplications" to typeCheckerApplications.toLong(),
                "averageResolverApplications" to
                    resolverApplicationsPerQuery.average().toLong(),
                "medianVariableBearingResolverApplications" to
                    variableBearingResolverApplicationsPerQuery.percentile(0.5),
                "maximumVariableBearingResolverApplications" to
                    (variableBearingResolverApplicationsPerQuery.maxOrNull() ?: 0),
                "averageVariableArgumentsPerVariableBearingApplicationTimes100" to
                    (
                        if (variableArgumentCounts.isEmpty()) {
                            0
                        } else {
                            (variableArgumentCounts.average() * 100).toLong()
                        }
                    ),
                "maximumVariableArgumentsPerVariableBearingApplication" to
                    (variableArgumentCounts.maxOrNull() ?: 0),
                "maximumVariableStackDepth" to maximumVariableStackDepth,
                "distinctResolverFields" to distinctResolverFields.size.toLong(),
                "activatedFromArgumentApplications" to
                    activatedFromArgumentApplications.toLong(),
                "activatedFromPathApplications" to
                    activatedFromPathApplications.toLong(),
                "activatedFromQueryPathApplications" to
                    activatedFromQueryPathApplications.toLong(),
                "activatedFromProviderApplications" to
                    activatedFromProviderApplications.toLong(),
                "fromArgumentVariables" to
                    registry.features.fromArgumentVariableCount.toLong(),
                "fromPathVariables" to
                    registry.features.fromObjectFieldVariableCount.toLong(),
                "fromQueryPathVariables" to
                    registry.features.fromQueryFieldVariableCount.toLong(),
                "fromProviderVariables" to
                    registry.features.fromProviderVariableCount.toLong(),
                "queryFragments" to registry.features.queryFragmentCount.toLong(),
                "maximumVariablesPerOwner" to
                    registry.features.maximumVariablesPerOwner.toLong(),
                "maximumProviderPathLength" to
                    registry.features.maximumFromObjectFieldPathLength.toLong(),
                "maximumVariableUseDepth" to
                    registry.features.maximumFromObjectFieldVariableUseDepth.toLong(),
                "ownerDependencies" to
                    registry.fromObjectFieldVariableOwnerDependencies.size.toLong(),
                "activeSchemaFields" to activeFieldCount,
                "passiveSchemaFields" to passiveFieldCount,
                "passiveFieldsPerActiveFieldTimes100" to
                    passiveFieldsPerActiveFieldTimes100,
                "averageActiveFieldsPerObjectTimes100" to
                    averageActiveFieldsPerObjectTimes100,
                "averagePassiveFieldsPerObjectTimes100" to
                    averagePassiveFieldsPerObjectTimes100,
                "objectFragmentSelections" to objectFragmentSelectionCounts.sum(),
                "averageObjectFragmentSelectionsTimes100" to
                    averageObjectFragmentSelectionsTimes100,
                "p90ObjectFragmentSelections" to
                    p90ObjectFragmentSelections,
                "maximumObjectFragmentSelections" to
                    maximumObjectFragmentSelections,
                "averageObjectFragmentDepthTimes100" to
                    (objectFragmentDepths.average() * 100).toLong(),
                "p90ObjectFragmentDepth" to objectFragmentDepths.percentile(0.9),
                "maximumObjectFragmentDepth" to
                    (objectFragmentDepths.maxOrNull() ?: 0),
            )

        private fun closeness(
            value: Long,
            target: Long,
            radius: Long,
        ): Long = (radius - abs(value - target)).coerceAtLeast(0)

        private fun List<Long>.percentile(percentile: Double): Long {
            if (isEmpty()) return 0
            val sorted = sorted()
            val index =
                ceil(sorted.size * percentile)
                    .toInt()
                    .coerceAtLeast(1) - 1
            return sorted[index]
        }

        fun summary(
            seed: Long,
            counts: TestCaseCount,
        ): String =
            "Resolver benchmark corpus winner: " +
                metrics(seed, counts).entries.joinToString { (name, value) -> "$name=$value" }
    }
}
