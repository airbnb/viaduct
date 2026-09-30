package semantics.resolvers.resolver21

import model.Arguments
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.Promise
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.SelectionForest
import model.engineObjectDataOf
import model.merge
import model.outputValue
import model.requireQueryTypeDef
import model.schemaType
import semantics.resolver26.CoroutineOrchestrationTaskBase
import semantics.resolver26.installParentBackedgeFields
import semantics.resolvers.closeOrchestrationConstructionDemand
import semantics.shared.Demand
import semantics.shared.OEROccurrence
import semantics.shared.OrchestrationConstructionDemand
import semantics.shared.SharedOERContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/** Closes one object's demand before passive descent, then prepares and dispatches its field work. */
internal class CoroutineOrchestrationTask private constructor(
    operation: CoroutineOperationContext,
    objectOER: SharedOERContext,
    queryOER: SharedOERContext,
    val closedConstructionDemand: OrchestrationConstructionDemand<ObjectSelectionForest>,
) : CoroutineOrchestrationTaskBase<CoroutineOperationContext>(operation, objectOER, queryOER) {
    companion object {
        /** Reserves a possible type check until parent lookahead settles checked provenance. */
        fun createObjectResult(
            operation: CoroutineOperationContext,
            type: ViaductSchema.Object,
            constructionDemand: Demand<SelectionForest>,
        ): ObjectEngineResult {
            val typeCheckerResult =
                if (
                    (constructionDemand.typeCheckDemanded || operation.world.parentFieldRelations.isNotEmpty()) &&
                    operation.world.resolverRegistry.typeChecker(type) != null
                ) {
                    Promise.ofDeferred<CheckerResult?>()
                } else {
                    Promise.of<CheckerResult?>(null)
                }
            return ObjectEngineResult.of(
                type = type,
                typeCheckerResult = typeCheckerResult,
                mutable = true,
            )
        }

        /** Prepares grounded bindings and parent backedges without dispatching active work. */
        fun create(
            operation: CoroutineOperationContext,
            occurrence: OEROccurrence,
            source: EngineObjectData.Sync,
            constructionDemand: SelectionForest,
        ): CoroutineOrchestrationTask =
            create(
                operation,
                occurrence,
                source,
                Demand.checked(constructionDemand),
            )

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
            if (!closedConstructionDemand.objectRooted.typeCheckDemanded && !occurrence.target.typeCheckerResult.isCompleted) {
                check(occurrence.target.typeCheckerResult.complete(null)) {
                    "Absent type-checker result was completed twice"
                }
            }
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
            hasApplicableTypeChecker() || listOf(
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

    private fun hasApplicableTypeChecker(): Boolean =
        closedConstructionDemand.objectRooted.typeCheckDemanded &&
            operation.world.resolverRegistry.typeChecker(objectOER.occurrence.target.type) != null

    override fun duplicateDispatchException(): RuntimeException = IllegalStateException("Object orchestrated twice: ${objectOER.occurrence.path}")

    override fun prepareAndDispatchFieldWork() {
        val fieldPublications = CoroutineFieldResolverTask.prepareAll(this)
        val checkerPublications = CoroutineFieldCheckerTask.prepareAll(this)
        val typeCheckerPublications = CoroutineTypeCheckerTask.prepareAll(this)
        val checkedCells = checkerPublications.mapTo(linkedSetOf()) { it.publicationCell }
        listOf(objectOER, queryOER).forEach { oer ->
            oer.occurrence.target.keys.forEach { key ->
                val cell = oer.occurrence.target.getCell(key)
                if (cell !in checkedCells && !cell.fieldCheckerResult.isCompleted) {
                    check(cell.fieldCheckerResult.complete(null)) {
                        "Field-checker result was completed twice"
                    }
                }
            }
        }
        checkerPublications.filter { it.checker == null }.forEach { publication ->
            check(publication.publicationCell.fieldCheckerResult.complete(null)) {
                "Field-checker result was completed twice"
            }
        }
        fieldPublications.forEach(operation.dispatcher::dispatchFieldResolver)
        checkerPublications.filter { it.checker != null }.forEach { publication ->
            operation.dispatcher.dispatchFieldChecker(publication)
        }
        typeCheckerPublications.forEach { publication ->
            operation.dispatcher.dispatchTypeChecker(publication)
        }
    }

    private fun observeQueryOER() {
        operation.resolverObserver.onQueryOERPrepared(queryOER, objectOER.occurrence)
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

    private fun queryFragmentOwners(oer: SharedOERContext): List<Pair<ObjectEngineResult.GroundKey, ResolverOccurrenceId>> =
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
