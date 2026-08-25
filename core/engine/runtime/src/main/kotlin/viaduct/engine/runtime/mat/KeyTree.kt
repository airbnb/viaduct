package viaduct.engine.runtime.mat

import graphql.schema.GraphQLObjectType
import java.util.Collections
import viaduct.engine.runtime.result.ObjectEngineResult

/**
 * A [KeyTree] represents the shape of a selection set using a normalized tree.
 *
 * A concrete type may have no fields. Such an entry represents an empty type branch and is
 * distinct from a tree with no type branches.
 */
class KeyTree(
    byType: Map<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>
) {
    private val byType: Map<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>> = snapshotByType(byType)

    /** Returns true when this tree has no concrete type branches. */
    fun isEmpty(): Boolean = byType.isEmpty()

    /** get an immutable view of this [KeyTree] */
    internal fun keysByType(): Map<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>> = byType

    /** Returns the selections in this [KeyTree] that are not covered by [other] */
    operator fun minus(other: KeyTree): KeyTree {
        val result = mutableMapOf<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>()
        for ((type, fields) in byType) {
            val otherFields = other.byType[type]
            if (otherFields == null) {
                result[type] = fields
                continue
            }
            val needed = mutableMapOf<ObjectEngineResult.Key, KeyTree>()
            for ((key, sub) in fields) {
                if (key !in otherFields) {
                    needed[key] = sub
                    continue
                }
                if (sub.isEmpty()) continue // leaf, covered
                val neededSub = sub - otherFields.getValue(key)
                if (!neededSub.isEmpty()) needed[key] = neededSub
            }
            if (needed.isNotEmpty()) result[type] = needed
        }
        return KeyTree(result)
    }

    /** Returns the union of this [KeyTree] and [other] */
    operator fun plus(other: KeyTree): KeyTree {
        if (other.isEmpty()) return this
        if (isEmpty()) return other
        val result = mutableMapOf<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>()
        for (type in byType.keys + other.byType.keys) {
            val a = byType[type] ?: emptyMap()
            val b = other.byType[type] ?: emptyMap()
            val merged = a.toMutableMap()
            for ((key, sub) in b) {
                val extant = merged[key]
                merged[key] = when {
                    extant == null -> sub
                    extant.isEmpty() -> sub
                    sub.isEmpty() -> extant
                    else -> extant + sub
                }
            }
            result[type] = merged
        }
        return KeyTree(result)
    }

    /** Returns the selections shared by this [KeyTree] and [other]. */
    fun intersect(other: KeyTree): KeyTree {
        if (this === other) return this
        if (isEmpty() || other.isEmpty()) return empty
        if (this == other) return this
        val result = mutableMapOf<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>()
        for ((type, fields) in byType) {
            val otherFields = other.byType[type] ?: continue
            val commonFields = mutableMapOf<ObjectEngineResult.Key, KeyTree>()
            for ((key, children) in fields) {
                val otherChildren = otherFields[key] ?: continue
                commonFields[key] = if (children.isEmpty() || otherChildren.isEmpty()) {
                    empty
                } else {
                    children.intersect(otherChildren)
                }
            }
            result[type] = commonFields
        }
        return KeyTree(result)
    }

    /** Returns the child subtree under the exact field [key]. */
    fun subtreeForKey(
        type: GraphQLObjectType,
        key: ObjectEngineResult.Key,
    ): KeyTree = byType[type]?.get(key) ?: empty

    /** Returns true when the exact field [key] is selected on [type]. */
    fun containsKey(
        type: GraphQLObjectType,
        key: ObjectEngineResult.Key,
    ): Boolean = byType[type]?.containsKey(key) == true

    /**
     * Returns response keys selected directly on a concrete object type.
     *
     * This is a shallow view of the tree: it returns the result keys for selections at this level
     * only, and does not include response keys from nested sub-selections. Callers that need nested
     * response keys should first navigate to the nested [KeyTree] with [subtreeForKey].
     *
     * @param type is the concrete object type to inspect.
     */
    fun responseKeysForType(type: GraphQLObjectType): Set<String> = byType[type]?.keys?.mapTo(linkedSetOf()) { it.responseKey } ?: emptySet()

    /**
     * Returns a tree shaped like `{ key { this } }` on a concrete object type.
     *
     * This is used to bubble missing reads upward through embedded values.
     *
     * @param type is the concrete object type that owns [key].
     * @param key is the field key that should wrap this tree.
     */
    fun wrappedIn(
        type: GraphQLObjectType,
        key: ObjectEngineResult.Key
    ): KeyTree = KeyTree(mapOf(type to mapOf(key to this)))

    /** Return a [KeyTree] that has been recursively filtered by [filter] */
    fun filter(filter: KeyTreeFilter): KeyTree =
        when (filter) {
            // simple optimizations for known filters
            KeyTreeFilter.KeepAll -> this
            else -> filterInternal(filter, true)
        }

    private fun filterInternal(
        filter: KeyTreeFilter,
        topLevel: Boolean
    ): KeyTree {
        val result = mutableMapOf<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>()
        for ((type, fields) in byType) {
            val kept = mutableMapOf<ObjectEngineResult.Key, KeyTree>()
            for ((key, sub) in fields) {
                if (!filter(type, key, topLevel)) continue
                kept[key] = sub.filterInternal(filter, false)
            }
            result[type] = kept
        }
        return KeyTree(result)
    }

    /** Recursively removes concrete type branches that contain no fields. */
    internal fun withoutEmptyTypeBranches(): KeyTree {
        val result = mutableMapOf<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>()
        for ((type, fields) in byType) {
            if (fields.isEmpty()) continue
            result[type] = fields.mapValues { (_, children) ->
                children.withoutEmptyTypeBranches()
            }
        }
        return KeyTree(result)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyTree) return false
        return byType == other.byType
    }

    override fun hashCode(): Int = byType.hashCode()

    override fun toString(): String =
        byType.entries.joinToString(
            prefix = "KeyTree(",
            postfix = ")",
        ) { (type, fields) ->
            fields.entries.joinToString(
                prefix = "${type.name}={",
                postfix = "}",
            ) { (key, children) ->
                "$key=$children"
            }
        }

    companion object {
        /** An empty [KeyTree] */
        val empty: KeyTree = KeyTree(emptyMap())

        private fun snapshotByType(byType: Map<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>): Map<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>> {
            val snapshot = LinkedHashMap<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>()
            for ((type, fields) in byType) {
                snapshot[type] = Collections.unmodifiableMap(LinkedHashMap(fields))
            }
            return Collections.unmodifiableMap(snapshot)
        }
    }
}

/** Returns the subtree at [path]. */
internal fun KeyTree.subtreeAt(path: MatPath): KeyTree {
    var subtree = this
    var parentType = path.rootType
    for (segment in path.segments) {
        subtree = subtree.subtreeForKey(parentType, segment.key)
        parentType = segment.type
    }
    return subtree
}

/**
 * A predicate for filtering a [KeyTree].
 *
 * Returning true keeps a key; returning false filters out the key and its subtree.
 *
 * @see [KeyTree.filter]
 */
fun interface KeyTreeFilter {
    operator fun invoke(
        type: GraphQLObjectType,
        key: ObjectEngineResult.Key,
        topLevel: Boolean
    ): Boolean

    /** Returns a filter that is the logical AND of this and [other] */
    infix fun and(other: KeyTreeFilter): KeyTreeFilter = AndFilter(this, other)

    /** Returns a filter that is the logical OR of this and [other] */
    infix fun or(other: KeyTreeFilter): KeyTreeFilter = OrFilter(this, other)

    @JvmInline
    private value class Const(val value: Boolean) : KeyTreeFilter {
        override fun invoke(
            type: GraphQLObjectType,
            key: ObjectEngineResult.Key,
            topLevel: Boolean
        ): Boolean = value
    }

    private class AndFilter(val left: KeyTreeFilter, val right: KeyTreeFilter) : KeyTreeFilter {
        override fun invoke(
            type: GraphQLObjectType,
            key: ObjectEngineResult.Key,
            topLevel: Boolean
        ): Boolean = left(type, key, topLevel) && right(type, key, topLevel)
    }

    private class OrFilter(val left: KeyTreeFilter, val right: KeyTreeFilter) : KeyTreeFilter {
        override fun invoke(
            type: GraphQLObjectType,
            key: ObjectEngineResult.Key,
            topLevel: Boolean
        ): Boolean = left(type, key, topLevel) || right(type, key, topLevel)
    }

    companion object {
        /** A [KeyTreeFilter] that includes all keys */
        val KeepAll: KeyTreeFilter = Const(true)

        /** A [KeyTreeFilter] that drops all keys */
        val DropAll: KeyTreeFilter = Const(false)
    }
}
