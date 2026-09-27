package semantics.correctresolution

import model.ResolverOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Arguments
import model.PathComponent
import model.RootFieldReferenceData
import semantics.shared.argumentsContainErrorValue
import semantics.shared.groundedArguments
import semantics.shared.isContextuallyGrounded
import semantics.shared.objectFragmentAt
import model.outputValue
import model.schemaType
import model.usedVariables
import viaduct.engine.api.EngineObjectData
import semantics.shared.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

/**
 * Whether every standard registered resolver activated by this result has its required input.
 *
 * A source object exceptionally owns every argumentless field it supplies, including null and
 * error values. A source-absent registered field activates its standard resolver unless its
 * arguments contain an error. Resolver outputs are deterministically reapplied to classify
 * descendant occurrences.
 *
 * A claimed field-checker slot additionally requires the checker's raw object fragment in the
 * containing OER. Active value resolvers reached through those raw selections retain their
 * ordinary checked input closure. This predicate does not interpret the checker-result value or
 * count checker applications.
 */
fun ObjectEngineResult.isClosedUnderResolverDemand(operation: SharedOperationContext<*>): Boolean =
    isClosedUnderResolverDemand(operation, operation.resolverApplicationCache(this))

internal fun ObjectEngineResult.isClosedUnderResolverDemand(
    operation: SharedOperationContext<*>,
    resolverApplicationCache: ResolverApplicationCache,
): Boolean =
    ResolverDemandValidationLogic(operation, resolverApplicationCache).isClosed(this)

/** Checks resolver demand for one result using its operation and existing replay cache. */
private class ResolverDemandValidationLogic(
    private val operation: SharedOperationContext<*>,
    private val resolverApplicationCache: ResolverApplicationCache,
) {
    fun isClosed(result: ObjectEngineResult): Boolean =
        result.objectIsClosedUnderResolverDemand(
            path = emptyList(),
            source = null,
            structuralParent = null,
            producerField = null,
        )

    private fun ObjectEngineResult.objectIsClosedUnderResolverDemand(
        path: List<PathComponent>,
        source: EngineObjectData.Sync?,
        structuralParent: ObjectEngineResult?,
        producerField: ViaductSchema.ObjectField?,
    ): Boolean {
        val registry = operation.world.resolverRegistry

        return keys.all { key ->
            if (!getCell(key).getValue().isCompleted) return@all true
            if (!key.isContextuallyGrounded(operation)) return@all false
            val arguments = key.groundedArguments(operation)
            val value = getCell(key).getValue().get()
            val fieldName = key.field.name
            val argumentsContainError = arguments.argumentsContainErrorValue()
            val sourceSuppliesField = source?.isPresent(fieldName) == true
            source.requireArgumentlessField(key)
            val fieldResolverDemandIsClosed =
                when {
                    key is ObjectEngineResult.ParentKey ->
                        value === structuralParent &&
                            operation.world.parentFieldRelations[key.field] == producerField
                    argumentsContainError -> true
                    sourceSuppliesField ->
                        (arguments as? Arguments.Resolved)
                            ?.fieldValues
                            ?.isEmpty() == true
                    key.field !in registry -> source == null
                    else ->
                        registry
                            .resolver(key.field)
                            .let { resolver ->
                                val coordinate = path + key
                                val instantiatedSelections =
                                    resolver
                                        .instantiateFragmentsAt(
                                            resolverApplicationCache.root,
                                            coordinate,
                                        ).objectFragment
                                        .constructionSelections
                                if (
                                    instantiatedSelections.usedVariables().all { variable ->
                                        operation.variableBindings.isBound(variable.instanceId!!)
                                    }
                                ) {
                                    conformsToSelectionsAt(
                                        operation,
                                        selections = instantiatedSelections,
                                        path = path,
                                    )
                                } else {
                                    val instantiatedFragment =
                                        resolver.objectFragmentAt(
                                            operation,
                                            resolverApplicationCache.root,
                                            coordinate,
                                        )
                                    conformsToSelectionsAt(
                                        operation,
                                        instantiatedFragment,
                                        path,
                                    )
                                }
                            }
                }
            val fieldCheckerDemandIsClosed =
                when {
                    key is ObjectEngineResult.ParentKey -> true
                    !getCell(key).isFieldCheckerResultSet() -> true
                    argumentsContainError -> true
                    getCell(key).getFieldCheckerResult().get() == null -> true
                    else ->
                        registry.fieldChecker(key.field)?.let { checker ->
                            val coordinate = path + key
                            val fragments =
                                checker.instantiateFragmentsAt(
                                    resolverApplicationCache.root,
                                    coordinate,
                                )
                            conformsToSelectionsAt(
                                operation = operation,
                                selections = fragments.objectFragment.constructionSelections,
                                path = path,
                            )
                        } ?: true
                }

            fieldResolverDemandIsClosed && fieldCheckerDemandIsClosed &&
                when {
                    key is ObjectEngineResult.ParentKey -> true
                    argumentsContainError -> true
                    source != null && sourceSuppliesField ->
                        value.engineResultIsClosedUnderResolverDemand(
                            path = path + key,
                            source = source.outputValue(fieldName),
                            structuralParent = this,
                            producerField = key.field,
                        )
                    key.field in registry ->
                        reapplyResolver(operation, resolverApplicationCache, key, path)?.let { application ->
                            value.engineResultIsClosedUnderResolverDemand(
                                path = path + key,
                                source = application.output,
                                structuralParent = this,
                                producerField = key.field,
                            )
                        } == true
                    source == null ->
                        value.engineResultIsClosedUnderResolverDemand(
                            path = path + key,
                            structuralParent = this,
                            producerField = key.field,
                        )
                    else -> false
                }
        }
    }

    private fun EngineResult?.engineResultIsClosedUnderResolverDemand(
        path: List<PathComponent>,
        source: ResolverOutputData?,
        structuralParent: ObjectEngineResult,
        producerField: ViaductSchema.ObjectField,
    ): Boolean {
        if (source is RootFieldReferenceData) {
            return operation.reapplyRootFieldReference(
                resolverApplicationCache = resolverApplicationCache,
                reference = source,
                publicationRoot = resolverApplicationCache.root,
                publicationPath = path,
                validationDemand = completedOutputDemand(),
            )?.let { application ->
                engineResultIsClosedUnderResolverDemand(
                    path = path,
                    source = application.output,
                    structuralParent = structuralParent,
                    producerField = producerField,
                )
            } == true
        }
        return when (this) {
            null,
            is ErrorEngineResult,
            -> true

            is ObjectEngineResult ->
                source is EngineObjectData.Sync &&
                    type == source.schemaType &&
                    objectIsClosedUnderResolverDemand(
                        path = path,
                        source = source,
                        structuralParent = structuralParent,
                        producerField = producerField,
                    )
            is ListEngineResult ->
                source is List<*> &&
                    size == source.size &&
                    indices.all { index ->
                        get(index).getValue().get().engineResultIsClosedUnderResolverDemand(
                            path = path + ListEngineResult.Index.of(index),
                            source = source[index],
                            structuralParent = structuralParent,
                            producerField = producerField,
                        )
                    }
            else -> true
        }
    }

    private fun EngineResult?.engineResultIsClosedUnderResolverDemand(
        path: List<PathComponent>,
        structuralParent: ObjectEngineResult,
        producerField: ViaductSchema.ObjectField,
    ): Boolean =
        when (this) {
            null,
            is ErrorEngineResult,
            -> true

            is ObjectEngineResult ->
                objectIsClosedUnderResolverDemand(
                    path = path,
                    source = null,
                    structuralParent = structuralParent,
                    producerField = producerField,
                )
            is ListEngineResult ->
                indices.all { index ->
                    get(index).getValue().get().engineResultIsClosedUnderResolverDemand(
                        path = path + ListEngineResult.Index.of(index),
                        structuralParent = structuralParent,
                        producerField = producerField,
                    )
                }
            else -> true
        }
}
