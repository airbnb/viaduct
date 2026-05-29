package viaduct.arbitrary.graphql

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.RequiredSelectionSet

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
            ctx.engineSelectionSetFactory.engineSelectionSet(requiredSelectionSet.selections, emptyMap())
        )
    }

    suspend fun exercise(
        engineObjectData: EngineObjectData,
        ss: EngineSelectionSet,
    ) {
        val projected = ss.selectionSetForType(engineObjectData.type.name)

        // NB: the ss that we have in hand was created without variable values, which are unavailable to executors.
        // Since it does not have variable values, its methods will default to including any selections might depend
        // on variables.
        //
        // For example, in a selection set like `{ foo @skip(if:$var) { x } }`, the `foo` selection depends
        // on the value of variable $var, and will always be returned in calls like `selections` or
        // `traversableSelections` if $var is unknown.
        //
        // These values will be a superset of the actual selections reported by the engineObjectData, which is backed
        // by a selection set with variable values. We can use the engineObjectData to identify what was selected,
        // and cross-reference that with `ss` to determine if a selection is traversable.
        val fetchedSelections = engineObjectData.fetchSelections().toSet()
        val traversableSelections = projected.traversableSelections()
            .map { it.selectionName }
            .toSet()
        val selectionNames = projected.selections()
            .map { it.selectionName }
            .distinct()

        selectionNames.forEach { selectionName ->
            if (selectionName !in fetchedSelections) return@forEach

            if (selectionName in traversableSelections) {
                // selection supports subselections -- fetch the selected value and recursively exercise it
                val subSelections = projected.selectionSetForSelection(projected.type, selectionName)
                traverseAndExercise(
                    engineObjectData.fetch(selectionName),
                    subSelections
                )
            } else {
                engineObjectData.fetch(selectionName)
            }
        }
    }

    suspend fun traverseAndExercise(
        value: Any?,
        subSelections: EngineSelectionSet
    ): Unit =
        when (value) {
            null -> {}
            is EngineObjectData -> exercise(value, subSelections)
            is Iterable<*> -> value.forEach {
                traverseAndExercise(it, subSelections)
            }
            else -> throw IllegalArgumentException("Unexpected value: $value")
        }
}
