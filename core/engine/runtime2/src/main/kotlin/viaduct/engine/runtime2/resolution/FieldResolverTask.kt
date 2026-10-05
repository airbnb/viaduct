package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineObjectOrErrorData
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.FieldValueResolver
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.ResolverFragment
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.resolution.framework.CycleTask
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedFieldPublicationOccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.argumentsContainErrorValue
import viaduct.engine.runtime2.resolution.framework.fieldResolverCycleTask
import viaduct.engine.runtime2.resolution.framework.materializeResult
import viaduct.engine.runtime2.resolution.framework.valueCycleSlot

/**
 * One field or list-element value publication: its operation, containing object occurrence,
 * initial value source, destination cell, and provider reads. Retained across all reference-hop
 * invocations of its task; publication completes the destination cell's value slot.
 */
internal class SymbolicFieldPublicationOccurrence(
    override val operation: OperationContext,
    override val oerOccurrence: OEROccurrence,
    val sourceOccurrence: ValueSourceOccurrence,
    override val publicationCell: EngineResultCell,
    /** Associated Query context for ordinary fragments; absent for mutation and list-reference publications. */
    val queryOER: SharedOERContext?,
    /** Variable-provider reads rooted in the publication's object or associated Query OER. */
    val variableProviderReads: List<VariableProviderReadOccurrence>,
    val checkerScheduled: Boolean = false,
) : SharedFieldPublicationOccurrence<OperationContext, CoroutineTaskDispatcher>,
    CoroutinePublicationOccurrence,
    OperationContext by operation {
    override fun dispatch(requestScope: CoroutineScope) {
        requestScope.launch {
            FieldResolverTask.execute(this@SymbolicFieldPublicationOccurrence, this)
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) FieldResolverTask.cancel(this@SymbolicFieldPublicationOccurrence, cause)
        }
    }
}

/** Owns setup and resolution for one field publication. */
internal class FieldResolverTask private constructor(
    publication: SymbolicFieldPublicationOccurrence,
    fieldTaskScope: CoroutineScope,
) : CoroutineFieldResolverTaskBase<SymbolicFieldPublicationOccurrence>(publication, fieldTaskScope),
    ResolutionExecutionContext {
    override val engineExecutionContext get() = publication.operation.engineExecutionContext

    private val resolutionLogic = FieldResolutionLogic(this)

    companion object {
        fun prepareMutation(
            operation: OperationContext,
            occurrence: OEROccurrence,
            selection: ObjectSelection,
        ): SymbolicFieldPublicationOccurrence {
            val path = occurrence.coordinate(selection.key)
            val id = ResolverOccurrenceId.at(occurrence.root, path)
            val resolver = operation.world.resolverRegistry.resolver(selection.key.field)
            return prepare(
                operation,
                occurrence,
                FieldResolverOccurrence(selection, occurrence.root, path, id, resolver, emptyList(), resolver.instantiateFragments(id)),
                providerReads = emptyList(),
            )
        }

        /** Installs all local value promises and writers before orchestration dispatches any producer. */
        fun prepareAll(orchestrationTask: OrchestrationTask): List<SymbolicFieldPublicationOccurrence> {
            val operation = orchestrationTask.operation
            return listOf(
                orchestrationTask.objectOER to orchestrationTask.closedConstructionDemand.objectRooted,
                orchestrationTask.queryOER to orchestrationTask.closedConstructionDemand.queryRooted,
            ).flatMap { (oer, closedOER) ->
                buildList {
                    closedOER.fieldResolverOccurrences.forEach { (objectKey, fieldResolverOccurrence) ->
                        check(objectKey.field in operation.world.resolverRegistry) {
                            "Resolution attempted to install passive key $objectKey"
                        }
                        check(!oer.source.isPresent(objectKey.field.name)) {
                            "Resolution attempted to install source-provided key $objectKey"
                        }
                        add(
                            prepare(
                                operation = operation,
                                oerOccurrence = oer.occurrence,
                                sourceOccurrence = fieldResolverOccurrence,
                                checkerScheduled = objectKey in closedOER.fieldCheckerOccurrences,
                                queryOER = orchestrationTask.queryOER,
                                providerReads =
                                    closedOER.variableProviderReadsByResolverOccurrence.getValue(
                                        fieldResolverOccurrence.resolverOccurrenceId,
                                    ),
                            ),
                        )
                    }
                    closedOER.rootFieldReferenceOccurrences.values.forEach { referenceOccurrence ->
                        val objectKey = referenceOccurrence.selection.key
                        check(
                            oer.source.outputValue(objectKey.field.name) ===
                                referenceOccurrence.reference,
                        ) {
                            "Resolution root reference does not match its source value"
                        }
                        add(
                            prepare(
                                operation = operation,
                                oerOccurrence = oer.occurrence,
                                sourceOccurrence = referenceOccurrence,
                                checkerScheduled = objectKey in closedOER.fieldCheckerOccurrences,
                                queryOER = orchestrationTask.queryOER,
                                providerReads = emptyList(),
                            ),
                        )
                    }
                }
            }
        }

        /** Claims and dispatches a reference publication at its exact list-element path. */
        fun prepareAndDispatchListElement(
            operation: OperationContext,
            oerOccurrence: OEROccurrence,
            sourceOccurrence: ValueSourceOccurrence,
            publicationCell: EngineResultCell,
        ) {
            publicationCell.value.claim()
            operation.cycleChecker.registerWriter(
                slot = publicationCell.valueCycleSlot,
                writer = oerOccurrence.root.fieldResolverCycleTask(sourceOccurrence.publicationPath),
            )
            operation.dispatcher.dispatchFieldResolver(
                SymbolicFieldPublicationOccurrence(
                    operation,
                    oerOccurrence,
                    sourceOccurrence,
                    publicationCell,
                    null,
                    emptyList(),
                ),
            )
        }

        /** Claims a conditioned passive field during descent; its orchestration owns dispatch. */
        fun prepareConditionedPassiveValue(
            orchestrationTask: OrchestrationTask,
            sourceOccurrence: PassiveValueOccurrence,
        ): SymbolicFieldPublicationOccurrence =
            prepare(
                operation = orchestrationTask.operation,
                oerOccurrence = orchestrationTask.objectOER.occurrence,
                sourceOccurrence = sourceOccurrence,
                queryOER = orchestrationTask.queryOER,
                providerReads = emptyList(),
                checkerScheduled = sourceOccurrence.selection.key in
                    orchestrationTask.closedConstructionDemand.objectRooted.fieldCheckerOccurrences,
            )

        private fun prepare(
            operation: OperationContext,
            oerOccurrence: OEROccurrence,
            sourceOccurrence: ValueSourceOccurrence,
            queryOER: SharedOERContext? = null,
            providerReads: List<VariableProviderReadOccurrence>,
            checkerScheduled: Boolean = false,
        ): SymbolicFieldPublicationOccurrence {
            val objectKey = sourceOccurrence.selection.key
            val publicationCell = oerOccurrence.target.reserveCell(objectKey)
            publicationCell.value.claim()
            operation.cycleChecker.registerWriter(
                slot = publicationCell.valueCycleSlot,
                writer = oerOccurrence.fieldResolverCycleTask(objectKey),
            )
            return SymbolicFieldPublicationOccurrence(
                operation,
                oerOccurrence,
                sourceOccurrence,
                publicationCell,
                queryOER,
                providerReads,
                checkerScheduled,
            )
        }

        /** Enters the existing field-task body under its dispatched coroutine's scope. */
        internal suspend fun execute(
            publication: SymbolicFieldPublicationOccurrence,
            scope: CoroutineScope,
        ) {
            val task = FieldResolverTask(
                publication = publication,
                fieldTaskScope = scope,
            )
            task.run()
        }

        /** Terminates owned promises even when cancellation prevents the task body from entering. */
        internal fun cancel(
            publication: SymbolicFieldPublicationOccurrence,
            cause: CancellationException
        ) {
            with(publication) {
                publicationCell.value.cancel(cause)
                if (!checkerScheduled) publicationCell.fieldCheckerResult.cancel(cause)
                val fieldResolverOccurrence =
                    sourceOccurrence as? FieldResolverOccurrence
                        ?: return
                variableProviderReads.forEach { providerRead ->
                    operation.variableBindings.cancelBinding(
                        requireNotNull(providerRead.definition.variable.instanceId),
                        cause,
                    )
                }
                cancelInvocationBindings(operation, fieldResolverOccurrence, cause)
            }
        }

        private fun cancelInvocationBindings(
            operation: OperationContext,
            fieldResolverOccurrence: FieldResolverOccurrence,
            cause: CancellationException,
        ) {
            fieldResolverOccurrence.variableDefinitions.forEach { definition ->
                if (
                    definition.definition == VariableDefinition.FromProvider ||
                    definition.definition is VariableDefinition.FromArgument
                ) {
                    operation.variableBindings.cancelBinding(
                        requireNotNull(definition.variable.instanceId),
                        cause,
                    )
                }
            }
            fieldResolverOccurrence.fragments.queryFragment.pathVariableDefinitions.forEach {
                    definition ->
                operation.variableBindings.cancelBinding(
                    requireNotNull(definition.variable.instanceId),
                    cause,
                )
            }
        }
    }

    override suspend fun executeAndPublish() {
        try {
            resolutionLogic.validate()
        } catch (cause: Exception) {
            currentCoroutineContext().ensureActive()
            // Validation precedes provider readers, so no helper will complete their bindings.
            publication.variableProviderReads.forEach { providerRead ->
                publication.operation.variableBindings.completeBinding(
                    requireNotNull(providerRead.definition.variable.instanceId),
                    VariableBinding.Error,
                )
            }
            throw cause
        }
        launchTaskSetupCoroutines()
        resolutionLogic.publishResult()
    }

    override fun publishFailure(cause: Exception) {
        resolutionLogic.publishFieldError(cause)
    }

    /**
     * Resolves a nested `ctx.query` selection as structured child work of this field task.
     *
     * This is distinct from the resolver's declared Query fragment. Startup installs the selected
     * result cells; [materializeResult] projects their values for the caller and can await them.
     */
    override suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync {
        val childOperation = publication.operation.forChildScope(fieldTaskScope)
        val result = childOperation.startResolve(selections.constructionSelections())
        return result.materializeResult(
            operation = childOperation,
            selections = selections,
            reader =
                publication.oerOccurrence.root
                    .fieldResolverCycleTask(publication.sourceOccurrence.publicationPath),
            cycleChecker = childOperation.cycleChecker,
        )
    }

    private fun launchTaskSetupCoroutines() {
        publication.sourceOccurrence as? FieldResolverOccurrence ?: return
        if (publication.variableProviderReads.isNotEmpty()) {
            fieldTaskScope.launch {
                if (!publication.publicationCell.fetchActivated()) return@launch
                completeProviderBindings(
                    publication.operation,
                    publication.variableProviderReads,
                )
            }
        }
    }

    /** Produces the independent Query input for one root-field-reference invocation. */
    fun launchIndependentQueryFragmentProducer(fieldResolverOccurrence: FieldResolverOccurrence): Deferred<EngineObjectOrErrorData> {
        // Register each invocation with the field-task root, including later reference hops.
        // cancel(publication, cause) also covers the original bindings before field-task entry.
        fieldTaskScope.coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                cancelInvocationBindings(publication.operation, fieldResolverOccurrence, cause)
            }
        }
        val selectionKey = fieldResolverOccurrence.selection.key
        return fieldTaskScope
            .async {
                try {
                    val objectValue =
                        if (
                            selectionKey is ObjectEngineResult.GroundKey &&
                            selectionKey.arguments.argumentsContainErrorValue()
                        ) {
                            engineObjectDataOf(publication.operation.world.schema.requireQueryTypeDef())
                        } else {
                            fieldResolverOccurrence.resolver.resolveQueryFragment(
                                queryFragment = fieldResolverOccurrence.fragments.queryFragment,
                                operation = publication.operation,
                                reader =
                                    fieldResolverOccurrence.invocationRoot
                                        .fieldResolverCycleTask(fieldResolverOccurrence.invocationPath),
                                inclusionCondition =
                                    fieldResolverOccurrence.selection.inclusionCondition,
                            )
                        }
                    EngineObjectOrErrorData.of(objectValue)
                } catch (cause: Exception) {
                    currentCoroutineContext().ensureActive()
                    completeQueryPathBindingsWithError(fieldResolverOccurrence)
                    EngineObjectOrErrorData.of(EngineErrorData.of(cause))
                }
            }
    }

    private fun completeQueryPathBindingsWithError(fieldResolverOccurrence: FieldResolverOccurrence) {
        fieldResolverOccurrence.fragments.queryFragment.pathVariableDefinitions.forEach { definition ->
            publication.operation.variableBindings.completeBinding(
                requireNotNull(definition.variable.instanceId),
                VariableBinding.Error,
            )
        }
    }
}

private suspend fun FieldValueResolver.resolveQueryFragment(
    queryFragment: ResolverFragment,
    operation: OperationContext,
    reader: CycleTask,
    inclusionCondition: InclusionCondition,
): EngineObjectData.Sync {
    if (queryFragment.constructionSelections.isEmpty()) {
        return engineObjectDataOf(operation.world.schema.requireQueryTypeDef())
    }

    val constructionSelections =
        queryFragment.constructionSelections.guardedBy(inclusionCondition)
    val source = operation.world.resolverRegistry.createRootQueryInput()
    val queryResult =
        OrchestrationTask.createObjectResult(operation, source.schemaType, Demand.checked(constructionSelections))
    val orchestration =
        OrchestrationTask.create(
            operation = operation,
            occurrence =
                OEROccurrence(
                    root = queryResult,
                    path = emptyList(),
                    target = queryResult,
                ),
            source = source,
            constructionDemand = constructionSelections,
        )
    operation.resolverObserver.onIndependentQueryFragmentPrepared(
        queryFragment.resolverOccurrenceId,
        queryResult,
    )
    operation.dispatcher.dispatchOrchestration(orchestration)
    completeProviderBindings(
        operation = operation,
        providerReads =
            queryFragment.pathVariableDefinitions.map { definition ->
                VariableProviderReadOccurrence(
                    providerResult = queryResult,
                    definition = definition,
                    reader = reader,
                    inclusionCondition = inclusionCondition,
                )
            },
    )
    val materializeSelections =
        instantiateQueryMaterializationSelections(queryFragment.resolverOccurrenceId)
            .guardedBy(inclusionCondition)
    return queryResult.materializeResolverInput(
        operation = operation,
        cycleChecker = operation.cycleChecker,
        selections = materializeSelections,
        reader = reader,
        resultPath = emptyList(),
    )
}
