@file:Suppress("ForbiddenImport")

package semantics.resolver26

import semantics.shared.ResolverInvocationObservation
import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineOutputData
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.Fragment
import model.fragmentFrom
import model.outputValue
import model.nodeReferenceIdentityOrNull
import model.schemaType
import org.junit.jupiter.api.Test
import semantics.arbitrary.Config
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.ListValueSize
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.MaxOutputListDepth
import semantics.arbitrary.MinimumSelectionDepth
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.ParentFieldsEnabled
import semantics.arbitrary.RandomParentFieldsEnabled
import semantics.arbitrary.RootFieldReferencesEnabled
import semantics.arbitrary.RootFieldReferenceWeight
import semantics.arbitrary.FieldCoordinate
import semantics.contract.RegisteredResolverOccurrence
import semantics.arbitrary.ResolutionOccurrenceApplicationLog
import semantics.arbitrary.ResolutionWitness
import semantics.arbitrary.ResolverFromQueryFieldVariablesEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverFromFieldProviderPathLength
import semantics.arbitrary.ResolverFromFieldVariableUseDepth
import semantics.arbitrary.ResolverFromObjectFieldVariablesEnabled
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFragmentArgumentFieldWeight
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverQueryFragmentWeight
import semantics.arbitrary.ResolverQueryFragmentsEnabled
import semantics.arbitrary.ResolverTestExecution
import semantics.arbitrary.ResolverTestRun
import semantics.arbitrary.ResolverVariableCount
import semantics.arbitrary.ResolverVariableSingletonCoercionEnabled
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SometimesPassiveFieldWeight
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.configuredResolverTestExecution
import semantics.arbitrary.executeResolverTestCases
import semantics.arbitrary.isGeneratedRandomParentField
import semantics.contract.registeredResolverOccurrences
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCounts
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCountsFor
import semantics.contract.registeredResolverOccurrenceApplicationKeyCounts
import semantics.contract.validateFromFieldBindings
import semantics.correctresolution.correctResolution
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import semantics.shared.SharedOperationContext
import semantics.correctresolution.CorrectnessResolverObserver
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema
import kotlin.coroutines.CoroutineContext

/**
 * Unfiltered Resolver26 stress: every generated registry/query product is resolved and validated.
 */
class ResolverBroadStressTest : Resolver26DispatcherResource {
    @Test
    fun `root field reference focused randomized worlds resolve correctly`(): Unit =
        runBlocking {
            val defaultCounts =
                TestCaseCount(schemas = 10, registriesPerSchema = 5, queriesPerSchema = 5)
            val propertyProfile = "resolver26-root-field-references"
            val execution = configuredResolverTestExecution(defaultCounts, propertyProfile)
            val counts = execution.counts
            val completed =
                runResolver26BroadStress(
                    resolverCoroutineContext = resolverDispatcher,
                    requiredSignatures = emptySet(),
                    propertyProfile = propertyProfile,
                    counts = counts,
                    config =
                        Resolver26BroadStressProfile.BALANCED.config +
                            (RootFieldReferenceWeight to 0.6),
                    seed = configuredSeed(default = 2026091001L),
                    execution = execution,
                )

            assertEquals(
                if (execution.selectedCase == null) {
                    counts.schemas * counts.registriesPerSchema * counts.queriesPerSchema
                } else {
                    1
                },
                completed,
            )
        }

    @Test
    fun `parent focused randomized worlds resolve correctly`(): Unit =
        runBlocking {
            val defaultCounts =
                TestCaseCount(schemas = 40, registriesPerSchema = 5, queriesPerSchema = 5)
            val propertyProfile = "resolver26-parent-fields"
            val execution = configuredResolverTestExecution(defaultCounts, propertyProfile)
            val counts = execution.counts
            val completed =
                runResolver26BroadStress(
                    resolverCoroutineContext = resolverDispatcher,
                    requiredSignatures =
                        setOf(
                            Resolver26StructuralSignature.GREAT_GRANDPARENT_PARENT_DEMAND,
                        ),
                    propertyProfile = propertyProfile,
                    counts = counts,
                    config =
                        Config.default +
                            (ParentFieldsEnabled to true) +
                            (MinimumSelectionDepth to 2) +
                            (MaxSelectionDepth to 6) +
                            (RandomParentFieldsEnabled to true) +
                            (ResolverFragmentsEnabled to true) +
                            (ResolverFragmentWeight to 1.0) +
                            (ResolverFragmentDepth to 3) +
                            (FieldArgumentWeight to 0.65) +
                            (ResolverFragmentArgumentFieldWeight to 1.0) +
                            (ResolverQueryFragmentsEnabled to true) +
                            (ResolverQueryFragmentWeight to 0.25) +
                            (ResolverVariablesEnabled to true) +
                            (ResolverFromArgumentVariablesEnabled to true) +
                            (ResolverFromObjectFieldVariablesEnabled to true) +
                            (ResolverFromQueryFieldVariablesEnabled to true) +
                            (ResolverVariableWeight to 1.0) +
                            (ResolverVariableCount to 2..3) +
                            (ResolverVariableSingletonCoercionEnabled to true) +
                            (ResolverFromFieldProviderPathLength to 1..3) +
                            (ResolverFromFieldVariableUseDepth to 1..3) +
                            (SometimesPassiveFieldWeight to 1.0) +
                            (NodeResolversEnabled to false) +
                            (MaxOutputListDepth to 2) +
                            (ListValueSize to 1..1) +
                            (NullValueWeight to 0.0) +
                            (ErrorValueWeight to 0.0),
                    seed = configuredSeed(default = 2026090403L),
                    execution = execution,
                    parentFocusedReportSlices = if (execution.selectedCase == null) 4 else 1,
                )

            assertEquals(
                if (execution.selectedCase == null) {
                    counts.schemas * counts.registriesPerSchema * counts.queriesPerSchema
                } else {
                    1
                },
                completed,
            )
        }

    @Test
    fun `broad full-feature worlds resolve correctly`(): Unit =
        runBlocking {
            val broadProfile: Resolver26BroadStressProfile = configuredProfile()
            runResolver26BroadStress(
                resolverCoroutineContext = resolverDispatcher,
                requiredSignatures = broadProfile.requiredSignatures,
                propertyProfile = broadProfile.propertyProfile,
                counts = configuredCounts(broadProfile),
                config = broadProfile.config,
                seed = configuredSeed(),
            )
        }

    // Returns the named generator distribution selected for this run.
    private fun configuredProfile(): Resolver26BroadStressProfile {
        val configured: String =
            System.getProperty(PROFILE_PROPERTY)
                ?: System.getenv(PROFILE_ENVIRONMENT)
                ?: System.getProperty("resolver.property.profile")
                ?: Resolver26BroadStressProfile.BALANCED.propertyProfile
        return Resolver26BroadStressProfile.fromConfigured(configured)
    }

    // Returns the configured S:R:Q product dimensions.
    private fun configuredCounts(
        broadProfile: Resolver26BroadStressProfile,
    ): TestCaseCount {
        val configured: String =
            System.getProperty(SIZE_PROPERTY)
                ?: System.getenv(SIZE_ENVIRONMENT)
                ?: System.getProperty("resolver.property.size")
                ?: broadProfile.defaultSize
        val dimensions: List<String> = configured.split(':')
        require(dimensions.size == 3) {
            "$SIZE_PROPERTY/$SIZE_ENVIRONMENT must have S:R:Q form: $configured"
        }
        val parsed: List<Int> =
            dimensions.map { dimension ->
                dimension.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: error(
                        "$SIZE_PROPERTY/$SIZE_ENVIRONMENT must have positive dimensions: " +
                            configured,
                    )
            }
        return TestCaseCount(
            schemas = parsed[0],
            registriesPerSchema = parsed[1],
            queriesPerSchema = parsed[2],
        )
    }

    // Returns the explicit seed required for reproducible broad generation.
    private fun configuredSeed(default: Long? = null): Long {
        val configured: String =
            System.getProperty(SEED_PROPERTY)
                ?: System.getenv(SEED_ENVIRONMENT)
                ?: System.getProperty("resolver.property.seed")
                ?: default?.toString()
                ?: error(
                    "Set $SEED_PROPERTY or $SEED_ENVIRONMENT; use the " +
                        ":semantics:resolver26BroadStress task",
                )
        return configured.toLongOrNull()
            ?: error("$SEED_PROPERTY/$SEED_ENVIRONMENT must be a Long: $configured")
    }

    private companion object {
        const val PROFILE_PROPERTY = "resolver26.broad.stress.profile"
        const val PROFILE_ENVIRONMENT = "RESOLVER26_BROAD_STRESS_PROFILE"
        const val SIZE_PROPERTY = "resolver26.broad.stress.size"
        const val SIZE_ENVIRONMENT = "RESOLVER26_BROAD_STRESS_SIZE"
        const val SEED_PROPERTY = "resolver26.broad.stress.seed"
        const val SEED_ENVIRONMENT = "RESOLVER26_BROAD_STRESS_SEED"
    }
}

// Resolves and independently validates every case in one Resolver26 generated product.
internal suspend fun runResolver26BroadStress(
    resolverCoroutineContext: CoroutineContext,
    requiredSignatures: Set<Resolver26StructuralSignature>,
    propertyProfile: String,
    counts: TestCaseCount,
    config: Config,
    seed: Long,
    execution: ResolverTestExecution = configuredResolverTestExecution(counts, propertyProfile),
    parentFocusedReportSlices: Int = 0,
): Int {
    val startedAt: Long = System.nanoTime()
    var attemptedCases = 0
    var resolutionCalls = 0
    var completedCases = 0
    var resolverApplications = 0
    var generatedArgumentVariables = 0
    var generatedObjectPathVariables = 0
    var generatedQueryPathVariables = 0
    var activatedArgumentVariableApplications = 0
    var activatedObjectPathVariableApplications = 0
    var activatedQueryPathVariableApplications = 0
    var maximumProviderPathLength = 0
    var maximumVariableUseDepth = 0
    var generatedSometimesPassiveFields = 0
    var activatedSometimesPassiveOccurrences = 0
    var activatedSometimesPassiveParentDemandOccurrences = 0
    val sometimesPassiveParentDemandDepths: MutableMap<Int, Int> = linkedMapOf()
    var generatedQueryFragments = 0
    var activatedQueryFragmentApplications = 0
    var generatedRootFieldReferences = 0
    var generatedNodeRootFieldReferences = 0
    var activatedRootFieldReferences = 0
    var activatedNodeRootFieldReferences = 0
    var activatedListRootFieldReferences = 0
    var maximumRootFieldReferenceTailLength = 0
    var activatedRootFieldReferenceFallbacks = 0
    var activatedRootFieldReferenceExtensions = 0
    var activatedRootFieldReferenceOverrides = 0
    var activatedRootTargetsWithFromArgument = 0
    var activatedRootTargetsWithFromQueryField = 0
    val activatedRootFieldReferencePathDepths = linkedSetOf<Int>()
    val activatedRootFieldReferenceArgumentCounts = linkedSetOf<Int>()
    val activatedRootFieldReferenceTargetKinds = linkedSetOf<String>()
    var activatedParentDemandApplications = 0
    var materializedParentFieldActivations = 0
    var materializedRandomParentFieldActivations = 0
    var materializedParentSelectionSets = 0
    var parentSelectionSetsWithArgumentVariables = 0
    var argumentVariableSelectionsBeneathParent = 0
    var parentSelectionSetsWithSelectedResolvers = 0
    var resolverSelectionsBeneathParent = 0
    var directResolverSelectionsBeneathParent = 0
    var resolverSelectionsWithVariablesBeneathParent = 0
    var directResolverSelectionsWithVariablesBeneathParent = 0
    var resolverVariableArgumentSelectionsBeneathParent = 0
    var parentSelectionSetsWithDiagonalDemand = 0
    var diagonalResolverSelectionsBeneathParent = 0
    var directDiagonalResolverSelectionsBeneathParent = 0
    var diagonalResolverSelectionsWithVariables = 0
    var diagonalVariableArgumentSelections = 0
    val materializedParentFieldDepths: MutableMap<Int, Int> = linkedMapOf()
    val materializedParentFields: MutableSet<FieldCoordinate> = linkedSetOf()
    val parentSelectionSetVariableSourceCombinations: MutableMap<Set<ParentVariableSource>, Int> =
        linkedMapOf()
    val argumentVariableSourceCombinationsBeneathParent:
        MutableMap<Set<ParentVariableSource>, Int> = linkedMapOf()
    val resolverVariableSourceCombinationsBeneathParent:
        MutableMap<Set<ParentVariableSource>, Int> = linkedMapOf()
    val resolverVariableArgumentFragmentsBeneathParent:
        MutableMap<ParentResolverInputFragment, Int> =
        linkedMapOf()
    val resolverVariableArgumentDepthsBeneathParent: MutableMap<Int, Int> = linkedMapOf()
    val resolverVariableArgumentSourceCombinationsBeneathParent:
        MutableMap<Set<ParentVariableSource>, Int> = linkedMapOf()
    val diagonalParentDepths: MutableMap<Int, Int> = linkedMapOf()
    val diagonalVariableSourceCombinations: MutableMap<Set<ParentVariableSource>, Int> =
        linkedMapOf()
    val diagonalVariableArgumentSourceCombinations:
        MutableMap<Set<ParentVariableSource>, Int> = linkedMapOf()
    val parentCoverageLock = Any()
    val observedSignatures: MutableSet<Resolver26StructuralSignature> = linkedSetOf()
    val parentFocusedReport =
        parentFocusedReportSlices.takeIf { slices -> slices > 0 }?.let { slices ->
            ParentFocusedCoverageReport(counts.schemas, slices)
        }

    try {
        val run: ResolverTestRun =
            executeResolverTestCases(
                execution = execution,
                config = config,
                profile = propertyProfile,
                seed = seed,
            ) { testWorld, testCase ->
                attemptedCases += 1
                val schemaIndex = requireNotNull(testCase.coordinates).schemaIndex
                var caseParentFocusedCoverage = ParentFocusedCoverageSnapshot()
                generatedArgumentVariables +=
                    testCase.registry.features.fromArgumentVariableCount
                generatedObjectPathVariables +=
                    testCase.registry.features.fromObjectFieldVariableCount
                generatedQueryPathVariables +=
                    testCase.registry.features.fromQueryFieldVariableCount
                generatedSometimesPassiveFields +=
                    testCase.registry.features.sometimesPassiveFieldCount
                generatedQueryFragments += testCase.registry.features.queryFragmentCount
                generatedRootFieldReferences +=
                    testCase.registry.features.generatedRootFieldReferenceCount
                generatedNodeRootFieldReferences +=
                    testCase.registry.features.generatedNodeRootFieldReferenceCount
                maximumProviderPathLength =
                    maxOf(
                        maximumProviderPathLength,
                        testCase.registry.features.maximumFromObjectFieldPathLength,
                        testCase.registry.features.maximumFromQueryFieldPathLength,
                    )
                maximumVariableUseDepth =
                    maxOf(
                        maximumVariableUseDepth,
                        testCase.registry.features.maximumFromObjectFieldVariableUseDepth,
                        testCase.registry.features.maximumFromQueryFieldVariableUseDepth,
                    )
                val world: Assumptions =
                    testWorld.newAssumptions(selectiveResolvers = true)
                val fragment: Fragment = world.fragmentFrom(testCase.query.source)

                testCase.registry.clearResolutionWitness()
                val occurrenceLog = ResolutionOccurrenceApplicationLog()
                resolutionCalls += 1

                val witnessObserver = testCase.registry.resolverObserver()

                val recordingObserver = object : CorrectnessResolverObserver() {
                    override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                        super.onResolverInvocation(observation)
                        witnessObserver.onResolverInvocation(observation)
                        val parentActivations =
                            observation.input.materializedParentFieldActivations(
                                observation.inputSelections,
                            )
                        val parentCoverage =
                            ParentCoverageAnalyzer(world).analyze(observation)
                        synchronized(parentCoverageLock) {
                            caseParentFocusedCoverage +=
                                parentFocusedCoverageSnapshot(world, parentCoverage)
                            materializedParentFieldActivations += parentActivations.size
                            materializedRandomParentFieldActivations +=
                                parentActivations.count { activation ->
                                    activation.field.isGeneratedRandomParentField()
                                }
                            parentActivations.forEach { activation ->
                                materializedParentFields += activation.field
                                materializedParentFieldDepths[activation.depth] =
                                    materializedParentFieldDepths.getOrDefault(
                                        activation.depth,
                                        0,
                                ) + 1
                            }
                            materializedParentSelectionSets += parentCoverage.size
                            parentCoverage.forEach { parent ->
                                if (parent.argumentVariables.isNotEmpty()) {
                                    parentSelectionSetsWithArgumentVariables += 1
                                }
                                argumentVariableSelectionsBeneathParent +=
                                    parent.argumentVariables.size
                                parent.argumentVariables.forEach { argument ->
                                    argumentVariableSourceCombinationsBeneathParent.increment(
                                        argument.variableSources,
                                    )
                                }
                                if (parent.selectedResolvers.isNotEmpty()) {
                                    parentSelectionSetsWithSelectedResolvers += 1
                                }
                                resolverSelectionsBeneathParent +=
                                    parent.selectedResolvers.size
                                directResolverSelectionsBeneathParent +=
                                    parent.selectedResolvers.count { selected ->
                                        selected.selectionDepthBelowParent == 1
                                    }
                                val variableSources =
                                    parent.selectedResolvers
                                        .flatMap { selected ->
                                            selected.requiredInputVariableSources
                                        }.toSet()
                                if (variableSources.isNotEmpty()) {
                                    parentSelectionSetVariableSourceCombinations.increment(
                                        variableSources,
                                    )
                                }
                                parent.selectedResolvers.forEach { selected ->
                                    if (selected.requiredInputVariableSources.isNotEmpty()) {
                                        resolverSelectionsWithVariablesBeneathParent += 1
                                        if (selected.selectionDepthBelowParent == 1) {
                                            directResolverSelectionsWithVariablesBeneathParent +=
                                                1
                                        }
                                        resolverVariableSourceCombinationsBeneathParent
                                            .increment(
                                                selected.requiredInputVariableSources,
                                            )
                                    }
                                    resolverVariableArgumentSelectionsBeneathParent +=
                                        selected.variableArgumentSelections.size
                                    selected.variableArgumentSelections.forEach { argument ->
                                        resolverVariableArgumentFragmentsBeneathParent.increment(
                                            argument.fragment,
                                        )
                                        resolverVariableArgumentDepthsBeneathParent.increment(
                                            argument.selectionDepth,
                                        )
                                        resolverVariableArgumentSourceCombinationsBeneathParent
                                            .increment(argument.variableSources)
                                    }
                                    if (selected.diagonalParentDepth > 0) {
                                        diagonalResolverSelectionsBeneathParent += 1
                                        if (selected.selectionDepthBelowParent == 1) {
                                            directDiagonalResolverSelectionsBeneathParent += 1
                                        }
                                        diagonalParentDepths.increment(
                                            selected.diagonalParentDepth,
                                        )
                                        if (
                                            selected.requiredInputVariableSources.isNotEmpty()
                                        ) {
                                            diagonalResolverSelectionsWithVariables += 1
                                            diagonalVariableSourceCombinations.increment(
                                                selected.requiredInputVariableSources,
                                            )
                                        }
                                        diagonalVariableArgumentSelections +=
                                            selected.variableArgumentSelections.size
                                        selected.variableArgumentSelections.forEach { argument ->
                                            diagonalVariableArgumentSourceCombinations.increment(
                                                argument.variableSources,
                                            )
                                        }
                                    }
                                }
                                if (
                                    parent.selectedResolvers.any { selected ->
                                        selected.diagonalParentDepth > 0
                                    }
                                ) {
                                    parentSelectionSetsWithDiagonalDemand += 1
                                }
                            }
                        }
                        occurrenceLog.record(
                            resolverOccurrenceId = observation.resolverOccurrenceId,
                            occurrencePath = observation.occurrencePath,
                            field =
                                FieldCoordinate(
                                    observation.field.containingDef.name,
                                    observation.field.name,
                                ),
                            arguments = observation.arguments,
                            input = observation.input,
                        )
                    }
                }
                val operation =
                    SharedOperationContext.create(world, resolverObserver = recordingObserver)
                val result: ObjectEngineResult =
                    operation.resolve(
                        selections = fragment.subselections,
                        coroutineContext = resolverCoroutineContext,
                    )
                val witness: ResolutionWitness = testCase.registry.resolutionWitness()
                val rootFieldReferenceInvocations =
                    recordingObserver.rootFieldReferenceInvocations()
                activatedRootFieldReferences += rootFieldReferenceInvocations.size
                rootFieldReferenceInvocations.forEach { observation ->
                    activatedRootFieldReferencePathDepths += observation.reference.path.size
                    activatedRootFieldReferenceArgumentCounts +=
                        observation.reference.arguments.fieldValues.size
                    if (
                        observation.publicationPath.any { component ->
                            component is model.ListEngineResult.Index
                        }
                    ) {
                        activatedListRootFieldReferences += 1
                    }
                    activatedRootFieldReferenceTargetKinds +=
                        when (observation.reference.type) {
                            is ViaductSchema.Interface -> "interface"
                            is ViaductSchema.Union -> "union"
                            is ViaductSchema.Enum -> "enum"
                            is ViaductSchema.Scalar -> "scalar"
                            is ViaductSchema.Object -> "object"
                            else -> "other"
                        }
                    val publicationField =
                        (observation.publicationPath.lastOrNull()
                            as? ObjectEngineResult.ObjectKey)?.field
                    if (observation.reference.nodeReferenceIdentityOrNull() != null) {
                        activatedNodeRootFieldReferences += 1
                    }
                    if (
                        publicationField != null &&
                        testCase.registry.sourceFieldIsRootFieldReferenceOverride(
                            FieldCoordinate(
                                publicationField.containingDef.name,
                                publicationField.name,
                            ),
                        )
                    ) {
                        activatedRootFieldReferenceOverrides += 1
                    }
                    val targetField =
                        FieldCoordinate(
                            observation.reference.targetField.containingDef.name,
                            observation.reference.targetField.name,
                        )
                    if (
                        testCase.registry.sourceResolverHasFromArgumentVariables(targetField)
                    ) {
                        activatedRootTargetsWithFromArgument += 1
                    }
                    if (
                        testCase.registry.sourceResolverHasFromQueryFieldVariables(targetField)
                    ) {
                        activatedRootTargetsWithFromQueryField += 1
                    }
                }
                maximumRootFieldReferenceTailLength =
                    maxOf(
                        maximumRootFieldReferenceTailLength,
                        rootFieldReferenceInvocations
                            .groupingBy { observation ->
                                observation.publicationRoot to observation.publicationPath
                            }
                            .eachCount()
                            .values
                            .maxOrNull() ?: 0,
                    )
                val occurrenceWitness = occurrenceLog.snapshot()
                assertEquals(
                    witness.applicationIdentityCounts(),
                    occurrenceWitness.applications
                        .groupingBy { application -> application.application.identity }
                        .eachCount(),
                    "Resolver26 occurrence instrumentation missed an application",
                )
                val occurrences: List<RegisteredResolverOccurrence> =
                    result.registeredResolverOccurrences(operation, operation.world.resolverRegistry)
                observedSignatures +=
                    resolver26StructuralSignatures(
                        occurrences = occurrences,
                        witness = witness,
                        registry = testCase.registry,
                    )
                resolverApplications += witness.applications.size
                activatedRootFieldReferenceFallbacks +=
                    witness.applications.count { application ->
                        testCase.registry.sourceResolverIsRootFieldReferenceFallback(
                            application.key.field,
                        )
                    }
                activatedRootFieldReferenceExtensions +=
                    occurrenceWitness.applications.count { application ->
                        testCase.registry.sourceResolverIsRootFieldReferenceExtension(
                            application.application.key.field,
                        ) &&
                            rootFieldReferenceInvocations.any { reference ->
                                application.resolverOccurrenceId ==
                                    ResolverOccurrenceId.at(
                                        reference.publicationRoot,
                                        application.occurrencePath,
                                    ) &&
                                    application.occurrencePath
                                        .take(reference.publicationPath.size) ==
                                    reference.publicationPath
                            }
                    }
                witness.applications.forEach { application ->
                    val sourceField =
                        testCase.registry.sourceResolverCoordinate(application.key.field)
                    if (
                        testCase.registry.parentDemandOwnerFields.getOrDefault(sourceField, 0) >= 3
                    ) {
                        activatedParentDemandApplications += 1
                    }
                    if (
                        testCase.registry.queryFragmentSources[
                            testCase.registry.sourceResolverCoordinate(application.key.field)
                        ]?.isNotEmpty() == true
                    ) {
                        activatedQueryFragmentApplications += 1
                    }
                    if (
                        testCase.registry.sourceResolverHasFromArgumentVariables(
                            application.key.field,
                        )
                    ) {
                        activatedArgumentVariableApplications += 1
                    }
                    if (
                        testCase.registry.sourceResolverHasFromObjectFieldVariables(
                            application.key.field,
                        )
                    ) {
                        activatedObjectPathVariableApplications += 1
                    }
                    if (
                        testCase.registry.sourceResolverHasFromQueryFieldVariables(
                            application.key.field,
                        )
                    ) {
                        activatedQueryPathVariableApplications += 1
                    }
                }

                val observedOccurrenceCounts = occurrenceWitness.applicationIdentityCounts()
                if (config[SometimesPassiveFieldWeight] > 0.0) {
                    val expectedOccurrenceKeyCounts =
                        result.registeredResolverOccurrenceApplicationKeyCounts(operation)
                    val observedOccurrenceKeyCounts = occurrenceWitness.applicationKeyCounts()
                    observedOccurrenceKeyCounts.forEach { (key, count) ->
                        assertTrue(
                            count <= expectedOccurrenceKeyCounts.getOrDefault(key, 0),
                            "Observed $count applications of $key but request results " +
                                "contain only " +
                                "${expectedOccurrenceKeyCounts.getOrDefault(key, 0)} " +
                                "matching occurrences",
                        )
                    }
                    val expectedObservedIdentities =
                        result.registeredResolverOccurrenceApplicationIdentityCountsFor(
                            operation,
                            occurrenceWitness.applications
                                .map { application -> application.resolverOccurrenceId }
                                .toSet(),
                        )
                    assertEquals(expectedObservedIdentities, observedOccurrenceCounts)
                    var caseSometimesPassiveOccurrences = 0
                    val caseSometimesPassiveParentDemandDepths = mutableListOf<Int>()
                    expectedOccurrenceKeyCounts.forEach { (key, expectedCount) ->
                        val passiveCount =
                            expectedCount - observedOccurrenceKeyCounts.getOrDefault(key, 0)
                        check(passiveCount >= 0)
                        caseSometimesPassiveOccurrences += passiveCount
                        val sourceField =
                            testCase.registry.sourceResolverCoordinate(key.applicationKey.field)
                        testCase.registry.parentDemandOwnerFields[sourceField]?.let { parentDepth ->
                            repeat(passiveCount) {
                                caseSometimesPassiveParentDemandDepths += parentDepth
                            }
                        }
                    }
                    activatedSometimesPassiveOccurrences += caseSometimesPassiveOccurrences
                    activatedSometimesPassiveParentDemandOccurrences +=
                        caseSometimesPassiveParentDemandDepths.size
                    caseSometimesPassiveParentDemandDepths.forEach { depth ->
                        sometimesPassiveParentDemandDepths.increment(depth)
                    }
                    caseParentFocusedCoverage +=
                        ParentFocusedCoverageSnapshot(
                            sometimesPassiveParentDemandOccurrences =
                                caseSometimesPassiveParentDemandDepths.size,
                            sometimesPassiveParentDemandDepths =
                                caseSometimesPassiveParentDemandDepths.toSet(),
                        )
                } else {
                    val expectedOccurrenceCounts =
                        result.registeredResolverOccurrenceApplicationIdentityCounts(operation)
                    assertEquals(
                        expectedOccurrenceCounts,
                        observedOccurrenceCounts,
                    )
                }
                assertTrue(
                    result.correctResolution(operation, fragment),
                )
                result.validateFromFieldBindings(
                    operation,
                    occurrenceWitness.applications
                        .map { application -> application.resolverOccurrenceId }
                        .toSet(),
                )
                parentFocusedReport?.record(schemaIndex, caseParentFocusedCoverage)
                completedCases += 1
            }

        assertEquals(run.expectedCases, run.attemptedCases)
        assertEquals(run.expectedCases, attemptedCases)
        assertEquals(run.expectedCases, resolutionCalls)
        assertEquals(run.expectedCases, completedCases)
        run.assertAggregate(
            observedSignatures.containsAll(requiredSignatures),
            "Resolver26 profile $propertyProfile missed required signatures: " +
                "${requiredSignatures - observedSignatures}; " +
                "observed=$observedSignatures",
        )
        if (config[SometimesPassiveFieldWeight] > 0.0) {
            run.assertAggregate(
                generatedSometimesPassiveFields > 0 &&
                    activatedSometimesPassiveOccurrences > 0,
                "Resolver26 profile $propertyProfile did not activate sometimes-passive fields",
            )
            if (config[RandomParentFieldsEnabled]) {
                run.assertAggregate(
                    activatedSometimesPassiveParentDemandOccurrences > 0,
                    "Resolver26 profile $propertyProfile did not source-supply a registered " +
                        "field whose standard resolver has @parent demand",
                )
            }
        }
        if (config[ResolverQueryFragmentsEnabled]) {
            run.assertAggregate(
                generatedQueryFragments > 0 && activatedQueryFragmentApplications > 0,
                "Resolver26 profile $propertyProfile did not activate query fragments",
            )
        }
        if (config[RootFieldReferencesEnabled]) {
            run.assertAggregate(
                generatedRootFieldReferences > 0 && activatedRootFieldReferences > 0,
                "Resolver26 profile $propertyProfile did not generate and activate root-field references",
            )
            if (config[NodeResolversEnabled]) {
                run.assertAggregate(
                    generatedNodeRootFieldReferences > 0 &&
                        activatedNodeRootFieldReferences > 0,
                    "Resolver26 profile $propertyProfile did not generate and activate a " +
                        "root-field-reference-returning node resolver",
                )
            }
            run.assertAggregate(
                activatedRootFieldReferencePathDepths.containsAll(setOf(2, 3, 4)),
                "Resolver26 profile $propertyProfile missed root-field-reference namespace depths: " +
                    "observed=$activatedRootFieldReferencePathDepths",
            )
            run.assertAggregate(
                activatedRootFieldReferenceArgumentCounts.containsAll(setOf(0, 1, 4)),
                "Resolver26 profile $propertyProfile missed root-field-reference arities: " +
                    "observed=$activatedRootFieldReferenceArgumentCounts",
            )
            run.assertAggregate(
                activatedRootFieldReferenceTargetKinds.containsAll(
                    setOf("object", "interface", "union", "enum", "scalar"),
                ),
                "Resolver26 profile $propertyProfile missed root-field-reference target kinds: " +
                    "observed=$activatedRootFieldReferenceTargetKinds",
            )
            run.assertAggregate(
                activatedListRootFieldReferences > 0,
                "Resolver26 profile $propertyProfile did not activate a list-element root-field reference",
            )
            run.assertAggregate(
                maximumRootFieldReferenceTailLength >= 3,
                "Resolver26 profile $propertyProfile did not activate a three-hop root-field-reference tail",
            )
            run.assertAggregate(
                activatedRootFieldReferenceFallbacks > 0,
                "Resolver26 profile $propertyProfile did not activate root-field-reference fallback",
            )
            run.assertAggregate(
                activatedRootFieldReferenceOverrides > 0,
                "Resolver26 profile $propertyProfile did not suppress a registered resolver with a root-field reference",
            )
            run.assertAggregate(
                activatedRootFieldReferenceExtensions > 0,
                "Resolver26 profile $propertyProfile did not resolve successor demand within a referenced result",
            )
            run.assertAggregate(
                activatedRootTargetsWithFromArgument > 0 &&
                    activatedRootTargetsWithFromQueryField > 0,
                "Resolver26 profile $propertyProfile did not activate root targets using both " +
                    "FromArgument and FromQueryField variables",
            )
        }
        if (config[ResolverFromQueryFieldVariablesEnabled]) {
            run.assertAggregate(
                generatedQueryPathVariables > 0 &&
                    activatedQueryPathVariableApplications > 0,
                "Resolver26 profile $propertyProfile did not activate FromQueryField variables",
            )
        }
        if (config[ParentFieldsEnabled]) {
            run.assertAggregate(
                activatedParentDemandApplications > 0,
                "Resolver26 profile $propertyProfile did not activate great-grandparent demand",
            )
            run.assertAggregate(
                argumentVariableSelectionsBeneathParent == 0,
                "Resolver26 profile $propertyProfile materialized a directly variable-bearing " +
                    "selection beneath @parent",
            )
        }
        if (config[RandomParentFieldsEnabled]) {
            run.assertAggregate(
                materializedRandomParentFieldActivations > 0,
                "Resolver26 profile $propertyProfile did not materialize a random parent field",
            )
            run.assertAggregate(
                directDiagonalResolverSelectionsBeneathParent > 0,
                "Resolver26 profile $propertyProfile did not activate diagonal parent demand",
            )
            if (config[ResolverVariablesEnabled]) {
                buildList {
                    add(ParentVariableSource.ARGUMENT)
                    add(ParentVariableSource.OBJECT_FIELD)
                    if (config[ResolverFromQueryFieldVariablesEnabled]) {
                        add(ParentVariableSource.QUERY_FIELD)
                    }
                }.forEach { source ->
                    run.assertAggregate(
                        resolverVariableSourceCombinationsBeneathParent.keys.any { sources ->
                            source in sources
                        },
                        "Resolver26 profile $propertyProfile did not activate $source beneath " +
                            "@parent",
                    )
                }
                run.assertAggregate(
                    diagonalResolverSelectionsWithVariables > 0,
                    "Resolver26 profile $propertyProfile did not activate variables in " +
                        "diagonal parent demand",
                )
            }
            parentFocusedReport?.requireCombinedHit()
        }
        return completedCases
    } finally {
        parentFocusedReport?.let { report -> println(report.render()) }
        println(
            "Resolver26 broad stress: profile=$propertyProfile, seed=$seed, " +
                "size=${counts.summary()}, " +
                "attemptedCases=$attemptedCases, resolutionCalls=$resolutionCalls, " +
                "completedCases=$completedCases, " +
                "resolverApplications=$resolverApplications, " +
                "generatedArgumentVariables=$generatedArgumentVariables, " +
                "generatedObjectPathVariables=$generatedObjectPathVariables, " +
                "generatedQueryPathVariables=$generatedQueryPathVariables, " +
                "activatedArgumentVariableApplications=" +
                "$activatedArgumentVariableApplications, " +
                "activatedObjectPathVariableApplications=" +
                "$activatedObjectPathVariableApplications, " +
                "activatedQueryPathVariableApplications=" +
                "$activatedQueryPathVariableApplications, " +
                "maximumProviderPathLength=$maximumProviderPathLength, " +
                "maximumVariableUseDepth=$maximumVariableUseDepth, " +
                "generatedSometimesPassiveFields=$generatedSometimesPassiveFields, " +
                "activatedSometimesPassiveOccurrences=" +
                "$activatedSometimesPassiveOccurrences, " +
                "activatedSometimesPassiveParentDemandOccurrences=" +
                "$activatedSometimesPassiveParentDemandOccurrences, " +
                "sometimesPassiveParentDemandDepths=$sometimesPassiveParentDemandDepths, " +
                "generatedQueryFragments=$generatedQueryFragments, " +
                "activatedQueryFragmentApplications=$activatedQueryFragmentApplications, " +
                "generatedRootFieldReferences=$generatedRootFieldReferences, " +
                "generatedNodeRootFieldReferences=$generatedNodeRootFieldReferences, " +
                "activatedRootFieldReferences=$activatedRootFieldReferences, " +
                "activatedNodeRootFieldReferences=$activatedNodeRootFieldReferences, " +
                "activatedListRootFieldReferences=$activatedListRootFieldReferences, " +
                "maximumRootFieldReferenceTailLength=$maximumRootFieldReferenceTailLength, " +
                "activatedRootFieldReferenceFallbacks=$activatedRootFieldReferenceFallbacks, " +
                "activatedRootFieldReferenceExtensions=$activatedRootFieldReferenceExtensions, " +
                "activatedRootFieldReferenceOverrides=$activatedRootFieldReferenceOverrides, " +
                "activatedRootTargetsWithFromArgument=$activatedRootTargetsWithFromArgument, " +
                "activatedRootTargetsWithFromQueryField=$activatedRootTargetsWithFromQueryField, " +
                "activatedRootFieldReferencePathDepths=$activatedRootFieldReferencePathDepths, " +
                "activatedRootFieldReferenceArgumentCounts=$activatedRootFieldReferenceArgumentCounts, " +
                "activatedRootFieldReferenceTargetKinds=$activatedRootFieldReferenceTargetKinds, " +
                "activatedParentDemandApplications=$activatedParentDemandApplications, " +
                "materializedParentFieldActivations=$materializedParentFieldActivations, " +
                "materializedRandomParentFieldActivations=" +
                "$materializedRandomParentFieldActivations, " +
                "materializedParentSelectionSets=$materializedParentSelectionSets, " +
                "parentSelectionSetsWithArgumentVariables=" +
                "$parentSelectionSetsWithArgumentVariables, " +
                "argumentVariableSelectionsBeneathParent=" +
                "$argumentVariableSelectionsBeneathParent, " +
                "argumentVariableSourceCombinationsBeneathParent=" +
                "$argumentVariableSourceCombinationsBeneathParent, " +
                "parentSelectionSetsWithSelectedResolvers=" +
                "$parentSelectionSetsWithSelectedResolvers, " +
                "resolverSelectionsBeneathParent=$resolverSelectionsBeneathParent, " +
                "directResolverSelectionsBeneathParent=" +
                "$directResolverSelectionsBeneathParent, " +
                "resolverSelectionsWithVariablesBeneathParent=" +
                "$resolverSelectionsWithVariablesBeneathParent, " +
                "directResolverSelectionsWithVariablesBeneathParent=" +
                "$directResolverSelectionsWithVariablesBeneathParent, " +
                "parentSelectionSetVariableSourceCombinations=" +
                "$parentSelectionSetVariableSourceCombinations, " +
                "resolverVariableSourceCombinationsBeneathParent=" +
                "$resolverVariableSourceCombinationsBeneathParent, " +
                "resolverVariableArgumentSelectionsBeneathParent=" +
                "$resolverVariableArgumentSelectionsBeneathParent, " +
                "resolverVariableArgumentFragmentsBeneathParent=" +
                "$resolverVariableArgumentFragmentsBeneathParent, " +
                "resolverVariableArgumentDepthsBeneathParent=" +
                "$resolverVariableArgumentDepthsBeneathParent, " +
                "resolverVariableArgumentSourceCombinationsBeneathParent=" +
                "$resolverVariableArgumentSourceCombinationsBeneathParent, " +
                "parentSelectionSetsWithDiagonalDemand=" +
                "$parentSelectionSetsWithDiagonalDemand, " +
                "diagonalResolverSelectionsBeneathParent=" +
                "$diagonalResolverSelectionsBeneathParent, " +
                "directDiagonalResolverSelectionsBeneathParent=" +
                "$directDiagonalResolverSelectionsBeneathParent, " +
                "diagonalResolverSelectionsWithVariables=" +
                "$diagonalResolverSelectionsWithVariables, " +
                "diagonalVariableArgumentSelections=" +
                "$diagonalVariableArgumentSelections, " +
                "diagonalParentDepths=$diagonalParentDepths, " +
                "diagonalVariableSourceCombinations=" +
                "$diagonalVariableSourceCombinations, " +
                "diagonalVariableArgumentSourceCombinations=" +
                "$diagonalVariableArgumentSourceCombinations, " +
                "distinctMaterializedParentFields=${materializedParentFields.size}, " +
                "materializedParentFieldDepths=$materializedParentFieldDepths, " +
                "signatures=$observedSignatures, " +
                "elapsedMillis=${(System.nanoTime() - startedAt) / 1_000_000}",
        )
    }
}

// Returns compact S:R:Q dimensions for diagnostics.
private fun TestCaseCount.summary(): String =
    "$schemas:$registriesPerSchema:$queriesPerSchema"

private fun <K> MutableMap<K, Int>.increment(key: K) {
    this[key] = getOrDefault(key, 0) + 1
}

private data class MaterializedParentFieldActivation(
    val field: FieldCoordinate,
    val depth: Int,
)

private fun EngineOutputData?.materializedParentFieldActivations(
    selections: MaterializeSelectionForest,
    parentDepth: Int = 0,
): List<MaterializedParentFieldActivation> =
    when (this) {
        is EngineObjectData.Sync ->
            selections
                .collect(schemaType)
                .byResponseKey()
                .flatMap { (responseKey, selection) ->
                    if (!isPresent(responseKey)) return@flatMap emptyList()
                    val isParent = selection.key is ObjectEngineResult.ParentKey
                    val nextParentDepth = if (isParent) parentDepth + 1 else 0
                    buildList {
                        if (isParent) {
                            add(
                                MaterializedParentFieldActivation(
                                    field =
                                        FieldCoordinate(
                                            selection.key.field.containingDef.name,
                                            selection.key.field.name,
                                        ),
                                    depth = nextParentDepth,
                                ),
                            )
                        }
                        addAll(
                            outputValue(responseKey).materializedParentFieldActivations(
                                selections = selection.subselections,
                                parentDepth = nextParentDepth,
                            ),
                        )
                    }
                }

        is List<*> ->
            flatMap { value ->
                value.materializedParentFieldActivations(selections, parentDepth)
            }

        else -> emptyList()
    }
