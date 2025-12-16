package viaduct.api.types

import viaduct.apiannotations.StableApi

/**
 * Tagging interface for virtual input types that wrap field arguments
 */
@StableApi
interface Arguments : InputLike {
    /** A marker object indicating the lack of schematic arguments */
    object NoArguments : Arguments
}
