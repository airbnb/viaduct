package semantics.resolver26

import semantics.arbitrary.Config
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.InputScalarValueRange
import semantics.arbitrary.ListTypeWeight
import semantics.arbitrary.ListValueSize
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.MinimumSelectionDepth
import semantics.arbitrary.NodeObjectWeight
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.ParentFieldsEnabled
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.QueryScalarFieldWeight
import semantics.arbitrary.ResolverArgumentErrorWeight
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentNestedPathWeight
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverFromFieldPassiveUseWeight
import semantics.arbitrary.ResolverFromFieldProviderArgumentVariableWeight
import semantics.arbitrary.ResolverFromFieldProviderPathLength
import semantics.arbitrary.ResolverFromFieldVariableOwnerLimit
import semantics.arbitrary.ResolverFromFieldVariableOwnerUseWeight
import semantics.arbitrary.ResolverFromFieldVariableUseDepth
import semantics.arbitrary.ResolverFromProviderVariablesEnabled
import semantics.arbitrary.ResolverFromQueryFieldVariablesEnabled
import semantics.arbitrary.ResolverLiteralVariableConvergenceWeight
import semantics.arbitrary.ResolverNestedProviderPathWeight
import semantics.arbitrary.ResolverQueryFragmentWeight
import semantics.arbitrary.ResolverQueryFragmentsEnabled
import semantics.arbitrary.ResolverVariableCount
import semantics.arbitrary.ResolverVariableSingletonCoercionEnabled
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.ResolverVariablesOnNonQueryFieldsOnly
import semantics.arbitrary.RootFieldReferenceWeight
import semantics.arbitrary.RootFieldReferencesEnabled
import semantics.arbitrary.RootQueryFieldCount
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.SometimesPassiveFieldWeight

// Defines orthogonal generated-world distributions for Resolver26's observable semantics.
internal enum class Resolver26BroadStressProfile(
    val id: String,
    val propertyProfile: String,
    val defaultSize: String,
    val requiredSignatures: Set<Resolver26StructuralSignature>,
    val config: Config,
) {
    BALANCED(
        id = "balanced",
        propertyProfile = "resolver26-broad-stress",
        defaultSize = "10:20:50",
        requiredSignatures =
            setOf(
                Resolver26StructuralSignature.SYMBOLIC_RESOLVER_INSTANCE,
                Resolver26StructuralSignature.OBJECT_PATH_VARIABLE_OWNER,
                Resolver26StructuralSignature.PROVIDER_VARIABLE_OWNER,
                Resolver26StructuralSignature.MIXED_BINDING_SOURCES,
                Resolver26StructuralSignature.ABSTRACT_PROVIDER_PATH,
                Resolver26StructuralSignature.GREAT_GRANDPARENT_PARENT_DEMAND,
            ),
        config = balancedBroadConfig(),
    ),
    DESCENDANT_VARIABLES(
        id = "descendant-variables",
        propertyProfile = "resolver26-broad-descendant-variables",
        defaultSize = "40:25:10",
        requiredSignatures =
            setOf(
                Resolver26StructuralSignature.NESTED_VARIABLE_USE,
                Resolver26StructuralSignature.PASSIVE_DESCENDANT_VARIABLE_USE,
                Resolver26StructuralSignature.LIST_SYMBOLIC_RESOLVER_INSTANCE,
            ),
        config =
            balancedBroadConfig() +
                (ExplicitFieldResolverWeight to 0.5) +
                (ListTypeWeight to 0.65) +
                (ListValueSize to 1..2) +
                (ResolverVariableWeight to 0.9) +
                (ResolverFromFieldPassiveUseWeight to 1.0) +
                (ResolverFromFieldProviderArgumentVariableWeight to 1.0) +
                (ResolverFromFieldVariableUseDepth to 2..4) +
                (ResolverVariablesOnNonQueryFieldsOnly to true),
    ),
    NULLABLE_ERRORS(
        id = "nullable-errors",
        propertyProfile = "resolver26-broad-nullable-errors",
        defaultSize = "10:20:50",
        requiredSignatures =
            setOf(
                Resolver26StructuralSignature.NESTED_PROVIDER_PATH,
                Resolver26StructuralSignature.NULL_PROVIDER_INTERMEDIATE,
                Resolver26StructuralSignature.ERROR_PROVIDER_INTERMEDIATE,
            ),
        config =
            balancedBroadConfig() +
                (NullableTypeWeight to 0.75) +
                (NullValueWeight to 0.45) +
                (ErrorValueWeight to 0.45) +
                (ResolverArgumentErrorWeight to 0.15) +
                (ResolverNestedProviderPathWeight to 1.0) +
                (ResolverFromFieldProviderPathLength to 2..4),
    ),
    SYMBOLIC_IDENTITY(
        id = "symbolic-identity",
        propertyProfile = "resolver26-broad-symbolic-identity",
        defaultSize = "10:20:50",
        requiredSignatures =
            setOf(
                Resolver26StructuralSignature.EQUAL_SYMBOLIC_ARGUMENTS,
            ),
        config =
            balancedBroadConfig() +
                (DuplicateSelectionWeight to 0.5) +
                (FieldArgumentWeight to 0.9) +
                (InputScalarValueRange to 0..1) +
                (ResolverVariableWeight to 0.95) +
                (ResolverVariableCount to 2..4) +
                (ResolverLiteralVariableConvergenceWeight to 0.75),
    ),
    MULTIPLE_OWNERS(
        id = "multiple-owners",
        propertyProfile = "resolver26-broad-multiple-owners",
        defaultSize = "10:50:20",
        requiredSignatures =
            setOf(
                Resolver26StructuralSignature.MULTIPLE_OBJECT_PATH_OWNERS,
                Resolver26StructuralSignature.OBJECT_PATH_OWNER_DEPENDENCY,
            ),
        config =
            balancedBroadConfig() +
                (RootQueryFieldCount to 6..8) +
                (ResolverVariableWeight to 0.9) +
                (ResolverVariableCount to 1..1) +
                (ResolverFromFieldVariableOwnerLimit to 4) +
                (ResolverFromFieldVariableOwnerUseWeight to 1.0),
    ),
    ;

    companion object {
        // Returns the profile named by either its command-line id or replay profile.
        fun fromConfigured(configured: String): Resolver26BroadStressProfile =
            entries.singleOrNull { profile ->
                configured == profile.id || configured == profile.propertyProfile
            } ?: error(
                "Unknown Resolver26 broad stress profile $configured; profiles=" +
                    entries.joinToString { profile -> profile.id },
            )
    }
}

// Enlarges world depth while bounding list fanout so a stress round remains finite and diagnosable.
internal fun Config.withLargeDeepResolver26Worlds(): Config =
    this +
        (MinimumSelectionDepth to 4) +
        (MaxSelectionDepth to 6) +
        (ListValueSize to 1..1) +
        (SchemaObjectCount to 8..12) +
        (ObjectFieldCount to 6..10) +
        (QueryFieldCount to 10..14) +
        (RootQueryFieldCount to 8..12) +
        (ResolverFragmentDepth to 5)

// Returns the broad baseline from which directed Resolver26 profiles apply pressure.
private fun balancedBroadConfig(): Config =
    Config.default +
        (MinimumSelectionDepth to 2) +
        (MaxSelectionDepth to 6) +
        (SchemaObjectCount to 5..7) +
        (ObjectFieldCount to 4..6) +
        (QueryFieldCount to 6..8) +
        (RootQueryFieldCount to 4..6) +
        (DuplicateSelectionWeight to 0.2) +
        (FieldArgumentWeight to 0.65) +
        (ExplicitFieldResolverWeight to 0.8) +
        (InputScalarValueRange to 0..4) +
        (ListTypeWeight to 0.25) +
        (ListValueSize to 0..2) +
        (NullableTypeWeight to 0.35) +
        (NullValueWeight to 0.15) +
        (ErrorValueWeight to 0.08) +
        (NodeObjectWeight to 0.2) +
        (QueryScalarFieldWeight to 0.2) +
        (ResolverFragmentsEnabled to true) +
        (ResolverQueryFragmentsEnabled to true) +
        (ParentFieldsEnabled to true) +
        (RootFieldReferencesEnabled to true) +
        (RootFieldReferenceWeight to 0.2) +
        (ResolverQueryFragmentWeight to 0.1) +
        (ResolverFragmentWeight to 0.8) +
        (ResolverFragmentDepth to 3) +
        (ResolverArgumentErrorWeight to 0.05) +
        (ResolverFromArgumentNestedPathWeight to 0.5) +
        (ResolverFromArgumentVariablesEnabled to true) +
        (ResolverFromProviderVariablesEnabled to true) +
        (ResolverVariablesEnabled to true) +
        (ResolverFromQueryFieldVariablesEnabled to true) +
        (ResolverVariableWeight to 0.65) +
        (ResolverVariableCount to 1..3) +
        (ResolverVariableSingletonCoercionEnabled to true) +
        (ResolverLiteralVariableConvergenceWeight to 0.2) +
        (ResolverNestedProviderPathWeight to 0.5) +
        (ResolverFromFieldProviderPathLength to 1..3) +
        (ResolverFromFieldVariableUseDepth to 1..3) +
        (ResolverFromFieldVariableOwnerLimit to 4) +
        (ResolverFromFieldPassiveUseWeight to 0.25) +
        (ResolverFromFieldVariableOwnerUseWeight to 0.25) +
        (SometimesPassiveFieldWeight to 0.25)
