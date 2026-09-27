package semantics.resolvers.resolver21

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

/** Grounded selection collection and field-task dispatch around the common passive traversal. */
internal class CoroutinePassiveValueResolutionLogic(operation: CoroutineOperationContext) :
    SharedPassiveValueResolutionLogic<CoroutineOrchestrationTask, CoroutineOperationContext>(operation) {
    override fun createOrchestrationTask(
        occurrence: OEROccurrence,
        source: EngineObjectData.Sync,
        constructionDemand: Demand<SelectionForest>,
    ): CoroutineOrchestrationTask =
        CoroutineOrchestrationTask.create(operation, occurrence, source, constructionDemand)

    override fun closedConstructionDemand(
        orchestration: CoroutineOrchestrationTask,
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
        CoroutineFieldResolverTask.prepareAndDispatchListElement(
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
        )
    }
}
