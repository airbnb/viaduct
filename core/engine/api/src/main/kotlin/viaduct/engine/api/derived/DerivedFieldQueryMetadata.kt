package viaduct.engine.api.derived

data class DerivedFieldQueryMetadata(
    val queryName: String,
    val rootFieldName: String,
    val classPath: String,
    val providerShortClasspath: String,
    val onRootQuery: Boolean,
    val onRootMutation: Boolean,
    val allowMutationOnQuery: Boolean,
    val fieldOwningTenant: String?,
    val forceEngineResolution: Boolean = false
)
