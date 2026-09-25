package semantics.resolvers.resolver21

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import model.EngineErrorData
import model.EngineObjectOrErrorData
import model.ObjectEngineResult
import model.PathComponent
import model.RootFieldReferenceData
import model.engineObjectDataOf
import model.merge
import model.outputValue
import model.registry.FieldResolver
import model.registry.ResolverFragment
import model.requireQueryTypeDef
import model.selectionForestOf
import semantics.resolvers.GroundedFieldPublicationOccurrence
import semantics.resolvers.materializeResolverInput
import semantics.shared.CycleTask
import semantics.shared.Demand
import semantics.shared.fieldResolverCycleTask
import semantics.shared.valueCycleSlot

/** Owns field-local helper coroutines and delegates invocation and publication to resolution logic. */
internal class CoroutineFieldResolverTask private constructor(
    publication: GroundedFieldPublicationOccurrence<CoroutineOperationContext>,
    fieldTaskScope: CoroutineScope,
) : semantics.resolver26.CoroutineFieldResolverTask<GroundedFieldPublicationOccurrence<CoroutineOperationContext>>(publication, fieldTaskScope) {
    private val resolutionLogic = FieldResolutionLogic(this)

    companion object {
        /** Installs all local promises before dispatching any producer, including source references. */
        fun prepareAll(
            orchestrationTask: CoroutineOrchestrationTask,
        ): List<GroundedFieldPublicationOccurrence<CoroutineOperationContext>> {
            val operation = orchestrationTask.operation
            return listOf(
                orchestrationTask.objectOER to orchestrationTask.closedConstructionDemand.objectRooted,
                orchestrationTask.queryOER to orchestrationTask.closedConstructionDemand.queryRooted,
            ).flatMap { (oer, constructionDemand) ->
                val occurrence = oer.occurrence
                val checkedByKey = constructionDemand.checked.byGroundKey()
                val uncheckedByKey = constructionDemand.unchecked.byGroundKey()
                oer.closedDemand.byGroundKey()
                    .filterKeys { !occurrence.target.isCellSet(it) }
                    .map { (key, selection) ->
                        val reference = if (oer.source.isPresent(key.field.name)) {
                            oer.source.outputValue(key.field.name) as RootFieldReferenceData
                        } else null
                        prepare(
                            GroundedFieldPublicationOccurrence(
                                operation = operation,
                                oerOccurrence = occurrence,
                                selection = selection,
                                publicationCell = occurrence.target.reserveCell(key),
                                reference = reference,
                                queryOER = orchestrationTask.queryOER,
                                constructionDemand =
                                    Demand(
                                        checked = checkedByKey[key]?.subselections ?: selectionForestOf(),
                                        unchecked = uncheckedByKey[key]?.subselections ?: selectionForestOf(),
                                    ),
                            ),
                        )
                    }
            }
        }

        /** List references use the same publication protocol at their exact list-element path. */
        fun launchForListElement(publication: GroundedFieldPublicationOccurrence<CoroutineOperationContext>) {
            publication.operation.dispatcher.dispatchFieldResolver(prepare(publication))
        }

        private fun prepare(publication: GroundedFieldPublicationOccurrence<CoroutineOperationContext>): GroundedFieldPublicationOccurrence<CoroutineOperationContext> = publication.apply {
            publicationCell.createValuePromise()
            // List cells are activated when the shared traversal allocates their list.
            if (publicationPath.last() is ObjectEngineResult.ObjectKey) {
                check(publicationCell.setActivated(true)) { "Cell activation was decided twice" }
            }
            operation.cycleChecker.registerWriter(
                slot = publicationCell.valueCycleSlot,
                writer = oerOccurrence.root.fieldResolverCycleTask(publicationPath),
            )
        }

        internal suspend fun execute(publication: GroundedFieldPublicationOccurrence<CoroutineOperationContext>, scope: CoroutineScope) {
            CoroutineFieldResolverTask(publication, scope).run()
        }
    }

    override suspend fun executeAndPublish() {
        resolutionLogic.publishResult()
    }

    override fun publishFailure(cause: Exception) {
        resolutionLogic.publishFieldError(cause)
    }

    /**
     * Produces an independent Query input under the field scope. Its orchestration and field work
     * are dispatched on the request root, as in Resolver26; the producer awaits only its input.
     * Failures are returned to the owning field without cancelling its scope.
     */
    fun launchQueryFragmentProducer(
        resolver: FieldResolver,
        queryFragment: ResolverFragment,
        reader: CycleTask,
    ): Deferred<EngineObjectOrErrorData> = fieldTaskScope.async {
        try {
            val queryValue = if (queryFragment.constructionSelections.isEmpty()) {
                engineObjectDataOf(publication.operation.world.schema.requireQueryTypeDef())
            } else {
                val queryResult = publication.operation.startResolve(
                    publication.operation.world.resolverRegistry.createRootQueryInput(), queryFragment.constructionSelections,
                    queryFragmentOwner = queryFragment.resolverOccurrenceId,
                )
                queryResult.materializeResolverInput(
                    operation = publication.operation,
                    cycleChecker = publication.operation.cycleChecker,
                    selections =
                        resolver.instantiateQueryMaterializationSelections(
                            queryFragment.resolverOccurrenceId,
                        ),
                    reader = reader,
                )
            }
            EngineObjectOrErrorData.of(queryValue)
        } catch (cause: Exception) {
            currentCoroutineContext().ensureActive()
            EngineObjectOrErrorData.of(EngineErrorData.of(cause))
        }
    }
}
