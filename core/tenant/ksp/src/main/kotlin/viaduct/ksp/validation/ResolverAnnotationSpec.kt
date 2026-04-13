package viaduct.ksp.validation

/**
 * Data extracted from a @Resolver annotation at compile-time
 */
internal data class ResolverAnnotationSpec(
    val fragments: List<ResolverFragmentSpec>,
    val variables: List<ResolverVariableSpec>,
    val metadata: Metadata,
    val variablesProviderVarNames: Set<String> = emptySet(),
)
