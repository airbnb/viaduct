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
 * Returns whether [field] was explicitly provided on this input.
 *
 * This distinguishes a field that a GraphQL operation actually supplied a value for (including an
 * explicit `null`) from one that was left unset. It is only meaningful for the **top-level** fields
 * of this input: graphql-java applies input coercion — including default values — deep inside the
 * engine for nested input objects, so presence cannot be determined for fields nested any deeper
 * than the receiver itself.
 *
 * Defined as an extension on [InputLike] ([Input][viaduct.api.types.Input] /
 * [Arguments][viaduct.api.types.Arguments]) so it is discoverable via completion on an input
 * instance and cannot be called on output-object types.
 */
@StableApi
fun <Parent : InputLike> Parent.isPresent(field: Field<Parent>): Boolean = (this as? FieldPresenceProbe)?.isFieldPresent(field.name) ?: false
