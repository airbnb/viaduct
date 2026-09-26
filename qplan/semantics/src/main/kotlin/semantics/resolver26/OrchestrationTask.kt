package semantics.resolver26

import model.Arguments
import model.InclusionCondition
import model.ObjectEngineResult
import model.SelectionForest
import model.VariableBinding
import model.registry.VariableDefinition
import model.requireQueryTypeDef
import model.schemaType
import semantics.shared.argumentsContainErrorValue
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
    queryOER: SharedOERContext,
    private val closed: ClosedConstructionDemandContext,
) : CoroutineOrchestrationTask<OperationContext>(operation, objectOER, queryOER) {
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
            val queryType = operation.world.schema.requireQueryTypeDef()
            val emptyQuerySource = operation.world.resolverRegistry.createRootQueryInput()
            val queryResult = ObjectEngineResult.of(queryType, mutable = true)
            val queryOccurrence = OEROccurrence(queryResult, emptyList(), queryResult)
            val closed =
                source.closeOrchestratorConstructionDemand(
                    world = operation.world,
                    objectOccurrence = occurrence,
                    queryOccurrence = queryOccurrence,
                    initialDemand = initialDemand,
                )
            val objectOER =
                SharedOERContext(
                    occurrence = occurrence,
                    source = source,
                    closedDemand = closed.objectRooted.demand,
                )
            val queryOER =
                SharedOERContext(
                    occurrence = queryOccurrence,
                    source = emptyQuerySource,
                    closedDemand = closed.queryRooted.demand,
                )
            return OrchestrationTask(operation, objectOER, queryOER, closed).apply {
                declareBindings()
                listOf(this.objectOER, this.queryOER).forEach { oer ->
                    oer.occurrence.installParentBackedgeFields(
                        operation,
                        oer.closedDemand.byKey().keys.filterIsInstance<ObjectEngineResult.ParentKey>(),
                    )
                    operation.bindingsState.markBindingsDeclared(oer.occurrence.target)
                }
                observeQueryOER()
            }
        }
    }

    override val hasActiveWork: Boolean
        get() = listOf(closed.objectRooted, closed.queryRooted).any { oer ->
            oer.fieldResolverOccurrences.isNotEmpty() ||
                oer.rootFieldReferenceOccurrences.isNotEmpty() ||
                oer.variableProviderReadsByResolverOccurrence.values.any { it.isNotEmpty() }
        }

    override fun installFieldTasks() {
        FieldResolverTask.launchAll(this, closed)
    }

    // Checks that passive values selected by closed demand were installed before task dispatch.
    override fun validateDispatch() {
        listOf(objectOER to closed.objectRooted, queryOER to closed.queryRooted)
            .forEach { (oer, closedOER) ->
                closedOER.demand.byKey().forEach { (objectKey, selection) ->
                    if (selection.inclusionCondition === InclusionCondition.Never) {
                        return@forEach
                    }
                    if (
                        objectKey !in closedOER.fieldResolverOccurrences &&
                        objectKey !in closedOER.rootFieldReferenceOccurrences
                    ) {
                        check(
                            objectKey is ObjectEngineResult.GroundKey &&
                                oer.occurrence.target.isCellSet(objectKey),
                        ) {
                            "Resolver26 passive key $objectKey was not materialized by " +
                                "resolvePassiveValues"
                        }
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
        listOf(closed.objectRooted, closed.queryRooted).forEach { closedOER ->
            closedOER.fieldResolverOccurrences.values.forEach { fieldResolverOccurrence ->
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
            val providerVariableIds =
                closedOER.variableProviderReadsByResolverOccurrence.values
                    .flatten()
                    .mapTo(linkedSetOf()) { providerRead ->
                        requireNotNull(providerRead.definition.variable.instanceId)
                    }
            providerVariableIds.forEach(operation.variableBindings::declareBinding)
            closedOER.fieldResolverOccurrences.values.forEach { fieldResolverOccurrence ->
                fieldResolverOccurrence.fragments.queryFragment.pathVariableDefinitions
                    .map { definition -> requireNotNull(definition.variable.instanceId) }
                    .filterNot(providerVariableIds::contains)
                    .forEach(operation.variableBindings::declareBinding)
            }
        }
    }

    private fun observeQueryOER() {
        operation.resolverObserver.onQueryOERPrepared(queryOER)
        listOf(objectOER to closed.objectRooted, queryOER to closed.queryRooted)
            .forEach { (resolverOER, closedOER) ->
                closedOER.fieldResolverOccurrences.values.forEach { fieldResolverOccurrence ->
                    val queryFragment = fieldResolverOccurrence.fragments.queryFragment
                    val selectionKey = fieldResolverOccurrence.selection.key
                    if (
                        !queryFragment.constructionSelections.isEmpty() &&
                        !(
                            selectionKey is ObjectEngineResult.GroundKey &&
                                selectionKey.arguments.argumentsContainErrorValue()
                        )
                    ) {
                        operation.resolverObserver.onQueryFragmentPrepared(
                            queryFragment.resolverOccurrenceId,
                            queryOER.occurrence.target,
                            objectOER.occurrence,
                        )
                        operation.resolverObserver.onQueryFragmentOwnerAddress(
                            queryFragment.resolverOccurrenceId,
                            resolverOER.occurrence,
                            selectionKey,
                        )
                    }
                }
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
