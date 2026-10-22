package viaduct.service.runtime

import graphql.ExecutionResult as GJExecutionResult
import graphql.GraphQLError as GJGraphQLError
import graphql.incremental.IncrementalExecutionResult as GJIncrementalExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import viaduct.engine.api.CompletedEntry as EngineCompletedEntry
import viaduct.engine.api.IncrementalEntry as EngineIncrementalEntry
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.IncrementalExecutionResult.Update
import viaduct.engine.api.PendingEntry as EnginePendingEntry
import viaduct.graphql.SourceLocation
import viaduct.service.api.CompletedEntry
import viaduct.service.api.ExecutionResult
import viaduct.service.api.GraphQLError
import viaduct.service.api.IncrementalEntry
import viaduct.service.api.PendingEntry

internal class StandardResultImpl(
    private val delegate: GJExecutionResult,
) : ExecutionResult.Standard {
    @Suppress("UNCHECKED_CAST")
    override fun getData(): Map<String, Any?>? =
        when (val data = delegate.getData<Any?>()) {
            null -> null
            is Map<*, *> -> data as Map<String, Any?>
            else -> mapOf("data" to data)
        }

    override val errors = delegate.errors.toViaductErrors()
    override val isDataPresent get() = delegate.isDataPresent
    override val extensions get() = delegate.extensions

    override fun toSpecification(): Map<String, Any?> = delegate.toSpecification()
}

internal class InitialResultImpl(
    private val delegate: IncrementalExecutionResult,
) : ExecutionResult.Initial {
    override fun getData(): Map<String, Any?>? = delegate.getData()

    override val errors = delegate.errors.toViaductErrors()
    override val pending: List<PendingEntry> = delegate.pending.map(::PendingEntryImpl)
    override val hasNext get() = delegate.hasNext
    override val extensions get() = delegate.extensions

    override fun toSpecification(): Map<String, Any?> = delegate.toSpecification()
}

internal class IncrementalUpdateImpl(
    private val delegate: Update,
) : ExecutionResult.Update {
    override val hasNext get() = delegate.hasNext
    override val incremental: List<IncrementalEntry> = delegate.incremental.map {
        when (it) {
            is EngineIncrementalEntry.Object -> ObjectIncrementalEntryImpl(it)
        }
    }
    override val completed: List<CompletedEntry> = delegate.completed.map(::CompletedEntryImpl)
    override val pending: List<PendingEntry> = delegate.pending.map(::PendingEntryImpl)

    override fun toSpecification(): Map<String, Any?> = delegate.toSpecification()
}

internal class PendingEntryImpl(
    private val delegate: EnginePendingEntry,
) : PendingEntry {
    override val id get() = delegate.id
    override val path = delegate.path.toList()
    override val label get() = delegate.label

    override fun toSpecification(): Map<String, Any?> = delegate.toSpecification()
}

internal class CompletedEntryImpl(
    private val delegate: EngineCompletedEntry,
) : CompletedEntry {
    override val id get() = delegate.id
    override val errors = delegate.errors.toViaductErrors()

    override fun toSpecification(): Map<String, Any?> = delegate.toSpecification()
}

internal class ObjectIncrementalEntryImpl(
    private val delegate: EngineIncrementalEntry.Object,
) : IncrementalEntry.Object {
    override val id get() = delegate.id
    override val data get() = delegate.data
    override val errors = delegate.errors.toViaductErrors()
    override val subPath = delegate.subPath?.toList()

    override fun toSpecification(): Map<String, Any?> = delegate.toSpecification()
}

internal fun Update.toExecutionResult(): ExecutionResult.Update = IncrementalUpdateImpl(this)

internal fun IncrementalExecutionResult.toExecutionResult(): ExecutionResult.Initial = InitialResultImpl(this)

internal fun GJExecutionResult.toExecutionResults(): Flow<ExecutionResult> =
    flow {
        try {
            emit(toExecutionResult())
            if (this@toExecutionResults is IncrementalExecutionResult) {
                updates.collect { emit(it.toExecutionResult()) }
            }
        } finally {
            if (this@toExecutionResults is IncrementalExecutionResult) cancel()
        }
    }

internal fun GJExecutionResult.toStandardExecutionResult(): ExecutionResult.Standard {
    if (this is IncrementalExecutionResult) {
        cancel()
        error("Expected a standard execution result")
    }
    require(this !is GJIncrementalExecutionResult) { "Unexpected graphql-java IncrementalExecutionResult" }
    return StandardResultImpl(this)
}

internal fun GJExecutionResult.toExecutionResult(): ExecutionResult =
    when (this) {
        is IncrementalExecutionResult -> toExecutionResult()
        else -> toStandardExecutionResult()
    }

private fun List<GJGraphQLError>.toViaductErrors(): List<GraphQLError> =
    map { error ->
        GraphQLError(
            message = error.message ?: "",
            path = error.path,
            locations = error.locations?.map { SourceLocation(it.line, it.column, it.sourceName) },
            extensions = error.extensions ?: emptyMap(),
        )
    }
