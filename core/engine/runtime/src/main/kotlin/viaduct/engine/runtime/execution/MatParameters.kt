package viaduct.engine.runtime.execution

import viaduct.engine.runtime.MatSource
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeFilter
import viaduct.engine.runtime.mat.MatLedger
import viaduct.engine.runtime.mat.MatPath
import viaduct.engine.runtime.mat.MatPath.Segment

/**
 * Describes how to execute a resolver for a specific selection shape.
 *
 * @param ledger the ledger responsible for the resolver-owned subtree.
 * @param path the path to the selected object within that subtree.
 * @param requestedShape the exact field keys requested from the owning resolver, rooted at
 *   [ledger].
 * @param parameters the execution parameters used to resolve [requestedShape].
 * @param rootNodeId the intrinsic id when [ledger] is backed by a node reference.
 */
internal data class MatParameters(
    val ledger: MatLedger,
    val path: MatPath,
    val requestedShape: KeyTree,
    val parameters: ExecutionParameters,
    val rootNodeId: String?,
) {
    companion object {
        fun create(
            objectResult: ObjectEngineResultImpl,
            terminalShape: KeyTree,
            terminalParameters: ExecutionParameters,
        ): MatParameters {
            tailrec fun route(
                current: ObjectEngineResultImpl,
                reversedSegments: MutableList<Segment>,
            ): MatParameters =
                when (val source = current.matSource) {
                    is MatSource.Ledger -> {
                        val path = MatPath(current.type, reversedSegments.asReversed().toList())
                        createFromLedger(
                            ledger = source.ledger,
                            path = path,
                            terminalShape = terminalShape,
                            terminalParameters = terminalParameters,
                            matFilter = source.matFilter,
                            rootNodeId = source.rootNodeId,
                        )
                    }
                    is MatSource.Embedded -> {
                        reversedSegments += source.segment
                        route(source.parent, reversedSegments)
                    }
                    null ->
                        error("MatParameters requires a ledger-backed OER, found no backing at ${current.type.name}")
                }

            return route(objectResult, mutableListOf())
        }

        private fun createFromLedger(
            ledger: MatLedger,
            path: MatPath,
            terminalShape: KeyTree,
            terminalParameters: ExecutionParameters,
            matFilter: KeyTreeFilter,
            rootNodeId: String?,
        ): MatParameters {
            if (path.segments.isEmpty()) {
                return MatParameters(
                    ledger = ledger,
                    path = path,
                    requestedShape = terminalShape
                        .filter(matFilter)
                        .withoutEmptyTypeBranches(),
                    parameters = terminalParameters,
                    rootNodeId = rootNodeId,
                )
            }

            var shape = terminalShape
            var parameters = terminalParameters
            var selectionSet = parameters.selectionSet

            for (index in path.segments.indices.reversed()) {
                val segment = path.segments[index]
                val fieldRoute = parameters.routeFor(segment)
                val parentType = if (index == 0) path.rootType else path.segments[index - 1].type

                shape = shape.wrappedIn(
                    parentType,
                    segment.key,
                )
                selectionSet = selectionSet.wrappedIn(
                    fieldRoute.field,
                    fieldRoute.fieldParameters,
                )
                parameters = fieldRoute.parentParameters.copy(
                    coercedVariables = parameters.coercedVariables,
                    queryPlan = parameters.queryPlan,
                    queryPlanIndex = parameters.queryPlanIndex,
                    selectionSet = selectionSet,
                    // Rebuilding the parent path does not start another Mat, so keep the current depth.
                    matBatchDepth = terminalParameters.matBatchDepth,
                )
            }

            return MatParameters(
                ledger = ledger,
                path = path,
                requestedShape = shape
                    .filter(matFilter)
                    .withoutEmptyTypeBranches(),
                parameters = parameters,
                rootNodeId = rootNodeId,
            )
        }
    }
}

private data class FieldRoute(
    val fieldParameters: ExecutionParameters,
    val parentParameters: ExecutionParameters,
    val field: QueryPlan.CollectedField,
)

private fun ExecutionParameters.routeFor(segment: Segment): FieldRoute {
    tailrec fun loop(parameters: ExecutionParameters): FieldRoute {
        val origin = parameters.executionOrigin

        val parentParameters = when (origin) {
            ExecutionOrigin.Root ->
                error("Missing ExecutionParameters for materialized segment $segment")
            is ExecutionOrigin.Field -> origin.parameters
            is ExecutionOrigin.ObjectTraversal -> origin.parameters
            is ExecutionOrigin.ChildQueryPlan -> origin.parameters
        }

        // Object traversal and child-plan frames may carry surrounding field context, but only a
        // Field frame owns the field and the variable scope under which it was collected.
        val field = if (origin is ExecutionOrigin.Field) {
            checkNotNull(parameters.field)
        } else {
            null
        }
        return if (field != null && parameters.matches(segment, field)) {
            FieldRoute(parameters, parentParameters, field)
        } else {
            loop(parentParameters)
        }
    }

    return loop(this)
}

private fun ExecutionParameters.matches(
    segment: Segment,
    field: QueryPlan.CollectedField,
): Boolean = FieldExecutionHelpers.buildOERKeyForField(this, field) == segment.key

private fun QueryPlan.SelectionSet.wrappedIn(
    field: QueryPlan.CollectedField,
    fieldParameters: ExecutionParameters,
): QueryPlan.SelectionSet {
    // Ancestor variables belong to the frame that originally collected the field. Close them
    // before attaching a terminal selection set from a later plan.
    val closedField = VariableInliner(fieldParameters).shallowInline(field)
    return QueryPlan.SelectionSet(
        fieldParameters.currentObjectEngineResult.type,
        closedField.copy(selectionSet = this),
    )
}
