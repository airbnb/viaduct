package viaduct.arbitrary.graphql

import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj
import viaduct.engine.runtime.select.coord

/**
 * Exercise a provided [EngineObjectData] by recursively fetching every selection that it
 * contains.
 **/
internal object EngineDataExerciser {
    suspend fun exercise(
        engineObjectData: EngineObjectData,
        ctx: EngineExecutionContext,
        requiredSelectionSet: RequiredSelectionSet
    ) {
        exercise(
            engineObjectData,
            ctx.fullSchema,
            ctx.engineSelectionSetFactory.engineSelectionSet(requiredSelectionSet.selections, emptyMap())
        )
    }

    suspend fun exercise(
        engineObjectData: EngineObjectData,
        schema: ViaductSchema,
        ss: EngineSelectionSet,
    ) {
        val projected = ss.selectionSetForType(engineObjectData.type.name)

        // NB: the ss that we have in hand was created without variable values, which are unavailable to executors.
        // Since it does not have variable values, its methods will default to including any selections that might
        // depend on variables.
        //
        // For example, in a selection set like `{ foo @skip(if:$var) { x } }`, the `foo` selection depends
        // on the value of variable $var, and will always be returned in calls like `selections` or
        // `traversableSelections` when $var is unknown.
        //
        // These values will be a superset of the actual selections reported by the engineObjectData, which is backed
        // by a selection set with variable values. We can use the engineObjectData to identify what was selected,
        // and cross-reference that with `ss` to determine if a selection is traversable.
        val fetchedSelections = engineObjectData.fetchSelections().toSet()
        val traversableSelections = projected.traversableSelections()
            .map { it.selectionName }
            .toSet()

        projected.selections().forEach { sel ->
            if (sel.selectionName !in fetchedSelections) return@forEach

            val value = engineObjectData.fetch(sel.selectionName)
            if (sel.selectionName in traversableSelections) {
                // selection supports subselections -- fetch the selected value and recursively exercise it
                val subSelections = projected.selectionSetForSelection(sel.typeCondition, sel.selectionName)
                traverseAndExercise(
                    value,
                    schema,
                    subSelections
                )
            }

            val field = schema.schema.getFieldDefinition(sel.coord.gj)
            if (GraphQLTypeUtil.isNonNull(field.type)) {
                requireNotNull(value) {
                    "Expected a non-null value for selection $sel"
                }
            }
        }
    }

    suspend fun traverseAndExercise(
        value: Any?,
        schema: ViaductSchema,
        subSelections: EngineSelectionSet
    ): Unit =
        when (value) {
            null -> {}
            is EngineObjectData -> exercise(value, schema, subSelections)
            is Iterable<*> -> value.forEach {
                traverseAndExercise(it, schema, subSelections)
            }
            else -> throw IllegalArgumentException("Unexpected value: $value")
        }
}
