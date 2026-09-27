package semantics.resolver26

import model.EngineResult
import model.EngineResultCell
import model.InclusionCondition
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.merge
import semantics.shared.OEROccurrence
import semantics.shared.Demand
import semantics.shared.fieldResolverCycleTask
import semantics.shared.valueCycleSlot
import semantics.shared.SharedPassiveValueResolutionLogic
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

internal fun ResolverOutputData?.resolvePassiveValues(
    operation: OperationContext,
    root: ObjectEngineResult,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    path: List<PathComponent>,
    invocationDemand: SelectionForest,
    constructionDemand: Demand<SelectionForest>,
    parent: OEROccurrence? = null,
): EngineResult? =
    PassiveValueResolutionLogic(operation).resolvePassiveValues(
        this,
        root,
        expectedType,
        path,
        constructionDemand,
        invocationDemand,
        parent,
    )

/** Only symbolic demand and task dispatch are specific to Resolver26. */
private class PassiveValueResolutionLogic(
    operation: OperationContext,
) : SharedPassiveValueResolutionLogic<OrchestrationTask, OperationContext>(operation) {
    override fun createOrchestrationTask(
        occurrence: OEROccurrence,
        source: EngineObjectData.Sync,
        constructionDemand: Demand<SelectionForest>,
    ): OrchestrationTask = OrchestrationTask.create(operation, occurrence, source, constructionDemand)

    override fun closedConstructionDemand(orchestration: OrchestrationTask): Demand<ObjectSelectionForest> = orchestration.closed.objectRooted.constructionDemand

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
        cell.createValuePromise()
        operation.cycleChecker.registerWriter(
            slot = cell.valueCycleSlot,
            writer = parent.root.fieldResolverCycleTask(path),
        )
        FieldResolverTask.launchForListElement(
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
        occurrence: OEROccurrence,
        selection: ObjectSelection,
        value: ResolverOutputData?,
        invocationDemand: SelectionForest,
        constructionDemand: Demand<SelectionForest>,
    ): Boolean {
        if (selection.inclusionCondition === InclusionCondition.Always) return false
        if (selection.inclusionCondition !== InclusionCondition.Never) {
            FieldResolverTask.installAndLaunch(
                operation = operation,
                oerOccurrence = occurrence,
                sourceOccurrence = PassiveValueOccurrence(
                    selection = selection,
                    value = value,
                    invocationDemand = invocationDemand,
                    publicationConstructionDemand = constructionDemand,
                    publicationPath = occurrence.coordinate(selection.key),
                ),
            )
        }
        return true
    }
}
