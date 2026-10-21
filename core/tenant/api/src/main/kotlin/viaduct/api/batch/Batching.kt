@file:OptIn(ExperimentalApi::class, InternalApi::class)

package viaduct.api.batch

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import viaduct.api.FieldValue
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.internal.InternalSelectionSet
import viaduct.api.internal.internalType
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.FieldCoordinate
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.errors.resultOfSuspend

/**
 * Partitions [contexts] by structurally equal complete selection sets and invokes [resolve] once
 * for each group.
 *
 * Each result key must match a context in the current group under normal map-key equality.
 * Contexts omitted from the returned map remain omitted from the combined result.
 *
 * Blocks for different groups may run concurrently, so [resolve] should not mutate shared state.
 * Failures are handled as described in [batchByCustomGrouping].
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
 *
 * Blocks for different groups may run concurrently, so [resolve] should not mutate shared state.
 * Failures are handled as described in [batchByCustomGrouping].
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
 * - [Group.contexts] keeps the input order.
 * - Blocks for different groups may run concurrently, so [resolve] should not mutate shared state.
 * - [resolve] should return a value for every context in its group. If it leaves one out, that
 *   context is missing from the result and Viaduct fails the whole batch.
 * - If a block throws an exception or times out, every context in its group gets an error.
 *   Other groups are not affected.
 * - If the caller is cancelled or [groupBy] throws, the whole call fails.
 */
@ExperimentalApi
suspend fun <T : NodeObject, C : SelectiveNodeExecutionContext<T>, K> batchByCustomGrouping(
    contexts: List<C>,
    groupBy: (SelectionSet<T>) -> K,
    resolve: suspend (Group<T, C, K>) -> Map<C, FieldValue<T>>,
): Map<C, FieldValue<T>> {
    val groups = contexts.groupBy { groupBy(it.selections()) }
    if (groups.isEmpty()) return emptyMap()
    if (groups.size == 1) {
        val (key, groupContexts) = groups.entries.single()
        return resolveGroup(key, groupContexts, resolve)
    }
    val groupResults = coroutineScope {
        groups.map { (key, groupContexts) -> async { resolveGroup(key, groupContexts, resolve) } }.awaitAll()
    }
    return linkedMapOf<C, FieldValue<T>>().apply { groupResults.forEach { putAll(it) } }
}

private suspend fun <T : NodeObject, C : SelectiveNodeExecutionContext<T>, K> resolveGroup(
    key: K,
    groupContexts: List<C>,
    resolve: suspend (Group<T, C, K>) -> Map<C, FieldValue<T>>,
): Map<C, FieldValue<T>> {
    val allowedContexts = groupContexts.toSet()
    // resultOfSuspend catches only Exception, so the cast below cannot fail.
    return resultOfSuspend {
        resolve(GroupImpl(groupContexts, key)).onEach { (context, _) ->
            require(context in allowedContexts) {
                "Batch group returned a context that was not a member of that group"
            }
        }
    }.getOrElse { failure -> groupContexts.associateWith { FieldValue.ofError(failure as Exception) } }
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
) : InternalSelectionSet<T> {
    init {
        require(members.isNotEmpty()) {
            "Cannot construct a selection view without members"
        }
        require(members.all { it.internalType() == members.first().internalType() }) {
            "All selections in a batch view must describe the same type"
        }
    }

    override val type: Type<T> = members.first().internalType()

    override fun selectedFieldCoordinates(): Set<FieldCoordinate> = members.flatMapTo(linkedSetOf()) { it.selectedFieldCoordinates() }

    override fun <U : T> contains(field: Field<U>): Boolean = members.any { it.contains(field) }

    override fun <U : T> requestsType(type: Type<U>): Boolean = members.any { it.requestsType(type) }

    override fun <U : T, R : CompositeOutput> selectionSetFor(field: viaduct.api.reflect.CompositeField<U, R>): SelectionSet<R> = AnySelections(members.map { it.selectionSetFor(field) })
}
