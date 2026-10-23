package viaduct.engine.runtime2.model

import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * A collection of opaque [Selection] members, commutative except for [MutationSelectionForest].
 * Ordinary forest construction and concatenation require coherent selections without mutation
 * members or mutation subselections; callers preserve this precondition without another check.
 *
 * ### Member Count
 *
 * [size] observes the number of current members. A forest returned by GraphQL selection
 * flattening has one member for each flattened GraphQL field occurrence, but that is a
 * postcondition of flattening rather than an invariant of every forest. Every representation
 * rejects a forest containing more than [MAX_SIZE] members.
 *
 * ### Equality And Observation
 *
 * Selection and forest equality are undefined, and no operation compares whole [Selection] values
 * or exposes member order except for [MutationSelectionForest]. [merge] is the explicit normalization boundary that compares structural
 * object keys and coalesces forest members.
 */
sealed interface SelectionForest {
    val size: Int

    fun isEmpty(): Boolean

    fun all(predicate: (Selection) -> Boolean): Boolean

    fun filter(predicate: (Selection) -> Boolean): SelectionForest

    fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest

    fun forEach(action: (Selection) -> Unit)

    fun single(): Selection

    operator fun plus(other: SelectionForest): SelectionForest

    companion object {
        internal const val MAX_SIZE = 500_000

        internal fun requireSupportedSize(size: Int) {
            require(size <= MAX_SIZE) {
                "A selection forest cannot contain more than $MAX_SIZE selections"
            }
        }
    }
}

/**
 * A normalized selection forest specialized to one concrete parent object type.
 *
 * ### Invariant: object-selection-forest-normalization
 *
 * Every selection is an [ObjectSelection] whose key field belongs to [type], whose
 * `possibleTypes == setOf(type)`, and whose key occurs exactly once.
 */
sealed interface ObjectSelectionForest : SelectionForest {
    val type: ViaductSchema.Object

    override fun filter(predicate: (Selection) -> Boolean): ObjectSelectionForest

    /** The concrete-object keys contributed by these normalized selections. */
    fun keys(): Set<ObjectEngineResult.ObjectKey>

    /** Returns the unique selection for each key. */
    fun byKey(): Map<ObjectEngineResult.ObjectKey, ObjectSelection>

    /**
     * Returns the ground keys contributed by these selections.
     *
     * @throws IllegalStateException when any key contains an open argument expression
     */
    fun groundKeys(): Set<ObjectEngineResult.GroundKey>

    /**
     * Returns the unique selection for each ground key.
     *
     * @throws IllegalStateException when any key contains an open argument expression
     */
    fun byGroundKey(): Map<ObjectEngineResult.GroundKey, ObjectSelection>

    /**
     * Returns the selection for [key].
     *
     * @throws NoSuchElementException when [key] is absent
     */
    operator fun get(key: ObjectEngineResult.ObjectKey): ObjectSelection

    companion object {
        /** Constructs a normalized forest satisfying [ObjectSelectionForest]'s invariant. */
        fun of(
            type: ViaductSchema.Object,
            selections: Iterable<ObjectSelection>,
        ): ObjectSelectionForest {
            val occurrences = selections.toList()
            val byKey =
                buildMap(occurrences.size) {
                    occurrences.forEach { selection ->
                        require(
                            selection.key.field.containingDef == type &&
                                selection.possibleTypes.size == 1 &&
                                type in selection.possibleTypes,
                        ) {
                            "Object selections must belong exclusively to ${type.name}"
                        }
                        require(put(selection.key, selection) == null) {
                            "Object selection keys must be unique"
                        }
                    }
                }
            return ObjectSelectionForestImpl(type, byKey, occurrences)
        }
    }
}

/** Constructs a [SelectionForest] containing the supplied occurrences. */
fun selectionForestOf(vararg selections: Selection): SelectionForest = ordinarySelectionForest(selections.asList())

fun selectionForestOf(): SelectionForest = EmptySelectionForest

fun selectionForestOf(selection: Selection): SelectionForest = SingletonSelectionForest(selection)

/** Constructs a [SelectionForest] from these occurrences. */
fun Iterable<Selection>.toSelectionForest(): SelectionForest = ordinarySelectionForest(toList())

internal fun SelectionForest.mapSelections(transform: (Selection) -> Selection): SelectionForest = mapNotNullSelections(transform)

internal fun SelectionForest.mapNotNullSelections(transform: (Selection) -> Selection?): SelectionForest =
    when (this) {
        is EmptySelectionForest -> this
        is SingletonSelectionForest -> transform(selection)?.let(::SingletonSelectionForest) ?: EmptySelectionForest
        else -> ordinarySelectionForest(buildList(size) { this@mapNotNullSelections.forEach { selection -> transform(selection)?.let(::add) } })
    }

private fun ordinarySelectionForest(selections: List<Selection>): SelectionForest =
    when (selections.size) {
        0 -> EmptySelectionForest
        1 -> SingletonSelectionForest(selections[0])
        else -> SelectionForestImpl(selections)
    }

/** Maps each input to a forest and concatenates the results without comparing selections. */
fun <T : Any> Iterable<T>.flatMapToSelectionForest(transform: (T) -> SelectionForest): SelectionForest =
    ordinarySelectionForest(
        buildList((this as? Collection<*>)?.size ?: 0) {
            this@flatMapToSelectionForest.forEach { element ->
                val forest = transform(element)
                forest.forEach(::add)
            }
        },
    )

/** Concatenates these forests without comparing their selection occurrences. */
fun Iterable<SelectionForest>.concatenateSelectionForests(): SelectionForest {
    if (this is Collection<SelectionForest> && size == 1) {
        val forest = first()
        if (forest !is MutationSelectionForest) return forest
    }
    return flatMapToSelectionForest { forest -> forest }
}

/**
 * Returns the demand applicable to concrete parent [type], normalized by structural object key.
 *
 * Applicability is tested before specialization, so an inapplicable occurrence contributes neither
 * its key nor its descendants. Each applicable key is reconstructed against the canonical field
 * on [type], including concrete argument defaults, and occurrences with equal reconstructed keys
 * are replaced by one selection whose subselections are their concatenated subselections.
 *
 * Arguments remain open. Merging must therefore be repeated after binding substitution can make
 * formerly distinct argument tuples equal. Subselections are not recursively merged because their
 * concrete runtime parent type is not yet known.
 */
fun SelectionForest.merge(type: ViaductSchema.Object): ObjectSelectionForest {
    require(this !is MutationSelectionForest) { "Mutation forests must retain response-key collection and source order" }
    (this as? CachedSelectionForest)?.merged?.let { if (it.type == type) return it }
    return mergeUncached(type).also { (this as? CachedSelectionForest)?.merged = it }
}

private fun SelectionForest.mergeUncached(type: ViaductSchema.Object): ObjectSelectionForest {
    if (isEmpty()) return ObjectSelectionForestImpl(type, emptyMap())
    if (size == 1) {
        val occurrence = single()
        if (type !in occurrence.possibleTypes) return ObjectSelectionForestImpl(type, emptyMap())
        val key = occurrence.objectKey(type)
        val selection = normalizedSingletonSelection(key, setOf(type), occurrence)
        return ObjectSelectionForestImpl(type, mapOf(key to selection), listOf(selection))
    }
    return mergeSlowPath(type)
}

private fun SelectionForest.mergeSlowPath(type: ViaductSchema.Object): ObjectSelectionForest {
    val possibleTypes = setOf(type)
    val selectionsByKey =
        LinkedHashMap<ObjectEngineResult.ObjectKey, Any?>(size).apply {
            this@mergeSlowPath.forEach { selection ->
                if (type in selection.possibleTypes) {
                    val key = selection.objectKey(type)
                    val previous = get(key)
                    if (previous == null) {
                        if (
                            selection is ObjectSelection &&
                            (selection.inclusionCondition === InclusionCondition.Always || selection.subselections.isEmpty())
                        ) {
                            put(key, selection)
                        } else {
                            put(key, ObjectSelectionBuilder(key, possibleTypes, selection, guardChildren = true))
                        }
                    } else {
                        val builder =
                            if (previous is ObjectSelectionBuilder) {
                                previous
                            } else {
                                val previousSelection = previous as ObjectSelection
                                ObjectSelectionBuilder(previousSelection.key, possibleTypes, previousSelection, guardChildren = false).also { put(key, it) }
                            }
                        builder.add(selection)
                    }
                }
            }
            entries.forEach { entry ->
                val builder = entry.value
                if (builder is ObjectSelectionBuilder) {
                    entry.setValue(builder.build())
                }
            }
        }
    @Suppress("UNCHECKED_CAST")
    val builtSelections = selectionsByKey as Map<ObjectEngineResult.ObjectKey, ObjectSelection>
    return ObjectSelectionForestImpl(type, builtSelections)
}

/** Merges already-specialized forests by exact object key without repeating type selection. */
fun Iterable<ObjectSelectionForest>.flatMerge(type: ViaductSchema.Object): ObjectSelectionForest {
    val possibleTypes = setOf(type)
    val selectionsByKey = linkedMapOf<ObjectEngineResult.ObjectKey, Any?>()
    forEach { forest ->
        require(forest.type == type) { "Flat merge requires forests specialized to ${type.name}" }
        forest.byKey().forEach { (key, selection) ->
            val previous = selectionsByKey[key]
            if (previous == null) {
                selectionsByKey[key] = normalizedSingletonSelection(key, possibleTypes, selection)
            } else {
                val builder =
                    if (previous is ObjectSelectionBuilder) {
                        previous
                    } else {
                        val previousSelection = previous as ObjectSelection
                        ObjectSelectionBuilder(previousSelection.key, possibleTypes, previousSelection, guardChildren = false)
                            .also { selectionsByKey[key] = it }
                    }
                builder.add(selection)
            }
        }
    }
    selectionsByKey.entries.forEach { entry ->
        val builder = entry.value
        if (builder is ObjectSelectionBuilder) entry.setValue(builder.build())
    }
    @Suppress("UNCHECKED_CAST")
    val builtSelections = selectionsByKey as Map<ObjectEngineResult.ObjectKey, ObjectSelection>
    return ObjectSelectionForestImpl(type, builtSelections)
}

private fun normalizedSingletonSelection(
    key: ObjectEngineResult.ObjectKey,
    possibleTypes: Set<ViaductSchema.Object>,
    occurrence: Selection,
): ObjectSelection =
    if (
        occurrence is ObjectSelection &&
        (occurrence.inclusionCondition === InclusionCondition.Always || occurrence.subselections.isEmpty())
    ) {
        occurrence
    } else {
        ObjectSelectionImpl(
            key = key,
            possibleTypes = possibleTypes,
            inclusionCondition = occurrence.inclusionCondition,
            subselections = occurrence.subselections.guardedBy(occurrence.inclusionCondition),
        )
    }

private class ObjectSelectionBuilder(
    private val key: ObjectEngineResult.ObjectKey,
    private val possibleTypes: Set<ViaductSchema.Object>,
    first: Selection,
    guardChildren: Boolean,
) {
    private var inclusionCondition: InclusionCondition = first.inclusionCondition

    private val children = if (key.field.type.baseTypeDef is ViaductSchema.SimpleTypeDef) null else ArrayList<Selection>(MERGED_CHILDREN_INITIAL_CAPACITY)

    init {
        appendChildren(first, guardChildren)
    }

    fun add(selection: Selection) {
        inclusionCondition = inclusionCondition.or(selection.inclusionCondition)
        appendChildren(selection, guardChildren = true)
    }

    fun build(): ObjectSelectionImpl = ObjectSelectionImpl(key, possibleTypes, inclusionCondition, children?.let(::ordinarySelectionForest) ?: EmptySelectionForest)

    private fun appendChildren(
        selection: Selection,
        guardChildren: Boolean,
    ) {
        val target = children ?: return
        SelectionForest.requireSupportedSize(target.size + selection.subselections.size)
        val condition = selection.inclusionCondition
        if (!guardChildren || condition === InclusionCondition.Always) {
            selection.subselections.forEach(target::add)
        } else {
            selection.subselections.forEach { child ->
                target.add(
                    Selection.of(
                        key = child.key,
                        possibleTypes = child.possibleTypes,
                        subselections = child.subselections,
                        inclusionCondition = condition.and(child.inclusionCondition),
                    ),
                )
            }
        }
    }
}

private const val MERGED_CHILDREN_INITIAL_CAPACITY = 3

/** Returns this selection's ground key. */
fun ObjectSelection.groundKey(): ObjectEngineResult.GroundKey =
    key as? ObjectEngineResult.GroundKey
        ?: error("Object selection key contains open arguments: $key")

/** Returns the variable instances used by this key's arguments. */
internal fun ObjectEngineResult.Key.instantiatedVariables(): Set<Arguments.Variable> = arguments.instantiatedVariables()

/** Returns the variable instances used recursively by this selection. */
private fun Selection.instantiatedVariables(): Set<Arguments.Variable> =
    key.instantiatedVariables() +
        inclusionCondition.usedVariables().filterTo(linkedSetOf()) { it.isInstantiated } +
        subselections.instantiatedVariables()

/** Returns the variable instances used recursively by this forest. */
fun SelectionForest.instantiatedVariables(): Set<Arguments.Variable> {
    val variables = linkedSetOf<Arguments.Variable>()
    forEach { selection -> variables += selection.instantiatedVariables() }
    return variables
}

/** Returns every variable expression used recursively by this selection. */
private fun Selection.usedVariables(): Set<Arguments.Variable> = key.arguments.usedVariables() + inclusionCondition.usedVariables() + subselections.usedVariables()

/** Returns every variable expression used recursively by this forest. */
fun SelectionForest.usedVariables(): Set<Arguments.Variable> {
    val variables = linkedSetOf<Arguments.Variable>()
    forEach { selection -> variables += selection.usedVariables() }
    return variables
}

/**
 * Specializes this selection's field coordinate to concrete parent [type].
 *
 * This operation preserves open argument expressions while applying the argument definition,
 * including defaults, of the corresponding canonical object field.
 */
fun Selection.objectKey(type: ViaductSchema.Object): ObjectEngineResult.ObjectKey {
    return key.objectKey(type)
}

internal fun ObjectEngineResult.Key.objectKey(type: ViaductSchema.Object): ObjectEngineResult.ObjectKey {
    // Concrete keys already carry this canonical field's coerced arguments and defaults.
    // Repeated closure/merge passes need not rebuild and revalidate the same argument tree.
    if (this is ObjectEngineResult.ObjectKey && field.containingDef == type) return this
    val concreteField = type.requireField(field.name)
    return ObjectEngineResult.ObjectKey.of(
        field = concreteField,
        arguments = arguments.retarget(concreteField),
    )
}

/**
 * A post-validation field-selection occurrence used for Viaduct field resolution.
 *
 * This is not a GraphQL AST selection or a description of field completion. Ordinary selections
 * omit aliases, response keys, source order, named fragments, inline-fragment nodes, and raw directives.
 * [MutationSelection] retains its response key, and [MutationSelectionForest] stipulates execution order. Inline
 * fragments have already been flattened into the field coordinate in [key], the applicability
 * guard in [possibleTypes], and [inclusionCondition].
 *
 * ### Invariant: selection-local-coherence
 *
 * Callers supply coherent components to [of], which preserves these invariants without rechecking them:
 * - [possibleTypes] is a subset of the object types contained by [key]'s field owner.
 * - When [isLeaf] is true, [subselections] is empty. The converse does not hold: a composite
 *   selection may also have no subselections.
 * - A selection's [key] is a [ObjectEngineResult.ObjectKey] exactly when it is an [ObjectSelection].
 *
 * ### Invariant: selection-well-foundedness
 *
 * A selection and its [subselections] form a finite, well-founded value.
 *
 * ### Equality
 *
 * Kotlin `equals` is currently undefined for [Selection]. The model does not yet assume that
 * selections can or need to be compared; proofs must not rely on selection equality until that
 * requirement is established.
 */
sealed interface Selection {
    /**
     * Exact alias-free output-field coordinate being selected.
     *
     * ### Invariant: selection-argument-coercion
     *
     * The arguments form the post-validation tuple for the selected field: declared defaults have
     * been applied, every required argument is present, omitted optional arguments without defaults
     * are absent, and every present non-variable value conforms recursively to its argument type.
     *
     * ### Representation
     *
     * [ObjectEngineResult.Key.field] is the canonical schema field intended by this selection, and
     * its containing type records the immediate field-lookup context. Non-variable argument values
     * are in their coerced semantic form. An argument may contain a [Arguments.Variable]. Variables
     * compare by name. Because a selection key is outside an OER or [EngineObjectData.Sync], its field may
     * belong to an abstract [ViaductSchema.Interface] or [ViaductSchema.Union], and its arguments may
     * contain variables. Before a key is present in either value, its field must belong to the
     * applicable concrete [ViaductSchema.Object] and its variables must be instantiated.
     *
     * Compared to GraphQL selections, model selections use an alias-free schema field coordinate
     * rather than a GraphQL response key.
     */
    val key: ObjectEngineResult.Key

    /**
     * The concrete runtime parent-object types for which this selection applies: if during resolution
     * this selection is applied to a type outside of this set, then the selection will be dropped.
     * In prose this property is often called the "type condition" of the selection.
     * All of these objects must be contained in the canonical schema.
     *
     * Compared to GraphQL selections, this is the intersection of all the possible types of all the
     * containing spreads and the type-level type of the containing document.  Unlike GraphQL
     * selection, this is the true intersection: even when a "broadening" type condition is
     * applied in the GraphQL selection, we won't broaden this corresponding type condition.
     */
    val possibleTypes: Set<ViaductSchema.Object>

    /** The symbolic condition that must permit inclusion of this occurrence. */
    val inclusionCondition: InclusionCondition

    /**
     * The subselections on the value this selection selects.
     *
     * This is empty when [isLeaf] is true. It may also be empty when this selection selects a
     * composite type but no fields within that value.
     *
     * Because of GraphQL's validation rules for type conditions, the relationship between the
     * field owner of a subselection and [key]'s field result type is complicated. Nested type
     * conditions need only overlap pairwise:
     * ```
     *    I1 = {A, B}
     *    I2 = {B, C}
     *    I3 = {C, D}
     * ```
     * Under an I1 field, ... on I2 { ... on I3 { x } } is valid, but I3 does not overlap I1.
     * So while we might someday need an invariant for the field owners of subselections, right now
     * we decline to state one.
     *
     * One might imagine an invariant that looks at the _actual_ object types that [key] could be applied
     * to (according to the local [possibleTypes]) and constrain those sub-[possibleTypes] to
     * contain the union of just those.  However, we don't think we need that invariant so currently
     * it is **not** an invariant of this data type.
     *
     * Compared to GraphQL selections, descending into this property _always_ descends a level
     * into the GraphQL object-value tree.  (In GraphQL selections, subselections can be spreads,
     * which do **not** descend into the object-value tree.)
     */
    val subselections: SelectionForest

    /**
     * Whether this selection's field has a simple base output type.
     *
     * The field's type expression may wrap that base type in lists or non-null constraints.
     */
    val isLeaf: Boolean
        get() = key.field.type.baseTypeDef is ViaductSchema.SimpleTypeDef

    companion object {
        /** Constructs a selection from components satisfying [Selection]'s invariants without rechecking them. */
        fun of(
            key: ObjectEngineResult.Key,
            possibleTypes: Set<ViaductSchema.Object>,
            subselections: SelectionForest,
            inclusionCondition: InclusionCondition = InclusionCondition.Always,
        ): Selection {
            return when (key) {
                is ObjectEngineResult.ObjectKey ->
                    ObjectSelectionImpl(
                        key = key,
                        possibleTypes = possibleTypes,
                        subselections = subselections,
                        inclusionCondition = inclusionCondition,
                    )
                else ->
                    SelectionImpl(
                        key = key,
                        possibleTypes = possibleTypes,
                        subselections = subselections,
                        inclusionCondition = inclusionCondition,
                    )
            }
        }

        /** Constructs the precise selection category for a concrete-object field key. */
        fun of(
            key: ObjectEngineResult.ObjectKey,
            possibleTypes: Set<ViaductSchema.Object>,
            subselections: SelectionForest,
            inclusionCondition: InclusionCondition = InclusionCondition.Always,
        ): ObjectSelection = ObjectSelection.of(key, possibleTypes, subselections, inclusionCondition)
    }
}

/** A selection whose field coordinate belongs to a concrete object type. */
sealed interface ObjectSelection : Selection {
    override val key: ObjectEngineResult.ObjectKey

    companion object {
        /** Constructs an object selection from components satisfying [Selection]'s invariants. */
        fun of(
            key: ObjectEngineResult.ObjectKey,
            possibleTypes: Set<ViaductSchema.Object>,
            subselections: SelectionForest,
            inclusionCondition: InclusionCondition = InclusionCondition.Always,
        ): ObjectSelection {
            return ObjectSelectionImpl(key, possibleTypes, inclusionCondition, subselections)
        }
    }
}

@Suppress("unused")
private fun validateSelection(
    key: ObjectEngineResult.Key,
    possibleTypes: Set<ViaductSchema.Object>,
    subselections: SelectionForest,
) {
    val fieldOwner = key.field.containingDef
    require(possibleTypes.all { it in fieldOwner.possibleObjectTypes }) {
        "Selection possible types must be contained by ${fieldOwner.name}"
    }
    require(
        key.field.type.baseTypeDef is ViaductSchema.CompositeTypeDef || subselections.isEmpty(),
    ) {
        "Leaf selection ${fieldOwner.name}.${key.field.name} has subselections"
    }
}

private class SelectionImpl(
    override val key: ObjectEngineResult.Key,
    override val possibleTypes: Set<ViaductSchema.Object>,
    override val inclusionCondition: InclusionCondition,
    override val subselections: SelectionForest,
) : Selection

private class ObjectSelectionImpl(
    override val key: ObjectEngineResult.ObjectKey,
    override val possibleTypes: Set<ViaductSchema.Object>,
    override val inclusionCondition: InclusionCondition,
    override val subselections: SelectionForest,
) : ObjectSelection

/** Conjunctively guards each occurrence while retaining a compact condition graph. */
fun SelectionForest.guardedBy(condition: InclusionCondition): SelectionForest =
    if (this !is MutationSelectionForest && (condition === InclusionCondition.Always || isEmpty())) {
        this
    } else {
        mapSelections { selection ->
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections,
                inclusionCondition = condition.and(selection.inclusionCondition),
            )
        }
    }

private object EmptySelectionForest : SelectionForest {
    override val size: Int get() = 0

    override fun isEmpty(): Boolean = true

    override fun all(predicate: (Selection) -> Boolean): Boolean = true

    override fun filter(predicate: (Selection) -> Boolean): SelectionForest = this

    override fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest = this

    override fun forEach(action: (Selection) -> Unit) {}

    override fun single(): Selection = throw NoSuchElementException("List is empty.")

    override fun plus(other: SelectionForest): SelectionForest = if (other.isEmpty()) this else other
}

internal fun SelectionForest.containsVariables(): Boolean {
    (this as? CachedSelectionForest)?.containsVariables?.let { return it }
    val result = !all { selection ->
        (selection.key.arguments is Arguments.Ground || selection.key.arguments.usedVariables().isEmpty()) &&
            selection.inclusionCondition.usedVariables().isEmpty() &&
            !selection.subselections.containsVariables()
    }
    (this as? CachedSelectionForest)?.containsVariables = result
    return result
}

internal fun SelectionForest.containsParentKeys(): Boolean {
    (this as? CachedSelectionForest)?.containsParentKeys?.let { return it }
    val result = !all { selection ->
        selection.key !is ObjectEngineResult.ParentKey &&
            !selection.subselections.containsParentKeys()
    }
    (this as? CachedSelectionForest)?.containsParentKeys = result
    return result
}

internal fun SelectionForest.containsParentKeys(interfaceParentFields: Set<ViaductSchema.Field>): Boolean {
    if (interfaceParentFields.isEmpty()) return containsParentKeys()
    return !all { selection ->
        selection.key !is ObjectEngineResult.ParentKey &&
            (selection.key is ObjectEngineResult.ObjectKey || selection.key.field !in interfaceParentFields) &&
            !selection.subselections.containsParentKeys(interfaceParentFields)
    }
}

private interface CachedSelectionForest : SelectionForest {
    var merged: ObjectSelectionForest?
    var containsVariables: Boolean?
    var containsParentKeys: Boolean?
}

private class SingletonSelectionForest(
    val selection: Selection,
) : CachedSelectionForest {
    @Volatile override var merged: ObjectSelectionForest? = null
    @Volatile override var containsVariables: Boolean? = null
    @Volatile override var containsParentKeys: Boolean? = null
    override val size: Int get() = 1

    override fun isEmpty(): Boolean = false

    override fun all(predicate: (Selection) -> Boolean): Boolean = predicate(selection)

    override fun filter(predicate: (Selection) -> Boolean): SelectionForest = if (predicate(selection)) this else EmptySelectionForest

    override fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest = flatMapSelections(transform)

    override fun forEach(action: (Selection) -> Unit) = action(selection)

    override fun single(): Selection = selection

    override fun plus(other: SelectionForest): SelectionForest = if (other.isEmpty()) this else ConcatenatedSelectionForest(this, other)
}

private abstract class AbstractSelectionForest(
    val occurrences: List<Selection>,
) : CachedSelectionForest {
    @Volatile override var merged: ObjectSelectionForest? = null
    @Volatile override var containsVariables: Boolean? = null
    @Volatile override var containsParentKeys: Boolean? = null

    init {
        SelectionForest.requireSupportedSize(occurrences.size)
    }

    override val size: Int
        get() = occurrences.size

    override fun isEmpty(): Boolean = occurrences.isEmpty()

    override fun all(predicate: (Selection) -> Boolean): Boolean = occurrences.all(predicate)

    override fun filter(predicate: (Selection) -> Boolean): SelectionForest = ordinarySelectionForest(occurrences.filter(predicate))

    override fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest = flatMapSelections(transform)

    override fun forEach(action: (Selection) -> Unit) {
        occurrences.forEach(action)
    }

    override fun single(): Selection = occurrences.single()

    override fun plus(other: SelectionForest): SelectionForest {
        if (other.isEmpty()) return this
        if (isEmpty()) return other
        return ConcatenatedSelectionForest(this, other)
    }
}

private class ConcatenatedSelectionForest(
    val left: SelectionForest,
    val right: SelectionForest,
) : CachedSelectionForest {
    @Volatile override var merged: ObjectSelectionForest? = null
    @Volatile override var containsVariables: Boolean? = null
    @Volatile override var containsParentKeys: Boolean? = null
    override val size: Int = left.size + right.size

    init {
        SelectionForest.requireSupportedSize(size)
    }

    override fun isEmpty(): Boolean = size == 0

    override fun all(predicate: (Selection) -> Boolean): Boolean = visitWhile(predicate)

    override fun filter(predicate: (Selection) -> Boolean): SelectionForest =
        ordinarySelectionForest(
            buildList(size) {
                this@ConcatenatedSelectionForest.forEach { selection ->
                    if (predicate(selection)) add(selection)
                }
            },
        )

    override fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest = flatMapSelections(transform)

    override fun forEach(action: (Selection) -> Unit) {
        visitWhile { selection ->
            action(selection)
            true
        }
    }

    override fun single(): Selection {
        if (size == 0) throw NoSuchElementException("List is empty.")
        require(size == 1) { "List has more than one element." }
        var result: Selection? = null
        forEach { result = it }
        return requireNotNull(result)
    }

    override fun plus(other: SelectionForest): SelectionForest {
        if (other.isEmpty()) return this
        // Ordinary forests are unordered; keep an added leaf on the left for chain traversal.
        return if (other is ConcatenatedSelectionForest) {
            ConcatenatedSelectionForest(this, other)
        } else {
            ConcatenatedSelectionForest(other, this)
        }
    }
}

private fun SelectionForest.flatMapSelections(transform: (Selection) -> SelectionForest): SelectionForest =
    ordinarySelectionForest(
        buildList(size) {
            this@flatMapSelections.forEach { selection ->
                val forest = transform(selection)
                forest.forEach(::add)
            }
        },
    )

private fun SelectionForest.visitWhile(predicate: (Selection) -> Boolean): Boolean {
    var forest = this
    var pending: ArrayDeque<SelectionForest>? = null
    while (true) {
        when (forest) {
            is ConcatenatedSelectionForest -> {
                if (forest.left is ConcatenatedSelectionForest) {
                    if (pending == null) pending = ArrayDeque()
                    pending.addLast(forest.right)
                    forest = forest.left
                } else {
                    if (!forest.left.all(predicate)) return false
                    forest = forest.right
                }
            }
            else -> {
                if (!forest.all(predicate)) return false
                forest = pending?.removeLastOrNull() ?: return true
            }
        }
    }
}

@Suppress("unused")
private fun Iterable<Selection>.validateOrdinarySelections() {
    require(none { it is MutationSelection || it.subselections is MutationSelectionForest }) {
        "An ordinary selection forest cannot contain mutation selections or mutation subselections"
    }
}

private class SelectionForestImpl(
    occurrences: List<Selection>,
) : AbstractSelectionForest(occurrences)

private class ObjectSelectionForestImpl(
    override val type: ViaductSchema.Object,
    private val selectionsByKey: Map<ObjectEngineResult.ObjectKey, ObjectSelection>,
    occurrences: List<ObjectSelection> = selectionsByKey.values.toList(),
) : AbstractSelectionForest(occurrences),
    ObjectSelectionForest {
    override fun filter(predicate: (Selection) -> Boolean): ObjectSelectionForest =
        ObjectSelectionForestImpl(
            type,
            selectionsByKey.filterValues(predicate),
        )

    override fun keys(): Set<ObjectEngineResult.ObjectKey> = selectionsByKey.keys

    override fun byKey(): Map<ObjectEngineResult.ObjectKey, ObjectSelection> = selectionsByKey

    override fun groundKeys(): Set<ObjectEngineResult.GroundKey> = byGroundKey().keys

    override fun byGroundKey(): Map<ObjectEngineResult.GroundKey, ObjectSelection> =
        selectionsByKey.mapKeys { (key, _) ->
            key as? ObjectEngineResult.GroundKey
                ?: error("Object selection forest contains open key: $key")
        }

    override fun get(key: ObjectEngineResult.ObjectKey): ObjectSelection = selectionsByKey.getValue(key)
}
