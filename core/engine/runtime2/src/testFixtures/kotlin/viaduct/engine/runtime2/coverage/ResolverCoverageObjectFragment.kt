package viaduct.engine.runtime2.coverage

import viaduct.engine.runtime2.arbitrary.ListsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFragmentsEnabled
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.usedVariables
import viaduct.graphql.schema.ViaductSchema

/**
 * The shape of an occurrence's resolver object fragment. Depths count selection levels below the parent object, so a
 * direct parent field is at depth 1. [failedResolverReads] counts direct resolver-backed fragment fields whose sibling
 * value in the occurrence's parent object completed as null or an error.
 */
data class ObjectFragmentShape(
    val selections: Int,
    val depth: Int,
    val resolverFields: Int,
    val nestedResolverFields: Int,
    val listTraversals: Int,
    val typeConditionedSelections: Int,
    val parentTraversals: Int,
    val failedResolverReads: Int,
) {
    init {
        require(csvValues().all { it >= 0 })
        require(nestedResolverFields <= resolverFields && failedResolverReads <= resolverFields)
    }

    internal fun csvValues(): List<Int> = listOf(selections, depth, resolverFields, nestedResolverFields, listTraversals, typeConditionedSelections, parentTraversals, failedResolverReads)

    companion object {
        val EMPTY = ObjectFragmentShape(0, 0, 0, 0, 0, 0, 0, 0)

        internal val CSV_COLUMNS =
            listOf(
                "object_fragment_selections",
                "object_fragment_depth",
                "object_fragment_resolver_fields",
                "object_fragment_nested_resolver_fields",
                "object_fragment_list_traversals",
                "object_fragment_type_conditioned_selections",
                "object_fragment_parent_traversals",
                "object_fragment_failed_resolver_reads",
            )

        internal fun fromCsvValues(values: List<Int>): ObjectFragmentShape {
            require(values.size == CSV_COLUMNS.size) { "Unexpected object-fragment column count" }
            return ObjectFragmentShape(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
        }
    }
}

/** A taxonomy trait of one occurrence's object fragment; descriptive unless a suite explicitly requires it. */
internal class ObjectFragmentTrait(val id: String, val description: String, val matches: (ObjectFragmentShape) -> Boolean)

internal val objectFragmentTraits =
    listOf(
        ObjectFragmentTrait("object_fragment:reads_resolver_field", "An invoked resolver's object fragment selects a resolver-backed field.") { it.resolverFields > 0 },
        ObjectFragmentTrait(
            "object_fragment:reads_nested_resolver_field",
            "An invoked resolver's object fragment selects a resolver-backed field below its first level."
        ) { it.nestedResolverFields > 0 },
        ObjectFragmentTrait("object_fragment:crosses_list", "An invoked resolver's object fragment selects beneath a list-valued field.") { it.listTraversals > 0 },
        ObjectFragmentTrait(
            "object_fragment:type_conditioned",
            "An invoked resolver's object fragment selects beneath an abstract field for only some of its object types.",
        ) { it.typeConditionedSelections > 0 },
        ObjectFragmentTrait("object_fragment:traverses_parent", "An invoked resolver's object fragment contains an @parent selection.") { it.parentTraversals > 0 },
    )

/** Interactions involving an object fragment; each holds on one invoked occurrence, so support and case evidence share one predicate. */
internal val objectFragmentObligations =
    listOf(
        OccurrenceCoverageObligation(
            id = "object_fragment_resolver_field_in_list_element",
            description = "An invoked resolver in a list element has an object fragment that selects a resolver-backed field.",
            code = "FL",
            applicableTo = { it[ResolverFragmentsEnabled] && it[ListsEnabled] },
        ) { it.locus == ResolverLocus.LIST_ELEMENT_OBJECT && it.objectFragment.resolverFields > 0 },
        OccurrenceCoverageObligation(
            id = "object_fragment_reads_failed_resolver_field",
            description = "An invoked resolver's object fragment selects a resolver-backed field that completed as null or an error.",
            code = "FE",
            applicableTo = { it[ResolverFragmentsEnabled] },
        ) { it.objectFragment.failedResolverReads > 0 },
    )

/** The occurrence-independent part of a resolver's object-fragment shape, computed once per resolver. */
internal class ObjectFragmentProfile(
    private val shape: ObjectFragmentShape,
    private val directResolverReads: List<ObjectEngineResult.ObjectKey>,
) {
    /** Only unconditional, variable-free keys name a cell the resolver certainly read; other keys are not matched. */
    suspend fun shapeIn(parent: ObjectEngineResult): ObjectFragmentShape {
        if (directResolverReads.isEmpty()) return shape
        val failed =
            directResolverReads.count { key ->
                key in parent.keys && parent.getCell(key).let { cell -> cell.fetchActivated() && cell.value.isCompleted && cell.value.get().failedOutput() }
            }
        return shape.copy(failedResolverReads = failed)
    }

    companion object {
        fun of(
            fragment: SelectionForest,
            registry: ResolverRegistry,
        ): ObjectFragmentProfile {
            var selections = 0
            var depth = 0
            var resolverFields = 0
            var nestedResolverFields = 0
            var listTraversals = 0
            var typeConditionedSelections = 0
            var parentTraversals = 0
            val directResolverReads = mutableListOf<ObjectEngineResult.ObjectKey>()

            fun visit(
                forest: SelectionForest,
                level: Int,
            ) {
                forest.forEach { selection ->
                    selections++
                    depth = maxOf(depth, level)
                    val key = selection.key
                    if (key is ObjectEngineResult.ParentKey) parentTraversals++
                    if (selection.isResolverBacked(registry)) {
                        resolverFields++
                        if (level > 1) nestedResolverFields++
                        if (level == 1 && selection.inclusionCondition === InclusionCondition.Always && key is ObjectEngineResult.ObjectKey &&
                            key !is ObjectEngineResult.ParentKey && key.arguments.usedVariables().isEmpty()
                        ) {
                            directResolverReads += key
                        }
                    }
                    if (!selection.subselections.isEmpty()) {
                        val baseType = key.field.type.baseTypeDef
                        if (key.field.type.isList) listTraversals++
                        if (baseType !is ViaductSchema.Object) {
                            selection.subselections.forEach { child ->
                                if (child.possibleTypes != baseType.possibleObjectTypes) typeConditionedSelections++
                            }
                        }
                    }
                    visit(selection.subselections, level + 1)
                }
            }

            visit(fragment, 1)
            val shape = ObjectFragmentShape(selections, depth, resolverFields, nestedResolverFields, listTraversals, typeConditionedSelections, parentTraversals, 0)
            return ObjectFragmentProfile(shape, directResolverReads)
        }
    }
}

// Nested fragment keys may name an abstract type's field; it is resolver-backed if any concrete branch it applies to is.
private fun Selection.isResolverBacked(registry: ResolverRegistry): Boolean =
    when (val field = key.field) {
        is ViaductSchema.ObjectField -> field in registry
        else -> possibleTypes.any { type -> type.field(field.name)?.let { it in registry } == true }
    }
