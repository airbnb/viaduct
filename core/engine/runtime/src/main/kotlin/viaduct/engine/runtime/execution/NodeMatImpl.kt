package viaduct.engine.runtime.execution

import kotlinx.coroutines.CancellationException
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.Mat
import viaduct.engine.runtime.mat.MatResult

/**
 * A [Mat] backed by a node reference.
 *
 * @property ref is the typed node reference represented by this mat.
 * @property outputSelectionSetFilter identifies the selections owned by the node resolver.
 * @property materialize resolves [ref] for one materialization selection set.
 * @property launch starts execution of the query plan represented by a materialization result.
 */
internal class NodeMatImpl(
    private val ref: NodeEngineObjectData,
    private val outputSelectionSetFilter: NodeOutputSelectionSetFilter,
    private val materialize: MatFn,
    private val launch: LaunchFn,
) : Mat {
    /**
     * Resolves the node reference for one requested selection set.
     *
     * This function is used for both the initial node load and later loads for missing fields.
     * [selections] is the selection set passed to the node resolver, and [selectionParameters]
     * describes the read that requested it. The returned object may include additional fields.
     *
     * Cancellation is rethrown. Other exceptions become a failed materialization and are reported
     * during initial node resolution or by a later field that reads the failed result.
     */
    fun interface MatFn {
        suspend operator fun invoke(
            selections: EngineSelectionSet,
            selectionParameters: ExecutionParameters,
        ): EngineObjectData
    }

    /**
     * Starts the fields selected by a node materialization.
     *
     * [materializationPlan] describes the work to start, and [requestedShape] is the shape used to
     * build that plan. Exceptions become failed materialization results.
     */
    fun interface LaunchFn {
        operator fun invoke(
            selectionParameters: ExecutionParameters,
            materializationPlan: QueryPlan,
            requestedShape: KeyTree,
        )
    }

    override fun toString(): String = "${ref.type.name}(id:${ref.id})"

    override suspend fun invoke(
        keyTree: KeyTree,
        selectionHandle: EngineExecutionContext.ExecutionHandle,
    ): MatResult {
        val selectionParameters = selectionHandle.asExecutionParameters()
        return try {
            resultFor(keyTree, selectionParameters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedResultFor(keyTree, selectionParameters, e)
        }
    }

    private suspend fun resultFor(
        keyTree: KeyTree,
        selectionParameters: ExecutionParameters,
    ): MatResult {
        val outputKeyTree = keyTree
            .filter(outputSelectionSetFilter)
            .withoutEmptyTypeBranches()

        // A resolver-owned selection can still require the initial node lifecycle, even when the
        // node owns none of its fields.
        val matKeyTree = if (outputKeyTree.isEmpty()) keyTree else outputKeyTree
        val matPlan = materializationPlan(selectionParameters, matKeyTree)
        val selections = FieldExecutionHelpers.engineSelectionSet(
            parameters = selectionParameters,
            projectionType = ref.type,
            selectionSet = matPlan.selectionSet,
            fragments = matPlan.fragments,
            queryPlan = matPlan,
        )
        val source = materialize(selections, selectionParameters)
        val returnedCoverage = source.toKeyTree(
            schema = selectionParameters.engineExecutionContext.activeSchema.schema,
            selections = outputKeyTree,
            filter = outputSelectionSetFilter,
        )
        // Launched work may re-enter the ledger while this result is being recorded.
        launch(selectionParameters, matPlan, matKeyTree)

        return MatResult(
            coverage = outputKeyTree + returnedCoverage,
            source = Result.success(source),
        )
    }
}
