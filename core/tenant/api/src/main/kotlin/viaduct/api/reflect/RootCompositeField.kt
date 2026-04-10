package viaduct.api.reflect

import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.GRT
import viaduct.apiannotations.StableApi

/**
 * A RootCompositeField describes static properties of a field on a root query or namespace type
 * whose output type is a non-list [CompositeOutput]. Unlike [CompositeField], which may represent
 * list-wrapped fields (with list wrappers stripped from [type]), RootCompositeField is only emitted
 * for fields whose return type is directly composite (not wrapped in a list).
 * The [A] type parameter captures the field's arguments type for compile-time type safety
 * in `ctx.rootFieldRef(field, args)`.
 */
@StableApi
interface RootCompositeField<Parent : GRT, UnwrappedType : CompositeOutput, A : Arguments> :
    CompositeField<Parent, UnwrappedType>
