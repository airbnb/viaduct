@file:OptIn(ExperimentalApi::class)

package viaduct.api.batch

import viaduct.api.FieldValue
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.FieldCoordinate
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.apiannotations.ExperimentalApi

/**
 * Partitions [contexts] by structurally equal complete selection sets and invokes [resolve] once
 * for each group.
 *
 * Each result key must match a context in the current group under normal map-key equality.
 * Contexts omitted from the returned map remain omitted from the combined result.
 */
@ExperimentalApi
suspend fun <T : NodeObject, C : SelectiveNodeExecutionContext<T>> batchBySameSelection(
    contexts: List<C>,
    resolve: suspend (Group<T, C, SelectionSet<T>>) -> Map<C, FieldValue<T>>,
): Map<C, FieldValue<T>> =
    batchByCustomGrouping(
        contexts = contexts,
        groupBy = { it },
        resolve = resolve,
    )

/**
 * Partitions [contexts] by the fields selected directly on the node and invokes [resolve] once
 * for each group.
 *
 * Nested selections do not affect the grouping key. [Group.selections] retains any-member
 * navigation over the complete selections in the group.
 *
 * For example, suppose three `Product` contexts have these selections:
 * ```
 * context 1: details { summary }
 * context 2: details { rating }
 * context 3: name
 * ```
 *
 * The resulting groups are:
 * ```
 * group 1:
 *   key: { Product.details }
 *   contexts: [context 1, context 2]
 *   selections:
 *     details {
 *       summary
 *       rating
 *     }
 *
 * group 2:
 *   key: { Product.name }
 *   contexts: [context 3]
 *   selections:
 *     name
 * ```
 */
@ExperimentalApi
suspend fun <T : NodeObject, C : SelectiveNodeExecutionContext<T>> batchByOwnFields(
    contexts: List<C>,
    resolve: suspend (Group<T, C, Set<FieldCoordinate>>) -> Map<C, FieldValue<T>>,
): Map<C, FieldValue<T>> =
    batchByCustomGrouping(
        contexts = contexts,
        groupBy = SelectionSet<T>::selectedFieldCoordinates,
        resolve = resolve,
    )

/**
 * Partitions [contexts] by a tenant-defined key derived from each context's selection set.
 *
 * Groups are visited in first-key input order, and each [Group.contexts] list retains input order.
 * The [resolve] result may contain only keys that match contexts in the current group under
 * normal map-key equality. Out-of-group keys are rejected.
 */
@ExperimentalApi
suspend fun <T : NodeObject, C : SelectiveNodeExecutionContext<T>, K> batchByCustomGrouping(
    contexts: List<C>,
    groupBy: (SelectionSet<T>) -> K,
    resolve: suspend (Group<T, C, K>) -> Map<C, FieldValue<T>>,
): Map<C, FieldValue<T>> {
    val combined = mutableMapOf<C, FieldValue<T>>()
    contexts.groupBy { groupBy(it.selections()) }.forEach { (key, groupContexts) ->
        val group = GroupImpl(groupContexts, key)
        val allowedContexts = groupContexts.toSet()
        resolve(group).forEach { (context, value) ->
            require(context in allowedContexts) {
                "Batch group returned a context that was not a member of that group"
            }
            combined[context] = value
        }
    }
    return combined
}

/**
 * Returns a read-only selection view with any-member semantics over this non-empty context list.
 *
 * A field or type is present when any member requests it. Field and type navigation returns
 * another any-member view over the corresponding nested selections.
 *
 * @throws IllegalArgumentException when this list is empty
 */
@ExperimentalApi
fun <T : NodeObject, C : SelectiveNodeExecutionContext<T>> List<C>.selections(): SelectionSet<T> {
    require(isNotEmpty()) {
        "Cannot construct a batch selection view from an empty context list"
    }
    return AnySelections(map { it.selections() })
}

private class GroupImpl<
    T : NodeObject,
    C : SelectiveNodeExecutionContext<T>,
    out K,
>(
    override val contexts: List<C>,
    override val key: K,
) : Group<T, C, K> {
    override val selections: SelectionSet<T> = AnySelections(contexts.map { it.selections() })
}

private class AnySelections<T : CompositeOutput>(
    private val members: List<SelectionSet<T>>,
) : SelectionSet<T> {
    init {
        require(members.isNotEmpty()) {
            "Cannot construct a selection view without members"
        }
        require(members.all { it.type == members.first().type }) {
            "All selections in a batch view must describe the same type"
        }
    }

    override val type: Type<T> = members.first().type

    override fun selectedFieldCoordinates(): Set<FieldCoordinate> = members.flatMapTo(linkedSetOf()) { it.selectedFieldCoordinates() }

    override fun <U : T> contains(field: Field<U>): Boolean = members.any { it.contains(field) }

    override fun <U : T> requestsType(type: Type<U>): Boolean = members.any { it.requestsType(type) }

    override fun <U : T, R : CompositeOutput> selectionSetFor(field: viaduct.api.reflect.CompositeField<U, R>): SelectionSet<R> = AnySelections(members.map { it.selectionSetFor(field) })

    override fun <U : T> selectionSetFor(type: Type<U>): SelectionSet<U> = AnySelections(members.map { it.selectionSetFor(type) })

    override fun isEmpty(): Boolean = members.all { it.isEmpty() }
}
