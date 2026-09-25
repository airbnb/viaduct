package semantics.correctresolution

import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.requireQueryTypeDef
import semantics.shared.SharedOperationContext

/**
 * Whether this primary Query-rooted result is a correct field-resolution result for [selections].
 *
 * The judgment is plan-independent. Checked client and resolver-input selections must claim their
 * registered field-checker slots. Claimed checker object fragments participate in value-demand
 * closure; checker relations are replayed from raw object and Query inputs; and recorded resolver
 * invocations must receive the same access-filtered values reconstructed from the OERs. Checker
 * application counts remain a separate judgment.
 *
 * This judgment is purposefully permissive: as long as the [ObjectEngineResult] conforms
 * to our world assumptions (e.g., regarding schema conformance and resolver conformance),
 * this predicate allows the [ObjectEngineResult] to contain more values than the input
 * [selections] and implicated [model.registry.FieldResolver.objectFragment]s require. Other
 * predicates define various degrees of minimality.
 *
 * [selections] must be rooted at the reasoning world's canonical Query type. Reapplying a resolver
 * with a nonempty Query fragment also requires its containing orchestration's associated Query OER
 * to be a correct resolution and its owner-local projection to conform to that fragment.
 * Each judged result gets one replay cache shared by demand and conformance checks. Nested Query
 * results get their own caches but retain the same reference witness across the whole judgment.
 * Neither the cache nor the witness is retained by [operation] across separate judgments.
 *
 * This is math, not programming: the Kotlin application syntax here expresses the
 * modeled function relation, not programming-language procedure executions.  These should
 * be reasoned about as inductively-defined relations, not recursive routines.
 */
fun ObjectEngineResult.correctResolution(
    operation: SharedOperationContext<*>,
    selections: ObjectSelectionForest,
): Boolean {
    val rootFieldReferenceWitness = operation.rootFieldReferenceWitness(this)
    return correctResolution(
        operation,
        selections,
        rootFieldReferenceWitness,
        QueryOERValidationState(),
    ) &&
        rootFieldReferenceWitness.isComplete()
}

internal fun ObjectEngineResult.correctResolution(
    operation: SharedOperationContext<*>,
    selections: ObjectSelectionForest,
    rootFieldReferenceWitness: RootFieldReferenceWitness,
    queryOERValidation: QueryOERValidationState = QueryOERValidationState(),
    selectionsAreChecked: Boolean = true,
): Boolean {
    require(selections.type == operation.world.schema.requireQueryTypeDef()) {
        "Correct-resolution selections must be rooted at Query"
    }
    val resolverApplicationCache =
        resolverApplicationCache(this, rootFieldReferenceWitness, queryOERValidation)
    val structurallyValid =
        rootedAndWellTyped(operation.world) &&
            conformsToSelections(operation, selections) &&
            (!selectionsAreChecked ||
                conformsToCheckedSelectionsAt(
                    operation = operation,
                    selections = selections,
                    path = emptyList(),
                    resolverApplicationCache = resolverApplicationCache,
                ))
    return structurallyValid &&
        isClosedUnderResolverDemand(operation, resolverApplicationCache) &&
        conformsToResolvers(operation, resolverApplicationCache)
}
