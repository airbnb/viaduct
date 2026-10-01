package viaduct.engine.runtime2.resolvers.resolver01

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.SharedOrchestrationTask
import viaduct.engine.runtime2.resolution.framework.descendants
import viaduct.engine.runtime2.resolvers.GroundedFieldPublicationOccurrence
import viaduct.engine.runtime2.resolvers.closeOrchestrationConstructionDemand

/** The two executable task kinds accepted by either depth-first dispatcher. */
internal sealed interface DepthFirstTask {
    /** Scheduling depth: the containing path for fields, and the object's own path for orchestration. */
    val path: List<PathComponent>

    /**
     * Number of associated Query-OER boundaries between this task's OER and its independently
     * rooted execution. This has the same meaning for Resolver01-03 and Resolver06-08; carrying it
     * through the recursive family keeps their task model aligned, while the reactor family uses it
     * to run Query producers before synchronous consumers in the containing scope.
     */
    val queryOERDepth: Int
}

/** Prepared grounded demand and active-field dispatch for Resolver01-03 and Resolver06-08. */
internal class DepthFirstOrchestrationTask private constructor(
    override val operation: DepthFirstOperationContext,
    override val objectOER: SharedOERContext,
    override val queryOER: SharedOERContext,
    val closedConstructionDemand: OrchestrationConstructionDemand<ObjectSelectionForest>,
    override val queryOERDepth: Int,
) : SharedOrchestrationTask<DepthFirstOperationContext>, DepthFirstTask {
    override val path get() = objectOER.occurrence.path

    /** Resolves the associated Query OER first, then its owner-local containing OER. */
    fun run(resolveFringe: () -> Unit = {}) {
        operation.resolverObserver.onQueryOERPrepared(
            queryOER = queryOER,
            owningOccurrence = objectOER.occurrence,
            queryOERDepth = queryOERDepth + 1,
        )
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
        listOf(queryOER, objectOER).forEach { oer ->
            runOer(
                oer = oer,
                querySide = oer === queryOER,
                resolveFringe = resolveFringe,
            )
        }
    }

    private fun runOer(
        oer: SharedOERContext,
        querySide: Boolean,
        resolveFringe: () -> Unit,
    ) {
        val oerOccurrence = oer.occurrence
        val oerSource = oer.source
        val target = oerOccurrence.target
        target.keys.forEach { key ->
            val cell = target.getCell(key)
            if (!cell.fieldCheckerResult.isCompleted) {
                check(cell.fieldCheckerResult.complete(null)) {
                    "Field-checker result was completed twice"
                }
            }
        }
        val unresolved = oer.closedValueSelections.byGroundKey().filterKeys { !target.isCellSet(it) }
        val constructionDemand =
            if (querySide) closedConstructionDemand.queryRooted else closedConstructionDemand.objectRooted

        val fieldQueryOERDepth = queryOERDepth + if (querySide) 1 else 0
        val references = unresolved.keys.mapNotNull { key ->
            val reference =
                if (oerSource.isPresent(key.field.name)) {
                    oerSource.outputValue(key.field.name) as? RootFieldReferenceData
                } else {
                    null
                }
            reference?.let { key to it }
        }.toMap()

        fun dispatch(
            key: ObjectEngineResult.GroundKey,
            reference: RootFieldReferenceData? = null
        ) {
            operation.dispatcher.dispatchFieldResolver(
                publication =
                    GroundedFieldPublicationOccurrence(
                        operation = operation,
                        oerOccurrence = oerOccurrence,
                        selection = unresolved.getValue(key),
                        publicationCell = target.reserveCell(key),
                        reference = reference,
                        queryOER = queryOER,
                        constructionDemand = constructionDemand.descendants(key),
                    ),
                queryOERDepth = fieldQueryOERDepth,
            )
            resolveFringe()
        }
        references.forEach { (key, reference) -> dispatch(key, reference) }
        SiblingDependencyLogic(operation, oerOccurrence, includeQueryFragments = querySide)
            .order(unresolved.keys - references.keys)
            .forEach { key -> dispatch(key) }
        target.freeze()
    }

    private fun queryFragmentOwners(oer: SharedOERContext): List<Pair<ObjectEngineResult.GroundKey, ResolverOccurrenceId>> =
        oer.closedValueSelections
            .byGroundKey()
            .keys
            .filter { key -> !oer.occurrence.target.isCellSet(key) }
            .mapNotNull { key ->
                val reference =
                    if (oer.source.isPresent(key.field.name)) {
                        oer.source.outputValue(key.field.name) as? RootFieldReferenceData
                    } else {
                        null
                    }
                if (reference != null || key.arguments !is Arguments.Resolved) return@mapNotNull null
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

    companion object {
        /** Closes the pair, retaining the optimistic Query OER even when its demand is empty. */
        fun create(
            operation: DepthFirstOperationContext,
            occurrence: OEROccurrence,
            source: EngineObjectData.Sync,
            constructionDemand: SelectionForest,
            queryOERDepth: Int,
        ): DepthFirstOrchestrationTask =
            create(
                operation,
                occurrence,
                source,
                Demand.checked(constructionDemand),
                queryOERDepth,
            )

        /** Retains checked and unchecked descendant demand through passive object boundaries. */
        fun create(
            operation: DepthFirstOperationContext,
            occurrence: OEROccurrence,
            source: EngineObjectData.Sync,
            constructionDemand: Demand<SelectionForest>,
            queryOERDepth: Int,
        ): DepthFirstOrchestrationTask {
            require(queryOERDepth >= 0) { "Query-OER depth must be nonnegative" }
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
            val queryOER =
                SharedOERContext(
                    occurrence = queryOccurrence,
                    source = engineObjectDataOf(queryType),
                    closedValueSelections = closedConstructionDemand.queryRooted.values.merge(queryType),
                )
            return DepthFirstOrchestrationTask(
                operation = operation,
                objectOER =
                    SharedOERContext(
                        occurrence = occurrence,
                        source = source,
                        closedValueSelections = closedConstructionDemand.objectRooted.values.merge(source.schemaType),
                    ),
                queryOER = queryOER,
                closedConstructionDemand = closedConstructionDemand,
                queryOERDepth = queryOERDepth,
            )
        }
    }
}
