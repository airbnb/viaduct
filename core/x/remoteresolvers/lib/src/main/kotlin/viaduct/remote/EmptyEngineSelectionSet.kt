package viaduct.remote

import graphql.language.Argument
import graphql.language.SelectionSet
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.fragment.Fragment

/**
 * No-op [EngineSelectionSet] used when the caller's selection-set handle isn't resolvable
 * on the RRS side (the registry is per-JVM). Mirrors the previously-public
 * `EngineSelectionSet.empty(...)` helper that was removed from the engine API.
 */
internal class EmptyEngineSelectionSet(override val type: String) : EngineSelectionSet {
    override fun selections(): List<EngineSelection> = emptyList()

    override fun traversableSelections(): List<EngineSelection> = emptyList()

    override fun toSelectionSet(): SelectionSet = SelectionSet(emptyList())

    override fun addVariables(variables: Map<String, Any?>): EngineSelectionSet = unsupported("addVariables")

    override fun toFragment(): Fragment = Fragment.empty

    override fun toNodelikeSelectionSet(
        nodeFieldName: String,
        arguments: List<Argument>
    ): EngineSelectionSet = unsupported("toNodelikeSelectionSet")

    override fun printAsFieldSet(): String = ""

    override fun containsField(
        type: String,
        field: String
    ): Boolean = false

    override fun containsSelection(
        type: String,
        selectionName: String
    ): Boolean = false

    override fun resolveSelection(
        type: String,
        selectionName: String
    ): EngineSelection = throw IllegalArgumentException("Not selected: $type.$selectionName")

    override fun requestsType(type: String): Boolean = false

    override fun selectionSetForField(
        type: String,
        field: String
    ): EngineSelectionSet = EmptyEngineSelectionSet(type)

    override fun selectionSetForSelection(
        type: String,
        selectionName: String
    ): EngineSelectionSet = EmptyEngineSelectionSet(type)

    override fun selectionSetForType(type: String): EngineSelectionSet = this

    override fun isEmpty(): Boolean = true

    override fun isTransitivelyEmpty(): Boolean = true

    override fun argumentsOfSelection(
        type: String,
        selectionName: String
    ): Map<String, Any?>? = null

    private fun unsupported(op: String): Nothing = throw UnsupportedOperationException("$op is not supported on EmptyEngineSelectionSet")
}
