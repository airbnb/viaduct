package viaduct.engine.api

import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.execution.ResultPath
import java.util.function.Consumer
import kotlinx.coroutines.flow.Flow

/**
 * A graphql-java-compatible incremental result for execution strategies and instrumentation.
 *
 * This shape specifically models the v0.2 incremental format (aka id-based results) described
 * in sha 045e19363c2b55f of https://github.com/graphql/graphql-spec/pull/1110
 *
 * This is in contrast to the v0.1 incremental format (aka path-based results) supported by
 * graphql-java as recently as version 26.1, [graphql.incremental.IncrementalExecutionResult]
 */
interface IncrementalExecutionResult : ExecutionResult {
    val pending: List<PendingEntry>
    val hasNext: Boolean

    /** Collect once within the caller's scope; completion or cancellation releases the remaining execution. */
    val updates: Flow<Update>

    /** Release execution when the caller will not collect [updates]. */
    fun cancel()

    override fun transform(builderConsumer: Consumer<ExecutionResult.Builder<*>>): IncrementalExecutionResult

    /** A later payload in an incremental execution. */
    interface Update {
        val hasNext: Boolean
        val incremental: List<IncrementalEntry>
        val completed: List<CompletedEntry>
        val pending: List<PendingEntry>

        fun toSpecification(): Map<String, Any?>
    }
}

/** Incremental work identified at a response path. */
class PendingEntry(val id: String, val path: ResultPath, val label: String?) {
    fun toSpecification(): Map<String, Any?> =
        buildMap {
            put("id", id)
            put("path", path.toList())
            label?.let { put("label", it) }
        }
}

/** Data delivered for previously announced incremental work. */
sealed interface IncrementalEntry {
    val id: String
    val errors: List<GraphQLError>

    fun toSpecification(): Map<String, Any?>

    /** Additional object fields delivered for deferred selections. */
    class Object(
        override val id: String,
        val data: Map<String, Any?>,
        override val errors: List<GraphQLError>,
        val subPath: ResultPath?
    ) : IncrementalEntry {
        override fun toSpecification(): Map<String, Any?> =
            buildMap {
                put("id", id)
                put("data", data)
                if (errors.isNotEmpty()) put("errors", errors.map { it.toSpecification() })
                subPath?.let { put("subPath", it.toList()) }
            }
    }
}

/** Completion of incremental work, including any errors that prevented delivery. */
class CompletedEntry(val id: String, val errors: List<GraphQLError>) {
    fun toSpecification(): Map<String, Any?> =
        buildMap {
            put("id", id)
            if (errors.isNotEmpty()) put("errors", errors.map { it.toSpecification() })
        }
}
