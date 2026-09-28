package semantics.contract

import model.EngineInputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.MaterializeSelectionForest
import model.VariableBinding
import model.outputType
import model.registry.FieldValueResolver
import model.registry.InstantiatedFieldPathDefinition
import model.registry.VariableDefinition
import model.registry.ProviderFragment
import model.toEngineInputListData
import model.toEngineSimpleData
import semantics.shared.findStoredKey
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.BitVector
import kotlin.test.assertEquals
import semantics.shared.SharedOperationContext
import semantics.correctresolution.CorrectnessResolverObserver

/**
 * Independently validates from-field bindings across every request-local Query root.
 *
 * An applied occurrence must have exactly all of its declared bindings and a passive occurrence
 * must have none.
 */
fun ObjectEngineResult.validateFromFieldBindings(
    operation: SharedOperationContext<*>,
    appliedResolverOccurrences: Set<ResolverOccurrenceId>,
) {
    fun validateOccurrence(
        root: ObjectEngineResult,
        field: ViaductSchema.ObjectField,
        path: List<PathComponent>,
        containingObject: ObjectEngineResult?,
    ) {
        val resolver = operation.world.resolverRegistry.resolver(field)
        val definitions = resolver.fieldPathDefinitions(root = root, path = path)
        if (definitions.isEmpty()) return

        val occurrenceId = ResolverOccurrenceId.at(root, path)
        val requiredBindingIds =
            definitions.mapTo(linkedSetOf()) { definition ->
                requireNotNull(definition.variable.instanceId)
            }
        val actualBindingIds =
            requiredBindingIds.filterTo(
                linkedSetOf(),
                operation.variableBindings::isBound,
            )
        when (appliedResolverOccurrences.contains(occurrenceId)) {
            true ->
                assertEquals(
                    requiredBindingIds,
                    actualBindingIds,
                    "Applied resolver occurrence $occurrenceId has incomplete from-field bindings",
                )
            false -> {
                assertEquals(
                    emptySet(),
                    actualBindingIds,
                    "Passive resolver occurrence $occurrenceId unexpectedly has from-field bindings",
                )
                return
            }
        }

        definitions.forEach { definition ->
            val providerRoot =
                when (definition.providerFragment) {
                    ProviderFragment.OBJECT -> containingObject
                        ?: error("Root-field-reference target unexpectedly has an object-field provider")
                    ProviderFragment.QUERY ->
                        operation.resolverObservations()
                            .queryFragmentResults(occurrenceId)
                            .single()
                }
            val sourceDefinition = resolver.variables.entries.single {
                it.key.variableName == definition.variable.variableName
            }.value as VariableDefinition.FromField
            val materializationSelections = when (definition.providerFragment) {
                ProviderFragment.OBJECT ->
                    resolver.instantiateObjectMaterializationSelections(occurrenceId)
                ProviderFragment.QUERY ->
                    resolver.instantiateQueryMaterializationSelections(occurrenceId)
            }
            val expected = providerRoot.readCompletedProvider(
                operation = operation,
                responsePath = sourceDefinition.responsePath,
                selections = materializationSelections,
            )
            assertEquals(
                expected,
                operation.variableBindings.getBinding(
                    requireNotNull(definition.variable.instanceId),
                ),
            )
        }
    }

    requestQueryRoots(operation).forEach { root ->
        root.forEachRegisteredResolverOccurrence(operation, operation.world.resolverRegistry) { cell ->
            validateOccurrence(root, cell.field, cell.occurrencePath, cell.containingObject)
        }
    }
    operation.resolverObservations().rootFieldReferenceInvocations().forEach { observation ->
        validateOccurrence(
            observation.invocationRoot,
            observation.invocationKey.field,
            observation.invocationPath,
            null,
        )
    }
}

internal fun FieldValueResolver.fieldPathDefinitions(
    root: ObjectEngineResult,
    path: List<PathComponent>,
): List<InstantiatedFieldPathDefinition> =
    instantiatedFieldPathVariableDefinitions(ResolverOccurrenceId.at(root, path))

private fun ObjectEngineResult.requestQueryRoots(operation: SharedOperationContext<*>): List<ObjectEngineResult> =
    buildList {
        add(this@requestQueryRoots)
        addAll(
            operation.resolverObservations()
                .allQueryFragmentResults()
                .values
                .flatten(),
        )
    }

private fun ObjectEngineResult.readCompletedProvider(
    operation: SharedOperationContext<*>,
    responsePath: List<String>,
    selections: MaterializeSelectionForest,
): VariableBinding {
    var current = this
    var localSelections = selections
    responsePath.forEachIndexed { index, responseKey ->
        // Collect the defining fragment independently of compiled provider-path conditions.
        val included = try {
            localSelections.filter { selection ->
                selection.responseKey == responseKey && current.type in selection.possibleTypes &&
                    selection.inclusionCondition.includeWith { variable ->
                        when (val binding = operation.variableBindings.getBinding(requireNotNull(variable.instanceId))) {
                            VariableBinding.Error -> throw ProviderConditionFailure()
                            is VariableBinding.Input -> binding.value as? Boolean ?: throw ProviderConditionFailure()
                        }
                    }
            }
        } catch (_: ProviderConditionFailure) {
            return VariableBinding.Error
        }
        if (included.isEmpty()) return VariableBinding.of(null)
        val selection = included.collect(current.type)[responseKey]
        val specialized = selection.key
        val key =
            current.findStoredKey(operation, specialized)
                ?: error("Completed provider key is absent from result: $specialized")
        val value = current.getCell(key).get()
        if (value == null) return VariableBinding.of(null)
        if (value is ErrorEngineResult) return VariableBinding.Error
        if (index == responsePath.lastIndex) {
            return value.toVariableBinding(key.field.outputType)
        }
        current =
            value as? ObjectEngineResult
                ?: error("Completed provider path crossed a non-object at $key")
        localSelections = selection.subselections
    }
    error("Provider path must be nonempty")
}

private class ProviderConditionFailure : RuntimeException()

private fun SharedOperationContext<*>.resolverObservations(): CorrectnessResolverObserver =
    resolverObserver as? CorrectnessResolverObserver
        ?: error("Resolver observations were not recorded for this operation")

private fun EngineResult.toVariableBinding(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
): VariableBinding =
    when (this) {
        is ErrorEngineResult -> VariableBinding.Error
        is ListEngineResult -> toInputListBinding()
        is ObjectEngineResult ->
            error("A from-field provider cannot terminate at an object")
        else ->
            VariableBinding.of(
                toEngineSimpleData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef),
            )
    }

private fun ListEngineResult.toInputListBinding(): VariableBinding {
    val baseType = typeExpr.baseTypeDef
    require(baseType is ViaductSchema.InputTypeDef)
    val values = mutableListOf<EngineInputData?>()
    indices.forEach { index ->
        val result = get(index).get()
        val binding =
            if (result == null) {
                VariableBinding.of(null)
            } else {
                result.toVariableBinding(typeExpr)
            }
        when (binding) {
            VariableBinding.Error -> return VariableBinding.Error
            is VariableBinding.Input -> values += binding.value
        }
    }
    return VariableBinding.of(
        toEngineInputListData(
            expectedType = typeExpr.withNonNullListWrapper(baseType),
            value = values,
        ),
    )
}

private fun ViaductSchema.TypeExpr<*>.withNonNullListWrapper(
    baseType: ViaductSchema.InputTypeDef,
): ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef> {
    val wrappers = BitVector(listDepth + 1)
    for (depth in 0 until listDepth) {
        if (nullableAtDepth(depth)) wrappers.set(depth + 1)
    }
    return ViaductSchema.TypeExpr(baseType, baseTypeNullable, wrappers)
}
