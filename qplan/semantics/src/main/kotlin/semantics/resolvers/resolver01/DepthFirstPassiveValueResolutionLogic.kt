package semantics.resolvers.resolver01

import model.EngineResultCell
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.RootFieldReferenceData
import model.SelectionForest
import model.requireQueryTypeDef
import semantics.resolvers.GroundedFieldPublicationOccurrence
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext
import semantics.shared.Demand
import semantics.shared.SharedPassiveValueResolutionLogic
import semantics.shared.applicableGroundSelections
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * Grounded selection collection and reference dispatch for the shared passive traversal. Each
 * instance carries the producing task's Query-OER depth so passively discovered work inherits the
 * same depth in both recursive and reactor-based resolvers.
 */
internal class DepthFirstPassiveValueResolutionLogic(
    operation: DepthFirstOperationContext,
    private val queryOERDepth: Int,
) : SharedPassiveValueResolutionLogic<DepthFirstOrchestrationTask, DepthFirstOperationContext>(operation) {
    override fun createOrchestrationTask(
        occurrence: OEROccurrence,
        source: EngineObjectData.Sync,
        constructionDemand: Demand<SelectionForest>,
    ): DepthFirstOrchestrationTask =
        DepthFirstOrchestrationTask.create(
            operation = operation,
            occurrence = occurrence,
            source = source,
            constructionDemand = constructionDemand,
            queryOERDepth = queryOERDepth,
        )

    override fun closedConstructionDemand(
        orchestration: DepthFirstOrchestrationTask,
    ): Demand<ObjectSelectionForest> = orchestration.closedConstructionDemand.objectRooted

    override fun collect(selections: SelectionForest, type: ViaductSchema.Object): ObjectSelectionForest =
        selections.applicableGroundSelections(operation, type)

    override fun resolveListReference(
        reference: RootFieldReferenceData,
        cell: EngineResultCell,
        path: List<PathComponent>,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        selection: ObjectSelection,
        constructionDemand: Demand<SelectionForest>,
        invocationDemand: SelectionForest,
        parent: OEROccurrence,
    ) {
        operation.dispatcher.dispatchFieldResolver(
            publication =
                GroundedFieldPublicationOccurrence(
                    operation = operation,
                    oerOccurrence = parent,
                    selection = selection,
                    publicationCell = cell,
                    reference = reference,
                    invocationDemand = invocationDemand,
                    publicationPath = path,
                    publicationExpectedType = expectedType,
                    queryOER = SharedOERContext.undemandedQuery(operation.world.schema.requireQueryTypeDef()),
                    constructionDemand = constructionDemand,
                ),
            queryOERDepth = queryOERDepth,
        )
    }
}
