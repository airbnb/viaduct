@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import io.kotest.property.PropertyTesting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.ArbitraryRegistry
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.arbitrary.ResolverTestCase
import viaduct.engine.runtime2.arbitrary.ResolverTestRun
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.checkResolverTestCases
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.nodeReferenceIdentityOrNull
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.propertytest.checkResolverTestSuite
import viaduct.engine.runtime2.propertytest.deepResolverStressConfig
import viaduct.engine.runtime2.schema.SourceSchemaAdapter
import viaduct.graphql.schema.ViaductSchema

/** Opt-in high-volume coverage for deep dependency-heavy generated worlds. */
interface DeepResolverStressContract : ResolverContract {
    val resolverName: String
    val objectPathVariablesEnabled: Boolean
        get() = false
    val queryPathVariablesEnabled: Boolean
        get() = false
    val nodeResolversEnabled: Boolean
        get() = true
    val mixedVariableCoverageRequired: Boolean
        get() = false
    val nestedObjectPathCoverageRequired: Boolean
        get() = false
    val minimumActivatedObjectPathResolverChainLength: Int
        get() = 0
    val sometimesPassiveCoverageRequired: Boolean
        get() = false
    val rootFieldReferenceCoverageRequired: Boolean
        get() = false
    val queryFragmentCoverageRequired: Boolean
        get() = false
    val minimumDemandedQueryOERDepth: Int
        get() = 0
    val stressConfigOverrides: Config
        get() = Config.default

    @Test
    fun `deep dependency-heavy arbitrary worlds resolve correctly`(): Unit =
        runBlocking {
            val config =
                deepResolverStressConfig(
                    queryFragments = queryFragmentCoverageRequired,
                    nodeResolvers = nodeResolversEnabled,
                    objectPathVariables = objectPathVariablesEnabled,
                    queryPathVariables = queryPathVariablesEnabled,
                    overrides = stressConfigOverrides,
                )
            val suite = ResolverTestSuiteDefinition.forProfile("$resolverName-stress", config)
            val configuredCases = configured(stressCasesProperty, stressCasesEnvironment)
            val configuredSeed = configured(stressSeedProperty, stressSeedEnvironment)
            // A configured seed or size explores live; otherwise the stored suite replays with coverage enforced.
            val live = configuredCases != null || configuredSeed != null
            val liveCounts =
                if (live) {
                    val cases =
                        configuredCases?.toIntOrNull()
                            ?: error("$stressCasesProperty/$stressCasesEnvironment must be an integer")
                    require(cases >= MINIMUM_STRESS_CASES && cases % CASES_PER_SCHEMA == 0) {
                        "$stressCasesProperty/$stressCasesEnvironment must be at least " +
                            "$MINIMUM_STRESS_CASES and a multiple of $CASES_PER_SCHEMA"
                    }
                    TestCaseCount(
                        schemas = cases / CASES_PER_SCHEMA,
                        registriesPerSchema = REGISTRIES_PER_SCHEMA,
                        queriesPerSchema = QUERIES_PER_SCHEMA,
                    )
                } else {
                    null
                }
            val seed =
                if (live) {
                    configuredSeed?.toLongOrNull()
                        ?: error("$stressSeedProperty/$stressSeedEnvironment must be a Long")
                } else {
                    suite.seed
                }
            var attemptedCases = 0
            var verifiedCases = 0
            var resolverApplications = 0
            var generatedNodeResolvers = 0
            var nodeLoaderApplications = 0
            var argumentBearingNodeProducerApplications = 0
            var polymorphicNodeLoaderApplications = 0
            var generatedFromArgumentVariables = 0
            var generatedObjectPathVariables = 0
            var generatedQueryPathVariables = 0
            var generatedSometimesPassiveFields = 0
            var generatedRootFieldReferences = 0
            var generatedNodeRootFieldReferences = 0
            var generatedQueryFragments = 0
            var queryFragmentFreeCases = 0
            var activatedFromArgumentApplications = 0
            var activatedObjectPathApplications = 0
            var activatedQueryPathApplications = 0
            var activatedNestedObjectPathApplications = 0
            var activatedSometimesPassiveOccurrences = 0
            var activatedRootFieldReferences = 0
            var activatedNodeRootFieldReferences = 0
            var activatedQueryFragmentApplications = 0
            var casesWithDemandedQueryOERs = 0
            var casesWithoutDemandedQueryOERs = 0
            val demandedQueryOERDepthCounts = linkedMapOf<Int, Int>()
            var maximumDemandedQueryOERDepth = 0
            var maximumActivatedObjectPathResolverChainLength = 0
            var coactivatedMixedVariableCases = 0
            val previousSeed = PropertyTesting.defaultSeed
            PropertyTesting.defaultSeed = seed

            val run: ResolverTestRun
            try {
                run = checkStressCases(suite, liveCounts) { testWorld, testCase ->
                    attemptedCases += 1
                    generatedNodeResolvers += testCase.registry.nodeResolverTypes.size
                    generatedFromArgumentVariables +=
                        testCase.registry.features.fromArgumentVariableCount
                    generatedObjectPathVariables +=
                        testCase.registry.features.fromObjectFieldVariableCount
                    generatedQueryPathVariables +=
                        testCase.registry.features.fromQueryFieldVariableCount
                    generatedSometimesPassiveFields +=
                        testCase.registry.features.sometimesPassiveFieldCount
                    generatedRootFieldReferences +=
                        testCase.registry.features.generatedRootFieldReferenceCount
                    generatedNodeRootFieldReferences +=
                        testCase.registry.features.generatedNodeRootFieldReferenceCount
                    val caseQueryFragmentCount = testCase.registry.features.queryFragmentCount
                    generatedQueryFragments += caseQueryFragmentCount
                    if (caseQueryFragmentCount == 0) queryFragmentFreeCases += 1
                    assertTrue(testCase.query.selectionDepth >= 4)
                    val world = testWorld.newAssumptions()
                    val fragment = testWorld.schemas.fragmentFrom(testCase.query.source)
                    testCase.registry.clearResolutionWitness()
                    val resolution =
                        observeResolution(
                            world,
                            world.objectOf("Query"),
                            fragment.subselections,
                            resolutionObserver = testCase.registry.resolutionObserver(),
                            record = false,
                        )
                    val result = resolution.result
                    val operation = resolution.operation
                    val correctnessObserver =
                        operation.resolutionObserver as CorrectnessResolverObserver
                    val demandedQueryOERs =
                        correctnessObserver
                            .allQueryOERs()
                            .values
                            .filter { queryOER -> queryOER.isDemanded() }
                    val demandedQueryOERDepths =
                        correctnessObserver
                            .allQueryOERDepths()
                            .mapNotNull { (result, depth) ->
                                depth.takeIf {
                                    requireNotNull(correctnessObserver.queryOER(result)).isDemanded()
                                }
                            }
                    if (demandedQueryOERs.isEmpty()) {
                        casesWithoutDemandedQueryOERs += 1
                    } else {
                        casesWithDemandedQueryOERs += 1
                    }
                    if (demandedQueryOERDepths.isNotEmpty()) {
                        demandedQueryOERDepths.forEach { depth ->
                            demandedQueryOERDepthCounts.compute(depth) { _, count ->
                                (count ?: 0) + 1
                            }
                        }
                        maximumDemandedQueryOERDepth =
                            maxOf(
                                maximumDemandedQueryOERDepth,
                                demandedQueryOERDepths.max(),
                            )
                    }
                    if (rootFieldReferenceCoverageRequired) {
                        val references =
                            correctnessObserver.rootFieldReferenceInvocations()
                        activatedRootFieldReferences += references.size
                        activatedNodeRootFieldReferences +=
                            references.count { observation ->
                                observation.reference.nodeReferenceIdentityOrNull() != null
                            }
                    }
                    val witness = testCase.registry.resolutionWitness()
                    val activatedSourceResolvers =
                        witness.applications.mapTo(linkedSetOf()) { application ->
                            testCase.registry.sourceResolverCoordinate(application.key.field)
                        }
                    if (sometimesPassiveCoverageRequired) {
                        // Source-owned registry fields have result occurrences but no application.
                        val resultOccurrenceCounts =
                            result
                                .registeredResolverOccurrenceApplicationKeyCounts(operation)
                                .entries
                                .groupingBy { entry -> entry.key.applicationKey }
                                .fold(0) { count, entry -> count + entry.value }
                        witness.applicationCounts().forEach { (key, count) ->
                            assertTrue(
                                count <= resultOccurrenceCounts.getOrDefault(key, 0),
                                "Observed $count applications of $key but result contains only " +
                                    "${resultOccurrenceCounts.getOrDefault(key, 0)} occurrences",
                            )
                        }
                        activatedSometimesPassiveOccurrences +=
                            resultOccurrenceCounts.values.sum() - witness.applications.size
                    } else {
                        assertEquals(
                            result.registeredResolverApplicationIdentityCounts(operation),
                            witness.applicationIdentityCounts(),
                        )
                    }
                    assertTrue(
                        result.correctResolution(operation, fragment),
                    )
                    resolverApplications += witness.applications.size
                    var activatedFromArgument = false
                    var activatedObjectPath = false
                    witness.applications.forEach { application ->
                        if (
                            testCase.registry
                                .queryFragmentSources[application.key.field]
                                ?.isNotEmpty() == true
                        ) {
                            activatedQueryFragmentApplications += 1
                        }
                        if (
                            testCase.registry.sourceResolverHasFromArgumentVariables(
                                application.key.field,
                            )
                        ) {
                            activatedFromArgumentApplications += 1
                            activatedFromArgument = true
                        }
                        if (
                            testCase.registry.sourceResolverHasFromObjectFieldVariables(
                                application.key.field,
                            )
                        ) {
                            activatedObjectPathApplications += 1
                            activatedObjectPath = true
                        }
                        if (
                            testCase.registry.sourceResolverHasFromQueryFieldVariables(
                                application.key.field,
                            )
                        ) {
                            activatedQueryPathApplications += 1
                        }
                        if (
                            testCase.registry.sourceResolverHasNestedFromObjectFieldVariable(
                                application.key.field,
                            )
                        ) {
                            activatedNestedObjectPathApplications += 1
                        }
                        if (
                            application.key.field.fieldName.endsWith("") &&
                            world.schema
                                .requireField(
                                    application.key.field.typeName,
                                    application.key.field.fieldName,
                                ).args
                                .isNotEmpty()
                        ) {
                            argumentBearingNodeProducerApplications += 1
                        }
                        val possibleTypes =
                            testCase.registry.nodeLoaderPossibleTypes(
                                testCase.schema,
                                application.key.field,
                            )
                        if (possibleTypes.isNotEmpty()) {
                            nodeLoaderApplications += 1
                            if (possibleTypes.size > 1) {
                                polymorphicNodeLoaderApplications += 1
                            }
                        }
                    }
                    if (activatedFromArgument && activatedObjectPath) {
                        coactivatedMixedVariableCases += 1
                    }
                    maximumActivatedObjectPathResolverChainLength =
                        maxOf(
                            maximumActivatedObjectPathResolverChainLength,
                            maximumActivatedResolverChainLength(
                                world = world,
                                registry = testCase.registry,
                                activatedSourceResolvers = activatedSourceResolvers,
                                roots =
                                    testCase.registry
                                        .nestedFromObjectFieldVariableOwnerFields,
                            ),
                        )
                    verifiedCases += 1
                }
            } finally {
                PropertyTesting.defaultSeed = previousSeed
                println(
                    "$displayName stress: seed=$seed, counts=${liveCounts ?: suite.counts}, " +
                        "attemptedCases=$attemptedCases, verifiedCases=$verifiedCases, " +
                        "resolverApplications=$resolverApplications, " +
                        "generatedNodeResolvers=$generatedNodeResolvers, " +
                        "nodeLoaderApplications=$nodeLoaderApplications, " +
                        "argumentBearingNodeProducerApplications=" +
                        "$argumentBearingNodeProducerApplications, " +
                        "polymorphicNodeLoaderApplications=" +
                        "$polymorphicNodeLoaderApplications, " +
                        "generatedFromArgumentVariables=$generatedFromArgumentVariables, " +
                        "generatedObjectPathVariables=$generatedObjectPathVariables, " +
                        "generatedQueryPathVariables=$generatedQueryPathVariables, " +
                        "generatedSometimesPassiveFields=$generatedSometimesPassiveFields, " +
                        "generatedRootFieldReferences=$generatedRootFieldReferences, " +
                        "generatedNodeRootFieldReferences=$generatedNodeRootFieldReferences, " +
                        "generatedQueryFragments=$generatedQueryFragments, " +
                        "queryFragmentFreeCases=$queryFragmentFreeCases, " +
                        "activatedFromArgumentApplications=$activatedFromArgumentApplications, " +
                        "activatedObjectPathApplications=$activatedObjectPathApplications, " +
                        "activatedQueryPathApplications=$activatedQueryPathApplications, " +
                        "activatedNestedObjectPathApplications=" +
                        "$activatedNestedObjectPathApplications, " +
                        "activatedSometimesPassiveOccurrences=" +
                        "$activatedSometimesPassiveOccurrences, " +
                        "activatedRootFieldReferences=$activatedRootFieldReferences, " +
                        "activatedNodeRootFieldReferences=$activatedNodeRootFieldReferences, " +
                        "activatedQueryFragmentApplications=" +
                        "$activatedQueryFragmentApplications, " +
                        "casesWithDemandedQueryOERs=$casesWithDemandedQueryOERs, " +
                        "casesWithoutDemandedQueryOERs=$casesWithoutDemandedQueryOERs, " +
                        "demandedQueryOERDepthCounts=$demandedQueryOERDepthCounts, " +
                        "maximumDemandedQueryOERDepth=$maximumDemandedQueryOERDepth, " +
                        "maximumActivatedObjectPathResolverChainLength=" +
                        "$maximumActivatedObjectPathResolverChainLength, " +
                        "coactivatedMixedVariableCases=$coactivatedMixedVariableCases, " +
                        "minimumDepth=4",
                )
            }

            assertEquals(run.attemptedCases, attemptedCases)
            assertEquals(run.attemptedCases, verifiedCases)
            val requestedCases = run.expectedCases
            run.requireCoverage(
                resolverApplications >= requestedCases,
                "Deep stress coverage: resolverApplications=$resolverApplications, expected >= $requestedCases",
            )
            if (nodeResolversEnabled) {
                run.requireCoverage(
                    generatedNodeResolvers >= requestedCases / 100,
                    "Deep stress coverage: generatedNodeResolvers=$generatedNodeResolvers, expected >= ${requestedCases / 100}",
                )
                run.requireCoverage(
                    nodeLoaderApplications >= requestedCases / 1_000,
                    "Deep stress coverage: nodeLoaderApplications=$nodeLoaderApplications, expected >= ${requestedCases / 1_000}",
                )
                run.requireCoverage(
                    argumentBearingNodeProducerApplications >= requestedCases / 1_000,
                    "Deep stress missed argument-bearing node producer coverage",
                )
            } else {
                assertEquals(0, generatedNodeResolvers)
                assertEquals(0, nodeLoaderApplications)
                assertEquals(0, argumentBearingNodeProducerApplications)
            }
            if (objectPathVariablesEnabled) {
                run.requireCoverage(
                    generatedObjectPathVariables > 0,
                    "Deep stress coverage: generatedObjectPathVariables=$generatedObjectPathVariables, expected > 0",
                )
                run.requireCoverage(
                    activatedObjectPathApplications > 0,
                    "Deep stress coverage: activatedObjectPathApplications=$activatedObjectPathApplications, expected > 0",
                )
            }
            if (queryPathVariablesEnabled) {
                run.requireCoverage(
                    generatedQueryPathVariables > 0,
                    "Deep stress coverage: generatedQueryPathVariables=$generatedQueryPathVariables, expected > 0",
                )
                run.requireCoverage(
                    activatedQueryPathApplications > 0,
                    "Deep stress coverage: activatedQueryPathApplications=$activatedQueryPathApplications, expected > 0",
                )
            }
            if (queryFragmentCoverageRequired) {
                run.requireCoverage(
                    generatedQueryFragments > 0,
                    "Deep stress coverage: generatedQueryFragments=$generatedQueryFragments, expected > 0",
                )
                run.requireCoverage(
                    queryFragmentFreeCases > 0,
                    "Deep stress coverage: queryFragmentFreeCases=$queryFragmentFreeCases, expected > 0",
                )
                run.requireCoverage(
                    activatedQueryFragmentApplications > 0,
                    "Deep stress coverage: activatedQueryFragmentApplications=$activatedQueryFragmentApplications, expected > 0",
                )
                run.requireCoverage(
                    casesWithDemandedQueryOERs > 0,
                    "Deep stress coverage: casesWithDemandedQueryOERs=$casesWithDemandedQueryOERs, expected > 0",
                )
                run.requireCoverage(
                    casesWithoutDemandedQueryOERs > 0,
                    "Deep stress coverage: casesWithoutDemandedQueryOERs=$casesWithoutDemandedQueryOERs, expected > 0",
                )
                if (minimumDemandedQueryOERDepth > 0) {
                    run.requireCoverage(
                        maximumDemandedQueryOERDepth >= minimumDemandedQueryOERDepth,
                        "Expected demanded Query-OER depth of at least " +
                            "$minimumDemandedQueryOERDepth, found $maximumDemandedQueryOERDepth",
                    )
                }
            }
            if (nestedObjectPathCoverageRequired) {
                run.requireCoverage(
                    activatedNestedObjectPathApplications > 0,
                    "Deep stress coverage: activatedNestedObjectPathApplications=$activatedNestedObjectPathApplications, expected > 0",
                )
            }
            if (minimumActivatedObjectPathResolverChainLength > 0) {
                run.requireCoverage(
                    maximumActivatedObjectPathResolverChainLength >=
                        minimumActivatedObjectPathResolverChainLength,
                    "Expected an activated object-path resolver chain of length " +
                        "$minimumActivatedObjectPathResolverChainLength, found " +
                        maximumActivatedObjectPathResolverChainLength,
                )
            }
            if (mixedVariableCoverageRequired) {
                run.requireCoverage(
                    generatedFromArgumentVariables > 0,
                    "Deep stress coverage: generatedFromArgumentVariables=$generatedFromArgumentVariables, expected > 0",
                )
                run.requireCoverage(
                    generatedObjectPathVariables > 0,
                    "Deep stress coverage: generatedObjectPathVariables=$generatedObjectPathVariables, expected > 0",
                )
                run.requireCoverage(
                    activatedFromArgumentApplications > 0,
                    "Deep stress coverage: activatedFromArgumentApplications=$activatedFromArgumentApplications, expected > 0",
                )
                run.requireCoverage(
                    activatedObjectPathApplications > 0,
                    "Deep stress coverage: activatedObjectPathApplications=$activatedObjectPathApplications, expected > 0",
                )
                run.requireCoverage(
                    coactivatedMixedVariableCases > 0,
                    "Deep stress coverage: coactivatedMixedVariableCases=$coactivatedMixedVariableCases, expected > 0",
                )
            }
            if (sometimesPassiveCoverageRequired) {
                run.requireCoverage(
                    generatedSometimesPassiveFields > 0,
                    "Deep stress coverage: generatedSometimesPassiveFields=$generatedSometimesPassiveFields, expected > 0",
                )
                run.requireCoverage(
                    activatedSometimesPassiveOccurrences > 0,
                    "Deep stress coverage: activatedSometimesPassiveOccurrences=$activatedSometimesPassiveOccurrences, expected > 0",
                )
            }
            if (rootFieldReferenceCoverageRequired) {
                run.requireCoverage(
                    generatedRootFieldReferences > 0,
                    "Deep stress coverage: generatedRootFieldReferences=$generatedRootFieldReferences, expected > 0",
                )
                run.requireCoverage(
                    activatedRootFieldReferences > 0,
                    "Deep stress coverage: activatedRootFieldReferences=$activatedRootFieldReferences, expected > 0",
                )
                if (nodeResolversEnabled) {
                    run.requireCoverage(
                        generatedNodeRootFieldReferences > 0,
                        "Deep stress coverage: generatedNodeRootFieldReferences=$generatedNodeRootFieldReferences, expected > 0",
                    )
                    run.requireCoverage(
                        activatedNodeRootFieldReferences > 0,
                        "Deep stress coverage: activatedNodeRootFieldReferences=$activatedNodeRootFieldReferences, expected > 0",
                    )
                }
            }
        }

    /** Returns the longest activated resolver chain beginning at one of [roots]. */
    private fun maximumActivatedResolverChainLength(
        world: Assumptions,
        registry: ArbitraryRegistry,
        activatedSourceResolvers: Set<FieldCoordinate>,
        roots: Set<FieldCoordinate>,
    ): Int {
        val sourceSchema = SourceSchemaAdapter(world.schema)

        fun canonicalField(coordinate: FieldCoordinate): ViaductSchema.ObjectField? = sourceSchema.field(coordinate.typeName, coordinate.fieldName) as? ViaductSchema.ObjectField

        fun depth(
            field: ViaductSchema.ObjectField,
            visited: Set<ViaductSchema.ObjectField>,
        ): Int =
            1 +
                world.resolverRegistry
                    .mayDemandFrom(field)
                    .filter { dependency ->
                        registry.sourceResolverCoordinate(
                            FieldCoordinate(
                                dependency.containingDef.name,
                                dependency.name,
                            ),
                        ) in activatedSourceResolvers
                    }.filterNot { dependency -> dependency in visited }
                    .maxOfOrNull { dependency -> depth(dependency, visited + field) }
                    .orZero()

        return roots
            .filter { root -> root in activatedSourceResolvers }
            .mapNotNull(::canonicalField)
            .maxOfOrNull { root -> depth(root, emptySet()) }
            .orZero()
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun configured(
        property: String,
        environment: String,
    ): String? = System.getProperty(property) ?: System.getenv(environment)

    private suspend fun checkStressCases(
        suite: ResolverTestSuiteDefinition,
        liveCounts: TestCaseCount?,
        property: suspend (TestWorld, ResolverTestCase) -> Unit,
    ): ResolverTestRun =
        if (liveCounts == null) {
            checkResolverTestSuite(suite, selectiveResolvers = selectiveResolvers, property = property)
        } else {
            checkResolverTestCases(liveCounts, suite.config, profile = suite.profile, selectiveResolvers = selectiveResolvers, property = property)
        }

    private val displayName: String
        get() = resolverName.replaceFirstChar(Char::uppercase)

    private val stressCasesEnvironment: String
        get() = "${resolverName.uppercase()}_STRESS_CASES"

    private val stressCasesProperty: String
        get() = "$resolverName.stress.cases"

    private val stressSeedEnvironment: String
        get() = "${resolverName.uppercase()}_STRESS_SEED"

    private val stressSeedProperty: String
        get() = "$resolverName.stress.seed"

    private companion object {
        const val REGISTRIES_PER_SCHEMA = 2
        const val QUERIES_PER_SCHEMA = 5
        const val CASES_PER_SCHEMA = REGISTRIES_PER_SCHEMA * QUERIES_PER_SCHEMA
        const val MINIMUM_STRESS_CASES = 10_000
    }
}
