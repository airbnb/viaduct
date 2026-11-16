package viaduct.engine.runtime2.model

import viaduct.engine.api.FieldDirectives
import viaduct.graphql.schema.ViaductSchema

/** Collects compatible source occurrences by response key for one concrete parent type.
 * Runtime conditions must be filtered by the caller before collection. Construction uses [merge]
 * on the same source graph and groups by invocation instead of response key.
 */
fun SelectionForest.collect(type: ViaductSchema.Object): ObjectMaterializeSelectionForest = cachedProjection(type) { collectUncached(type) }

/**
 * One response-key group collected for a concrete parent object type.
 *
 * Equality is undefined. [key] is the group's compatible open construction invocation;
 * [subselections] retains all nested source occurrences for collection at each concrete child OER.
 */
sealed interface ObjectMaterializeSelection {
    val responseKey: String
    val key: ObjectEngineResult.ObjectKey
    val fieldDirectives: FieldDirectives?
    val subselections: SelectionForest

    val isLeaf: Boolean
        get() = key.field.type.baseTypeDef is ViaductSchema.SimpleTypeDef
}

/** A concrete-parent materialize forest containing one group per response key. */
sealed interface ObjectMaterializeSelectionForest {
    val type: ViaductSchema.Object
    val size: Int

    fun isEmpty(): Boolean

    fun responseKeys(): Set<String>

    fun byResponseKey(): Map<String, ObjectMaterializeSelection>

    operator fun get(responseKey: String): ObjectMaterializeSelection
}

private class ObjectMaterializeSelectionImpl(
    override val responseKey: String,
    override val key: ObjectEngineResult.ObjectKey,
    override val fieldDirectives: FieldDirectives?,
    override val subselections: SelectionForest,
) : ObjectMaterializeSelection

private fun SelectionForest.collectUncached(type: ViaductSchema.Object): ObjectMaterializeSelectionForest {
    val membersByResponseKey =
        linkedMapOf<
            String,
            MutableList<Triple<ObjectEngineResult.ObjectKey, SelectionForest, FieldDirectives?>>,
        >()
    forEach { selection ->
        if (type in selection.possibleTypes) {
            membersByResponseKey
                .getOrPut(selection.responseKey, ::mutableListOf)
                .add(
                    Triple(
                        selection.key.objectKey(type),
                        selection.subselections,
                        selection.fieldDirectives,
                    ),
                )
        }
    }
    val groups =
        membersByResponseKey.mapValues { (responseKey, members) ->
            val key = members.first().first
            if (members.size == 1) {
                val (_, subselections, directives) = members.single()
                return@mapValues ObjectMaterializeSelectionImpl(responseKey, key, directives, subselections)
            }
            require(
                members.all { (memberKey, _, _) ->
                    memberKey.field == key.field &&
                        memberKey.arguments == key.arguments
                },
            ) {
                "Response key $responseKey has incompatible field invocations on " +
                    type.name
            }
            ObjectMaterializeSelectionImpl(
                responseKey = responseKey,
                key = key,
                fieldDirectives = mergeFieldDirectives(members.map { it.third }),
                subselections =
                    members
                        .map(Triple<ObjectEngineResult.ObjectKey, SelectionForest, FieldDirectives?>::second)
                        .flatMapToSelectionForest { it },
            )
        }
    return ObjectMaterializeSelectionForestImpl(type, groups)
}

private class ObjectMaterializeSelectionForestImpl(
    override val type: ViaductSchema.Object,
    private val selectionsByResponseKey: Map<String, ObjectMaterializeSelection>,
) : ObjectMaterializeSelectionForest {
    override val size: Int
        get() = selectionsByResponseKey.size

    override fun isEmpty(): Boolean = selectionsByResponseKey.isEmpty()

    override fun responseKeys(): Set<String> = selectionsByResponseKey.keys

    override fun byResponseKey(): Map<String, ObjectMaterializeSelection> = selectionsByResponseKey

    override fun get(responseKey: String): ObjectMaterializeSelection = selectionsByResponseKey.getValue(responseKey)
}

/**
 * Conservatively exposes a directive only when every collected source occurrence exposes it.
 * A missing directive context makes the collected context unavailable.
 */
private fun mergeFieldDirectives(directives: List<FieldDirectives?>): FieldDirectives? {
    if (directives.any { it == null }) return null
    val available = directives.filterNotNull()
    if (available.size == 1) return available.single()
    return object : FieldDirectives {
        override fun hasDirective(
            name: String,
            args: ((Map<String, Any?>) -> Boolean)?,
        ): Boolean = available.all { it.hasDirective(name, args) }
    }
}
