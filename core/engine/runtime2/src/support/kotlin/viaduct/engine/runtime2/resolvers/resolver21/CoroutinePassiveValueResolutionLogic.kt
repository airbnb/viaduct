package viaduct.engine.runtime2.resolvers.resolver21

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.SharedPassiveValueResolutionLogic
import viaduct.engine.runtime2.resolvers.applicableGroundSelections
import viaduct.graphql.schema.ViaductSchema

/** Grounded selection collection and field-task dispatch around the common passive traversal. */
internal class CoroutinePassiveValueResolutionLogic(operation: CoroutineOperationContext) :
    SharedPassiveValueResolutionLogic<CoroutineOrchestrationTask, CoroutineOperationContext>(operation) {
    override fun createOrchestrationTask(
        occurrence: OEROccurrence,
        source: EngineObjectData.Sync,
        constructionDemand: Demand<SelectionForest>,
    ): CoroutineOrchestrationTask = CoroutineOrchestrationTask.create(operation, occurrence, source, constructionDemand)

    override fun createObjectResult(
        type: ViaductSchema.Object,
        constructionDemand: Demand<SelectionForest>,
    ): viaduct.engine.runtime2.model.ObjectEngineResult = CoroutineOrchestrationTask.createObjectResult(operation, type, constructionDemand)

    override fun closedConstructionDemand(orchestration: CoroutineOrchestrationTask): Demand<ObjectSelectionForest> = orchestration.closedConstructionDemand.objectRooted

    override fun collect(
        selections: SelectionForest,
        type: ViaductSchema.Object
    ): ObjectSelectionForest = selections.applicableGroundSelections(operation, type)

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
            CoroutineFieldPublicationOccurrence(
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
