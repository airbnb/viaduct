package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.ResolverTarget
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.materializeResult
import viaduct.engine.runtime2.resolution.framework.typeCheckerCycleSlot
import viaduct.engine.runtime2.resolution.framework.typeCheckerCycleTask

/** One concrete OER owns this publication and all of its runtime checker bindings. */
internal class SymbolicTypeCheckerPublicationOccurrence(
    val operation: OperationContext,
    val oerOccurrence: OEROccurrence,
    val checkerOccurrence: TypeCheckerOccurrence,
    val queryOER: SharedOERContext,
) : CoroutinePublicationOccurrence {
    override fun dispatch(requestScope: CoroutineScope) {
        requestScope.launch {
            TypeCheckerTask.execute(this@SymbolicTypeCheckerPublicationOccurrence, this)
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) TypeCheckerTask.cancel(this@SymbolicTypeCheckerPublicationOccurrence, cause)
        }
    }
}

/** Resolves named raw inputs and runtime variables before publishing the OER-owned type result. */
internal class TypeCheckerTask private constructor(
    private val publication: SymbolicTypeCheckerPublicationOccurrence,
    private val scope: CoroutineScope,
) : ResolutionExecutionContext {
    private val operation = publication.operation
    private val occurrence = publication.checkerOccurrence
    private val reader = publication.oerOccurrence.typeCheckerCycleTask()

    override val engineExecutionContext get() = operation.engineExecutionContext

    companion object {
        /** Installs exact OER writer ownership before passive descent or local dispatch. */
        fun prepareAll(orchestrationTask: OrchestrationTask): List<SymbolicTypeCheckerPublicationOccurrence> {
            val oer = orchestrationTask.objectOER
            val checker = orchestrationTask.closedConstructionDemand.objectRooted.typeCheckerOccurrence
            if (checker == null) {
                // Close speculative parent-lookahead reservations before exposing the OER.
                if (!oer.occurrence.target.typeCheckerResult.isCompleted) oer.occurrence.target.typeCheckerResult.complete(null)
                return emptyList()
            }
            check(!oer.occurrence.target.typeCheckerResult.isCompleted) {
                "Applicable type-checker result was completed before orchestration preparation"
            }
            orchestrationTask.operation.cycleChecker.registerWriter(
                oer.occurrence.target.typeCheckerCycleSlot,
                oer.occurrence.typeCheckerCycleTask(),
            )
            return listOf(SymbolicTypeCheckerPublicationOccurrence(orchestrationTask.operation, oer.occurrence, checker, orchestrationTask.queryOER))
        }

        suspend fun execute(
            publication: SymbolicTypeCheckerPublicationOccurrence,
            scope: CoroutineScope
        ) {
            TypeCheckerTask(publication, scope).run()
        }

        fun cancel(
            publication: SymbolicTypeCheckerPublicationOccurrence,
            cause: CancellationException
        ) {
            publication.oerOccurrence.target.typeCheckerResult.cancel(cause)
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
            check(publication.oerOccurrence.target.typeCheckerResult.fail(cause)) { "Type-checker failure was published twice" }
        }
    }

    private suspend fun executeAndPublish() {
        val included = occurrence.inclusionCondition.includeAnyReadyAlternative { variable ->
            when (val binding = operation.variableBindings.fetchBinding(requireNotNull(variable.instanceId))) {
                VariableBinding.Error -> error("Checker inclusion-condition variable failed")
                is VariableBinding.Input -> binding.value as? Boolean ?: error("Checker condition must be Boolean")
            }
        }
        if (!included) {
            completePendingBindings(VariableBinding.of(null))
            check(publication.oerOccurrence.target.typeCheckerResult.complete(null))
            return
        }
        if (occurrence.providerReads.isNotEmpty()) {
            scope.launch { completeProviderBindings(operation, occurrence.providerReads) }
        }
        val variables =
            withVariablesProviderResolutionContext(this) {
                occurrence.checker.provideVariables()
            }
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
            operation.checkerObserver.onCheckerQueryFragmentPrepared(occurrence.checker.target, id, publication.queryOER.occurrence.target)
        }
        operation.checkerObserver.onCheckerInvocation(
            CheckerInvocationObservation(
                checkerKind = CheckerKind.TYPE,
                logicalQueryRoot = publication.oerOccurrence.root,
                occurrencePath = publication.oerOccurrence.path,
                arguments = null,
                checkedTarget = ResolverTarget.TypeCheckerTarget(publication.oerOccurrence.target.type),
            ),
            inputs,
        )
        val result = occurrence.checker(inputs, this)
        check(publication.oerOccurrence.target.typeCheckerResult.complete(result)) { "Type-checker result was completed twice" }
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
