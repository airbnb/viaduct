package viaduct.engine.runtime.select

import graphql.language.Argument
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.gj

/**
 * An [EngineSelectionSet] projected to a specific concrete [GraphQLObjectType].
 *
 * Provides O(1) field lookup via pre-built indices:
 * - [selectionsByResultKey]: keyed by result key (alias or field name) for
 *   [containsSelection]/[resolveSelection]
 * - [fieldNames]: set of field names for [containsField]
 *
 * This is the optimized implementation returned by [EngineSelectionSetImpl.selectionSetForType]
 * when the target type is a concrete object type.
 *
 * All selections have already been:
 * - filtered by Constraints (statically unreachable selections removed)
 * - filtered by type-spreadability (only selections applicable to [concreteType] are present)
 *
 * As a result, no type-relation checks are needed at lookup time for the hot path
 * (when the [type] argument matches [concreteType]).
 */
internal class ProjectedEngineSelectionSet(
    private val concreteType: GraphQLObjectType,
    private val projectedSelections: List<FieldSelection>,
    private val selectionsByResultKey: Map<String, FieldSelection>,
    private val fieldNames: Set<String>,
    private val ctx: EngineSelectionSetContext,
    internal val sourceImpl: EngineSelectionSetImpl
) : EngineSelectionSet {
    override val type: String get() = concreteType.name

    override fun containsField(
        type: String,
        field: String
    ): Boolean {
        if (type == concreteType.name) return fieldNames.contains(field)
        return sourceImpl.containsField(type, field)
    }

    override fun containsSelection(
        type: String,
        selectionName: String
    ): Boolean {
        if (type == concreteType.name) return selectionsByResultKey.containsKey(selectionName)
        return sourceImpl.containsSelection(type, selectionName)
    }

    override fun resolveSelection(
        type: String,
        selectionName: String
    ): EngineSelection {
        if (type == concreteType.name) {
            return selectionsByResultKey[selectionName]?.toEngineSelection()
                ?: throw IllegalArgumentException("No selection found for selectionName `$selectionName`")
        }
        return sourceImpl.resolveSelection(type, selectionName)
    }

    override fun selections(): List<EngineSelection> = projectedSelections.map { it.toEngineSelection() }

    override fun traversableSelections(): List<EngineSelection> =
        projectedSelections.mapNotNull { sel ->
            // Guard against reprojections (e.g., union widening then narrowing to a different
            // member). projectedSelections are already filtered by isSpreadable in
            // selectionSetForType, so this is a no-op under normal construction but prevents
            // leaking non-spreadable selections if this instance is ever constructed directly.
            if (!ctx.schema.rels.isSpreadable(sel.typeCondition, concreteType)) return@mapNotNull null

            val selectionType = GraphQLTypeUtil.unwrapAll(fieldDef(sel).type)
            if (selectionType !is GraphQLCompositeType) return@mapNotNull null
            sel.toEngineSelection()
        }

    override fun isEmpty(): Boolean = projectedSelections.isEmpty()

    override fun isTransitivelyEmpty(): Boolean {
        if (isEmpty()) return true
        return projectedSelections
            .groupBy { it.field.name }
            .all { (fname, cfs) ->
                // Uses the first type condition for field lookup. When the same field name appears
                // under different type conditions, this matches EngineSelectionSetImpl behavior.
                // All type conditions in projectedSelections are spreadable on concreteType,
                // so the field coordinate is valid regardless of which one is chosen.
                val u = cfs.first().typeCondition
                if (u is GraphQLFieldsContainer) {
                    val coord = (u.name to fname).gj
                    val field = ctx.schema.schema.getFieldDefinition(coord)

                    val unwrapped = GraphQLTypeUtil.unwrapAll(field.type)
                    if (unwrapped is GraphQLCompositeType) {
                        selectionSetForField(u.name, fname).isTransitivelyEmpty()
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
    }

    override fun selectionSetForType(type: String): EngineSelectionSet {
        val rawType = ctx.schema.schema.getType(type)
            ?: throw IllegalArgumentException("type $type is not defined")
        val u = rawType as? GraphQLCompositeType
            ?: throw IllegalArgumentException("Type $type is not a composite type")

        if (u == concreteType) return this

        return sourceImpl.selectionSetForType(type)
    }

    override fun toSelectionSet() = sourceImpl.toSelectionSet()

    override fun addVariables(variables: Map<String, Any?>): EngineSelectionSet {
        val updatedSourceImpl = sourceImpl.addVariables(variables) as EngineSelectionSetImpl
        return from(
            concreteType = concreteType,
            filteredSelections = updatedSourceImpl.selections,
            ctx = updatedSourceImpl.ctx,
            sourceImpl = updatedSourceImpl
        )
    }

    override fun toFragment(): Fragment = sourceImpl.toFragment()

    override fun toNodelikeSelectionSet(
        nodeFieldName: String,
        arguments: List<Argument>
    ): EngineSelectionSet = sourceImpl.toNodelikeSelectionSet(nodeFieldName, arguments)

    override fun printAsFieldSet(): String = sourceImpl.printAsFieldSet()

    override fun requestsType(type: String): Boolean = sourceImpl.requestsType(type)

    override fun selectionSetForField(
        type: String,
        field: String
    ): EngineSelectionSet = sourceImpl.selectionSetForField(type, field)

    override fun selectionSetForSelection(
        type: String,
        selectionName: String
    ): EngineSelectionSet = sourceImpl.selectionSetForSelection(type, selectionName)

    override fun argumentsOfSelection(
        type: String,
        selectionName: String
    ): Map<String, Any?>? = sourceImpl.argumentsOfSelection(type, selectionName)

    private fun fieldDef(sel: FieldSelection): GraphQLFieldDefinition {
        val coord = (sel.typeCondition.name to sel.field.name).gj
        return ctx.schema.schema.getFieldDefinition(coord)
    }

    private fun FieldSelection.toEngineSelection(): EngineSelection =
        EngineSelection(
            typeCondition = typeCondition.name,
            fieldName = field.name,
            selectionName = field.resultKey
        )

    internal companion object {
        /**
         * Build a [ProjectedEngineSelectionSet] from a concrete type, pre-filtered selections,
         * context, and the source [EngineSelectionSetImpl] that would have been returned without
         * this optimization.
         *
         * The [sourceImpl] must have [EngineSelectionSetImpl.def] == [concreteType] and
         * [EngineSelectionSetImpl.selections] == [filteredSelections].
         */
        fun from(
            concreteType: GraphQLObjectType,
            filteredSelections: List<FieldSelection>,
            ctx: EngineSelectionSetContext,
            sourceImpl: EngineSelectionSetImpl
        ): ProjectedEngineSelectionSet {
            // Build the primary index: resultKey → FieldSelection for O(1) containsSelection/resolveSelection.
            //
            // Duplicate result keys CAN occur when the same field appears under multiple type conditions
            // that all apply to the concrete type (e.g., `... on Node { id }` and `... on Foo { id }`).
            // First-wins matches EngineSelectionSetImpl.findSelection which uses List.find (also first-wins).
            //
            // NOTE: this index is for existence/identity lookups only (containsField, containsSelection,
            // resolveSelection). Sub-selection merging — combining subfields from all occurrences of
            // the same field — is handled correctly by sourceImpl.selectionSetForField and
            // sourceImpl.selectionSetForSelection, which iterate the full filteredSelections list
            // and fold all matching selections together via buildSubselections.
            val selectionsByResultKey = LinkedHashMap<String, FieldSelection>(filteredSelections.size)
            for (sel in filteredSelections) {
                selectionsByResultKey.putIfAbsent(sel.field.resultKey, sel)
            }

            val fieldNames = filteredSelections.mapTo(mutableSetOf()) { it.field.name }

            return ProjectedEngineSelectionSet(
                concreteType = concreteType,
                projectedSelections = filteredSelections,
                selectionsByResultKey = selectionsByResultKey,
                fieldNames = fieldNames,
                ctx = ctx,
                sourceImpl = sourceImpl
            )
        }
    }
}
