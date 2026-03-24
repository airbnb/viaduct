package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType

/**
 * An interface that represents a result similar to the result of
 * [ExecuteSelectionSet algorithm](https://spec.graphql.org/draft/#sec-Executing-Selection-Sets)
 */
interface ObjectEngineResult {
    /**
     * A key for a field, including the field name and a simple map of argument names -> values.
     */
    class Key private constructor(
        val name: String,
        val alias: String? = null,
        val arguments: Map<String, Any?> = emptyMap()
    ) {
        companion object {
            operator fun invoke(
                name: String,
                alias: String? = null,
                arguments: Map<String, Any?> = emptyMap()
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
                    arguments
                )
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Key

            if (name != other.name) return false
            if (alias != other.alias) return false
            if (arguments != other.arguments) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + (alias?.hashCode() ?: 0)
            result = 31 * result + arguments.hashCode()
            return result
        }

        override fun toString(): String {
            return "Key(name='$name', alias='$alias', arguments=${arguments.entries.joinToString()})"
        }
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
