package viaduct.api.types

import viaduct.apiannotations.StableApi

/**
 * Tagging interface for output types that have fields, i.e. interfaces, objects, and unions
 */
@StableApi
interface CompositeOutput : GRT {
    /**
     * A marker object indicating that a type does not support selections
     * NotComposite may be used in places where a CompositeOutput type is required by the compiler
     * but one is not available.
     */
    object NotComposite : CompositeOutput
}
