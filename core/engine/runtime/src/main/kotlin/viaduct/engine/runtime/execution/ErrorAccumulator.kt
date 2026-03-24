package viaduct.engine.runtime.execution

import graphql.GraphQLError
import java.util.concurrent.ConcurrentHashMap

/** A thread-safe blackboard for recording errors encountered during query execution */
class ErrorAccumulator {
    private val errors = ConcurrentHashMap<String, GraphQLError>()

    /** return true if this object has not recorded any errors */
    fun isEmpty(): Boolean = errors.isEmpty()

    /** add a single error */
    fun add(error: GraphQLError) {
        val pathString = pathString(error.path)
        errors.putIfAbsent(pathString, error)
    }

    /** add multiple errors */
    fun addAll(errors: Iterable<GraphQLError>) {
        for (error in errors) {
            add(error)
        }
    }

    private fun pathString(parts: List<Any>): String =
        buildString {
            append("/")
            parts.forEach {
                append(it.toString())
                append("/")
            }
        }

    /** add a single error */
    operator fun plusAssign(error: GraphQLError) = add(error)

    /** add multiple errors */
    operator fun plusAssign(errors: Iterable<GraphQLError>) = addAll(errors)

    /** return an immutable list of the accumulated errors, in lexicographic order of error path */
    fun toList(): List<GraphQLError> =
        errors.toList()
            .sortedBy { it.first }
            .map { it.second }
}
