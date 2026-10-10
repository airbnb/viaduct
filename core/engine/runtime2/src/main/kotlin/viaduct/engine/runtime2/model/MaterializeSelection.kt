package viaduct.engine.runtime2.model

import viaduct.engine.api.FieldDirectives
import viaduct.graphql.schema.ViaductSchema

/**
 * One response-key-preserving source field occurrence used to materialize resolver input.
 *
 * Unlike [Selection], this value retains the GraphQL [responseKey]. It remains an uncollected
 * source occurrence so mutually exclusive type-conditioned alternatives can retain different
 * field invocations under one response key. [MaterializeSelectionForest.collect] is the explicit
 * concrete-type field-collection boundary.
 *
 * ### Equality
 *
 * Kotlin equality is undefined. Materialize selections are opaque occurrences and must not be
 * compared, hashed, or deduplicated.
 */
sealed interface MaterializeSelection {
    /** The GraphQL response key: the alias when present, otherwise the selected field name. */
    val responseKey: String

    /** The canonical field invocation used by ordinary construction. */
    val key: ObjectEngineResult.Key

    /** The concrete runtime parent types for which this source occurrence applies. */
    val possibleTypes: Set<ViaductSchema.Object>

    /** The symbolic condition that must permit inclusion of this source occurrence. */
    val inclusionCondition: InclusionCondition

    /** Generic directives applied to this source field occurrence, when available. */
    val fieldDirectives: FieldDirectives?

    /** Response-key-preserving selections on this field's result. */
    val subselections: MaterializeSelectionForest

    /** Whether this selection's field has a simple base output type. */
    val isLeaf: Boolean
        get() = key.field.type.baseTypeDef is ViaductSchema.SimpleTypeDef

    companion object {
        /**
         * Constructs one source field occurrence.
         *
         * @throws IllegalArgumentException when [possibleTypes] or [subselections] are incoherent
         * with [key]
         */
        fun of(
            responseKey: String,
            key: ObjectEngineResult.Key,
            possibleTypes: Set<ViaductSchema.Object>,
            subselections: MaterializeSelectionForest,
            inclusionCondition: InclusionCondition = InclusionCondition.Always,
            fieldDirectives: FieldDirectives? = null,
        ): MaterializeSelection {
            require(responseKey.isNotEmpty()) {
                "A materialize selection requires a non-empty response key"
            }
            val fieldOwner = key.field.containingDef
            require(possibleTypes.all { it in fieldOwner.possibleObjectTypes }) {
                "Materialize selection possible types must be contained by ${fieldOwner.name}"
            }
            require(
                key.field.type.baseTypeDef is ViaductSchema.CompositeTypeDef || subselections.isEmpty(),
            ) {
                "Leaf materialize selection ${fieldOwner.name}.${key.field.name} " +
                    "has subselections"
            }
            return MaterializeSelectionImpl(
                responseKey = responseKey,
                key = key,
                possibleTypes = possibleTypes,
                inclusionCondition = inclusionCondition,
                fieldDirectives = fieldDirectives,
                subselections = subselections,
            )
        }
    }
}

/**
 * A free commutative collection of opaque [MaterializeSelection] source occurrences.
 *
 * Every occurrence retains its response key and applicability guard. Use [collect] only after the
 * concrete parent object type is known. Use [constructionSelections] when ordinary alias-free OER
 * demand is required.
 */
sealed interface MaterializeSelectionForest {
    val size: Int

    fun isEmpty(): Boolean

    fun all(predicate: (MaterializeSelection) -> Boolean): Boolean

    fun filter(predicate: (MaterializeSelection) -> Boolean): MaterializeSelectionForest

    fun flatMap(transform: (MaterializeSelection) -> MaterializeSelectionForest): MaterializeSelectionForest

    fun forEach(action: (MaterializeSelection) -> Unit)

    fun single(): MaterializeSelection

    operator fun plus(other: MaterializeSelectionForest): MaterializeSelectionForest

    /**
     * Returns the ordinary construction view, recursively erasing only response-key information.
     *
     * Source occurrences, canonical keys, applicability guards, and nested construction selections
     * are preserved.
     */
    fun constructionSelections(): SelectionForest

    /**
     * Filters this forest to [type] and collects co-applicable occurrences by response key. The
     * caller must first remove occurrences whose [MaterializeSelection.inclusionCondition] is
     * false in its variable-binding environment.
     *
     * Collection happens before argument binding. Members of one response-key group must select
     * the same concrete field with syntactically equal open arguments. Their subselection
     * occurrences are concatenated without recursively collecting them because the nested concrete
     * object type is not known yet.
     *
     * @throws IllegalArgumentException when one co-applicable response-key group contains
     * incompatible field invocations
     */
    fun collect(type: ViaductSchema.Object): ObjectMaterializeSelectionForest
}

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
    val subselections: MaterializeSelectionForest

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

/** Constructs a materialize forest containing the supplied source occurrences. */
fun materializeSelectionForestOf(vararg selections: MaterializeSelection): MaterializeSelectionForest = MaterializeSelectionForestImpl(selections.asList())

/** Constructs a materialize forest from these source occurrences. */
fun Iterable<MaterializeSelection>.toMaterializeSelectionForest(): MaterializeSelectionForest = MaterializeSelectionForestImpl(toList())

/** Maps each input to a materialize forest and concatenates the source occurrences. */
fun <T : Any> Iterable<T>.flatMapToMaterializeSelectionForest(transform: (T) -> MaterializeSelectionForest): MaterializeSelectionForest =
    MaterializeSelectionForestImpl(
        buildList {
            this@flatMapToMaterializeSelectionForest.forEach { element ->
                addAll(transform(element).occurrences())
            }
        },
    )

/** Returns an alias-free materialize forest for an existing ordinary construction forest. */
fun SelectionForest.toCanonicalMaterializeSelectionForest(): MaterializeSelectionForest {
    val selections = mutableListOf<MaterializeSelection>()
    forEach { selection ->
        selections +=
            MaterializeSelection.of(
                responseKey = selection.key.field.name,
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                inclusionCondition = selection.inclusionCondition,
                subselections =
                    selection.subselections.toCanonicalMaterializeSelectionForest(),
            )
    }
    return selections.toMaterializeSelectionForest()
}

/** Conjunctively guards each source occurrence while retaining a compact condition graph. */
fun MaterializeSelectionForest.guardedBy(condition: InclusionCondition): MaterializeSelectionForest =
    flatMap { selection ->
        materializeSelectionForestOf(
            MaterializeSelection.of(
                responseKey = selection.responseKey,
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections,
                inclusionCondition = condition.and(selection.inclusionCondition),
                fieldDirectives = selection.fieldDirectives,
            ),
        )
    }

private class MaterializeSelectionImpl(
    override val responseKey: String,
    override val key: ObjectEngineResult.Key,
    override val possibleTypes: Set<ViaductSchema.Object>,
    override val inclusionCondition: InclusionCondition,
    override val fieldDirectives: FieldDirectives?,
    override val subselections: MaterializeSelectionForest,
) : MaterializeSelection

private class ObjectMaterializeSelectionImpl(
    override val responseKey: String,
    override val key: ObjectEngineResult.ObjectKey,
    override val fieldDirectives: FieldDirectives?,
    override val subselections: MaterializeSelectionForest,
) : ObjectMaterializeSelection

private class MaterializeSelectionForestImpl(
    private val selections: List<MaterializeSelection>,
) : MaterializeSelectionForest {
    override val size: Int
        get() = selections.size

    override fun isEmpty(): Boolean = selections.isEmpty()

    override fun all(predicate: (MaterializeSelection) -> Boolean): Boolean = selections.all(predicate)

    override fun filter(predicate: (MaterializeSelection) -> Boolean): MaterializeSelectionForest = MaterializeSelectionForestImpl(selections.filter(predicate))

    override fun flatMap(transform: (MaterializeSelection) -> MaterializeSelectionForest): MaterializeSelectionForest = selections.flatMapToMaterializeSelectionForest(transform)

    override fun forEach(action: (MaterializeSelection) -> Unit) {
        selections.forEach(action)
    }

    override fun single(): MaterializeSelection = selections.single()

    override fun plus(other: MaterializeSelectionForest): MaterializeSelectionForest = MaterializeSelectionForestImpl(selections + other.occurrences())

    override fun constructionSelections(): SelectionForest =
        selections
            .map { selection ->
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    inclusionCondition = selection.inclusionCondition,
                    subselections = selection.subselections.constructionSelections(),
                )
            }.toSelectionForest()

    override fun collect(type: ViaductSchema.Object): ObjectMaterializeSelectionForest {
        val membersByResponseKey =
            linkedMapOf<
                String,
                MutableList<Triple<ObjectEngineResult.ObjectKey, MaterializeSelectionForest, FieldDirectives?>>,
            >()
        selections.forEach { selection ->
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
                            .map(Triple<ObjectEngineResult.ObjectKey, MaterializeSelectionForest, FieldDirectives?>::second)
                            .flatMapToMaterializeSelectionForest { it },
                )
            }
        return ObjectMaterializeSelectionForestImpl(type, groups)
    }

    fun occurrences(): List<MaterializeSelection> = selections
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

private fun MaterializeSelectionForest.occurrences(): List<MaterializeSelection> = (this as MaterializeSelectionForestImpl).occurrences()

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
