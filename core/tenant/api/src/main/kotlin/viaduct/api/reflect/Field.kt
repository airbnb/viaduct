package viaduct.api.reflect

import viaduct.api.types.FieldPresenceProbe
import viaduct.api.types.GRT
import viaduct.api.types.InputLike
import viaduct.apiannotations.StableApi

/** A Field describes static properties of a GraphQL field */
@StableApi
interface Field<Parent : GRT> {
    /** the GraphQL name of this field */
    val name: String

    /** the descriptor of the type that this field is mounted on */
    val containingType: Type<Parent>
}

/**
 * Returns whether this input contains a value for [field] after GraphQL defaults are applied.
 * Explicit `null` counts as present. An omitted field with a schema default is present; an omitted
 * field without a default is absent.
 *
 * Defined as an extension on [InputLike] ([Input][viaduct.api.types.Input] /
 * [Arguments][viaduct.api.types.Arguments]) so it is discoverable via completion on an input
 * instance and cannot be called on output-object types.
 */
@StableApi
fun <Parent : InputLike> Parent.isPresent(field: Field<Parent>): Boolean = (this as? FieldPresenceProbe)?.isFieldPresent(field.name) ?: false
