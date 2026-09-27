package semantics.resolvers.resolver21

import model.Arguments
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.SelectionForest
import model.engineObjectDataOf
import model.merge
import model.outputValue
import model.requireQueryTypeDef
import model.schemaType
import semantics.shared.OrchestrationConstructionDemand
import semantics.resolver26.CoroutineOrchestrationTaskBase
import semantics.resolvers.closeOrchestrationConstructionDemand
import semantics.shared.OEROccurrence
import semantics.shared.Demand
import semantics.shared.SharedOERContext
import semantics.resolver26.installParentBackedgeFields
import viaduct.engine.api.EngineObjectData

/** Closes one object's demand before passive descent, then prepares and dispatches its field work. */
internal class CoroutineOrchestrationTask private constructor(
    operation: CoroutineOperationContext,
    objectOER: SharedOERContext,
    queryOER: SharedOERContext,
    val closedConstructionDemand: OrchestrationConstructionDemand<ObjectSelectionForest>,
) : CoroutineOrchestrationTaskBase<CoroutineOperationContext>(operation, objectOER, queryOER) {
    companion object {
        /** Prepares grounded bindings and parent backedges without dispatching active work. */
        fun create(
            operation: CoroutineOperationContext,
            occurrence: OEROccurrence,
            source: EngineObjectData.Sync,
            constructionDemand: SelectionForest,
        ): CoroutineOrchestrationTask =
            create(operation, occurrence, source, Demand.checked(constructionDemand))

        /** Retains checked and unchecked descendant demand through passive object boundaries. */
        fun create(
            operation: CoroutineOperationContext,
            occurrence: OEROccurrence,
            source: EngineObjectData.Sync,
            constructionDemand: Demand<SelectionForest>,
        ): CoroutineOrchestrationTask {
            require(source.schemaType == occurrence.target.type) {
                "Source type ${source.schemaType.name} does not match result type ${occurrence.target.type.name}"
            }
            val queryType = operation.world.schema.requireQueryTypeDef()
            val queryResult = ObjectEngineResult.of(queryType, mutable = true)
            val queryOccurrence = OEROccurrence(queryResult, emptyList(), queryResult)
            val closedConstructionDemand =
                source.closeOrchestrationConstructionDemand(
                    operation = operation,
                    objectOccurrence = occurrence,
                    queryOccurrence = queryOccurrence,
                    initialDemand =
                        OrchestrationConstructionDemand(
                            objectRooted = constructionDemand,
                            queryRooted = Demand.EMPTY,
                        ),
                )
            val objectOER =
                SharedOERContext(
                    occurrence = occurrence,
                    source = source,
                    closedValueSelections = closedConstructionDemand.objectRooted.values.merge(source.schemaType),
                )
            val queryOER =
                SharedOERContext(
                    occurrence = queryOccurrence,
                    source = engineObjectDataOf(queryType),
                    closedValueSelections = closedConstructionDemand.queryRooted.values.merge(queryType),
                )
            val orchestration = CoroutineOrchestrationTask(operation, objectOER, queryOER, closedConstructionDemand)
            listOf(orchestration.objectOER, orchestration.queryOER).forEach { oer ->
                val parentKeys =
                    oer.closedValueSelections
                        .groundKeys()
                        .filterIsInstance<ObjectEngineResult.ParentKey>()
                oer.occurrence.installParentBackedgeFields(operation, parentKeys)
            }
            orchestration.observeQueryOER()
            return orchestration
        }
    }

    override val hasActiveWork: Boolean
        get() =
            listOf(
                objectOER to closedConstructionDemand.objectRooted,
                queryOER to closedConstructionDemand.queryRooted,
            ).any { (oer, constructionDemand) ->
                val checkedKeys = constructionDemand.checked.byGroundKey().keys
                oer.closedValueSelections.groundKeys().any { key ->
                key !is ObjectEngineResult.ParentKey &&
                    (
                        !oer.source.isPresent(key.field.name) ||
                            oer.source.outputValue(key.field.name) is RootFieldReferenceData ||
                            (
                                key in checkedKeys &&
                                    operation.world.resolverRegistry.fieldChecker(key.field) != null
                            )
                    )
            }
        }

    override fun duplicateDispatchException(): RuntimeException =
        IllegalStateException("Object orchestrated twice: ${objectOER.occurrence.path}")

    override fun prepareAndDispatchFieldWork() {
        val fieldPublications = CoroutineFieldResolverTask.prepareAll(this)
        val checkerPublications = CoroutineFieldCheckerTask.prepareAll(this)
        checkerPublications.filter { it.checker == null }.forEach { publication ->
            check(publication.publicationCell.getFieldCheckerResult().complete(null)) {
                "Field-checker result was completed twice"
            }
        }
        fieldPublications.forEach(operation.dispatcher::dispatchFieldResolver)
        checkerPublications.filter { it.checker != null }.forEach(operation.dispatcher::dispatchFieldChecker)
    }

    private fun observeQueryOER() {
        operation.resolverObserver.onQueryOERPrepared(queryOER)
        listOf(objectOER, queryOER).forEach { resolverOER ->
            queryFragmentOwners(resolverOER).forEach { (resolverKey, owner) ->
                operation.resolverObserver.onQueryFragmentPrepared(
                    owner,
                    queryOER.occurrence.target,
                    objectOER.occurrence,
                )
                operation.resolverObserver.onQueryFragmentOwnerAddress(
                    owner,
                    resolverOER.occurrence,
                    resolverKey,
                )
            }
        }
    }

    private fun queryFragmentOwners(
        oer: SharedOERContext,
    ): List<Pair<ObjectEngineResult.GroundKey, ResolverOccurrenceId>> =
        oer.closedValueSelections
            .byGroundKey()
            .keys
            .filter { key -> !oer.occurrence.target.isCellSet(key) }
            .mapNotNull { key ->
                if (oer.source.isPresent(key.field.name) || key.arguments !is Arguments.Resolved) {
                    return@mapNotNull null
                }
                val queryFragment =
                    operation.world.resolverRegistry
                        .resolver(key.field)
                        .instantiateFragmentsAt(
                            oer.occurrence.root,
                            oer.occurrence.coordinate(key),
                        )
                        .queryFragment
                if (queryFragment.constructionSelections.isEmpty()) {
                    null
                } else {
                    key to queryFragment.resolverOccurrenceId
                }
            }
}
