package viaduct.api.reflect

import viaduct.api.types.GRT
import viaduct.apiannotations.StableApi

/** A Field describes static properties of a GraphQL field */
@StableApi
interface Field<Parent : GRT> {
    /** the GraphQL name of this field */
    val name: String

    /** the descriptor of the type that this field is mounted on */
    val containingType: Type<Parent>
}
