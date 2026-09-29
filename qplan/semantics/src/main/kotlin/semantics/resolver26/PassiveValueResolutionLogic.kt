package semantics.resolver26

import model.EngineResultCell
import model.InclusionCondition
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.merge
import semantics.shared.Demand
import semantics.shared.OEROccurrence
import semantics.shared.SharedPassiveValueResolutionLogic
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/** Only symbolic demand and task dispatch are specific to Resolver26. */
internal class PassiveValueResolutionLogic(
    operation: OperationContext,
) : SharedPassiveValueResolutionLogic<OrchestrationTask, OperationContext>(operation) {
    override fun createOrchestrationTask(
        occurrence: OEROccurrence,
        source: EngineObjectData.Sync,
        constructionDemand: Demand<SelectionForest>,
    ): OrchestrationTask = OrchestrationTask.create(operation, occurrence, source, constructionDemand)

    override fun closedConstructionDemand(orchestration: OrchestrationTask): Demand<ObjectSelectionForest> = orchestration.closedConstructionDemand.objectRooted.constructionDemand

    override fun collect(
        selections: SelectionForest,
        type: ViaductSchema.Object
    ): ObjectSelectionForest = selections.merge(type)

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
        FieldResolverTask.prepareAndDispatchListElement(
            operation = operation,
            oerOccurrence = parent,
            sourceOccurrence = RootFieldReferenceOccurrence(
                selection = selection,
                reference = reference,
                publicationConstructionDemand = constructionDemand,
                publicationPath = path,
                publicationExpectedType = expectedType,
            ),
            publicationCell = cell,
        )
    }

    override fun deferReferenceList(
        orchestration: OrchestrationTask,
        selection: ObjectSelection,
        value: ResolverOutputData?,
        invocationDemand: SelectionForest,
        constructionDemand: Demand<SelectionForest>,
    ): Boolean {
        if (selection.inclusionCondition === InclusionCondition.Always) return false
        if (selection.inclusionCondition !== InclusionCondition.Never) {
            orchestration.prepareConditionedPassiveValue(
                PassiveValueOccurrence(
                    selection = selection,
                    value = value,
                    invocationDemand = invocationDemand,
                    publicationConstructionDemand = constructionDemand,
                    publicationPath = orchestration.objectOER.occurrence.coordinate(selection.key),
                ),
            )
        }
        return true
    }
}
