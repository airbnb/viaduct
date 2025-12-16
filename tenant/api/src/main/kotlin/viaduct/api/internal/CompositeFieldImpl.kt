package viaduct.api.internal

import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Type
import viaduct.api.types.GRT
import viaduct.apiannotations.InternalApi

@InternalApi
class CompositeFieldImpl<Parent : GRT, UnwrappedType : GRT>(
    override val name: String,
    override val containingType: Type<Parent>,
    override val type: Type<UnwrappedType>
) : CompositeField<Parent, UnwrappedType>
