package viaduct.engine.runtime2.model

import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * A collection of opaque [Selection] members, commutative except for [MutationSelectionForest].
 *
 * ### Member Count
 *
 * [size] observes the number of current members. A forest returned by GraphQL selection
 * flattening has one member for each flattened GraphQL field occurrence, but that is a
 * postcondition of flattening rather than an invariant of every forest.
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
            val occurrences =
                buildList {
                    addAll(selections)
                }
            val byKey =
                buildMap {
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
fun selectionForestOf(vararg selections: Selection): SelectionForest = SelectionForestImpl(selections.asList())

/** Constructs a [SelectionForest] from these occurrences. */
fun Iterable<Selection>.toSelectionForest(): SelectionForest = SelectionForestImpl(toList())

/** Maps each input to a forest and concatenates the results without comparing selections. */
fun <T : Any> Iterable<T>.flatMapToSelectionForest(transform: (T) -> SelectionForest): SelectionForest =
    SelectionForestImpl(
        buildList {
            this@flatMapToSelectionForest.forEach { element ->
                addAll(transform(element).occurrences())
            }
        },
    )

/** Concatenates these forests without comparing their selection occurrences. */
fun Iterable<SelectionForest>.concatenateSelectionForests(): SelectionForest = flatMapToSelectionForest { forest -> forest }

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
    val occurrencesByKey =
        buildMap<ObjectEngineResult.ObjectKey, MutableList<Selection>> {
            occurrences().forEach { selection ->
                if (type in selection.possibleTypes) {
                    getOrPut(selection.objectKey(type), ::mutableListOf).add(selection)
                }
            }
        }
    return normalizedObjectSelectionForest(type, occurrencesByKey)
}

private fun normalizedObjectSelectionForest(
    type: ViaductSchema.Object,
    occurrencesByKey: Map<ObjectEngineResult.ObjectKey, List<Selection>>,
): ObjectSelectionForest {
    // Specialization preserves local validity; grouping establishes normalized unique keys.
    val possibleTypes = setOf(type)
    val selectionsByKey =
        buildMap {
            occurrencesByKey.forEach { (key, occurrences) ->
                put(
                    key,
                    ObjectSelectionImpl(
                        key = key,
                        possibleTypes = possibleTypes,
                        inclusionCondition =
                            InclusionCondition.anyOf(
                                occurrences.map { it.inclusionCondition },
                            ),
                        subselections =
                            occurrences
                                .map { occurrence ->
                                    occurrence.subselections.guardedBy(
                                        occurrence.inclusionCondition,
                                    )
                                }.concatenateSelectionForests(),
                    ),
                )
            }
        }
    return ObjectSelectionForestImpl(type, selectionsByKey)
}

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
 * Selections constructed by this model use [of], which ensures:
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
        /**
         * Constructs a selection that satisfies the invariant documented on [Selection].
         */
        fun of(
            key: ObjectEngineResult.Key,
            possibleTypes: Set<ViaductSchema.Object>,
            subselections: SelectionForest,
            inclusionCondition: InclusionCondition = InclusionCondition.Always,
        ): Selection {
            validateSelection(key, possibleTypes, subselections)
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
        fun of(
            key: ObjectEngineResult.ObjectKey,
            possibleTypes: Set<ViaductSchema.Object>,
            subselections: SelectionForest,
            inclusionCondition: InclusionCondition = InclusionCondition.Always,
        ): ObjectSelection {
            validateSelection(key, possibleTypes, subselections)
            return ObjectSelectionImpl(key, possibleTypes, inclusionCondition, subselections)
        }
    }
}

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

/** Conjunctively guards each occurrence, distributing disjunction into concatenated occurrences. */
fun SelectionForest.guardedBy(condition: InclusionCondition): SelectionForest =
    condition.alternatives().flatMapToSelectionForest { alternative ->
        flatMap { selection ->
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections = selection.subselections,
                    inclusionCondition = alternative.and(selection.inclusionCondition),
                ),
            )
        }
    }

private abstract class AbstractSelectionForest(
    val occurrences: List<Selection>,
) : SelectionForest {
    init {
        require(occurrences.none { it is MutationSelection || it.subselections is MutationSelectionForest }) {
            "An ordinary selection forest cannot contain mutation selections or mutation subselections"
        }
    }

    override val size: Int
        get() = occurrences.size

    override fun isEmpty(): Boolean = occurrences.isEmpty()

    override fun all(predicate: (Selection) -> Boolean): Boolean = occurrences.all(predicate)

    override fun filter(predicate: (Selection) -> Boolean): SelectionForest = SelectionForestImpl(occurrences.filter(predicate))

    override fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest = occurrences.flatMapToSelectionForest(transform)

    override fun forEach(action: (Selection) -> Unit) {
        occurrences.forEach(action)
    }

    override fun single(): Selection = occurrences.single()

    override fun plus(other: SelectionForest): SelectionForest = SelectionForestImpl(occurrences + other.occurrences())
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

private fun SelectionForest.occurrences(): List<Selection> =
    when (this) {
        is MutationSelectionForest -> orderedSelections()
        else -> (this as AbstractSelectionForest).occurrences
    }
