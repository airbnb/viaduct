package viaduct.api.reflect

import viaduct.api.types.Arguments
import viaduct.api.types.GRT
import viaduct.api.types.Object
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * A RootObjectField describes static properties of a field on a root query field (a field on the
 * root query type or a namespace type reachable from the root query type), whose output type is a
 * non-list object type.
 *
 * The [A] type parameter captures the field's arguments type for compile-time type safety
 * in the generated root field call that `ctx.ref` resolves.
 */
@ExperimentalApi
interface RootObjectField<Parent : GRT, UnwrappedType : Object, out A : Arguments> :
    CompositeField<Parent, UnwrappedType> {
    /**
     * Path of field names from the query root (optionally through `@namespaceType` types) to this field.
     * For example, `["_factories", "products", "create"]` for a field `create` on a namespace
     * type reachable via `Query._factories.products`.
     */
    @InternalApi
    val pathFromQueryRoot: List<String>
}
