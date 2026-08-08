package viaduct.api.batch

import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import viaduct.apiannotations.ExperimentalApi

/** A subset of selective node batch contexts that share a selection-derived key. */
@ExperimentalApi
interface Group<
    T : NodeObject,
    C : SelectiveNodeExecutionContext<T>,
    out K,
> {
    /** The original contexts in this group, in input order. */
    val contexts: List<C>

    /** The value that formed this group. */
    val key: K

    /** A read-only any-member view over this group's selections. */
    val selections: SelectionSet<T>
}
