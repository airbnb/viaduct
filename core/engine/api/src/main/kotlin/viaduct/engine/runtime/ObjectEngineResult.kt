package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType

/**
 * An interface that represents a result similar to the result of
 * [ExecuteSelectionSet algorithm](https://spec.graphql.org/draft/#sec-Executing-Selection-Sets)
 */
interface ObjectEngineResult {
    /**
     * Canonical identity for selections that affect OER memoization.
     */
    interface Selections {
        val type: String
        val document: String
        val variables: Map<String, Any?>
    }

    /**
     * A key for a field, including the field name and a simple map of argument names -> values.
     */
    class Key private constructor(
        val name: String,
        val alias: String? = null,
        val arguments: Map<String, Any?> = emptyMap(),
        val selectionSet: Selections? = null,
    ) {
        private val canonicalSelectionSet =
            selectionSet?.let { selectionSet ->
                CanonicalSelectionSet(
                    type = selectionSet.type,
                    document = selectionSet.document,
                    variables = selectionSet.variables
                )
            }

        companion object {
            operator fun invoke(
                name: String,
                alias: String? = null,
                arguments: Map<String, Any?> = emptyMap(),
                selectionSet: Selections? = null,
            ): Key =
                // graphql field merging is done by result name, which means
                // that in this selection set:
                //   { test, test:test }
                // The two selections are mergeable because they have the same
                // result name.
                //
                // This implies that an OER key without an alias should be equivalent
                // to the same key with an alias value equal to its field value.
                Key(
                    name,
                    // remove alias if it matches name
                    alias?.takeIf { it != name },
                    arguments,
                    selectionSet
                )
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Key

            if (name != other.name) return false
            if (alias != other.alias) return false
            if (arguments != other.arguments) return false
            if (canonicalSelectionSet != other.canonicalSelectionSet) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + (alias?.hashCode() ?: 0)
            result = 31 * result + arguments.hashCode()
            result = 31 * result + (canonicalSelectionSet?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String {
            return "Key(name='$name', alias='$alias', arguments=${arguments.entries.joinToString()}, selectionSet=$selectionSet)"
        }

        private data class CanonicalSelectionSet(
            val type: String,
            val document: String,
            val variables: Map<String, Any?>,
        )
    }

    val type: GraphQLObjectType

    /**
     * Fetch a value in the given [slotNo] using the provided [key]. The value will contain an ObjectEngineResult
     * if the key describes a composite-typed field, otherwise the value will contain a kotlin
     * representation of a scalar or enum value. The values returned by this API have not
     * been [completed](https://spec.graphql.org/draft/#sec-Value-Completion), and may contain
     * un-bubbled nulls and uncoerced scalar values.
     */
    suspend fun fetch(
        key: Key,
        slotNo: Int
    ): Any?
}
