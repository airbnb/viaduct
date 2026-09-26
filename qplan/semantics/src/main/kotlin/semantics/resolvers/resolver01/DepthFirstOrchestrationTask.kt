package semantics.resolvers.resolver01

import model.Arguments
import model.ObjectEngineResult
import model.PathComponent
import model.RootFieldReferenceData
import model.ResolverOccurrenceId
import model.SelectionForest
import model.engineObjectDataOf
import model.merge
import model.outputValue
import model.requireQueryTypeDef
import model.schemaType
import semantics.resolvers.GroundedFieldPublicationOccurrence
import semantics.resolvers.OrchestratorConstructionDemand
import semantics.resolvers.closeOrchestratorConstructionDemand
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext
import semantics.shared.SharedOrchestrationTask
import viaduct.engine.api.EngineObjectData

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
    override val queryOERDepth: Int,
) : SharedOrchestrationTask<DepthFirstOperationContext>, DepthFirstTask {
    override val path get() = objectOER.occurrence.path

    /** Resolves the associated Query OER first, then its owner-local containing OER. */
    fun run(resolveFringe: () -> Unit = {}) {
        operation.resolverObserver.onQueryOERPrepared(queryOER)
        (queryFragmentOwners(objectOER) + queryFragmentOwners(queryOER))
            .forEach { owner ->
                operation.resolverObserver.onQueryFragmentPrepared(
                    owner,
                    queryOER.occurrence.target,
                )
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
        val unresolved = oer.closedDemand.byGroundKey().filterKeys { !target.isCellSet(it) }
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
        fun dispatch(key: ObjectEngineResult.GroundKey, reference: RootFieldReferenceData? = null) {
            operation.dispatcher.dispatchFieldResolver(
                publication =
                    GroundedFieldPublicationOccurrence(
                        operation = operation,
                        oerOccurrence = oerOccurrence,
                        selection = unresolved.getValue(key),
                        publicationCell = target.reserveCell(key),
                        reference = reference,
                        queryOER = queryOER,
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

    private fun queryFragmentOwners(oer: SharedOERContext): List<ResolverOccurrenceId> =
        oer.closedDemand
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
                queryFragment.resolverOccurrenceId.takeUnless {
                    queryFragment.constructionSelections.isEmpty()
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
        ): DepthFirstOrchestrationTask {
            require(queryOERDepth >= 0) { "Query-OER depth must be nonnegative" }
            require(source.schemaType == occurrence.target.type) {
                "Source type ${source.schemaType.name} does not match result type ${occurrence.target.type.name}"
            }
            val queryType = operation.world.schema.requireQueryTypeDef()
            val queryResult = ObjectEngineResult.of(queryType, mutable = true)
            val queryOccurrence = OEROccurrence(queryResult, emptyList(), queryResult)
            val closed =
                source.closeOrchestratorConstructionDemand(
                    operation = operation,
                    objectOccurrence = occurrence,
                    queryOccurrence = queryOccurrence,
                    initialDemand = OrchestratorConstructionDemand.checkedObject(constructionDemand),
                )
            val queryOER =
                SharedOERContext(
                    occurrence = queryOccurrence,
                    source = engineObjectDataOf(queryType),
                    closedDemand = closed.queryRooted.values.merge(queryType),
                )
            return DepthFirstOrchestrationTask(
                operation = operation,
                objectOER =
                    SharedOERContext(
                        occurrence = occurrence,
                        source = source,
                        closedDemand = closed.objectRooted.values.merge(source.schemaType),
                    ),
                queryOER = queryOER,
                queryOERDepth = queryOERDepth,
            )
        }
    }
}
