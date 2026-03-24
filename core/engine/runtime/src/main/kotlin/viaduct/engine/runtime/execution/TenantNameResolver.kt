package viaduct.engine.runtime.execution

/*
    This class is used for overriding the tenant name within the codebase.
    The main use case is to classify a field as deprecated.
 */
open class TenantNameResolver {
    open fun resolve(
        typeName: String,
        fieldName: String
    ): String? = null
}
