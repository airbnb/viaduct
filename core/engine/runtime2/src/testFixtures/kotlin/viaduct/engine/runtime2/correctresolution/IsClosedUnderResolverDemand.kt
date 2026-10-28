package viaduct.engine.runtime2.correctresolution

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.rootFieldReferenceDataOrNull
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.model.usedVariables
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.argumentsContainErrorValue
import viaduct.engine.runtime2.resolution.framework.groundedArguments
import viaduct.engine.runtime2.resolution.framework.isContextuallyGrounded
import viaduct.engine.runtime2.resolvers.objectFragmentAt
import viaduct.graphql.schema.ViaductSchema

/**
 * Whether every standard registered resolver activated by this result has its required input.
 *
 * A source object exceptionally owns every argumentless field it supplies, including null and
 * error values. A source-absent registered field activates its standard resolver unless its
 * arguments contain an error. Resolver outputs are deterministically reapplied to classify
 * descendant occurrences.
 *
 * A claimed field- or type-checker slot additionally requires the checker's raw object fragment
 * in its owning OER. Active value resolvers reached through those raw selections retain their
 * ordinary checked input closure. This predicate does not interpret the checker-result value or
 * count checker applications.
 */
fun ObjectEngineResult.isClosedUnderResolverDemand(operation: SharedOperationContext<*>): Boolean = isClosedUnderResolverDemand(operation, operation.resolverApplicationCache(this))

fun ObjectEngineResult.isClosedUnderResolverDemand(
    operation: SharedOperationContext<*>,
    resolverApplicationCache: ResolverApplicationCache,
): Boolean = ResolverDemandValidationLogic(operation, resolverApplicationCache).isClosed(this)

/** Checks resolver demand for one result using its operation and existing replay cache. */
internal class ResolverDemandValidationLogic(
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

        if (!typeCheckerDemandIsClosed(this, path)) return false

        return keys.all { key ->
            if (!getCell(key).value.isCompleted) return@all true
            if (!key.isContextuallyGrounded(operation)) return@all false
            val arguments = key.groundedArguments(operation)
            val value = getCell(key).value.get()
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
                    else -> resolverDemandIsClosed(this, key, path)
                }

            fieldResolverDemandIsClosed && fieldCheckerDemandIsClosed(this, key, arguments, path) &&
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

    fun typeCheckerDemandIsClosed(
        result: ObjectEngineResult,
        path: List<PathComponent>,
    ): Boolean {
        val checker = operation.world.resolverRegistry.typeChecker(result.type)
        if (result.typeCheckerResult.get() == null || checker == null) return true
        val fragments = checker.instantiateFragmentsAt(resolverApplicationCache.root, path)
        return result.conformsToSelectionsAt(operation, fragments.objectFragment.constructionSelections, path)
    }

    fun resolverDemandIsClosed(
        result: ObjectEngineResult,
        key: ObjectEngineResult.ObjectKey,
        path: List<PathComponent>,
    ): Boolean {
        val resolver = operation.world.resolverRegistry.resolver(key.field)
        val coordinate = path + key
        val selections = resolver.instantiateFragmentsAt(resolverApplicationCache.root, coordinate)
            .objectFragment.constructionSelections
        return if (selections.usedVariables().all { operation.variableBindings.isBound(it.instanceId!!) }) {
            result.conformsToSelectionsAt(operation, selections, path)
        } else {
            result.conformsToSelectionsAt(
                operation,
                resolver.objectFragmentAt(operation, resolverApplicationCache.root, coordinate),
                path,
            )
        }
    }

    fun fieldCheckerDemandIsClosed(
        result: ObjectEngineResult,
        key: ObjectEngineResult.ObjectKey,
        arguments: Arguments.Ground,
        path: List<PathComponent>,
    ): Boolean {
        if (key is ObjectEngineResult.ParentKey || arguments.argumentsContainErrorValue() ||
            result.getCell(key).fieldCheckerResult.get() == null
        ) {
            return true
        }
        val checker = operation.world.resolverRegistry.fieldChecker(key.field) ?: return true
        val fragments = checker.instantiateFragmentsAt(resolverApplicationCache.root, path + key)
        return result.conformsToSelectionsAt(operation, fragments.objectFragment.constructionSelections, path)
    }

    private fun EngineResult?.engineResultIsClosedUnderResolverDemand(
        path: List<PathComponent>,
        source: ResolverOutputData?,
        structuralParent: ObjectEngineResult,
        producerField: ViaductSchema.ObjectField,
    ): Boolean {
        val reference = source.rootFieldReferenceDataOrNull()
        if (reference != null) {
            return operation.reapplyRootFieldReference(
                resolverApplicationCache = resolverApplicationCache,
                reference = reference,
                publicationRoot = resolverApplicationCache.root,
                publicationPath = path,
                validationDemand = resolverApplicationCache.completedOutputDemand(this),
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
                    type == source.schemaType(operation.world.schema) &&
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
                        get(index).value.get().engineResultIsClosedUnderResolverDemand(
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
                    get(index).value.get().engineResultIsClosedUnderResolverDemand(
                        path = path + ListEngineResult.Index.of(index),
                        structuralParent = structuralParent,
                        producerField = producerField,
                    )
                }
            else -> true
        }
}
