package viaduct.api

import viaduct.apiannotations.StableApi

@StableApi
interface TenantModule {
    /** Metadata to be associated with this module. */
    val metadata: Map<String, String>

    /** The package name for the module */
    val packageName: String
        get() = javaClass.`package`.name
}
