package semantics.correctresolution

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.EngineErrorData
import model.ResolverOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.outputType
import model.outputValue
import model.PathComponent
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.VariableBinding
import semantics.shared.groundedArguments
import semantics.shared.isContextuallyGrounded
import model.schemaType
import viaduct.engine.api.EngineObjectData
import model.toEngineOutputData
import model.usedVariables
import model.registry.FieldValueResolver
import model.registry.ResolverFragments
import model.registry.VariableDefinition
import model.merge
import model.requireQueryTypeDef
import semantics.shared.SharedOperationContext

/**
 * Whether every value agrees with the resolver output that owns its exact occurrence.
 *
 * A source object exceptionally owns every argumentless field it supplies, including null and
 * error values. An absent registered field is owned by its standard registered resolver. Field
 * resolvers receive the containing object materialized according to their object fragment.
 *
 * This predicate assumes [isClosedUnderResolverDemand] has established that every resolver input
 * value is present. Correctness replay materializes checked object and Query inputs from the OERs;
 * when runtime invocation evidence is available, those reconstructed values must equal the values
 * actually passed to the resolver, including access errors at exact response-key and list positions.
 */
fun ObjectEngineResult.conformsToResolvers(operation: SharedOperationContext<*>): Boolean =
    operation.resolverApplicationCache(this).let { resolverApplicationCache ->
        conformsToResolvers(operation, resolverApplicationCache) &&
            resolverApplicationCache.hasCompleteRootFieldReferenceWitness()
    }

internal fun ObjectEngineResult.conformsToResolvers(
    operation: SharedOperationContext<*>,
    resolverApplicationCache: ResolverApplicationCache,
): Boolean =
    ResolverConformanceLogic(operation, resolverApplicationCache).conforms(this)

/** Checks resolver conformance for one result using its operation and existing replay cache. */
private class ResolverConformanceLogic(
    private val operation: SharedOperationContext<*>,
    private val resolverApplicationCache: ResolverApplicationCache,
) {
    fun conforms(result: ObjectEngineResult): Boolean =
        result.objectConformsToResolvers(
            path = emptyList(),
            source = null,
            structuralParent = null,
            producerField = null,
        )

    private fun ObjectEngineResult.objectConformsToResolvers(
        path: List<PathComponent>,
        source: EngineObjectData.Sync?,
        structuralParent: ObjectEngineResult?,
        producerField: ViaductSchema.ObjectField?,
    ): Boolean =
        keys.all { key ->
            if (!getCell(key).value.isCompleted) return@all true
            if (!key.isContextuallyGrounded(operation)) return@all false
            val value = getCell(key).value.get()
            val arguments = key.groundedArguments(operation)
            val fieldName = key.field.name
            source.requireArgumentlessField(key)
            when {
                key is ObjectEngineResult.ParentKey ->
                    value === structuralParent &&
                        operation.world.parentFieldRelations[key.field] == producerField

                arguments !is Arguments.Resolved ->
                    value is ErrorEngineResult &&
                        errorArgumentQueryFragmentConforms(
                            key = key,
                            path = path + key,
                        )

                source?.isPresent(fieldName) == true ->
                    arguments.fieldValues.isEmpty() &&
                        value.engineResultConformsToResolverValue(
                            resolverValue = source.outputValue(fieldName),
                            expectedType = key.field.outputType,
                            path = path + key,
                            structuralParent = this,
                            producerField = key.field,
                        )

                key.field in operation.world.resolverRegistry ->
                    reapplyResolver(operation, resolverApplicationCache, key, path)
                        ?.let { application ->
                            value.engineResultConformsToResolverValue(
                                resolverValue = application.output,
                                expectedType = key.field.outputType,
                                path = path + key,
                                structuralParent = this,
                                producerField = key.field,
                            )
                        } == true

                source == null ->
                    value.engineResultConformsToResolvers(
                        path = path + key,
                        structuralParent = this,
                        producerField = key.field,
                    )

                else -> false
            }
        }

    private fun ObjectEngineResult.errorArgumentQueryFragmentConforms(
        key: ObjectEngineResult.ObjectKey,
        path: List<PathComponent>,
    ): Boolean {
        if (key.field !in operation.world.resolverRegistry) return true
        val resolver = operation.world.resolverRegistry.resolver(key.field)
        val queryFragment =
            resolver.instantiateFragmentsAt(resolverApplicationCache.root, path).queryFragment
        if (queryFragment.constructionSelections.isEmpty()) return true
        val queryResults =
            (operation.resolverObserver as? CorrectnessResolverObserver)
                ?.queryFragmentResults(
                    ResolverOccurrenceId.at(resolverApplicationCache.root, path),
                ).orEmpty()
        if (key is ObjectEngineResult.GroundKey) return queryResults.isEmpty()
        val queryResult = queryResults.singleOrNull() ?: return false
        val querySelections =
            queryFragment.constructionSelections.merge(operation.world.schema.requireQueryTypeDef())
        return resolverApplicationCache.queryResultConforms(
            operation,
            queryResult,
            querySelections,
        )
    }

    private fun EngineResult?.engineResultConformsToResolvers(
        path: List<PathComponent>,
        structuralParent: ObjectEngineResult,
        producerField: ViaductSchema.ObjectField,
    ): Boolean =
        when (this) {
            null,
            is ErrorEngineResult,
            -> true

            is ObjectEngineResult ->
                objectConformsToResolvers(
                    path = path,
                    source = null,
                    structuralParent = structuralParent,
                    producerField = producerField,
                )
            is ListEngineResult ->
                indices.all { index ->
                    get(index).value.get().engineResultConformsToResolvers(
                        path = path + ListEngineResult.Index.of(index),
                        structuralParent = structuralParent,
                        producerField = producerField,
                    )
                }
            else -> true
        }

    private fun EngineResult?.engineResultConformsToResolverValue(
        resolverValue: ResolverOutputData?,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        path: List<PathComponent>,
        structuralParent: ObjectEngineResult,
        producerField: ViaductSchema.ObjectField,
    ): Boolean {
        if (resolverValue is RootFieldReferenceData) {
            return operation.reapplyRootFieldReference(
                resolverApplicationCache = resolverApplicationCache,
                reference = resolverValue,
                publicationRoot = resolverApplicationCache.root,
                publicationPath = path,
                validationDemand = completedOutputDemand(),
            )?.let { application ->
                engineResultConformsToResolverValue(
                    resolverValue = application.output,
                    expectedType = expectedType,
                    path = path,
                    structuralParent = structuralParent,
                    producerField = producerField,
                )
            } == true
        }
        return when (this) {
            null -> resolverValue == null
            is ErrorEngineResult -> resolverValue is EngineErrorData

            is ObjectEngineResult ->
                resolverValue is EngineObjectData.Sync &&
                    objectFieldsConformToResolverValue(
                        resolverValue = resolverValue,
                        path = path,
                        structuralParent = structuralParent,
                        producerField = producerField,
                    )

            is ListEngineResult ->
                resolverValue is List<*> &&
                    size == resolverValue.size &&
                    indices.all { index ->
                        get(index).value.get().engineResultConformsToResolverValue(
                            resolverValue[index],
                            typeExpr,
                            path + ListEngineResult.Index.of(index),
                            structuralParent,
                            producerField,
                        )
                    }

            else ->
                toEngineOutputData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef) ==
                    resolverValue
        }
    }

    private fun ObjectEngineResult.objectFieldsConformToResolverValue(
        resolverValue: EngineObjectData.Sync,
        path: List<PathComponent>,
        structuralParent: ObjectEngineResult,
        producerField: ViaductSchema.ObjectField,
    ): Boolean {
        if (type != resolverValue.schemaType) return false

        return objectConformsToResolvers(
            path = path,
            source = resolverValue,
            structuralParent = structuralParent,
            producerField = producerField,
        )
    }
}

internal fun FieldValueResolver.fragmentsSatisfiedBy(
    operation: SharedOperationContext<*>,
    root: ObjectEngineResult,
    result: ObjectEngineResult,
    path: List<PathComponent>,
): ResolverFragments? {
    val fragments = instantiateFragmentsAt(root, path)
    val objectFragment = fragments.objectFragment
    val arguments =
        (path.lastOrNull() as? ObjectEngineResult.ObjectKey)
            ?.groundedArguments(operation) as? Arguments.Resolved
            ?: return null
    return fragments.takeIf {
        val constructionSelections = objectFragment.constructionSelections
        fromArgumentBindingsAgree(
            operation = operation,
            fragments = fragments,
            arguments = arguments,
        ) &&
            constructionSelections.usedVariables().all { variable ->
                operation.variableBindings.isBound(variable.instanceId!!)
            } &&
            result.conformsToSelectionsAt(
                operation = operation,
                selections = constructionSelections,
                path = path.dropLast(1),
            )
    }
}

private fun FieldValueResolver.fromArgumentBindingsAgree(
    operation: SharedOperationContext<*>,
    fragments: ResolverFragments,
    arguments: Arguments.Resolved,
): Boolean {
    val usedVariables =
        fragments.objectFragment.constructionSelections.usedVariables() +
            fragments.queryFragment.constructionSelections.usedVariables()
    val resolverOccurrenceId = fragments.objectFragment.resolverOccurrenceId
    return instantiatedVariableDefinitions(resolverOccurrenceId)
        .filter { variableDefinition -> variableDefinition.variable in usedVariables }
        .all { variableDefinition ->
            val definition = variableDefinition.definition
            if (definition !is VariableDefinition.FromArgument) return@all true
            val instanceId = requireNotNull(variableDefinition.variable.instanceId)
            operation.variableBindings.isBound(instanceId) &&
                operation.variableBindings.getBinding(instanceId) ==
                VariableBinding.of(definition.read(arguments))
        }
}
