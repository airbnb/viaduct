package viaduct.api.internal

import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.types.GRT
import viaduct.apiannotations.InternalApi

@InternalApi
class FieldImpl<Parent : GRT>(
    override val name: String,
    override val containingType: Type<Parent>
) : Field<Parent>
