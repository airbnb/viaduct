package viaduct.api.internal

import viaduct.api.reflect.RootObjectField
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.GRT
import viaduct.api.types.Object
import viaduct.apiannotations.InternalApi

@InternalApi
class RootObjectFieldImpl<Parent : GRT, UnwrappedType : Object, A : Arguments>(
    override val name: String,
    override val containingType: Type<Parent>,
    override val type: Type<UnwrappedType>,
    override val pathFromQueryRoot: List<String>
) : RootObjectField<Parent, UnwrappedType, A> {
    init {
        require(pathFromQueryRoot.isNotEmpty() && pathFromQueryRoot.last() == name) {
            "pathFromQueryRoot must end with the field name '$name', but was $pathFromQueryRoot"
        }
    }
}
