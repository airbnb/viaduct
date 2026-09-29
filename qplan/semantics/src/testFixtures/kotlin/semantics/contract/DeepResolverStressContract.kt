@file:Suppress("ForbiddenImport")

package semantics.contract

import io.kotest.property.PropertyTesting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.SourceSchemaAdapter
import model.fragmentFrom
import model.nodeReferenceIdentityOrNull
import model.objectOf
import model.requireField
import semantics.arbitrary.ArbitraryRegistry
import semantics.arbitrary.Config
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.MinimumSelectionDepth
import semantics.arbitrary.NodeObjectWeight
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverFromQueryFieldVariablesEnabled
import semantics.arbitrary.ResolverQueryFragmentWeight
import semantics.arbitrary.ResolverQueryFragmentsEnabled
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
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
            val requestedCases =
                configured(stressCasesProperty, stressCasesEnvironment)
                    .toIntOrNull()
                    ?.also { cases ->
                        require(cases >= MINIMUM_STRESS_CASES && cases % CASES_PER_SCHEMA == 0) {
                            "$stressCasesProperty/$stressCasesEnvironment must be at least " +
                                "$MINIMUM_STRESS_CASES and a multiple of $CASES_PER_SCHEMA"
                        }
                    }
                    ?: error("$stressCasesProperty/$stressCasesEnvironment must be an integer")
            val seed =
                configured(stressSeedProperty, stressSeedEnvironment)
                    .toLongOrNull()
                    ?: error("$stressSeedProperty/$stressSeedEnvironment must be a Long")
            val counts =
                TestCaseCount(
                    schemas = requestedCases / CASES_PER_SCHEMA,
                    registriesPerSchema = REGISTRIES_PER_SCHEMA,
                    queriesPerSchema = QUERIES_PER_SCHEMA,
                )
            val config =
                Config.default +
                    (MinimumSelectionDepth to 4) +
                    (MaxSelectionDepth to 6) +
                    (SchemaObjectCount to 4..5) +
                    (ObjectFieldCount to 3..5) +
                    (QueryFieldCount to 2..4) +
                    (FieldArgumentWeight to 0.65) +
                    (ExplicitFieldResolverWeight to 0.7) +
                    (NullableTypeWeight to 0.15) +
                    (NullValueWeight to 0.05) +
                    (ErrorValueWeight to 0.02) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 0.85) +
                    (ResolverFragmentDepth to 3) +
                    (ResolverQueryFragmentsEnabled to queryFragmentCoverageRequired) +
                    (ResolverQueryFragmentWeight to 0.1) +
                    (NodeResolversEnabled to nodeResolversEnabled) +
                    // Static tests exhaustively cover dispatch; stress samples interactions cheaply.
                    (NodeObjectWeight to 0.05) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (
                        ResolverVariablesEnabled to
                            (objectPathVariablesEnabled || queryPathVariablesEnabled)
                    ) +
                    (ResolverFromQueryFieldVariablesEnabled to queryPathVariablesEnabled) +
                    stressConfigOverrides
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

            try {
                checkResolverTestCases(
                    counts,
                    config,
                    profile = "$resolverName-stress",
                ) { testWorld, testCase ->
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
                    val fragment = world.fragmentFrom(testCase.query.source)
                    testCase.registry.clearResolutionWitness()
                    val resolution =
                        observeResolution(
                            world,
                            world.objectOf("Query"),
                            fragment.subselections,
                            resolverObserver = testCase.registry.resolverObserver(),
                        )
                    val result = resolution.result
                    val operation = resolution.operation
                    val correctnessObserver =
                        operation.resolverObserver as CorrectnessResolverObserver
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
                    "$displayName stress: seed=$seed, requestedCases=$requestedCases, " +
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

            assertEquals(requestedCases, attemptedCases)
            assertEquals(requestedCases, verifiedCases)
            assertTrue(resolverApplications >= requestedCases)
            if (nodeResolversEnabled) {
                assertTrue(generatedNodeResolvers >= requestedCases / 100)
                assertTrue(nodeLoaderApplications >= requestedCases / 1_000)
                assertTrue(
                    argumentBearingNodeProducerApplications >= requestedCases / 1_000,
                )
            } else {
                assertEquals(0, generatedNodeResolvers)
                assertEquals(0, nodeLoaderApplications)
                assertEquals(0, argumentBearingNodeProducerApplications)
            }
            if (objectPathVariablesEnabled) {
                assertTrue(generatedObjectPathVariables > 0)
                assertTrue(activatedObjectPathApplications > 0)
            }
            if (queryPathVariablesEnabled) {
                assertTrue(generatedQueryPathVariables > 0)
                assertTrue(activatedQueryPathApplications > 0)
            }
            if (queryFragmentCoverageRequired) {
                assertTrue(generatedQueryFragments > 0)
                assertTrue(queryFragmentFreeCases > 0)
                assertTrue(activatedQueryFragmentApplications > 0)
                assertTrue(casesWithDemandedQueryOERs > 0)
                assertTrue(casesWithoutDemandedQueryOERs > 0)
                if (minimumDemandedQueryOERDepth > 0) {
                    assertTrue(
                        maximumDemandedQueryOERDepth >= minimumDemandedQueryOERDepth,
                        "Expected demanded Query-OER depth of at least " +
                            "$minimumDemandedQueryOERDepth, found $maximumDemandedQueryOERDepth",
                    )
                }
            }
            if (nestedObjectPathCoverageRequired) {
                assertTrue(activatedNestedObjectPathApplications > 0)
            }
            if (minimumActivatedObjectPathResolverChainLength > 0) {
                assertTrue(
                    maximumActivatedObjectPathResolverChainLength >=
                        minimumActivatedObjectPathResolverChainLength,
                    "Expected an activated object-path resolver chain of length " +
                        "$minimumActivatedObjectPathResolverChainLength, found " +
                        maximumActivatedObjectPathResolverChainLength,
                )
            }
            if (mixedVariableCoverageRequired) {
                assertTrue(generatedFromArgumentVariables > 0)
                assertTrue(generatedObjectPathVariables > 0)
                assertTrue(activatedFromArgumentApplications > 0)
                assertTrue(activatedObjectPathApplications > 0)
                assertTrue(coactivatedMixedVariableCases > 0)
            }
            if (sometimesPassiveCoverageRequired) {
                assertTrue(generatedSometimesPassiveFields > 0)
                assertTrue(activatedSometimesPassiveOccurrences > 0)
            }
            if (rootFieldReferenceCoverageRequired) {
                assertTrue(generatedRootFieldReferences > 0)
                assertTrue(activatedRootFieldReferences > 0)
                if (nodeResolversEnabled) {
                    assertTrue(generatedNodeRootFieldReferences > 0)
                    assertTrue(activatedNodeRootFieldReferences > 0)
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
    ): String =
        System.getProperty(property)
            ?: System.getenv(environment)
            ?: error("Set $property or $environment; use the :semantics:${resolverName}Stress task")

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
