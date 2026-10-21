package viaduct.engine.runtime2.model.registry

import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.graphql.schema.ViaductSchema

/** Prepared once at bootstrap, after dependency-cycle validation. */
internal class ParentConstructionDemandTemplate {
    internal var selections: List<PreparedParentSelection> = emptyList()
    internal var usesParent: Boolean = false
    internal var mayInduceParentDemand: Boolean = false
    internal var summary: PreparedParentDemand = PreparedParentDemand.EMPTY

    internal val dependencies: List<ParentConstructionDemandTemplate>
        get() = selections.flatMap { it.dependencies }
}

/** A bootstrap-compiled transition for matching requested fields to fixed parent requirements. */
class ParentSelectionDemand internal constructor(
    internal val field: ViaductSchema.ObjectField,
    internal val checked: PreparedParentDemand,
    internal val unchecked: PreparedParentDemand,
    internal val typeCheckers: PreparedParentDemand,
)

internal class PreparedParentSelection(
    val selection: ObjectSelection,
    val checked: Boolean,
    val parentProducer: ViaductSchema.ObjectField?,
    val children: ParentConstructionDemandTemplate,
    val resolver: ParentConstructionDemandTemplate?,
    val checker: ParentConstructionDemandTemplate?,
    val typeCheckers: List<ParentConstructionDemandTemplate>,
) {
    val dependencies: List<ParentConstructionDemandTemplate>
        get() = listOfNotNull(children, resolver, checker) + typeCheckers
}

internal class PreparedParentDemand(
    val checkedDemand: SelectionForest,
    val uncheckedDemand: SelectionForest,
    val typeCheckDemanded: Boolean,
    val typeCheckCondition: InclusionCondition,
    val requests: Map<ViaductSchema.ObjectField, PreparedParentRequest> = emptyMap(),
) {
    companion object {
        val EMPTY = PreparedParentDemand(selectionForestOf(), selectionForestOf(), false, InclusionCondition.Never)
    }
}

internal class PreparedParentRequest(
    val demand: PreparedParentDemand,
    val continuations: List<ParentDemandContinuation>,
)

internal data class ParentDemandContinuation(
    val program: ParentConstructionDemandTemplate,
    val condition: InclusionCondition,
)
