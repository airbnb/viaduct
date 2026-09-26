package semantics.resolver26

import model.Arguments
import model.InclusionCondition
import model.ObjectEngineResult
import model.SelectionForest
import model.VariableBinding
import model.registry.VariableDefinition
import model.requireQueryTypeDef
import model.schemaType
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext
import viaduct.engine.api.EngineObjectData

/**
 * Installs and launches the work associated with one object-result occurrence.
 *
 * An occurrence with active work retains a request-root coroutine as an architectural placeholder
 * for future asynchronous orchestration. The current orchestration body does not suspend.
 */
internal class OrchestrationTask private constructor(
    operation: OperationContext,
    objectOER: SharedOERContext,
    private val closed: ClosedConstructionDemandContext,
) : CoroutineOrchestrationTask<OperationContext>(operation, objectOER) {
    private var bindingDeclarationStarted = false

    init {
        val occurrence = objectOER.occurrence
        val source = objectOER.source
        require(occurrence.root.type == operation.world.schema.requireQueryTypeDef()) {
            "Resolver26 occurrence root must have Query type"
        }
        require(occurrence.path.isEmpty() == (occurrence.root === occurrence.target)) {
            "Only a root Resolver26 occurrence may use its root as its target"
        }
        require(source.schemaType == occurrence.target.type) {
            "Source type ${source.schemaType.name} does not match result type ${occurrence.target.type.name}"
        }
    }

    companion object {
        /** Creates a fully prepared task without dispatching its active work. */
        fun create(
            operation: OperationContext,
            occurrence: OEROccurrence,
            source: EngineObjectData.Sync,
            initialDemand: SelectionForest,
        ): OrchestrationTask {
            val closed = source.closeConstructionDemand(operation.world, occurrence, initialDemand)
            return OrchestrationTask(
                operation,
                SharedOERContext(occurrence, source, closed.demand),
                closed,
            ).apply {
                declareBindings()
                occurrence.installParentBackedgeFields(operation, closed.demand.byKey().keys.filterIsInstance<ObjectEngineResult.ParentKey>())
                operation.bindingsState.markBindingsDeclared(occurrence.target)
            }
        }
    }

    override val hasActiveWork: Boolean
        get() = closed.fieldResolverOccurrences.isNotEmpty() ||
            closed.rootFieldReferenceOccurrences.isNotEmpty() ||
            closed.variableProviderReadsByResolverOccurrence.values.any { it.isNotEmpty() }

    override fun installFieldTasks() {
        FieldResolverTask.launchAll(this, closed)
    }

    // Checks that passive values selected by closed demand were installed before task dispatch.
    override fun validateDispatch() {
        closed.demand.byKey().forEach { (objectKey, selection) ->
            if (selection.inclusionCondition === InclusionCondition.Never) {
                return@forEach
            }
            if (
                objectKey !in closed.fieldResolverOccurrences &&
                objectKey !in closed.rootFieldReferenceOccurrences
            ) {
                check(
                    objectKey is ObjectEngineResult.GroundKey &&
                        objectOER.occurrence.target.isCellSet(objectKey),
                ) {
                    "Resolver26 passive key $objectKey was not materialized by " +
                        "resolvePassiveValues"
                }
            }
        }
    }

    // Adds every binding introduced by the closed demand to the operation's binding domain.
    // Grounded argument bindings receive values immediately; open and provider bindings remain pending.
    private fun declareBindings() {
        check(!bindingDeclarationStarted) {
            "Resolver26 orchestration task attempted to declare its bindings twice"
        }
        bindingDeclarationStarted = true
        closed.fieldResolverOccurrences.values.forEach { fieldResolverOccurrence ->
            val ownerKey = fieldResolverOccurrence.selection.key
            fieldResolverOccurrence.variableDefinitions.forEach { variableDefinition ->
                val variableId = requireNotNull(variableDefinition.variable.instanceId)
                when (val definition = variableDefinition.definition) {
                    VariableDefinition.FromProvider ->
                        operation.variableBindings.declareBinding(variableId)

                    is VariableDefinition.FromArgument ->
                        if (ownerKey is ObjectEngineResult.GroundKey) {
                            operation.variableBindings.bindVariable(
                                variableId,
                                bindingFor(ownerKey.arguments, definition),
                            )
                        } else {
                            operation.variableBindings.declareBinding(variableId)
                        }

                    is VariableDefinition.FromField -> Unit
                }
            }
        }
        closed.variableProviderReadsByResolverOccurrence.values.flatten().forEach { providerRead ->
            operation.variableBindings.declareBinding(
                requireNotNull(providerRead.definition.variable.instanceId),
            )
        }
        closed.fieldResolverOccurrences.values
            .flatMap { fieldResolverOccurrence -> fieldResolverOccurrence.fragments.queryFragment.pathVariableDefinitions }
            .forEach { definition ->
                operation.variableBindings.declareBinding(
                    requireNotNull(definition.variable.instanceId),
                )
            }
    }
}

// Reads one FromArgument definition from grounded arguments while preserving argument errors.
internal fun bindingFor(
    arguments: Arguments.Ground,
    definition: VariableDefinition.FromArgument,
): VariableBinding =
    when (arguments) {
        Arguments.Error -> VariableBinding.Error
        is Arguments.Resolved -> VariableBinding.of(definition.read(arguments))
    }
