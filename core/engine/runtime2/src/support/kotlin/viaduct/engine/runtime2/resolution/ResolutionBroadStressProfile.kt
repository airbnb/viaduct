package viaduct.engine.runtime2.resolution

import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.DuplicateSelectionWeight
import viaduct.engine.runtime2.arbitrary.ErrorValueWeight
import viaduct.engine.runtime2.arbitrary.ExplicitFieldResolverWeight
import viaduct.engine.runtime2.arbitrary.FieldArgumentWeight
import viaduct.engine.runtime2.arbitrary.InputScalarValueRange
import viaduct.engine.runtime2.arbitrary.ListTypeWeight
import viaduct.engine.runtime2.arbitrary.ListValueSize
import viaduct.engine.runtime2.arbitrary.MaxSelectionDepth
import viaduct.engine.runtime2.arbitrary.MinimumSelectionDepth
import viaduct.engine.runtime2.arbitrary.NodeObjectWeight
import viaduct.engine.runtime2.arbitrary.NullValueWeight
import viaduct.engine.runtime2.arbitrary.NullableTypeWeight
import viaduct.engine.runtime2.arbitrary.ObjectFieldCount
import viaduct.engine.runtime2.arbitrary.ParentFieldsEnabled
import viaduct.engine.runtime2.arbitrary.QueryFieldCount
import viaduct.engine.runtime2.arbitrary.QueryScalarFieldWeight
import viaduct.engine.runtime2.arbitrary.ResolverArgumentErrorWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentDepth
import viaduct.engine.runtime2.arbitrary.ResolverFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentNestedPathWeight
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldPassiveUseWeight
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldProviderArgumentVariableWeight
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldProviderPathLength
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldVariableOwnerLimit
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldVariableOwnerUseWeight
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldVariableUseDepth
import viaduct.engine.runtime2.arbitrary.ResolverFromProviderVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromQueryFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverLiteralVariableConvergenceWeight
import viaduct.engine.runtime2.arbitrary.ResolverNestedProviderPathWeight
import viaduct.engine.runtime2.arbitrary.ResolverQueryFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverQueryFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariableCount
import viaduct.engine.runtime2.arbitrary.ResolverVariableSingletonCoercionEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariableWeight
import viaduct.engine.runtime2.arbitrary.ResolverVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariablesOnNonQueryFieldsOnly
import viaduct.engine.runtime2.arbitrary.RootFieldReferenceWeight
import viaduct.engine.runtime2.arbitrary.RootFieldReferencesEnabled
import viaduct.engine.runtime2.arbitrary.RootQueryFieldCount
import viaduct.engine.runtime2.arbitrary.SchemaObjectCount
import viaduct.engine.runtime2.arbitrary.SometimesPassiveFieldWeight

// Defines orthogonal generated-world distributions for Resolution's observable semantics.
internal enum class ResolutionBroadStressProfile(
    val id: String,
    val propertyProfile: String,
    val defaultSize: String,
    val requiredSignatures: Set<ResolutionStructuralSignature>,
    val config: Config,
) {
    BALANCED(
        id = "balanced",
        propertyProfile = "resolution-broad-stress",
        defaultSize = "10:20:50",
        requiredSignatures =
            setOf(
                ResolutionStructuralSignature.SYMBOLIC_RESOLVER_INSTANCE,
                ResolutionStructuralSignature.OBJECT_PATH_VARIABLE_OWNER,
                ResolutionStructuralSignature.PROVIDER_VARIABLE_OWNER,
                ResolutionStructuralSignature.MIXED_BINDING_SOURCES,
                ResolutionStructuralSignature.ABSTRACT_PROVIDER_PATH,
                ResolutionStructuralSignature.GREAT_GRANDPARENT_PARENT_DEMAND,
            ),
        config = balancedBroadConfig(),
    ),
    DESCENDANT_VARIABLES(
        id = "descendant-variables",
        propertyProfile = "resolution-broad-descendant-variables",
        defaultSize = "40:25:10",
        requiredSignatures =
            setOf(
                ResolutionStructuralSignature.NESTED_VARIABLE_USE,
                ResolutionStructuralSignature.PASSIVE_DESCENDANT_VARIABLE_USE,
                ResolutionStructuralSignature.LIST_SYMBOLIC_RESOLVER_INSTANCE,
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
        propertyProfile = "resolution-broad-nullable-errors",
        defaultSize = "10:20:50",
        requiredSignatures =
            setOf(
                ResolutionStructuralSignature.NESTED_PROVIDER_PATH,
                ResolutionStructuralSignature.NULL_PROVIDER_INTERMEDIATE,
                ResolutionStructuralSignature.ERROR_PROVIDER_INTERMEDIATE,
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
        propertyProfile = "resolution-broad-symbolic-identity",
        defaultSize = "10:20:50",
        requiredSignatures =
            setOf(
                ResolutionStructuralSignature.EQUAL_SYMBOLIC_ARGUMENTS,
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
        propertyProfile = "resolution-broad-multiple-owners",
        defaultSize = "10:50:20",
        requiredSignatures =
            setOf(
                ResolutionStructuralSignature.MULTIPLE_OBJECT_PATH_OWNERS,
                ResolutionStructuralSignature.OBJECT_PATH_OWNER_DEPENDENCY,
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
        fun fromConfigured(configured: String): ResolutionBroadStressProfile =
            entries.singleOrNull { profile ->
                configured == profile.id || configured == profile.propertyProfile
            } ?: error(
                "Unknown Resolution broad stress profile $configured; profiles=" +
                    entries.joinToString { profile -> profile.id },
            )
    }
}

// Enlarges world depth while bounding list fanout so a stress round remains finite and diagnosable.
internal fun Config.withLargeDeepResolutionWorlds(): Config =
    this +
        (MinimumSelectionDepth to 4) +
        (MaxSelectionDepth to 6) +
        (ListValueSize to 1..1) +
        (SchemaObjectCount to 8..12) +
        (ObjectFieldCount to 6..10) +
        (QueryFieldCount to 10..14) +
        (RootQueryFieldCount to 8..12) +
        (ResolverFragmentDepth to 5)

// Returns the broad baseline from which directed Resolution profiles apply pressure.
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
