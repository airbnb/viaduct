package viaduct.api.reflect

import viaduct.api.types.GRT
import viaduct.apiannotations.StableApi

/**
 * A CompositeField describes static properties of a GraphQL field,
 * with an output type that is a [GRT].
 */
@StableApi
interface CompositeField<Parent : GRT, UnwrappedType : GRT> : Field<Parent> {
    /** the descriptor of the type of this field, with list wrappers removed */
    val type: Type<UnwrappedType>
}
