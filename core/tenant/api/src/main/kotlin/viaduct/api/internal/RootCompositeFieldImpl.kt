package viaduct.api.internal

import viaduct.api.reflect.RootCompositeField
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.GRT
import viaduct.apiannotations.InternalApi

@InternalApi
class RootCompositeFieldImpl<Parent : GRT, UnwrappedType : CompositeOutput, A : Arguments>(
    override val name: String,
    override val containingType: Type<Parent>,
    override val type: Type<UnwrappedType>
) : RootCompositeField<Parent, UnwrappedType, A>
