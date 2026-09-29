package semantics.resolver26

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import model.EngineInputData
import model.EngineInputListData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Selection
import model.VariableBinding
import model.objectKey
import model.outputType
import model.registry.InstantiatedFieldPathDefinition
import model.registry.InstantiatedFieldPathElement
import model.selectionForestOf
import model.toEngineSimpleData
import semantics.shared.CycleTask
import semantics.shared.fetchGroundedArguments
import semantics.shared.fetchIncluded
import semantics.shared.valueCycleSlot
import viaduct.graphql.schema.ViaductSchema

// Traverses a provider path through OER promises and returns its terminal input-compatible value.
internal suspend fun ObjectEngineResult.readProvider(
    operation: OperationContext,
    definition: InstantiatedFieldPathDefinition,
    reader: CycleTask,
): VariableBinding = readProvider(operation, definition.path, reader)

// Reads and completes provider bindings; the owning field-task root handles cancellation cleanup.
internal suspend fun completeProviderBindings(
    operation: OperationContext,
    providerReads: List<VariableProviderReadOccurrence>,
) {
    coroutineScope {
        providerReads.forEach { providerRead ->
            val variableId = requireNotNull(providerRead.definition.variable.instanceId)
            launch {
                val binding =
                    try {
                        providerRead.providerResult.readProvider(
                            operation = operation,
                            definition = providerRead.definition,
                            reader = providerRead.reader,
                        )
                    } catch (exception: Exception) {
                        currentCoroutineContext().ensureActive()
                        VariableBinding.Error
                    }
                operation.variableBindings.completeBinding(variableId, binding)
            }
        }
    }
}

private suspend fun ObjectEngineResult.readProvider(
    operation: OperationContext,
    path: List<InstantiatedFieldPathElement>,
    reader: CycleTask,
): VariableBinding {
    var current = this
    path.forEachIndexed { index, element ->
        if (!element.inclusionCondition.fetchIncluded(operation)) return VariableBinding.of(null)
        val openKey = element.key
        operation.bindingsState.awaitBindingsDeclared(current)
        val specializedKey =
            Selection.of(
                key = openKey,
                possibleTypes = setOf(current.type),
                subselections = selectionForestOf(),
            ).objectKey(current.type)
        val objectKey = specializedKey
        objectKey.fetchGroundedArguments(operation)
        val cell = current.reserveCell(objectKey)
        operation.cycleChecker.cycleCheck(reader, cell.valueCycleSlot)
        val value = cell.value.await()
        if (index == path.lastIndex) {
            return value.toProviderBinding(objectKey.field.outputType)
        }
        when (value) {
            null -> return VariableBinding.of(null)
            is ErrorEngineResult -> return VariableBinding.Error
            is ObjectEngineResult -> current = value
            else -> error("Resolver26 provider path crossed a non-object at $objectKey")
        }
    }
    error("Resolver26 provider path must be nonempty")
}

// Converts a provider result to an input value and rejects object-valued terminals.
private suspend fun EngineResult?.toProviderBinding(expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>): VariableBinding =
    when (this) {
        null -> VariableBinding.of(null)
        is ErrorEngineResult -> VariableBinding.Error
        is ListEngineResult -> toProviderInputListBinding()
        is ObjectEngineResult ->
            error("A path-variable provider cannot terminate at an object")
        else ->
            VariableBinding.of(
                toEngineSimpleData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef),
            )
    }

// Converts a provider list to an input list after checking its element type.
private suspend fun ListEngineResult.toProviderInputListBinding(): VariableBinding {
    require(typeExpr.baseTypeDef is ViaductSchema.InputTypeDef) {
        "A path-variable provider list must contain input-compatible simple values"
    }
    val values = mutableListOf<EngineInputData?>()
    indices.forEach { index ->
        when (val binding = get(index).value.await().toProviderBinding(typeExpr)) {
            VariableBinding.Error -> return VariableBinding.Error
            is VariableBinding.Input -> values += binding.value
        }
    }
    val data: EngineInputListData = values.toList()
    return VariableBinding.of(data)
}
