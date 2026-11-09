package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.EngineInputListData
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.objectKey
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.toEngineSimpleData
import viaduct.engine.runtime2.resolution.framework.CycleSlotKind
import viaduct.engine.runtime2.resolution.framework.CycleTaskKind
import viaduct.engine.runtime2.resolution.framework.ProviderCellOutcome
import viaduct.engine.runtime2.resolution.framework.ResolverFieldOccurrence
import viaduct.engine.runtime2.resolution.framework.ResolverProviderReadObservation
import viaduct.engine.runtime2.resolution.framework.fetchGroundedArguments
import viaduct.engine.runtime2.resolution.framework.fetchIncluded
import viaduct.graphql.schema.ViaductSchema

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
                        providerRead.providerRoot.startingResult.readProvider(
                            operation = operation,
                            providerRead = providerRead,
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
    providerRead: VariableProviderReadOccurrence,
): VariableBinding {
    var current = this
    val reader = providerRead.reader
    val definition = providerRead.definition
    val onProviderRead =
        operation.resolutionObserver.onProviderRead
            .takeIf { reader.kind == CycleTaskKind.FIELD_RESOLVER }
    val providerPath = onProviderRead?.let { providerRead.providerRoot.pathToStartingResult.toMutableList() }
    definition.path.forEachIndexed { index, element ->
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
        operation.cycleChecker.cycleCheck(reader, cell, CycleSlotKind.VALUE)
        val value = cell.value.await()
        if (providerPath != null) {
            providerPath += objectKey
        }
        if (providerPath != null &&
            (index == definition.path.lastIndex || value == null || value is ErrorEngineResult)
        ) {
            onProviderRead.invoke(
                ResolverProviderReadObservation(
                    provider = ResolverFieldOccurrence(
                        root = providerRead.providerRoot.pathRoot,
                        path = providerPath.toList(),
                        field = objectKey.field,
                    ),
                    consumer = ResolverFieldOccurrence(
                        root = reader.root,
                        path = reader.path,
                        field = requireNotNull((reader.path.lastOrNull() as? ObjectEngineResult.ObjectKey)?.field),
                    ),
                    variableName = definition.variable.variableName,
                    providerFragment = definition.providerFragment,
                    outcome = when (value) {
                        null -> ProviderCellOutcome.NULL
                        is ErrorEngineResult -> ProviderCellOutcome.ERROR
                        else -> ProviderCellOutcome.VALUE
                    },
                ),
            )
        }
        if (index == definition.path.lastIndex) {
            return value.toProviderBinding(objectKey.field.outputType)
        }
        when (value) {
            null -> return VariableBinding.of(null)
            is ErrorEngineResult -> return VariableBinding.Error
            is ObjectEngineResult -> current = value
            else -> error("Resolution provider path crossed a non-object at $objectKey")
        }
    }
    error("Resolution provider path must be nonempty")
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
