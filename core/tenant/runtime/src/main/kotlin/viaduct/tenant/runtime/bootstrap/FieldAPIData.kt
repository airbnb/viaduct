package viaduct.tenant.runtime.bootstrap

data class FieldAPIData(
    val resolverClass: String,
    val resolverBaseClass: String,
    val returnTypeName: String?,
    val hasArguments: Boolean,
    val queryTypeName: String,
)

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.toFieldAPIData(): FieldAPIData =
    FieldAPIData(
        resolverClass = this["resolverClass"] as String,
        resolverBaseClass = this["resolverBaseClass"] as String,
        returnTypeName = this["returnTypeName"] as String?,
        hasArguments = this["hasArguments"] as? Boolean ?: false,
        queryTypeName = this["queryTypeName"] as String,
    )
