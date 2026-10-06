package viaduct.engine.runtime2.resolution

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedPassiveValueResolutionLogic
import viaduct.graphql.schema.ViaductSchema

/** Only symbolic demand and task dispatch are specific to Resolution. */
internal class PassiveValueResolutionLogic(
    operation: OperationContext,
) : SharedPassiveValueResolutionLogic<OrchestrationTask, OperationContext>(operation) {
    override fun createObjectResult(
        type: ViaductSchema.Object,
        constructionDemand: Demand<SelectionForest>,
    ): ObjectEngineResult = OrchestrationTask.createObjectResult(operation, type, constructionDemand)

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
