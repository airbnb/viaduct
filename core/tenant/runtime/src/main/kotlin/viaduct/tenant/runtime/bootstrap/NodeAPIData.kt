package viaduct.tenant.runtime.bootstrap

data class NodeAPIData(
    val resolverClass: String,
    val resolverBaseClass: String,
)

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.toNodeAPIData(): NodeAPIData =
    NodeAPIData(
        resolverClass = this["resolverClass"] as String,
        resolverBaseClass = this["resolverBaseClass"] as String,
    )
