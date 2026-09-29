package semantics.resolvers.resolver21

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import model.EngineErrorData
import model.EngineObjectOrErrorData
import model.EngineResultCell
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.RootFieldReferenceData
import model.SelectionForest
import model.engineObjectDataOf
import model.outputType
import model.outputValue
import model.registry.FieldValueResolver
import model.registry.ResolverFragment
import model.requireQueryTypeDef
import semantics.resolver26.CoroutineFieldResolverTaskBase
import semantics.resolver26.CoroutinePublicationOccurrence
import semantics.resolvers.GroundedFieldPublicationOccurrence
import semantics.resolvers.materializeResolverInput
import semantics.shared.CycleTask
import semantics.shared.Demand
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext
import semantics.shared.descendants
import semantics.shared.fieldResolverCycleTask
import semantics.shared.valueCycleSlot
import viaduct.graphql.schema.ViaductSchema

/** Grounded field publication with the Resolver21-23 coroutine task lifecycle attached. */
internal class CoroutineFieldPublicationOccurrence(
    operation: CoroutineOperationContext,
    oerOccurrence: OEROccurrence,
    selection: ObjectSelection,
    publicationCell: EngineResultCell,
    reference: RootFieldReferenceData? = null,
    invocationDemand: SelectionForest? = null,
    publicationPath: List<PathComponent> = oerOccurrence.coordinate(selection.key),
    publicationExpectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> = selection.key.field.outputType,
    queryOER: SharedOERContext,
    constructionDemand: Demand<SelectionForest> = Demand.checked(selection.subselections),
) : GroundedFieldPublicationOccurrence<CoroutineOperationContext>(
        operation = operation,
        oerOccurrence = oerOccurrence,
        selection = selection,
        publicationCell = publicationCell,
        reference = reference,
        invocationDemand = invocationDemand,
        publicationPath = publicationPath,
        publicationExpectedType = publicationExpectedType,
        queryOER = queryOER,
        constructionDemand = constructionDemand,
    ),
    CoroutinePublicationOccurrence {
    override fun dispatch(requestScope: CoroutineScope) {
        requestScope.launch {
            CoroutineFieldResolverTask.execute(this@CoroutineFieldPublicationOccurrence, this)
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) CoroutineFieldResolverTask.cancel(this@CoroutineFieldPublicationOccurrence, cause)
        }
    }
}

/** Owns field-local helper coroutines and delegates invocation and publication to resolution logic. */
internal class CoroutineFieldResolverTask private constructor(
    publication: CoroutineFieldPublicationOccurrence,
    fieldTaskScope: CoroutineScope,
) : CoroutineFieldResolverTaskBase<CoroutineFieldPublicationOccurrence>(publication, fieldTaskScope) {
    private val resolutionLogic = FieldResolutionLogic(this)

    companion object {
        /** Installs all local promises before dispatching any producer, including source references. */
        fun prepareAll(orchestrationTask: CoroutineOrchestrationTask): List<CoroutineFieldPublicationOccurrence> {
            val operation = orchestrationTask.operation
            return listOf(
                orchestrationTask.objectOER to orchestrationTask.closedConstructionDemand.objectRooted,
                orchestrationTask.queryOER to orchestrationTask.closedConstructionDemand.queryRooted,
            ).flatMap { (oer, constructionDemand) ->
                val occurrence = oer.occurrence
                oer.closedValueSelections.byGroundKey()
                    .filterKeys { !occurrence.target.isCellSet(it) }
                    .map { (key, selection) ->
                        val reference = if (oer.source.isPresent(key.field.name)) {
                            oer.source.outputValue(key.field.name) as RootFieldReferenceData
                        } else {
                            null
                        }
                        prepare(
                            CoroutineFieldPublicationOccurrence(
                                operation = operation,
                                oerOccurrence = occurrence,
                                selection = selection,
                                publicationCell = occurrence.target.reserveCell(key),
                                reference = reference,
                                queryOER = orchestrationTask.queryOER,
                                constructionDemand = constructionDemand.descendants(key),
                            ),
                        )
                    }
            }
        }

        /** List references use the same publication protocol at their exact list-element path. */
        fun prepareAndDispatchListElement(publication: CoroutineFieldPublicationOccurrence) {
            publication.operation.dispatcher.dispatchFieldResolver(prepare(publication))
        }

        private fun prepare(publication: CoroutineFieldPublicationOccurrence): CoroutineFieldPublicationOccurrence =
            publication.apply {
                publicationCell.value.claim()
                // List cells are activated when the shared traversal allocates their list.
                if (publicationPath.last() is ObjectEngineResult.ObjectKey) {
                    check(publicationCell.setActivated(true)) { "Cell activation was decided twice" }
                }
                operation.cycleChecker.registerWriter(
                    slot = publicationCell.valueCycleSlot,
                    writer = oerOccurrence.root.fieldResolverCycleTask(publicationPath),
                )
            }

        internal suspend fun execute(
            publication: CoroutineFieldPublicationOccurrence,
            scope: CoroutineScope
        ) {
            CoroutineFieldResolverTask(publication, scope).run()
        }

        internal fun cancel(
            publication: CoroutineFieldPublicationOccurrence,
            cause: CancellationException,
        ) {
            publication.publicationCell.value.cancel(cause)
        }
    }

    override suspend fun executeAndPublish() {
        resolutionLogic.validate()
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
    fun launchIndependentQueryFragmentProducer(
        resolver: FieldValueResolver,
        queryFragment: ResolverFragment,
        reader: CycleTask,
    ): Deferred<EngineObjectOrErrorData> =
        fieldTaskScope.async {
            try {
                val queryValue = if (queryFragment.constructionSelections.isEmpty()) {
                    engineObjectDataOf(publication.operation.world.schema.requireQueryTypeDef())
                } else {
                    val queryResult = publication.operation.startResolve(
                        source = publication.operation.world.resolverRegistry.createRootQueryInput(),
                        demand = Demand.checked(queryFragment.constructionSelections),
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
