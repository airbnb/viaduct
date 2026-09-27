package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import model.Arguments
import model.EngineResultCell
import model.InclusionCondition
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectSelection
import model.VariableBinding
import model.registry.CheckerInput
import model.registry.ResolutionExecutionContext
import model.registry.VariableDefinition
import semantics.shared.CheckerInvocationObservation
import semantics.shared.CheckerKind
import semantics.shared.Demand
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext
import semantics.shared.fetchGroundedArguments
import semantics.shared.fieldCheckerCycleSlot
import semantics.shared.fieldCheckerCycleTask
import semantics.shared.materializeResult
import viaduct.engine.api.EngineObjectData

/** A checker writer retains its original symbolic address independently of its late arguments. */
internal class SymbolicFieldCheckerPublicationOccurrence(
    val operation: OperationContext,
    val oerOccurrence: OEROccurrence,
    val checkerOccurrence: FieldCheckerOccurrence,
    override val publicationCell: EngineResultCell,
    val queryOER: SharedOERContext,
) : CoroutineFieldCheckerPublicationOccurrence

/** One claimed slot either has an executable checker or defers absence until value activation. */
internal class PreparedFieldCheckerSlot(
    val cell: EngineResultCell,
    val executablePublication: SymbolicFieldCheckerPublicationOccurrence?,
)

/**
 * Records every claim, including absent checkers. Passive values permit immediate absence after
 * descent; pending values leave absence to their field-resolver task's activation/cancellation.
 */
internal class FieldCheckerPreparation(val claimedSlots: List<PreparedFieldCheckerSlot>) {
    val executablePublications = claimedSlots.mapNotNull { it.executablePublication }
    val delayedAbsenceSlots = claimedSlots.filter { it.executablePublication == null }.map { it.cell }

    fun publishReadyAbsences() {
        delayedAbsenceSlots.forEach { cell ->
            if (cell.getValue().isCompleted && !cell.getFieldCheckerResult().isCompleted) {
                cell.getFieldCheckerResult().complete(null)
            }
        }
    }
}

/** Raw projections and runtime bindings for one checker, parallel to the field value task. */
internal class FieldCheckerTask private constructor(
    private val publication: SymbolicFieldCheckerPublicationOccurrence,
    private val scope: CoroutineScope,
) : ResolutionExecutionContext {
    private val operation = publication.operation
    private val occurrence = publication.checkerOccurrence
    private val key = occurrence.selection.key
    private val reader = publication.oerOccurrence.fieldCheckerCycleTask(key)

    companion object {
        /** Claims all checked slots before passive descent can expose cells to readers. */
        fun prepareAll(orchestrationTask: OrchestrationTask): FieldCheckerPreparation =
            FieldCheckerPreparation(
                listOf(
                    orchestrationTask.objectOER to orchestrationTask.closedConstructionDemand.objectRooted,
                    orchestrationTask.queryOER to orchestrationTask.closedConstructionDemand.queryRooted,
                ).flatMap { (oer, closedOER) ->
                    closedOER.constructionDemand.checked.byKey().mapNotNull { (key, selection) ->
                        prepare(orchestrationTask, oer, selection, closedOER.fieldCheckerOccurrences[key])
                    }
                },
            )

        private fun prepare(
            orchestrationTask: OrchestrationTask,
            oer: SharedOERContext,
            selection: ObjectSelection,
            checkerOccurrence: FieldCheckerOccurrence?,
        ): PreparedFieldCheckerSlot? {
            if (selection.inclusionCondition === InclusionCondition.Never) return null
            val key = selection.key
            val cell = oer.occurrence.target.reserveCell(key)
            cell.createFieldCheckerResultPromise()
            // Absence is published at the existing activation boundary, without a checker task.
            if (checkerOccurrence == null) return PreparedFieldCheckerSlot(cell, null)
            orchestrationTask.operation.cycleChecker.registerWriter(
                cell.fieldCheckerCycleSlot,
                oer.occurrence.fieldCheckerCycleTask(key),
            )
            val publication = SymbolicFieldCheckerPublicationOccurrence(
                orchestrationTask.operation, oer.occurrence, checkerOccurrence, cell, orchestrationTask.queryOER,
            )
            return PreparedFieldCheckerSlot(cell, publication)
        }

        suspend fun execute(
            publication: SymbolicFieldCheckerPublicationOccurrence,
            scope: CoroutineScope
        ) {
            FieldCheckerTask(publication, scope).run()
        }

        fun cancel(
            publication: SymbolicFieldCheckerPublicationOccurrence,
            cause: CancellationException
        ) {
            publication.publicationCell.cancelFieldCheckerResult(cause)
            publication.checkerOccurrence.variableDefinitions.forEach { definition ->
                publication.operation.variableBindings.cancelBinding(requireNotNull(definition.variable.instanceId), cause)
            }
        }
    }

    private suspend fun run() {
        try {
            executeAndPublish()
        } catch (cause: Exception) {
            currentCoroutineContext().ensureActive()
            completePendingBindings(VariableBinding.Error)
            check(publication.publicationCell.failFieldCheckerResult(cause)) { "Field-checker failure was published twice" }
        }
    }

    private suspend fun executeAndPublish() {
        if (!publication.publicationCell.fetchActivated()) {
            completePendingBindings(VariableBinding.of(null))
            return
        }
        val included = occurrence.selection.inclusionCondition.includeAnyReadyAlternative { variable ->
            when (val binding = operation.variableBindings.fetchBinding(requireNotNull(variable.instanceId))) {
                VariableBinding.Error -> error("Checker inclusion-condition variable failed")
                is VariableBinding.Input -> binding.value as? Boolean ?: error("Checker condition must be Boolean")
            }
        }
        if (!included) {
            completePendingBindings(VariableBinding.of(null))
            check(publication.publicationCell.getFieldCheckerResult().complete(null))
            return
        }
        val arguments = key.fetchGroundedArguments(operation)
        occurrence.variableDefinitions.forEach { variableDefinition ->
            val definition = variableDefinition.definition
            if (definition is VariableDefinition.FromArgument && key !is ObjectEngineResult.GroundKey) {
                operation.variableBindings.completeBinding(requireNotNull(variableDefinition.variable.instanceId), bindingFor(arguments, definition))
            }
        }
        if (arguments !is Arguments.Resolved) {
            completePendingBindings(VariableBinding.Error)
            check(publication.publicationCell.getFieldCheckerResult().complete(null))
            return
        }
        if (occurrence.providerReads.isNotEmpty()) {
            scope.launch { completeProviderBindings(operation, occurrence.providerReads) }
        }
        val variables = occurrence.checker.provideVariables(arguments)
        occurrence.variableDefinitions.filter { it.definition == VariableDefinition.FromProvider }.forEach { definition ->
            check(
                operation.variableBindings.completeBinding(
                    requireNotNull(definition.variable.instanceId),
                    VariableBinding.of(variables.getValue(definition.variable.variableName)),
                )
            ) { "Checker provider binding was completed twice" }
        }
        val id = occurrence.fragments.objectFragment.resolverOccurrenceId
        val objectSelections = occurrence.checker.instantiateObjectMaterializationSelections(id)
        val querySelections = occurrence.checker.instantiateQueryMaterializationSelections(id)
        val inputs = occurrence.checker.fragmentTemplates.keys.associateWith { name ->
            CheckerInput(
                objectValue = publication.oerOccurrence.target.materializeResolverInput(
                    operation = operation,
                    cycleChecker = operation.cycleChecker,
                    selections = objectSelections.getValue(name),
                    reader = reader,
                    resultPath = publication.oerOccurrence.path,
                    checked = false,
                ),
                queryValue = publication.queryOER.occurrence.target.materializeResolverInput(
                    operation = operation,
                    cycleChecker = operation.cycleChecker,
                    selections = querySelections.getValue(name),
                    reader = reader,
                    resultPath = emptyList(),
                    checked = false,
                ),
            )
        }
        if (!occurrence.fragments.queryFragment.constructionSelections.isEmpty()) {
            operation.checkerObserver.onCheckerQueryFragmentPrepared(id, publication.queryOER.occurrence.target)
        }
        operation.checkerObserver.onCheckerInvocation(
            CheckerInvocationObservation(
                checkerKind = CheckerKind.FIELD,
                logicalQueryRoot = publication.oerOccurrence.root,
                occurrencePath = publication.oerOccurrence.coordinate(key),
                arguments = arguments,
                checkedCoordinate = key.field,
            ),
        )
        val result = occurrence.checker(arguments, inputs, this)
        check(publication.publicationCell.getFieldCheckerResult().complete(result)) { "Field-checker result was completed twice" }
    }

    private fun completePendingBindings(binding: VariableBinding) {
        occurrence.variableDefinitions.forEach { definition ->
            operation.variableBindings.completeBinding(requireNotNull(definition.variable.instanceId), binding)
        }
    }

    override suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync {
        val childOperation = operation.forChildScope(scope)
        val result = childOperation.startResolve(Demand.unchecked(selections.constructionSelections()))
        return result.materializeResult(childOperation, selections, reader, childOperation.cycleChecker, checked = false)
    }
}
