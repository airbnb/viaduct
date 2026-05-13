package viaduct.api

import viaduct.apiannotations.InternalApi

/**
 * Marker interface for a Viaduct tenant module.
 *
 * Each tenant module that contributes resolvers to the Viaduct schema implements this
 * interface (typically via the generated bootstrapper). Framework code uses it to discover
 * and load modules at startup. Application developers do not implement this directly.
 */
@InternalApi
interface TenantModule {
    /** Metadata to be associated with this module. */
    val metadata: Map<String, String>

    /** The package name for the module */
    val packageName: String
        get() = javaClass.`package`.name
}
