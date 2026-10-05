package viaduct.engine.runtime2.resolution

import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Promise
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.argumentsContainErrorValue
import viaduct.graphql.schema.ViaductSchema

/**
 * Prepares and dispatches the work associated with one object-result occurrence.
 *
 * An occurrence with active work retains a request-root coroutine as an architectural placeholder
 * for future asynchronous orchestration. The current orchestration body does not suspend.
 */
internal class OrchestrationTask private constructor(
    operation: OperationContext,
    objectOER: SharedOERContext,
    queryOER: SharedOERContext,
    val closedConstructionDemand: ClosedConstructionDemandContext,
) : CoroutineOrchestrationTaskBase<OperationContext>(operation, objectOER, queryOER) {
    private var bindingDeclarationStarted = false
    internal lateinit var checkerPreparation: FieldCheckerPreparation
        private set
    internal var typeCheckerPublications = emptyList<SymbolicTypeCheckerPublicationOccurrence>()
        private set
    private val conditionedPassivePublications = mutableListOf<SymbolicFieldPublicationOccurrence>()

    init {
        val occurrence = objectOER.occurrence
        val source = objectOER.source
        require(occurrence.root.type == operation.world.schema.requireQueryTypeDef() || occurrence.root.type == operation.world.schema.mutationTypeDef) {
            "Resolution occurrence root must have Query or Mutation type"
        }
        require(occurrence.path.isEmpty() == (occurrence.root === occurrence.target)) {
            "Only a root Resolution occurrence may use its root as its target"
        }
        require(source.schemaType == occurrence.target.type) {
            "Source type ${source.schemaType.name} does not match result type ${occurrence.target.type.name}"
        }
    }

    companion object {
        fun createObjectResult(
            operation: OperationContext,
            type: ViaductSchema.Object,
            constructionDemand: Demand<SelectionForest>,
        ): ObjectEngineResult =
            ObjectEngineResult.of(
                type = type,
                mutable = true,
                // Parent lookahead can introduce a checked incoming backedge during closure.
                typeCheckerResult = if ((constructionDemand.typeCheckCondition !== InclusionCondition.Never || operation.world.parentFieldRelations.isNotEmpty()) &&
                    operation.world.resolverRegistry.typeChecker(type) != null
                ) {
                    Promise.ofDeferred<CheckerResult?>()
                } else {
                    Promise.of<CheckerResult?>(null)
                },
            )

        /** Creates a fully prepared task without dispatching its active work. */
        fun create(
            operation: OperationContext,
            occurrence: OEROccurrence,
            source: EngineObjectData.Sync,
            constructionDemand: SelectionForest,
        ): OrchestrationTask =
            create(
                operation,
                occurrence,
                source,
                Demand.checked(constructionDemand),
            )

        fun create(
            operation: OperationContext,
            occurrence: OEROccurrence,
            source: EngineObjectData.Sync,
            constructionDemand: Demand<SelectionForest>,
        ): OrchestrationTask {
            val queryType = operation.world.schema.requireQueryTypeDef()
            val emptyQuerySource = operation.world.resolverRegistry.createRootQueryInput()
            val queryResult = ObjectEngineResult.of(queryType, mutable = true)
            val queryOccurrence = OEROccurrence(queryResult, emptyList(), queryResult)
            val closedConstructionDemand =
                source.closeOrchestrationConstructionDemand(
                    world = operation.world,
                    objectOccurrence = occurrence,
                    queryOccurrence = queryOccurrence,
                    initialDemand = OrchestrationConstructionDemand(constructionDemand, Demand.EMPTY),
                )
            val objectOER =
                SharedOERContext(
                    occurrence = occurrence,
                    source = source,
                    closedValueSelections = closedConstructionDemand.objectRooted.closedValueSelections,
                )
            val queryOER =
                SharedOERContext(
                    occurrence = queryOccurrence,
                    source = emptyQuerySource,
                    closedValueSelections = closedConstructionDemand.queryRooted.closedValueSelections,
                )
            return OrchestrationTask(operation, objectOER, queryOER, closedConstructionDemand).apply {
                declareBindings()
                listOf(this.objectOER, this.queryOER).forEach { oer ->
                    oer.occurrence.installParentBackedgeFields(
                        operation,
                        oer.closedValueSelections
                            .byKey()
                            .keys
                            .filterIsInstance<ObjectEngineResult.ParentKey>(),
                    )
                    operation.bindingsState.markBindingsDeclared(oer.occurrence.target)
                }
                checkerPreparation = FieldCheckerTask.prepareAll(this)
                typeCheckerPublications = TypeCheckerTask.prepareAll(this)
                observeQueryOER()
            }
        }
    }

    override val hasActiveWork: Boolean
        get() = typeCheckerPublications.isNotEmpty() || conditionedPassivePublications.isNotEmpty() || listOf(closedConstructionDemand.objectRooted, closedConstructionDemand.queryRooted).any {
                closedOER ->
            closedOER.fieldCheckerOccurrences.isNotEmpty() ||
                closedOER.fieldResolverOccurrences.isNotEmpty() ||
                closedOER.rootFieldReferenceOccurrences.isNotEmpty() ||
                closedOER.variableProviderReadsByResolverOccurrence.values.any { it.isNotEmpty() }
        }

    /** Passive descent prepares the publication; field dispatch remains owned by this task. */
    internal fun prepareConditionedPassiveValue(sourceOccurrence: PassiveValueOccurrence) {
        conditionedPassivePublications += FieldResolverTask.prepareConditionedPassiveValue(this, sourceOccurrence)
    }

    override fun prepareAndDispatchFieldWork() {
        val fieldPublications = FieldResolverTask.prepareAll(this)
        (fieldPublications + conditionedPassivePublications).forEach(operation.dispatcher::dispatchFieldResolver)
        checkerPreparation.executablePublications.forEach { publication ->
            operation.dispatcher.dispatchFieldChecker(publication)
        }
        typeCheckerPublications.forEach(operation.dispatcher::dispatchTypeChecker)
        checkerPreparation.publishReadyAbsences()
        val checkedCells = checkerPreparation.claimedSlots.mapTo(linkedSetOf()) { it.cell }
        listOf(objectOER, queryOER).forEach { oer ->
            oer.occurrence.target.keys.forEach { key ->
                val cell = oer.occurrence.target.getCell(key)
                if (
                    cell !in checkedCells &&
                    cell.value.isCompleted &&
                    !cell.fieldCheckerResult.isCompleted
                ) {
                    check(cell.fieldCheckerResult.complete(null)) {
                        "Field-checker result was completed twice"
                    }
                }
            }
        }
    }

    // Checks that passive values selected by closed construction demand were installed before task dispatch.
    override fun validateDispatch() {
        listOf(objectOER to closedConstructionDemand.objectRooted, queryOER to closedConstructionDemand.queryRooted)
            .forEach { (oer, closedOER) ->
                closedOER.closedValueSelections.byKey().forEach entry@{ (key, selection) ->
                    if (selection.inclusionCondition === InclusionCondition.Never) {
                        return@entry
                    }
                    if (
                        key !in closedOER.fieldResolverOccurrences &&
                        key !in closedOER.rootFieldReferenceOccurrences
                    ) {
                        check(
                            key is ObjectEngineResult.GroundKey &&
                                oer.occurrence.target.isCellSet(key),
                        ) {
                            "Resolution passive key $key was not materialized by " +
                                "resolvePassiveValues"
                        }
                    }
                }
            }
    }

    // Adds every binding introduced by the closed construction demand to the operation's binding domain.
    // Grounded argument bindings receive values immediately; open and provider bindings remain pending.
    private fun declareBindings() {
        check(!bindingDeclarationStarted) {
            "Resolution orchestration task attempted to declare its bindings twice"
        }
        bindingDeclarationStarted = true
        listOf(closedConstructionDemand.objectRooted, closedConstructionDemand.queryRooted).forEach { closedOER ->
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
            closedOER.fieldCheckerOccurrences.values.forEach { checkerOccurrence ->
                val ownerKey = checkerOccurrence.selection.key
                checkerOccurrence.variableDefinitions.forEach { variableDefinition ->
                    val id = requireNotNull(variableDefinition.variable.instanceId)
                    val definition = variableDefinition.definition
                    if (definition is VariableDefinition.FromArgument && ownerKey is ObjectEngineResult.GroundKey) {
                        operation.variableBindings.bindVariable(id, bindingFor(ownerKey.arguments, definition))
                    } else {
                        operation.variableBindings.declareBinding(id)
                    }
                }
            }
            closedOER.typeCheckerOccurrence?.variableDefinitions?.forEach { definition ->
                operation.variableBindings.declareBinding(requireNotNull(definition.variable.instanceId))
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
        operation.resolverObserver.onQueryOERPrepared(queryOER, objectOER.occurrence)
        listOf(objectOER to closedConstructionDemand.objectRooted, queryOER to closedConstructionDemand.queryRooted)
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
