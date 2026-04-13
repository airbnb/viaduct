package viaduct.ksp.validation

internal data class ResolverVariableSpec(
    val name: String,
    val fromObjectField: String?,
    val fromQueryField: String?,
    val fromArgument: String?,
)
