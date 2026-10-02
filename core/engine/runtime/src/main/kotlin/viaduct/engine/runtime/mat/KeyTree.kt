package viaduct.engine.runtime.mat

import graphql.schema.GraphQLObjectType
import java.util.Collections
import java.util.IdentityHashMap
import viaduct.engine.runtime.mat.KeyTreeFilter.Result.DROP
import viaduct.engine.runtime.mat.KeyTreeFilter.Result.KEEP_AND_RECURSE
import viaduct.engine.runtime.mat.KeyTreeFilter.Result.KEEP_WITHOUT_CHILDREN
import viaduct.engine.runtime.result.ObjectEngineResult

/**
 * A [KeyTree] represents the shape of a selection set using a normalized tree.
 * Repeated subtrees may share an instance, making the in-memory representation a DAG.
 *
 * A concrete type may have no fields. Such an entry represents an empty type branch and is
 * distinct from a tree with no type branches.
 */
class KeyTree(
    byType: Map<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>
) {
    private val byType: Map<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>> = snapshotByType(byType)

    @Volatile
    private var cachedHashCode: Int? = null

    /** Returns true when this tree has no concrete type branches. */
    fun isEmpty(): Boolean = byType.isEmpty()

    /** get an immutable view of this [KeyTree] */
    internal fun keysByType(): Map<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>> = byType

    /** Returns the selections in this [KeyTree] that are not covered by [other] */
    operator fun minus(other: KeyTree): KeyTree = minus(other, PairMemo())

    private fun minus(
        other: KeyTree,
        memo: PairMemo<KeyTree>
    ): KeyTree {
        if (this === other) return empty
        if (isEmpty() || other.isEmpty()) return this
        return memo.get(this, other) { minusUncached(other, memo) }
    }

    private fun minusUncached(
        other: KeyTree,
        memo: PairMemo<KeyTree>
    ): KeyTree {
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
                val neededSub = sub.minus(otherFields.getValue(key), memo)
                if (!neededSub.isEmpty()) needed[key] = neededSub
            }
            if (needed.isNotEmpty()) result[type] = needed
        }
        return KeyTree(result)
    }

    /** Returns the union of this [KeyTree] and [other] */
    operator fun plus(other: KeyTree): KeyTree = plus(other, PairMemo())

    private fun plus(
        other: KeyTree,
        memo: PairMemo<KeyTree>
    ): KeyTree {
        if (this === other) return this
        if (other.isEmpty()) return this
        if (isEmpty()) return other
        return memo.get(this, other) { plusUncached(other, memo) }
    }

    private fun plusUncached(
        other: KeyTree,
        memo: PairMemo<KeyTree>
    ): KeyTree {
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
                    else -> extant.plus(sub, memo)
                }
            }
            result[type] = merged
        }
        return KeyTree(result)
    }

    /** Returns the selections shared by this [KeyTree] and [other]. */
    fun intersect(other: KeyTree): KeyTree = intersect(other, null)

    private fun intersect(
        other: KeyTree,
        memo: PairMemo<KeyTree>?
    ): KeyTree {
        if (this === other || isEmpty()) return this
        if (other.isEmpty()) return other
        val pairs = memo ?: PairMemo<KeyTree>()
        return pairs.get(this, other) { intersectUncached(other, pairs) }
    }

    private fun intersectUncached(
        other: KeyTree,
        memo: PairMemo<KeyTree>
    ): KeyTree {
        // Copy only changed branches; the intersection traversal also detects reusable subtrees.
        var result: MutableMap<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>? = null
        for ((type, fields) in byType) {
            val otherFields = other.byType[type]
            if (otherFields == null) {
                if (result == null) result = byType.toMutableMap()
                result.remove(type)
                continue
            }
            var commonFields: MutableMap<ObjectEngineResult.Key, KeyTree>? = null
            for ((key, children) in fields) {
                val otherChildren = otherFields[key]
                if (otherChildren == null) {
                    if (commonFields == null) commonFields = fields.toMutableMap()
                    commonFields.remove(key)
                } else {
                    val commonChildren = children.intersect(otherChildren, memo)
                    if (commonChildren !== children) {
                        if (commonFields == null) commonFields = fields.toMutableMap()
                        commonFields[key] = commonChildren
                    }
                }
            }
            if (commonFields != null) {
                if (result == null) result = byType.toMutableMap()
                result[type] = commonFields
            }
        }
        return result?.let(::KeyTree) ?: this
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
            KeyTreeFilter.KeepAll -> this
            else -> filterInternal(filter, true, IdentityHashMap())
        }

    private fun filterInternal(
        filter: KeyTreeFilter,
        topLevel: Boolean,
        memo: IdentityHashMap<KeyTree, KeyTree>,
    ): KeyTree {
        // A filter may treat the root differently, so only nested results are safe to reuse.
        if (!topLevel) memo[this]?.let { return it }
        if (isEmpty()) return this
        val result = mutableMapOf<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>()
        for ((type, fields) in byType) {
            val kept = mutableMapOf<ObjectEngineResult.Key, KeyTree>()
            for ((key, sub) in fields) {
                when (filter(type, key, topLevel)) {
                    DROP -> continue
                    KEEP_WITHOUT_CHILDREN -> kept[key] = empty
                    KEEP_AND_RECURSE -> kept[key] = sub.filterInternal(filter, false, memo)
                }
            }
            result[type] = kept
        }
        return KeyTree(result).also { if (!topLevel) memo[this] = it }
    }

    /** Recursively removes concrete type branches that contain no fields. */
    internal fun withoutEmptyTypeBranches(): KeyTree = withoutEmptyTypeBranches(IdentityHashMap())

    private fun withoutEmptyTypeBranches(memo: IdentityHashMap<KeyTree, KeyTree>): KeyTree {
        memo[this]?.let { return it }
        if (isEmpty()) return this
        val result = mutableMapOf<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>()
        for ((type, fields) in byType) {
            if (fields.isEmpty()) continue
            result[type] = fields.mapValues { (_, children) ->
                children.withoutEmptyTypeBranches(memo)
            }
        }
        return KeyTree(result).also { memo[this] = it }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyTree) return false
        return equalTo(other)
    }

    private fun equalTo(
        other: KeyTree,
        memo: PairMemo<Boolean>? = null,
    ): Boolean {
        if (this === other) return true
        if (hashCode() != other.hashCode()) return false
        val pairs = memo ?: PairMemo<Boolean>()
        return pairs.get(this, other) {
            byType.size == other.byType.size && byType.all { (type, fields) ->
                val otherFields = other.byType[type]
                otherFields != null && fields.size == otherFields.size && fields.all { (key, child) ->
                    otherFields[key]?.let { child.equalTo(it, pairs) } == true
                }
            }
        }
    }

    // Each immutable node caches its hash, so shared descendants are hashed only once.
    override fun hashCode(): Int = cachedHashCode ?: byType.hashCode().also { cachedHashCode = it }

    /** Binary recursion must key both identities because one shared node can meet many peers. */
    private class PairMemo<T> {
        private val values = IdentityHashMap<KeyTree, IdentityHashMap<KeyTree, T>>()

        fun get(
            left: KeyTree,
            right: KeyTree,
            compute: () -> T
        ): T = values.getOrPut(left) { IdentityHashMap() }.getOrPut(right, compute)
    }

    // Keep diagnostics shallow: traversing and printing large trees can be expensive.
    override fun toString(): String =
        byType.entries.joinToString(
            prefix = "KeyTree(",
            postfix = ")",
        ) { (type, fields) ->
            fields.keys.joinToString(
                prefix = "${type.name}={",
                postfix = "}",
            ) { it.responseKey }
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

/** Decides whether to keep a key and traverse its children in [KeyTree.filter]. */
fun interface KeyTreeFilter {
    enum class Result {
        DROP,
        KEEP_WITHOUT_CHILDREN,
        KEEP_AND_RECURSE,
    }

    operator fun invoke(
        type: GraphQLObjectType,
        key: ObjectEngineResult.Key,
        topLevel: Boolean
    ): Result

    /** Combines filters using the more restrictive result. */
    infix fun and(other: KeyTreeFilter): KeyTreeFilter = AndFilter(this, other)

    /** Combines filters using the less restrictive result. */
    infix fun or(other: KeyTreeFilter): KeyTreeFilter = OrFilter(this, other)

    @JvmInline
    private value class Const(val value: Result) : KeyTreeFilter {
        override fun invoke(
            type: GraphQLObjectType,
            key: ObjectEngineResult.Key,
            topLevel: Boolean
        ): Result = value
    }

    private class AndFilter(val left: KeyTreeFilter, val right: KeyTreeFilter) : KeyTreeFilter {
        override fun invoke(
            type: GraphQLObjectType,
            key: ObjectEngineResult.Key,
            topLevel: Boolean
        ): Result =
            when (left(type, key, topLevel)) {
                DROP -> DROP
                KEEP_WITHOUT_CHILDREN ->
                    if (right(type, key, topLevel) == DROP) DROP else KEEP_WITHOUT_CHILDREN
                KEEP_AND_RECURSE -> right(type, key, topLevel)
            }
    }

    private class OrFilter(val left: KeyTreeFilter, val right: KeyTreeFilter) : KeyTreeFilter {
        override fun invoke(
            type: GraphQLObjectType,
            key: ObjectEngineResult.Key,
            topLevel: Boolean
        ): Result =
            when (left(type, key, topLevel)) {
                DROP -> right(type, key, topLevel)
                KEEP_WITHOUT_CHILDREN ->
                    if (right(type, key, topLevel) == KEEP_AND_RECURSE) KEEP_AND_RECURSE else KEEP_WITHOUT_CHILDREN
                KEEP_AND_RECURSE -> KEEP_AND_RECURSE
            }
    }

    companion object {
        /** A [KeyTreeFilter] that includes all keys */
        val KeepAll: KeyTreeFilter = Const(KEEP_AND_RECURSE)

        /** A [KeyTreeFilter] that drops all keys */
        val DropAll: KeyTreeFilter = Const(DROP)
    }
}
