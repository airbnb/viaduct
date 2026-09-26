package semantics.contract

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineResult
import model.engineObjectDataOf
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.registry.FieldResolver
import model.registry.ResolverFragment
import model.usedVariables
import semantics.arbitrary.ResolverApplicationIdentity
import semantics.arbitrary.ResolverApplicationKey
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.ResolverOccurrenceApplicationKey
import semantics.arbitrary.ResolverOccurrenceApplicationIdentity
import semantics.arbitrary.resolutionFingerprint
import semantics.correctresolution.conformsToSelectionsAt
import semantics.correctresolution.ownedRootFieldReferenceInvocations
import semantics.shared.materializeResult
import semantics.shared.groundedArguments
import semantics.shared.SharedOperationContext
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.RootFieldReferenceInvocationObservation

/**
 * Expected deterministic resolver applications reconstructed from every request-local Query root.
 *
 * The receiver is the primary result root; Query-fragment roots come from resolver observations.
 * Ordinary occurrences are independent of the observed application stream. Symbolic-reference
 * occurrences necessarily use invocation observations for their fresh roots and paths, but only
 * after source-ownership replay justifies each observed hop from the completed results under test.
 */
fun EngineResult?.registeredResolverApplicationIdentityCounts(operation: SharedOperationContext<*>):
    Map<ResolverApplicationIdentity, Int> {
    val counts = linkedMapOf<ResolverApplicationIdentity, Int>()
    fun record(
        root: ObjectEngineResult,
        cell: RegisteredResolverOccurrence,
    ) {
        val resolver = operation.world.resolverRegistry.resolver(cell.field)
        val fragment =
            resolver.objectFragmentSatisfiedBy(
                operation = operation,
                root = root,
                result = cell.containingObject,
                path = cell.occurrencePath,
            ) ?: error("Registered resolver occurrence has no complete object fragment")
        val identity =
            ResolverApplicationIdentity(
                key = cell.applicationKey,
                inputFingerprint =
                    runBlocking {
                        cell.containingObject
                            .materializeResult(
                                operation = operation,
                                selections =
                                    resolver.instantiateObjectMaterializationSelections(
                                        fragment.resolverOccurrenceId,
                                    ),
                                reader = cell.occurrencePath,
                            ).resolutionFingerprint()
                    },
            )
        counts.increment(identity)
    }
    requestQueryRoots(operation).forEach { root ->
        root.forEachRegisteredResolverOccurrence(operation, operation.world.resolverRegistry) { cell -> record(root, cell) }
    }
    rootFieldReferenceOccurrences(operation).forEach { occurrence ->
        counts.increment(
            ResolverApplicationIdentity(
                key = occurrence.applicationKey(operation),
                inputFingerprint =
                    engineObjectDataOf(occurrence.invocationKey.field.containingDef)
                        .resolutionFingerprint(),
            ),
        )
    }
    return counts
}

/** Expected deterministic applications qualified by their exact request-local Query root and path. */
fun EngineResult?.registeredResolverOccurrenceApplicationIdentityCounts(operation: SharedOperationContext<*>): Map<
    ResolverOccurrenceApplicationIdentity,
    Int,
> =
    reconstructResolverOccurrenceApplicationIdentityCounts(operation, null)

/**
 * Expected exact identities for the requested occurrences only.
 *
 * This supports sometimes-passive validation: a skipped standard resolver can retain unbound
 * object-fragment variables, while every actually observed application has complete bindings.
 */
fun EngineResult?.registeredResolverOccurrenceApplicationIdentityCountsFor(
    operation: SharedOperationContext<*>,
    includedOccurrences: Set<ResolverOccurrenceId>,
): Map<ResolverOccurrenceApplicationIdentity, Int> =
    reconstructResolverOccurrenceApplicationIdentityCounts(operation, includedOccurrences)

private fun EngineResult?.reconstructResolverOccurrenceApplicationIdentityCounts(
    operation: SharedOperationContext<*>,
    includedOccurrences: Set<ResolverOccurrenceId>?,
): Map<ResolverOccurrenceApplicationIdentity, Int> {
    val counts = linkedMapOf<ResolverOccurrenceApplicationIdentity, Int>()
    fun record(
        root: ObjectEngineResult,
        cell: RegisteredResolverOccurrence,
    ) {
        val resolverOccurrenceId = ResolverOccurrenceId.at(root, cell.occurrencePath)
        if (includedOccurrences != null && resolverOccurrenceId !in includedOccurrences) return
        val resolver = operation.world.resolverRegistry.resolver(cell.field)
        val fragment =
            resolver.objectFragmentSatisfiedBy(
                operation = operation,
                root = root,
                result = cell.containingObject,
                path = cell.occurrencePath,
            ) ?: error("Registered resolver occurrence has no complete object fragment")
        val identity =
            ResolverOccurrenceApplicationIdentity(
                resolverOccurrenceId = resolverOccurrenceId,
                applicationIdentity =
                    ResolverApplicationIdentity(
                        key = cell.applicationKey,
                        inputFingerprint =
                            runBlocking {
                                cell.containingObject
                                    .materializeResult(
                                        operation = operation,
                                        selections =
                                            resolver.instantiateObjectMaterializationSelections(
                                                fragment.resolverOccurrenceId,
                                            ),
                                        reader = cell.occurrencePath,
                                    ).resolutionFingerprint()
                            },
                    ),
            )
        counts.increment(identity)
    }
    requestQueryRoots(operation).forEach { root ->
        root.forEachRegisteredResolverOccurrence(operation, operation.world.resolverRegistry) { cell ->
            record(root, cell)
        }
    }
    rootFieldReferenceOccurrences(operation).forEach { occurrence ->
        val resolverOccurrenceId =
            ResolverOccurrenceId.at(occurrence.invocationRoot, occurrence.invocationPath)
        if (includedOccurrences == null || resolverOccurrenceId in includedOccurrences) {
            counts.increment(
                ResolverOccurrenceApplicationIdentity(
                    resolverOccurrenceId = resolverOccurrenceId,
                    applicationIdentity =
                        ResolverApplicationIdentity(
                            key = occurrence.applicationKey(operation),
                            inputFingerprint =
                                engineObjectDataOf(occurrence.invocationKey.field.containingDef)
                                    .resolutionFingerprint(),
                        ),
                ),
            )
        }
    }
    return counts
}

/** Expected registered resolver occurrences without requiring their inputs to be materializable. */
fun EngineResult?.registeredResolverOccurrenceApplicationKeyCounts(operation: SharedOperationContext<*>):
    Map<ResolverOccurrenceApplicationKey, Int> {
    val counts = linkedMapOf<ResolverOccurrenceApplicationKey, Int>()
    requestQueryRoots(operation).forEach { root ->
        root.forEachRegisteredResolverOccurrence(operation, operation.world.resolverRegistry) { cell ->
            counts.increment(
                ResolverOccurrenceApplicationKey(
                    resolverOccurrenceId = ResolverOccurrenceId.at(root, cell.occurrencePath),
                    applicationKey = cell.applicationKey,
                ),
            )
        }
    }
    rootFieldReferenceOccurrences(operation).forEach { occurrence ->
        counts.increment(
            ResolverOccurrenceApplicationKey(
                resolverOccurrenceId =
                    ResolverOccurrenceId.at(
                        occurrence.invocationRoot,
                        occurrence.invocationPath,
                    ),
                applicationKey = occurrence.applicationKey(operation),
            ),
        )
    }
    return counts
}

private fun EngineResult?.requestQueryRoots(operation: SharedOperationContext<*>): List<ObjectEngineResult> {
    val primaryRoot = this as? ObjectEngineResult ?: return emptyList()
    val observations = operation.resolverObserver as? CorrectnessResolverObserver
    check(observations?.queryFragmentOwnershipIsConsistent() != false) {
        "Query-fragment ownership associations are inconsistent"
    }
    // A singular Query OER is observed once per owner but its resolver occurrences exist only once.
    val seen = Collections.newSetFromMap(IdentityHashMap<ObjectEngineResult, Boolean>())
    return buildList {
        if (seen.add(primaryRoot)) add(primaryRoot)
        operation.resolverObservations()
            .allQueryFragmentResults()
            .values
            .flatten()
            .forEach { queryRoot ->
                if (seen.add(queryRoot)) add(queryRoot)
            }
    }
}

private fun EngineResult?.rootFieldReferenceOccurrences(operation: SharedOperationContext<*>): List<
    RootFieldReferenceInvocationObservation,
> =
    (this as? ObjectEngineResult)?.ownedRootFieldReferenceInvocations(operation).orEmpty()

private fun RootFieldReferenceInvocationObservation.applicationKey(operation: SharedOperationContext<*>): ResolverApplicationKey =
    ResolverApplicationKey(
        field =
            FieldCoordinate(
                invocationKey.field.containingDef.name,
                invocationKey.field.name,
            ),
        arguments = invocationKey.groundedArguments(operation) as Arguments.Resolved,
    )

fun EngineResult?.unclosedRegisteredResolverOccurrences(operation: SharedOperationContext<*>): List<RegisteredResolverOccurrence> =
    buildList {
        fun recordIfUnclosed(
            root: ObjectEngineResult,
            cell: RegisteredResolverOccurrence,
        ) {
            val resolver = operation.world.resolverRegistry.resolver(cell.field)
            if (
                resolver.objectFragmentSatisfiedBy(
                    operation = operation,
                    root = root,
                    result = cell.containingObject,
                    path = cell.occurrencePath,
                ) == null
            ) {
                add(cell)
            }
        }
        requestQueryRoots(operation).forEach { root ->
            root.forEachRegisteredResolverOccurrence(operation, operation.world.resolverRegistry) { cell ->
                recordIfUnclosed(root, cell)
            }
        }
        // Reference targets have no object fragment, so their input is closed by construction.
    }

private fun <T> MutableMap<T, Int>.increment(key: T) {
    this[key] = getOrDefault(key, 0) + 1
}

private fun FieldResolver.objectFragmentSatisfiedBy(
    operation: SharedOperationContext<*>,
    root: ObjectEngineResult,
    result: ObjectEngineResult,
    path: List<PathComponent>,
): ResolverFragment? {
    val objectFragment = instantiateFragmentsAt(root, path).objectFragment
    return objectFragment.takeIf {
        val constructionSelections = objectFragment.constructionSelections
        constructionSelections.usedVariables().all { variable ->
            operation.variableBindings.isBound(variable.instanceId!!)
        } &&
            result.conformsToSelectionsAt(
                operation,
                selections = constructionSelections,
                path = path.dropLast(1),
            )
    }
}

private fun SharedOperationContext<*>.resolverObservations(): CorrectnessResolverObserver =
    resolverObserver as? CorrectnessResolverObserver
        ?: error("Resolver observations were not recorded for this operation")
