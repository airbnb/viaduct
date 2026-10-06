package viaduct.engine.runtime2.model

import viaduct.graphql.schema.ViaductSchema

/**
 * Ordered demand on the Mutation root or a mutation namespace object.
 * Members have ground object keys, singleton possible types, and Always inclusion.
 * Only mutation forests may contain mutation subselections. Descending past an active
 * mutation field returns to ordinary, unordered selection forests.
 * Equality remains undefined. Equal response keys collect at their first occurrence; distinct aliases remain distinct invocations.
 */
sealed interface MutationSelectionForest : SelectionForest {
    val type: ViaductSchema.Object

    /** Visits members in their stipulated execution order. */
    fun orderedSelections(): List<MutationSelection>

    override fun single(): MutationSelection

    override fun filter(predicate: (Selection) -> Boolean): MutationSelectionForest

    companion object {
        fun of(
            schema: ViaductSchema,
            type: ViaductSchema.Object,
            selections: Iterable<MutationSelection>,
        ): MutationSelectionForest {
            require(type in schema.mutationNamespaceTypes()) { "${type.name} is not a mutation namespace type" }
            val members = selections.toList()
            members.forEach { selection ->
                require(
                    selection.possibleTypes == setOf(type) &&
                        selection.key.field.containingDef == type && selection.inclusionCondition === InclusionCondition.Always
                ) { "Mutation selections require ground object keys, singleton parent types, and Always inclusion" }
                val child = selection.subselections
                if (child is MutationSelectionForest) {
                    require(selection.key.field.isMutationNamespaceField() && selection.key.field.type.baseTypeDef == child.type) {
                        "Mutation subselections must follow a namespace field"
                    }
                } else {
                    require(!selection.key.field.isMutationNamespaceField()) { "Mutation namespace fields require mutation subselections" }
                }
            }
            require(members.map { it.responseKey }.toSet().size == members.size) { "Mutation response keys must be unique" }
            return MutationSelectionForestImpl(schema, type, members)
        }
    }
}

/** Namespace edges are structural, rather than tenant resolver applications. */
fun ViaductSchema.ObjectField.isMutationNamespaceField(): Boolean =
    !hasAppliedDirective("resolver") &&
        (type.baseTypeDef as? ViaductSchema.Object)?.hasAppliedDirective("namespaceType") == true &&
        type.unwrapList() == null

/** The mutation root and namespace types reachable before crossing an active field. */
fun ViaductSchema.mutationNamespaceTypes(): Set<ViaductSchema.Object> {
    val result = linkedSetOf<ViaductSchema.Object>()

    fun visit(type: ViaductSchema.Object) {
        if (!result.add(type)) return
        type.fields.filter { it.isMutationNamespaceField() }.forEach { visit(it.type.baseTypeDef as ViaductSchema.Object) }
    }
    mutationTypeDef?.let(::visit)
    return result
}

private class MutationSelectionForestImpl(
    private val schema: ViaductSchema,
    override val type: ViaductSchema.Object,
    private val members: List<MutationSelection>,
) : MutationSelectionForest {
    override val size get() = members.size

    override fun isEmpty(): Boolean = members.isEmpty()

    override fun all(predicate: (Selection) -> Boolean): Boolean = members.all(predicate)

    override fun forEach(action: (Selection) -> Unit) = members.forEach(action)

    override fun single(): MutationSelection = members.single()

    override fun orderedSelections(): List<MutationSelection> = members.toList()

    override fun filter(predicate: (Selection) -> Boolean): MutationSelectionForest = MutationSelectionForest.of(schema, type, members.filter(predicate))

    override fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest = members.flatMapToSelectionForest(transform)

    override fun plus(other: SelectionForest): SelectionForest {
        require(other.isEmpty()) { "Mutation forests must be collected in source order before construction" }
        return this
    }
}

/** One response-key-preserving invocation on a mutation namespace object. */
sealed interface MutationSelection : ObjectSelection {
    override val key: ObjectEngineResult.GroundKey
    val responseKey: String

    companion object {
        fun of(
            responseKey: String,
            key: ObjectEngineResult.GroundKey,
            subselections: SelectionForest
        ): MutationSelection {
            require(responseKey.isNotEmpty()) { "Mutation response keys must be nonempty" }
            require(key.field.type.baseTypeDef is ViaductSchema.CompositeTypeDef || subselections.isEmpty()) {
                "Leaf mutation selections cannot have subselections"
            }
            return MutationSelectionImpl(responseKey, key, subselections)
        }
    }
}

private class MutationSelectionImpl(
    override val responseKey: String,
    override val key: ObjectEngineResult.GroundKey,
    override val subselections: SelectionForest,
) : MutationSelection {
    override val possibleTypes = setOf(key.field.containingDef)
    override val inclusionCondition = InclusionCondition.Always
}
