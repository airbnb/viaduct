package viaduct.service.api

import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.StableApi

/**
 * A result payload from executing a GraphQL operation through Viaduct.
 *
 * [Standard] represents a complete result delivered in one payload. Incremental results use an
 * [Initial] payload followed by [Update] payloads containing additional data and completion information.
 */
@StableApi
sealed interface ExecutionResult {
    /**
     * Converts this payload to the corresponding GraphQL response format.
     *
     * The returned map is suitable for JSON serialization in HTTP responses. Its fields depend on
     * whether this is a [Standard], [Initial], or [Update] payload.
     */
    fun toSpecification(): Map<String, Any?>

    /**
     * The complete result of an operation, delivered in a single payload by [Viaduct.execute] or
     * [Viaduct.executeAsync]. The structure of [getData] reflects [ExecutionInput.operationText].
     *
     * A complete result can contain partial data. If a nullable field encounters an error, its value
     * is `null` and the error is recorded in [errors], while other fields retain their data. Errors in
     * non-nullable fields propagate to the nearest nullable parent and can make the entire data `null`
     * (GraphQL Spec §6.4.4).
     *
     * Use [isDataPresent] to distinguish omitted data from an explicit `null`.
     */
    @StableApi
    interface Standard : ExecutionResult {
        /**
         * Returns the data from the GraphQL execution.
         *
         * The keys are top-level field response names, including aliases. Nested values follow this mapping:
         * - GraphQL Objects → `Map<String, Any?>`
         * - GraphQL Lists → `List<Any?>`
         * - GraphQL Scalars → values such as String, Int, Boolean, and Double
         * - GraphQL Nulls → `null`
         *
         * Given this query in [ExecutionInput.operationText]:
         * ```graphql
         * query {
         *   user(id: "123") {
         *     name
         *     posts { title }
         *   }
         * }
         * ```
         * the returned data can be:
         * ```kotlin
         * mapOf(
         *   "user" to mapOf(
         *     "name" to "Alice",
         *     "posts" to listOf(mapOf("title" to "First Post"))
         *   )
         * )
         * ```
         *
         * @return the execution data, or `null` when data is absent or execution failed at the root level
         * @see isDataPresent
         * @see errors
         */
        fun getData(): Map<String, Any?>?

        /**
         * Errors from parsing, validation, or execution, including errors accompanying partial data.
         *
         * Results returned by [Viaduct.execute] and [Viaduct.executeAsync] sort these errors by response
         * path and then by message. An empty list indicates that no GraphQL errors were reported.
         */
        val errors: List<GraphQLError>

        /** Whether the response contains a `data` field, even if its value is explicitly `null`. */
        val isDataPresent: Boolean

        /** Additional execution metadata as key-value pairs, or `null` when none is provided. */
        val extensions: Map<Any, Any?>?
    }

    /**
     * The first payload of an operation that delivers its result incrementally.
     *
     * [getData] contains the initially available fields, using the same value mapping as [Standard.getData].
     * Additional deferred fields arrive in [Update] payloads. [pending] identifies work whose results
     * will be associated with later incremental or completion entries.
     */
    @ExperimentalApi
    interface Initial : ExecutionResult {
        /** Returns the initial execution data, or `null` if execution failed at the root level. */
        fun getData(): Map<String, Any?>?

        /** Errors associated with the initial portion of the result, which may contain partial data. */
        val errors: List<GraphQLError>

        /** Additional execution metadata as key-value pairs, or `null` when none is provided. */
        val extensions: Map<Any, Any?>?

        /** Whether further payloads are expected for this operation. */
        val hasNext: Boolean

        /** Deferred work announced by this payload, identified for use in later entries. */
        val pending: List<PendingEntry>
    }

    /**
     * A later payload in an incremental execution.
     *
     * Data and errors are carried by [incremental] and [completed] entries rather than top-level
     * data and errors fields. Entries refer to identifiers announced in [Initial.pending] or [pending].
     * An update can also announce newly discovered work while completing earlier work.
     */
    @ExperimentalApi
    interface Update : ExecutionResult {
        /** Whether further payloads are expected; `false` marks the final payload. */
        val hasNext: Boolean

        /** Additional data and associated errors for previously announced work. */
        val incremental: List<IncrementalEntry>

        /** Work that has finished, including errors that prevented its data from being delivered. */
        val completed: List<CompletedEntry>

        /** Newly announced deferred work that may produce later entries. */
        val pending: List<PendingEntry>
    }
}

/** Incremental work identified at a response path. */
@ExperimentalApi
interface PendingEntry {
    /** Identifier used by subsequent incremental and completion entries within this operation. */
    val id: String

    /** Response path to the deferred work, with string field names and integer list indices. */
    val path: List<Any>

    /** The defer directive's label, or `null` if no label was supplied. */
    val label: String?

    /** Converts this entry to a map suitable for JSON serialization. */
    fun toSpecification(): Map<String, Any?>
}

/** Data delivered for previously announced incremental work. */
@ExperimentalApi
sealed interface IncrementalEntry {
    /** Identifier of the [PendingEntry] whose work produced this data. */
    val id: String

    /** Errors accompanying this entry's data, which may be partial. */
    val errors: List<GraphQLError>

    /** Converts this entry to a map suitable for JSON serialization. */
    fun toSpecification(): Map<String, Any?>

    /** Additional object fields delivered for deferred selections. */
    interface Object : IncrementalEntry {
        /** Fields to merge into the result, using the value mapping described by [ExecutionResult.Standard.getData]. */
        val data: Map<String, Any?>

        /** Path relative to [PendingEntry.path] at which to merge [data], or `null` to use the pending path. */
        val subPath: List<Any>?
    }
}

/** Completion of incremental work, including any errors that prevented delivery. */
@ExperimentalApi
interface CompletedEntry {
    /** Identifier of the [PendingEntry] whose work has finished. */
    val id: String

    /** Errors that prevented delivery; empty when the work completed without such errors. */
    val errors: List<GraphQLError>

    /** Converts this entry to a map suitable for JSON serialization. */
    fun toSpecification(): Map<String, Any?>
}
