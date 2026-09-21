package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import model.EngineErrorData
import model.EngineObjectOrErrorData
import model.EngineResultCell
import model.InclusionCondition
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.PathComponent
import model.VariableBinding
import model.engineObjectDataOf
import model.guardedBy
import model.outputValue
import model.registry.FieldResolver
import model.registry.ResolverFragment
import model.registry.ResolutionExecutionContext
import model.registry.VariableDefinition
import model.requireQueryTypeDef
import model.schemaType
import semantics.shared.argumentsContainErrorValue
import semantics.shared.SharedFieldPublicationOccurrence
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext
import semantics.shared.CycleTask
import semantics.shared.fieldResolverCycleTask
import semantics.shared.valueCycleSlot
import semantics.shared.materializeResult
import viaduct.engine.api.EngineObjectData

/**
 * One field or list-element value publication: its operation, containing object occurrence,
 * initial value source, destination cell, and provider reads. Retained across all reference-hop
 * invocations of its task; publication completes the destination cell's value slot.
 */
internal class FieldPublicationOccurrence(
    override val operation: OperationContext,
    override val oerOccurrence: OEROccurrence,
    val sourceOccurrence: ValueSourceOccurrence,
    override val publicationCell: EngineResultCell,
    val queryOER: SharedOERContext,
    /** Variable-provider reads rooted in the publication's object or associated Query OER. */
    val variableProviderReads: List<VariableProviderReadOccurrence>,
) : SharedFieldPublicationOccurrence<OperationContext, CoroutineTaskDispatcher<OrchestrationTask, FieldPublicationOccurrence, Nothing>>,
    OperationContext by operation

/** Owns setup and resolution for one field publication. */
internal class FieldResolverTask private constructor(
    publication: FieldPublicationOccurrence,
    fieldTaskScope: CoroutineScope,
) : CoroutineFieldResolverTask<FieldPublicationOccurrence>(publication, fieldTaskScope), ResolutionExecutionContext {
    private val resolutionLogic = FieldResolutionLogic(this)

    companion object {
        /** Installs and launches every local field task owned by one object orchestration. */
        fun launchAll(
            orchestrationTask: OrchestrationTask,
            closed: ClosedConstructionDemandContext,
        ) {
            val operation = orchestrationTask.operation
            val publications =
                listOf(
                    orchestrationTask.objectOER to closed.objectRooted,
                    orchestrationTask.queryOER to closed.queryRooted,
                ).flatMap { (oer, closedOER) ->
                    buildList {
                        closedOER.fieldResolverOccurrences.forEach { (objectKey, fieldResolverOccurrence) ->
                            check(objectKey.field in operation.world.resolverRegistry) {
                                "Resolver26 attempted to install passive key $objectKey"
                            }
                            check(!oer.source.isPresent(objectKey.field.name)) {
                                "Resolver26 attempted to install source-provided key $objectKey"
                            }
                            add(
                                prepare(
                                    operation = operation,
                                    oerOccurrence = oer.occurrence,
                                    sourceOccurrence = fieldResolverOccurrence,
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
                                "Resolver26 root reference does not match its source value"
                            }
                            add(
                                prepare(
                                    operation = operation,
                                    oerOccurrence = oer.occurrence,
                                    sourceOccurrence = referenceOccurrence,
                                    queryOER = orchestrationTask.queryOER,
                                    providerReads = emptyList(),
                                ),
                            )
                        }
                    }
                }
            publications.forEach(operation.dispatcher::dispatchFieldResolver)
        }

        // Called by PassiveValueResolutionLogic to launch a list-element task.
        // The caller has already claimed the cell and registered its writer.
        fun launchForListElement(
            operation: OperationContext,
            oerOccurrence: OEROccurrence,
            sourceOccurrence: ValueSourceOccurrence,
            publicationCell: EngineResultCell,
        ) {
            launchTask(
                operation = operation,
                oerOccurrence = oerOccurrence,
                sourceOccurrence = sourceOccurrence,
                publicationCell = publicationCell,
                queryOER = SharedOERContext.undemandedQuery(operation.world.schema.requireQueryTypeDef()),
                providerReads = emptyList(),
            )
        }

        // Installs one field task while retaining its symbolic cell key.
        fun installAndLaunch(
            operation: OperationContext,
            oerOccurrence: OEROccurrence,
            sourceOccurrence: ValueSourceOccurrence,
            queryOER: SharedOERContext =
                SharedOERContext.undemandedQuery(operation.world.schema.requireQueryTypeDef()),
            providerReads: List<VariableProviderReadOccurrence> = emptyList(),
        ) {
            val publication =
                prepare(
                    operation = operation,
                    oerOccurrence = oerOccurrence,
                    sourceOccurrence = sourceOccurrence,
                    queryOER = queryOER,
                    providerReads = providerReads,
                )
            operation.dispatcher.dispatchFieldResolver(publication)
        }

        private fun prepare(
            operation: OperationContext,
            oerOccurrence: OEROccurrence,
            sourceOccurrence: ValueSourceOccurrence,
            queryOER: SharedOERContext,
            providerReads: List<VariableProviderReadOccurrence>,
        ): FieldPublicationOccurrence {
            val objectKey = sourceOccurrence.selection.key
            val publicationCell = oerOccurrence.target.reserveCell(objectKey)
            publicationCell.createValuePromise()
            operation.cycleChecker.registerWriter(
                slot = publicationCell.valueCycleSlot,
                writer = oerOccurrence.fieldResolverCycleTask(objectKey),
            )
            return FieldPublicationOccurrence(
                operation, oerOccurrence, sourceOccurrence,
                publicationCell, queryOER, providerReads,
            )
        }

        private fun launchTask(
            operation: OperationContext,
            oerOccurrence: OEROccurrence,
            sourceOccurrence: ValueSourceOccurrence,
            publicationCell: EngineResultCell,
            queryOER: SharedOERContext,
            providerReads: List<VariableProviderReadOccurrence>,
        ) {
            operation.dispatcher.dispatchFieldResolver(
                FieldPublicationOccurrence(
                    operation, oerOccurrence, sourceOccurrence,
                    publicationCell, queryOER, providerReads,
                ),
            )
        }

        /** Enters the existing field-task body under its dispatched coroutine's scope. */
        internal suspend fun execute(
            publication: FieldPublicationOccurrence,
            scope: CoroutineScope,
        ) {
            val task = FieldResolverTask(
                publication = publication,
                fieldTaskScope = scope,
            )
            task.run()
        }

        /** Terminates owned promises even when cancellation prevents the task body from entering. */
        internal fun cancel(publication: FieldPublicationOccurrence, cause: CancellationException) {
            with(publication) {
                publicationCell.cancelValue(cause)
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
        launchTaskSetupCoroutines()
        resolutionLogic.validate()
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
    override suspend fun resolveSelectionSet(
        selections: MaterializeSelectionForest,
    ): EngineObjectData.Sync {
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
    fun launchQueryFragmentProducer(
        fieldResolverOccurrence: FieldResolverOccurrence,
    ): Deferred<EngineObjectOrErrorData> {
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

    private fun completeQueryPathBindingsWithError(
        fieldResolverOccurrence: FieldResolverOccurrence,
    ) {
        fieldResolverOccurrence.fragments.queryFragment.pathVariableDefinitions.forEach { definition ->
            publication.operation.variableBindings.completeBinding(
                requireNotNull(definition.variable.instanceId),
                VariableBinding.Error,
            )
        }
    }
}

private suspend fun FieldResolver.resolveQueryFragment(
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
        ObjectEngineResult.of(
            type = source.schemaType,
            mutable = true,
        )
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
            initialDemand = constructionSelections,
        )
    operation.resolverObserver.onIndependentQueryFragmentPrepared(
        queryFragment.resolverOccurrenceId,
        queryResult,
    )
    operation.dispatcher.dispatchOrchestrator(orchestration)
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
