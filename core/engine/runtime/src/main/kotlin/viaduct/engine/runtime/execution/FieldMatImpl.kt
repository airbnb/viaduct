package viaduct.engine.runtime.execution

import kotlinx.coroutines.CancellationException
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeFilter
import viaduct.engine.runtime.mat.Mat
import viaduct.engine.runtime.mat.MatResult

/**
 * A [Mat] backed by a field resolver.
 *
 * @property parameters is the field resolver's execution scope. It describes the
 *   resolver's parent object, source value, field arguments, execution path, field definition,
 *   local context, access-check scope, and instrumentation scope.
 * @property outputSelectionSetFilter identifies the selections owned by this field resolver.
 * @property materialize re-runs the field resolver when later reads need more of its output.
 */
internal class FieldMatImpl(
    private val parameters: ExecutionParameters,
    private val outputSelectionSetFilter: KeyTreeFilter,
    private val materialize: MatFn,
) : Mat {
    /**
     * Re-runs the field resolver when its first result did not include fields needed by a later
     * read.
     *
     * This function is not used for the initial resolver call. [requestedShape] contains the
     * missing fields owned by the resolver, and [selectionParameters] describes the read that
     * requested them. The returned object may include additional resolver-owned fields, or it may
     * be null when the resolver returns null.
     *
     * Cancellation is rethrown. Other exceptions are recorded as a failed materialization and are
     * reported when a field tries to read the failed result.
     */
    fun interface MatFn {
        suspend operator fun invoke(
            requestedShape: KeyTree,
            selectionParameters: ExecutionParameters,
        ): EngineObjectData?
    }

    private val field = checkNotNull(parameters.field) {
        "FieldMatImpl requires field execution parameters"
    }

    /**
     * These are the fields that the first resolver call was asked to provide.
     *
     * A field counts as covered even when the resolver leaves it out of its result. Otherwise,
     * the ledger would ask the resolver for the same field again.
     */
    private val initialCoverage: KeyTree =
        parameters.queryPlan.keyTree(
            parameters,
            field,
            outputSelectionSetFilter
        )

    override fun toString(): String = "${parameters.currentObjectEngineResult.type.name}.${field.fieldName}"

    /**
     * Turns the first field result into the first result stored in the ledger.
     *
     * The field must run before its returned object and ledger can be created. The ledger is
     * built with this result before the object is made available to other work. Later requests
     * use the ledger to resolve fields that are still missing.
     */
    suspend fun resultFromInitialFetch(source: EngineObjectData): MatResult =
        result(
            source = source,
            requestedCoverage = initialCoverage,
            selectionParameters = parameters,
        )

    override suspend fun invoke(
        keyTree: KeyTree,
        selectionHandle: EngineExecutionContext.ExecutionHandle,
    ): MatResult {
        val selectionParameters = selectionHandle.asExecutionParameters()
        val source = try {
            materialize(keyTree, selectionParameters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failedResultFor(keyTree, selectionParameters, e)
        }

        return result(
            source = source,
            requestedCoverage = keyTree,
            selectionParameters = selectionParameters,
        )
    }

    private suspend fun result(
        source: EngineObjectData?,
        requestedCoverage: KeyTree,
        selectionParameters: ExecutionParameters,
    ): MatResult {
        val returnedCoverage = source.toKeyTree(
            schema = selectionParameters.engineExecutionContext.activeSchema.schema,
            selections = requestedCoverage,
            filter = outputSelectionSetFilter,
        )
        return MatResult(
            coverage = requestedCoverage + returnedCoverage,
            source = Result.success(source),
        )
    }
}
