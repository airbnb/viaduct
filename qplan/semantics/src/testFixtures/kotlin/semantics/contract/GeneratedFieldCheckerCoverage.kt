package semantics.contract

import java.util.Collections
import java.util.IdentityHashMap
import model.ListEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.requireObjectField
import semantics.arbitrary.ArbitraryRegistry
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.GeneratedFieldCheckerMode
import semantics.arbitrary.ResolverTestRun
import semantics.correctresolution.CorrectnessCheckerObserver
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.CheckerInvocationObservation

/** Independently observable interactions in a generated field-checker case. */
internal enum class GeneratedFieldCheckerCoverageSignature {
    FIELD_CHECKER,
    NONEMPTY_OBJECT_FRAGMENT,
    NONEMPTY_QUERY_FRAGMENT,
    FROM_ARGUMENT_VARIABLE,
    NESTED_FROM_ARGUMENT_VARIABLE,
    NULLABLE_FROM_ARGUMENT_TRAVERSAL,
    PARENT_FIELD_DEMAND,
    DUPLICATE_NAMED_PAIR_PROJECTIONS,
    EMPTY_NAMED_PAIR,
    CHECKER_ONLY_OBJECT_DEMAND,
    CHECKER_ONLY_QUERY_DEMAND,
    PASSIVE_CHECKED_FIELD,
    SHARED_QUERY_OER_MULTIPLE_OWNERS,
    CHECKER_IN_ASSOCIATED_QUERY_OER,
    LIST_ELEMENT_OCCURRENCE,
    REPEATED_CHECKER_COORDINATE,
    ARGUMENT_DISTINCT_OCCURRENCES,
    ROOT_FIELD_REFERENCE_RESULT,
    CHECKER_SUCCESS,
    CHECKER_DENIAL,
    RESOLVER_WITHOUT_CHECKER,
}

/** Generated potential and runtime activation evidence for one generated case. */
internal data class GeneratedFieldCheckerCoverageSnapshot(
    val generated: Set<GeneratedFieldCheckerCoverageSignature>,
    val activated: Set<GeneratedFieldCheckerCoverageSignature>,
    val activatedApplications: Map<GeneratedFieldCheckerCoverageSignature, Int>,
) {
    init {
        require(generated.containsAll(activated)) {
            "Activated field-checker signatures were not generated: ${activated - generated}"
        }
    }
}

/** Aggregate signature counts, kept separate for generation and runtime activation. */
internal class GeneratedFieldCheckerCoverage(
    private val mode: GeneratedFieldCheckerMode,
) {
    private val generatedCases =
        GeneratedFieldCheckerCoverageSignature.entries.associateWithTo(linkedMapOf()) { 0 }
    private val activatedCases =
        GeneratedFieldCheckerCoverageSignature.entries.associateWithTo(linkedMapOf()) { 0 }
    private val activatedApplications =
        GeneratedFieldCheckerCoverageSignature.entries.associateWithTo(linkedMapOf()) { 0 }

    fun record(
        registry: ArbitraryRegistry,
        observation: GeneratedCaseObservation,
    ) {
        val snapshot = registry.fieldCheckerCoverage(mode, observation)
        snapshot.generated.forEach { signature -> generatedCases.increment(signature) }
        snapshot.activated.forEach { signature -> activatedCases.increment(signature) }
        snapshot.activatedApplications.forEach { (signature, count) ->
            activatedApplications.increment(signature, count)
        }
    }

    fun assertRequired(
        run: ResolverTestRun,
        required: Set<GeneratedFieldCheckerCoverageSignature>,
    ) {
        val missingGenerated = required.filterTo(linkedSetOf()) { generatedCases.getValue(it) == 0 }
        val missingActivated = required.filterTo(linkedSetOf()) { activatedCases.getValue(it) == 0 }
        run.assertAggregate(
            missingGenerated.isEmpty() && missingActivated.isEmpty(),
            buildString {
                append("Generated field-checker profile missed required coverage signatures")
                if (missingGenerated.isNotEmpty()) append("; not generated=$missingGenerated")
                if (missingActivated.isNotEmpty()) append("; not activated=$missingActivated")
                append("; ")
                append(summary())
            },
        )
    }

    fun summary(): String =
        "generatedCases=$generatedCases, " +
            "activatedCases=$activatedCases, " +
            "activatedApplications=$activatedApplications"
}

private fun ArbitraryRegistry.fieldCheckerCoverage(
    mode: GeneratedFieldCheckerMode,
    observation: GeneratedCaseObservation,
): GeneratedFieldCheckerCoverageSnapshot {
    val installedCheckerCoordinates = generatedFieldCheckerCoordinates(mode)
    val generated =
        installedCheckerCoordinates
            .flatMapTo(linkedSetOf()) { sourceField -> generatedCheckerSignatures(sourceField) }
            .apply {
                if (installedCheckerCoordinates.isNotEmpty()) {
                    add(GeneratedFieldCheckerCoverageSignature.DUPLICATE_NAMED_PAIR_PROJECTIONS)
                    add(GeneratedFieldCheckerCoverageSignature.EMPTY_NAMED_PAIR)
                }
                if (
                    generatedFieldCheckerOnlyObjectDemandCoordinates.any(
                        installedCheckerCoordinates::contains,
                    )
                ) {
                    add(GeneratedFieldCheckerCoverageSignature.CHECKER_ONLY_OBJECT_DEMAND)
                }
                if (
                    generatedFieldCheckerOnlyQueryDemandCoordinates.any(
                        installedCheckerCoordinates::contains,
                    )
                ) {
                    add(GeneratedFieldCheckerCoverageSignature.CHECKER_ONLY_QUERY_DEMAND)
                }
                if (
                    features.sometimesPassiveFieldCount > 0 ||
                    features.generatedRootFieldReferenceCount > 0
                ) {
                    add(GeneratedFieldCheckerCoverageSignature.PASSIVE_CHECKED_FIELD)
                }
                if (installedCheckerCoordinates.size > 1) {
                    add(GeneratedFieldCheckerCoverageSignature.SHARED_QUERY_OER_MULTIPLE_OWNERS)
                }
                if (features.queryFragmentCount > 0) {
                    add(GeneratedFieldCheckerCoverageSignature.CHECKER_IN_ASSOCIATED_QUERY_OER)
                }
                if (observation.testCase.schema.features.hasOutputLists) {
                    add(GeneratedFieldCheckerCoverageSignature.LIST_ELEMENT_OCCURRENCE)
                    add(GeneratedFieldCheckerCoverageSignature.REPEATED_CHECKER_COORDINATE)
                }
                if (
                    observation.testCase.query.features.hasDuplicateSelections ||
                    features.queryFragmentCount > 0
                ) {
                    add(GeneratedFieldCheckerCoverageSignature.REPEATED_CHECKER_COORDINATE)
                }
                if (
                    observation.testCase.query.features.hasDistinctArgumentSelections ||
                    installedCheckerCoordinates.any { coordinate ->
                        observation.ordinary.world.schema
                            .requireObjectField(coordinate.typeName, coordinate.fieldName)
                            .args
                            .isNotEmpty()
                    }
                ) {
                    add(GeneratedFieldCheckerCoverageSignature.ARGUMENT_DISTINCT_OCCURRENCES)
                }
                if (
                    installedCheckerCoordinates.isNotEmpty() &&
                    features.generatedRootFieldReferenceCount > 0
                ) {
                    add(GeneratedFieldCheckerCoverageSignature.ROOT_FIELD_REFERENCE_RESULT)
                }
                if (
                    installedCheckerCoordinates.any { coordinate ->
                        !generatedFieldCheckerDenies(coordinate, mode)
                    }
                ) {
                    add(GeneratedFieldCheckerCoverageSignature.CHECKER_SUCCESS)
                }
                if (
                    installedCheckerCoordinates.any { coordinate ->
                        generatedFieldCheckerDenies(coordinate, mode)
                    }
                ) {
                    add(GeneratedFieldCheckerCoverageSignature.CHECKER_DENIAL)
                }
                if ((generatedFieldCheckerCoordinates - installedCheckerCoordinates).isNotEmpty()) {
                    add(GeneratedFieldCheckerCoverageSignature.RESOLVER_WITHOUT_CHECKER)
                }
            }
    val activatedApplications = linkedMapOf<GeneratedFieldCheckerCoverageSignature, Int>()
    observation.executions.forEach { execution ->
        val resolverObserver = execution.operation.resolverObserver as CorrectnessResolverObserver
        val checkerObserver = execution.operation.checkerObserver as CorrectnessCheckerObserver
        val associatedRoots =
            Collections.newSetFromMap(IdentityHashMap<ObjectEngineResult, Boolean>()).apply {
                resolverObserver.allQueryFragmentResults().values.flatten().forEach(::add)
                checkerObserver.allQueryFragmentResults().values.flatten().forEach(::add)
            }
        val ownersByAssociatedRoot =
            IdentityHashMap<ObjectEngineResult, MutableSet<ResolverOccurrenceId>>()
        resolverObserver.allQueryFragmentResults().forEach { (owner, roots) ->
            roots.forEach { root ->
                ownersByAssociatedRoot.computeIfAbsent(root) { linkedSetOf() }.add(owner)
            }
        }
        checkerObserver.allQueryFragmentResults().forEach { (owner, roots) ->
            roots.forEach { root ->
                ownersByAssociatedRoot.computeIfAbsent(root) { linkedSetOf() }.add(owner)
            }
        }
        ownersByAssociatedRoot.values
            .filter { owners -> owners.size > 1 }
            .forEach { owners ->
                activatedApplications.increment(
                    GeneratedFieldCheckerCoverageSignature.SHARED_QUERY_OER_MULTIPLE_OWNERS,
                    owners.size,
                )
            }
        val invokedResolvers = resolverObserver.invokedResolverOccurrences()
        execution.checkerApplications.forEach { application ->
            val sourceField =
                sourceResolverCoordinate(
                    FieldCoordinate(
                        application.checkedCoordinate.containingDef.name,
                        application.checkedCoordinate.name,
                    ),
                )
            generatedCheckerSignatures(sourceField).forEach { signature ->
                activatedApplications.increment(signature)
            }
            activatedApplications.increment(
                if (generatedFieldCheckerDenies(sourceField, mode)) {
                    GeneratedFieldCheckerCoverageSignature.CHECKER_DENIAL
                } else {
                    GeneratedFieldCheckerCoverageSignature.CHECKER_SUCCESS
                },
            )
            if (sourceField in generatedFieldCheckerOnlyObjectDemandCoordinates) {
                activatedApplications.increment(
                    GeneratedFieldCheckerCoverageSignature.CHECKER_ONLY_OBJECT_DEMAND,
                )
            }
            if (sourceField in generatedFieldCheckerOnlyQueryDemandCoordinates) {
                activatedApplications.increment(
                    GeneratedFieldCheckerCoverageSignature.CHECKER_ONLY_QUERY_DEMAND,
                )
            }
            activatedApplications.increment(
                GeneratedFieldCheckerCoverageSignature.DUPLICATE_NAMED_PAIR_PROJECTIONS,
            )
            activatedApplications.increment(GeneratedFieldCheckerCoverageSignature.EMPTY_NAMED_PAIR)
            if (
                ResolverOccurrenceId.at(
                    application.logicalQueryRoot,
                    application.occurrencePath,
                ) !in invokedResolvers
            ) {
                activatedApplications.increment(
                    GeneratedFieldCheckerCoverageSignature.PASSIVE_CHECKED_FIELD,
                )
            }
            if (associatedRoots.contains(application.logicalQueryRoot)) {
                activatedApplications.increment(
                    GeneratedFieldCheckerCoverageSignature.CHECKER_IN_ASSOCIATED_QUERY_OER,
                )
            }
            if (application.occurrencePath.any { component -> component is ListEngineResult.Index }) {
                activatedApplications.increment(
                    GeneratedFieldCheckerCoverageSignature.LIST_ELEMENT_OCCURRENCE,
                )
            }
            if (
                resolverObserver.rootFieldReferenceInvocations().any { reference ->
                    reference.publicationRoot === application.logicalQueryRoot &&
                        application.occurrencePath.startsWith(reference.publicationPath)
                }
            ) {
                activatedApplications.increment(
                    GeneratedFieldCheckerCoverageSignature.ROOT_FIELD_REFERENCE_RESULT,
                )
            }
        }
        execution.checkerApplications
            .groupBy(CheckerInvocationObservation::checkedCoordinate)
            .values
            .filter { applications -> applications.size > 1 }
            .forEach { applications ->
                activatedApplications.increment(
                    GeneratedFieldCheckerCoverageSignature.REPEATED_CHECKER_COORDINATE,
                    applications.size,
                )
                val distinctArguments =
                    applications
                        .map { application -> application.arguments.fieldValues }
                        .distinct()
                if (distinctArguments.size > 1) {
                    check(distinctArguments.any(Map<*, *>::isNotEmpty)) {
                        "Argument-distinct checker classification found only empty tuples: " +
                            applications.map(CheckerInvocationObservation::arguments)
                    }
                    activatedApplications.increment(
                        GeneratedFieldCheckerCoverageSignature.ARGUMENT_DISTINCT_OCCURRENCES,
                        applications.size,
                    )
                }
            }
        resolverObserver.allResolverInvocations()
            .map { invocation ->
                sourceResolverCoordinate(
                    FieldCoordinate(
                        invocation.field.containingDef.name,
                        invocation.field.name,
                    ),
                )
            }.filter { coordinate ->
                coordinate in generatedFieldCheckerCoordinates &&
                    coordinate !in installedCheckerCoordinates
            }
            .forEach {
                activatedApplications.increment(
                    GeneratedFieldCheckerCoverageSignature.RESOLVER_WITHOUT_CHECKER,
                )
            }
    }
    return GeneratedFieldCheckerCoverageSnapshot(
        generated = generated,
        activated = activatedApplications.keys,
        activatedApplications = activatedApplications,
    )
}

private fun <T> List<T>.startsWith(prefix: List<T>): Boolean =
    size >= prefix.size && take(prefix.size) == prefix

private fun ArbitraryRegistry.generatedCheckerSignatures(
    sourceField: FieldCoordinate,
): Set<GeneratedFieldCheckerCoverageSignature> =
    buildSet {
        add(GeneratedFieldCheckerCoverageSignature.FIELD_CHECKER)
        if (objectFragmentSources.getValue(sourceField).isNotEmpty()) {
            add(GeneratedFieldCheckerCoverageSignature.NONEMPTY_OBJECT_FRAGMENT)
        }
        if (queryFragmentSources.getValue(sourceField).isNotEmpty()) {
            add(GeneratedFieldCheckerCoverageSignature.NONEMPTY_QUERY_FRAGMENT)
        }
        if (sourceField in fromArgumentVariableOwnerFields) {
            add(GeneratedFieldCheckerCoverageSignature.FROM_ARGUMENT_VARIABLE)
        }
        if (sourceField in nestedFromArgumentVariableOwnerFields) {
            add(GeneratedFieldCheckerCoverageSignature.NESTED_FROM_ARGUMENT_VARIABLE)
        }
        if (sourceField in nullableTraversalFromArgumentVariableOwnerFields) {
            add(GeneratedFieldCheckerCoverageSignature.NULLABLE_FROM_ARGUMENT_TRAVERSAL)
        }
        if (sourceField in parentDemandOwnerFields) {
            add(GeneratedFieldCheckerCoverageSignature.PARENT_FIELD_DEMAND)
        }
    }

private fun <K> MutableMap<K, Int>.increment(
    key: K,
    count: Int = 1,
) {
    this[key] = getOrDefault(key, 0) + count
}
