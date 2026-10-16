package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import java.util.function.Consumer
import kotlinx.coroutines.flow.Flow
import viaduct.engine.api.CompletedEntry
import viaduct.engine.api.IncrementalEntry
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.IncrementalExecutionResult.Update
import viaduct.engine.api.PendingEntry

class IncrementalExecutionResultImpl(
    private val initialResult: ExecutionResult,
    override val pending: List<PendingEntry>,
    override val hasNext: Boolean,
    override val updates: Flow<Update>,
    private val cancelExecution: () -> Unit = {},
) : IncrementalExecutionResult, ExecutionResult by initialResult {
    override fun toSpecification(): Map<String, Any?> = initialResult.toSpecification() + mapOf("pending" to pending.map { it.toSpecification() }, "hasNext" to hasNext)

    override fun cancel() = cancelExecution()

    override fun transform(builderConsumer: Consumer<ExecutionResult.Builder<*>>): IncrementalExecutionResult =
        IncrementalExecutionResultImpl(initialResult.transform(builderConsumer), pending, hasNext, updates, cancelExecution)
}

class UpdateExecutionResultImpl(
    override val hasNext: Boolean,
    override val incremental: List<IncrementalEntry>,
    override val completed: List<CompletedEntry>,
    override val pending: List<PendingEntry>
) : Update {
    override fun toSpecification(): Map<String, Any?> =
        buildMap {
            put("hasNext", hasNext)
            if (pending.isNotEmpty()) put("pending", pending.map { it.toSpecification() })
            if (incremental.isNotEmpty()) put("incremental", incremental.map { it.toSpecification() })
            if (completed.isNotEmpty()) put("completed", completed.map { it.toSpecification() })
        }
}
