@file:Suppress("ForbiddenImport")

package semantics.correctresolution

import java.util.IdentityHashMap
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineErrorData
import model.EngineResult
import model.ListEngineResult
import model.NodeReferenceIdentity
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.ResolverOccurrenceId
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.Selection
import model.SelectionForest
import model.VariableBinding
import model.concatenateSelectionForests
import model.engineObjectDataOf
import model.merge
import model.nodeReferenceIdentityOrNull
import model.outputValue
import model.registry.ResolutionExecutionContext
import model.requireQueryTypeDef
import model.schemaType
import model.selectionForestOf
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.shared.SharedOperationContext
import semantics.shared.fieldResolverCycleTask
import semantics.shared.groundedArguments
import semantics.shared.isContextuallyGrounded
import semantics.shared.materializeResult
import viaduct.engine.api.EngineObjectData

internal class ReappliedResolver(
    val output: ResolverOutputData?,
)

internal class ResolverApplicationCache(
    val root: ObjectEngineResult,
    internal val rootFieldReferenceWitness: RootFieldReferenceWitness,
    private val queryOERValidation: QueryOERValidationState,
) {
    private val applications =
        IdentityHashMap<
            ObjectEngineResult,
            MutableMap<ObjectEngineResult.ObjectKey, CachedResolverApplication>,
        >()
    private val checkerApplications =
        IdentityHashMap<
            ObjectEngineResult,
            MutableMap<ObjectEngineResult.ObjectKey, CachedCheckerApplication>,
        >()
    private val rootFieldReferenceApplications =
        mutableMapOf<List<PathComponent>, CachedRootFieldReferenceApplication>()

    fun getOrPut(
        result: ObjectEngineResult,
        key: ObjectEngineResult.ObjectKey,
        compute: () -> ReappliedResolver?,
    ): ReappliedResolver? {
        val byKey = applications.getOrPut(result, ::linkedMapOf)
        return byKey.getOrPut(key) {
            CachedResolverApplication(compute())
        }.application
    }

    fun getOrPutRootFieldReference(
        reference: RootFieldReferenceData,
        publicationPath: List<PathComponent>,
        compute: () -> ReappliedResolver?,
    ): ReappliedResolver? {
        val cached = rootFieldReferenceApplications[publicationPath]
        if (cached != null) {
            return cached.application.takeIf { cached.reference == reference }
        }
        return compute().also { application ->
            rootFieldReferenceApplications[publicationPath] =
                CachedRootFieldReferenceApplication(reference, application)
        }
    }

    fun getOrPutChecker(
        result: ObjectEngineResult,
        key: ObjectEngineResult.ObjectKey,
        compute: () -> ReappliedChecker?,
    ): ReappliedChecker? {
        val byKey = checkerApplications.getOrPut(result, ::linkedMapOf)
        return byKey.getOrPut(key) {
            CachedCheckerApplication(compute())
        }.application
    }

    fun rootFieldReferenceCandidates(publicationPath: List<PathComponent>): List<IndexedRootFieldReferenceObservation>? = rootFieldReferenceWitness.claim(root, publicationPath)

    fun acceptRootFieldReference(candidate: IndexedRootFieldReferenceObservation) {
        rootFieldReferenceWitness.accept(candidate)
    }

    fun hasCompleteRootFieldReferenceWitness(): Boolean = rootFieldReferenceWitness.isComplete()

    fun queryResultConforms(
        operation: SharedOperationContext<*>,
        result: ObjectEngineResult,
        ownerSelections: ObjectSelectionForest,
        selectionsAreChecked: Boolean = true,
    ): Boolean =
        if (result === root) {
            queryOERValidation.isValidOrValidating(result) &&
                result.conformsToSelections(operation, ownerSelections) &&
                (
                    !selectionsAreChecked ||
                        result.conformsToCheckedSelectionsAt(
                            operation = operation,
                            selections = ownerSelections,
                            path = emptyList(),
                            resolverApplicationCache = this,
                        )
                )
        } else {
            queryOERValidation.validate(
                operation = operation,
                result = result,
                ownerSelections = ownerSelections,
                rootFieldReferenceWitness = rootFieldReferenceWitness,
                selectionsAreChecked = selectionsAreChecked,
            )
        }
}

/** Recursion-safe shared-OER validation with an independent conformance check for every owner. */
internal class QueryOERValidationState {
    private val results = IdentityHashMap<ObjectEngineResult, Boolean?>()
    private val replayCaches = IdentityHashMap<ObjectEngineResult, ResolverApplicationCache>()

    fun replayCache(
        root: ObjectEngineResult,
        witness: RootFieldReferenceWitness
    ): ResolverApplicationCache = replayCaches.getOrPut(root) { ResolverApplicationCache(root, witness, this) }

    fun isValidOrValidating(result: ObjectEngineResult): Boolean = results.containsKey(result) && results[result] != false

    fun validate(
        operation: SharedOperationContext<*>,
        result: ObjectEngineResult,
        ownerSelections: ObjectSelectionForest,
        rootFieldReferenceWitness: RootFieldReferenceWitness,
        selectionsAreChecked: Boolean = true,
    ): Boolean {
        if (!result.conformsToSelections(operation, ownerSelections)) return false
        if (!results.containsKey(result)) {
            results[result] = null
            val queryOER =
                (operation.resolverObserver as? CorrectnessResolverObserver)
                    ?.queryOER(result)
            val selections = queryOER?.closedValueSelections ?: ownerSelections
            val hasExactOERKeys =
                queryOER == null ||
                    result.keys.toSet() == queryOER.closedValueSelections.byKey().keys
            val valid =
                hasExactOERKeys &&
                    result.correctResolution(
                        operation,
                        selections,
                        rootFieldReferenceWitness,
                        this,
                        selectionsAreChecked = false,
                    )
            results[result] = valid
        }
        if (results[result] != true) return false
        return !selectionsAreChecked ||
            result.conformsToCheckedSelectionsAt(
                operation = operation,
                selections = ownerSelections,
                path = emptyList(),
                resolverApplicationCache =
                    resolverApplicationCache(
                        result,
                        rootFieldReferenceWitness,
                        this,
                    ),
            )
    }
}

private class CachedResolverApplication(
    val application: ReappliedResolver?,
)

private class CachedCheckerApplication(
    val application: ReappliedChecker?,
)

private class CachedRootFieldReferenceApplication(
    val reference: RootFieldReferenceData,
    val application: ReappliedResolver?,
)

internal data class IndexedRootFieldReferenceObservation(
    val index: Int,
    val observation: RootFieldReferenceInvocationObservation,
)

internal class RootFieldReferenceWitness(
    private val observations: List<RootFieldReferenceInvocationObservation>,
    private val allowedPublicationRoots: List<ObjectEngineResult>,
) {
    private val indexedObservations =
        observations.mapIndexed(::IndexedRootFieldReferenceObservation)
    private val claimedPublicationPaths =
        IdentityHashMap<ObjectEngineResult, MutableSet<List<PathComponent>>>()
    private val acceptedIndices = linkedSetOf<Int>()
    private val forbiddenInvocationRoots =
        buildList {
            addAll(allowedPublicationRoots)
            addAll(observations.map { observation -> observation.publicationRoot })
        }

    fun claim(
        publicationRoot: ObjectEngineResult,
        publicationPath: List<PathComponent>,
    ): List<IndexedRootFieldReferenceObservation>? {
        if (!claimedPublicationPaths.getOrPut(publicationRoot, ::linkedSetOf).add(publicationPath)) {
            return null
        }
        return indexedObservations.filter { candidate ->
            candidate.observation.publicationRoot === publicationRoot &&
                candidate.observation.publicationPath == publicationPath
        }
    }

    fun accept(candidate: IndexedRootFieldReferenceObservation) {
        check(acceptedIndices.add(candidate.index)) {
            "Root-field-reference observation was accepted twice"
        }
    }

    fun isComplete(): Boolean =
        observations.haveDistinctInvocationRoots() &&
            observations.all { observation ->
                allowedPublicationRoots.any { allowedRoot ->
                    observation.publicationRoot === allowedRoot
                }
            } &&
            indexedObservations.none { candidate ->
                forbiddenInvocationRoots.any { forbiddenRoot ->
                    candidate.observation.invocationRoot === forbiddenRoot
                }
            } &&
            acceptedIndices == indexedObservations.mapTo(linkedSetOf()) { candidate -> candidate.index }

    fun validatedObservations(): List<RootFieldReferenceInvocationObservation> =
        indexedObservations
            .filter { candidate -> candidate.index in acceptedIndices }
            .map(IndexedRootFieldReferenceObservation::observation)
}

private fun List<RootFieldReferenceInvocationObservation>.haveDistinctInvocationRoots(): Boolean {
    val roots = IdentityHashMap<ObjectEngineResult, Unit>()
    return all { observation -> roots.put(observation.invocationRoot, Unit) == null }
}

internal fun SharedOperationContext<*>.rootFieldReferenceWitness(primaryRoot: ObjectEngineResult): RootFieldReferenceWitness {
    val observations = resolverObserver as? CorrectnessResolverObserver
    return RootFieldReferenceWitness(
        observations = observations?.rootFieldReferenceInvocations().orEmpty(),
        allowedPublicationRoots =
            listOf(primaryRoot) +
                observations
                    ?.allQueryFragmentResults()
                    ?.values
                    ?.flatten()
                    .orEmpty() +
                (checkerObserver as? CorrectnessCheckerObserver)
                    ?.allQueryFragmentResults()
                    ?.values
                    ?.flatten()
                    .orEmpty(),
    )
}

internal fun resolverApplicationCache(
    root: ObjectEngineResult,
    rootFieldReferenceWitness: RootFieldReferenceWitness,
    queryOERValidation: QueryOERValidationState = QueryOERValidationState(),
): ResolverApplicationCache = queryOERValidation.replayCache(root, rootFieldReferenceWitness)

internal fun SharedOperationContext<*>.resolverApplicationCache(root: ObjectEngineResult): ResolverApplicationCache = resolverApplicationCache(root, rootFieldReferenceWitness(root))

/** Reference invocations published beneath this root and justified by deterministic replay. */
internal fun ObjectEngineResult.ownedRootFieldReferenceInvocations(operation: SharedOperationContext<*>): List<
    RootFieldReferenceInvocationObservation,
> {
    val witness = operation.rootFieldReferenceWitness(this)
    val cache = resolverApplicationCache(this, witness)
    check(conformsToResolvers(operation, cache)) {
        "Cannot reconstruct root-field-reference applications from a nonconforming result"
    }
    return witness.validatedObservations()
}

/**
 * Reconstructs source ownership while traversing the completed result.
 *
 * The extensional correctness judgment re-evaluates deterministic resolver relations. An
 * argumentless field present in that output belongs to its ancestor source; an absent registered
 * field belongs to its standard resolver.
 */
internal fun ObjectEngineResult.reapplyResolver(
    operation: SharedOperationContext<*>,
    resolverApplicationCache: ResolverApplicationCache,
    key: ObjectEngineResult.ObjectKey,
    path: List<PathComponent>,
): ReappliedResolver? = ResolverReplayLogic(operation, resolverApplicationCache).reapply(this, key, path)

/** Reapplies every independently rooted resolver hop that justified one consumer value. */
internal fun SharedOperationContext<*>.reapplyRootFieldReference(
    resolverApplicationCache: ResolverApplicationCache,
    reference: RootFieldReferenceData,
    publicationRoot: ObjectEngineResult,
    publicationPath: List<PathComponent>,
    validationDemand: SelectionForest,
): ReappliedResolver? =
    ResolverReplayLogic(this, resolverApplicationCache).reapplyRootFieldReference(
        reference,
        publicationRoot,
        publicationPath,
        validationDemand,
    )

/**
 * Replays deterministic resolver relations using one operation and an existing per-result cache.
 * Nested Query validation creates its own cache while retaining this cache's reference witness.
 * This logic neither allocates replacement caches nor changes witness ownership.
 */
private class ResolverReplayLogic(
    private val operation: SharedOperationContext<*>,
    private val resolverApplicationCache: ResolverApplicationCache,
) {
    fun reapply(
        result: ObjectEngineResult,
        key: ObjectEngineResult.ObjectKey,
        path: List<PathComponent>,
    ): ReappliedResolver? = result.reapplyResolver(key, path)

    private fun ObjectEngineResult.reapplyResolver(
        key: ObjectEngineResult.ObjectKey,
        path: List<PathComponent>,
    ): ReappliedResolver? =
        resolverApplicationCache.getOrPut(this, key) {
            val arguments = key.groundedArguments(operation) as? Arguments.Resolved ?: return@getOrPut null
            val resolver = operation.world.resolverRegistry.resolver(key.field)
            val coordinate = path + key
            val fragments =
                resolver.fragmentsSatisfiedBy(
                    operation = operation,
                    root = resolverApplicationCache.root,
                    result = this,
                    path = coordinate,
                ) ?: return@getOrPut null
            val objectFragment = fragments.objectFragment
            if (
                !conformsToCheckedSelectionsAt(
                    operation = operation,
                    selections = objectFragment.constructionSelections,
                    path = path,
                    resolverApplicationCache = resolverApplicationCache,
                )
            ) {
                return@getOrPut null
            }
            val input: EngineObjectData.Sync =
                runBlocking {
                    materializeResult(
                        operation = operation,
                        selections =
                            resolver.instantiateObjectMaterializationSelections(
                                objectFragment.resolverOccurrenceId,
                            ),
                        reader = resolverApplicationCache.root.fieldResolverCycleTask(coordinate),
                    )
                }
            val resolverArguments =
                Arguments.Resolved.of(
                    field = key.field,
                    fields = arguments.fieldValues,
                )
            val resolverOccurrenceId = objectFragment.resolverOccurrenceId
            val queryFragment = fragments.queryFragment
            val queryValue =
                if (queryFragment.constructionSelections.isEmpty()) {
                    engineObjectDataOf(operation.world.schema.requireQueryTypeDef())
                } else {
                    val queryResult =
                        (operation.resolverObserver as? CorrectnessResolverObserver)
                            ?.queryFragmentResults(resolverOccurrenceId)
                            ?.singleOrNull()
                            ?: return@getOrPut null
                    val querySelections =
                        queryFragment.constructionSelections
                            .merge(operation.world.schema.requireQueryTypeDef())
                    if (
                        !resolverApplicationCache.queryResultConforms(
                            operation,
                            queryResult,
                            querySelections,
                        )
                    ) {
                        return@getOrPut null
                    }
                    runBlocking {
                        queryResult.materializeResult(
                            operation = operation,
                            selections =
                                resolver.instantiateQueryMaterializationSelections(
                                    queryFragment.resolverOccurrenceId,
                                ),
                            reader = resolverApplicationCache.root.fieldResolverCycleTask(coordinate),
                        )
                    }
                }
            if (
                !operation.observedResolverInputsConform(
                    resolverOccurrenceId = resolverOccurrenceId,
                    expectedObjectValue = input,
                    expectedQueryValue = queryValue,
                )
            ) {
                return@getOrPut null
            }
            ReappliedResolver(
                runBlocking {
                    resolver.evaluateRelation(
                        input = input,
                        queryValue = queryValue,
                        arguments = resolverArguments,
                        selections = getCell(key).value.get().completedOutputDemand(),
                        selectiveResolvers = operation.world.selectiveResolvers,
                        executionContext = ResolutionExecutionContext.Unsupported,
                    )
                },
            )
        }

    /** Reapplies every independently rooted resolver hop that justified one consumer value. */
    fun reapplyRootFieldReference(
        reference: RootFieldReferenceData,
        publicationRoot: ObjectEngineResult,
        publicationPath: List<PathComponent>,
        validationDemand: SelectionForest,
    ): ReappliedResolver? =
        resolverApplicationCache.getOrPutRootFieldReference(reference, publicationPath) compute@{
            if (publicationRoot !== resolverApplicationCache.root) return@compute null
            val candidates =
                resolverApplicationCache.rootFieldReferenceCandidates(publicationPath)
                    ?: return@compute null
            if (candidates.isEmpty()) return@compute null

            val authoritativeNodeIdentity = reference.nodeReferenceIdentityOrNull()
            var expectedReference = reference
            candidates.forEach { candidate ->
                val observation = candidate.observation
                if (!observation.matches(expectedReference, publicationRoot)) return@compute null
                val application =
                    observation.reapplyReferencedResolver(validationDemand) ?: return@compute null
                resolverApplicationCache.acceptRootFieldReference(candidate)
                val output = application.output
                if (output is RootFieldReferenceData) {
                    expectedReference = output
                } else {
                    return@compute ReappliedResolver(
                        output.withAuthoritativeNodeId(authoritativeNodeIdentity, validationDemand),
                    )
                }
            }
            null
        }

    private fun ResolverOutputData?.withAuthoritativeNodeId(
        identity: NodeReferenceIdentity?,
        demand: SelectionForest,
    ): ResolverOutputData? {
        if (identity == null || this !is EngineObjectData.Sync) return this
        if (schemaType != identity.type) return this
        val idField = identity.type.field("id") ?: return this
        if (demand
                .merge(identity.type)
                .byKey()
                .keys
                .none { key -> key.field == idField }
        ) {
            return this
        }
        return engineObjectDataOf(
            identity.type,
            getSelections().associateWith(::outputValue) + (idField.name to identity.id),
        )
    }

    private fun RootFieldReferenceInvocationObservation.matches(
        expectedReference: RootFieldReferenceData,
        expectedPublicationRoot: ObjectEngineResult,
    ): Boolean {
        val expectedInvocationPath: List<PathComponent> =
            expectedReference.path.mapIndexed { index, field ->
                ObjectEngineResult.GroundKey.of(
                    field = field,
                    arguments =
                        if (index == expectedReference.path.lastIndex) {
                            expectedReference.arguments
                        } else {
                            Arguments.Resolved.of(field, emptyMap())
                        },
                )
            }
        return reference == expectedReference &&
            invocationRoot !== expectedPublicationRoot &&
            invocationRoot.type == operation.world.schema.requireQueryTypeDef() &&
            invocationRoot.keys.isEmpty() &&
            invocationPath == expectedInvocationPath &&
            invocationKey == expectedInvocationPath.last()
    }

    private fun RootFieldReferenceInvocationObservation.reapplyReferencedResolver(validationDemand: SelectionForest): ReappliedResolver? {
        if (!invocationKey.isContextuallyGrounded(operation)) return null
        val arguments = invocationKey.groundedArguments(operation) as? Arguments.Resolved ?: return null
        val resolver = operation.world.resolverRegistry.resolver(invocationKey.field)
        val fragments = resolver.instantiateFragmentsAt(invocationRoot, invocationPath)
        if (!fragments.objectFragment.constructionSelections.isEmpty()) {
            return null
        }
        val input = engineObjectDataOf(invocationKey.field.containingDef)
        val resolverArguments =
            Arguments.Resolved.of(
                field = invocationKey.field,
                fields = arguments.fieldValues,
            )
        val resolverOccurrenceId = fragments.objectFragment.resolverOccurrenceId
        if (resolverOccurrenceId != ResolverOccurrenceId.at(invocationRoot, invocationPath)) return null
        if (
            resolver.instantiatedVariableDefinitions(resolverOccurrenceId).any { definition ->
                val instanceId = requireNotNull(definition.variable.instanceId)
                val source = definition.definition
                !operation.variableBindings.isBound(instanceId) ||
                    (
                        source is model.registry.VariableDefinition.FromArgument &&
                            operation.variableBindings.getBinding(instanceId) !=
                            VariableBinding.of(source.read(arguments))
                    )
            }
        ) {
            return null
        }
        val queryFragment = fragments.queryFragment
        val queryValue =
            if (queryFragment.constructionSelections.isEmpty()) {
                engineObjectDataOf(operation.world.schema.requireQueryTypeDef())
            } else {
                val queryResult =
                    (operation.resolverObserver as? CorrectnessResolverObserver)
                        ?.queryFragmentResults(resolverOccurrenceId)
                        ?.singleOrNull()
                        ?: return null
                val querySelections =
                    queryFragment.constructionSelections.merge(operation.world.schema.requireQueryTypeDef())
                if (
                    !resolverApplicationCache.queryResultConforms(
                        operation,
                        queryResult,
                        querySelections,
                    )
                ) {
                    return null
                }
                runBlocking {
                    queryResult.materializeResult(
                        operation = operation,
                        selections =
                            resolver.instantiateQueryMaterializationSelections(
                                queryFragment.resolverOccurrenceId,
                            ),
                        reader = invocationRoot.fieldResolverCycleTask(invocationPath),
                    )
                }
            }
        if (
            !operation.observedResolverInputsConform(
                resolverOccurrenceId = resolverOccurrenceId,
                expectedObjectValue = input,
                expectedQueryValue = queryValue,
            )
        ) {
            return null
        }
        return ReappliedResolver(
            runBlocking {
                resolver.evaluateRelation(
                    input = input,
                    queryValue = queryValue,
                    arguments = resolverArguments,
                    selections = validationDemand,
                    selectiveResolvers = operation.world.selectiveResolvers,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            },
        )
    }
}

/**
 * Validates the access-filtered values actually supplied at runtime against correctness replay.
 * Hand-constructed extensional judgments without invocation evidence retain their historical
 * value-only behavior.
 */
private fun SharedOperationContext<*>.observedResolverInputsConform(
    resolverOccurrenceId: ResolverOccurrenceId,
    expectedObjectValue: EngineObjectData.Sync,
    expectedQueryValue: EngineObjectData.Sync,
): Boolean {
    val observations = resolverObserver as? CorrectnessResolverObserver ?: return true
    if (!observations.hasResolverInvocations()) return true
    val invocations = observations.resolverInvocations(resolverOccurrenceId)
    return invocations.isNotEmpty() &&
        invocations.all { invocation ->
            invocation.input.sameMaterializedValueAs(expectedObjectValue) &&
                invocation.queryValue.sameMaterializedValueAs(expectedQueryValue)
        }
}

private fun EngineObjectData.Sync.sameMaterializedValueAs(other: EngineObjectData.Sync): Boolean {
    if (schemaType != other.schemaType) return false
    val selections = getSelections().toSet()
    if (selections != other.getSelections().toSet()) return false
    return selections.all { selection ->
        outputValue(selection).sameMaterializedValueAs(other.outputValue(selection))
    }
}

private fun ResolverOutputData?.sameMaterializedValueAs(other: ResolverOutputData?): Boolean =
    when {
        this is EngineErrorData && other is EngineErrorData ->
            cause === other.cause || cause == null && other.cause == null
        this is EngineObjectData.Sync && other is EngineObjectData.Sync ->
            sameMaterializedValueAs(other)
        this is List<*> && other is List<*> ->
            size == other.size &&
                indices.all { index ->
                    this[index].sameMaterializedValueAs(other[index])
                }
        else -> this == other
    }

/**
 * Reconstructs one canonical demand from the completed output occurrence under judgment.
 *
 * This is an extensional reapplication input, not a claim about the exact demand supplied by a
 * resolver algorithm. Selective resolver relations are required to agree on coordinates shared by
 * different demands, so this demand is sufficient for completed-result correctness without adding
 * scheduler witnesses to the judgment.
 */
internal fun EngineResult?.completedOutputDemand(): SelectionForest =
    when (this) {
        is ObjectEngineResult ->
            keys
                .filter { key ->
                    key !is ObjectEngineResult.ParentKey &&
                        getCell(key).value.isCompleted
                }
                .map { key ->
                    selectionForestOf(
                        Selection.of(
                            key = key,
                            possibleTypes = setOf(type),
                            subselections =
                                getCell(key)
                                    .value
                                    .get()
                                    .completedOutputDemand(),
                        ),
                    )
                }.concatenateSelectionForests()
        is ListEngineResult ->
            indices
                .map { index -> get(index).value.get().completedOutputDemand() }
                .concatenateSelectionForests()
        else -> selectionForestOf()
    }

internal fun EngineObjectData.Sync?.requireArgumentlessField(key: ObjectEngineResult.ObjectKey) {
    if (this?.isPresent(key.field.name) == true) {
        require(key.field.args.isEmpty()) {
            "Resolver output must not supply argument-bearing field " +
                "${key.field.containingDef.name}/${key.field.name}"
        }
    }
}
