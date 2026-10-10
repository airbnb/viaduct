package viaduct.engine.runtime2.arbitrary

/**
 * The shared selective resolver benchmark profile: node and field resolvers, object and Query
 * fragments, every supported variable source, singleton coercion, and selection-aware nodes.
 */
fun resolverBenchmarkFullConfig(): Config =
    Config.default +
        (QueryFragmentsEnabled to true) +
        (MinimumSelectionDepth to 4) +
        (MaxSelectionDepth to 6) +
        (SchemaObjectCount to 4..5) +
        (ObjectFieldCount to 3..5) +
        (QueryFieldCount to 2..4) +
        (FieldArgumentWeight to 0.65) +
        (ExplicitFieldResolverWeight to 0.7) +
        (NullableTypeWeight to 0.15) +
        (NullValueWeight to 0.05) +
        (ErrorValueWeight to 0.02) +
        (ResolverFragmentsEnabled to true) +
        (ResolverFragmentWeight to 0.85) +
        (ResolverFragmentDepth to 3) +
        (ResolverQueryFragmentsEnabled to true) +
        (ResolverQueryFragmentWeight to 0.2) +
        (NodeResolversEnabled to true) +
        (SelectiveNodeResolversEnabled to true) +
        (NodeObjectWeight to 0.05) +
        (ResolverFromArgumentVariablesEnabled to true) +
        (ResolverFromProviderVariablesEnabled to true) +
        (ResolverFromQueryFieldVariablesEnabled to true) +
        (ResolverVariableSingletonCoercionEnabled to true) +
        (ResolverVariablesEnabled to true)

/**
 * Generates large candidate schema/registry pairs for the fixed overhead corpus.
 */
fun resolverBenchmarkCorpusSearchConfig(): Config =
    Config.default +
        (QueryFragmentsEnabled to true) +
        (MinimumSelectionDepth to 8) +
        (MaxSelectionDepth to 10) +
        (SchemaObjectCount to 12..18) +
        (ObjectFieldCount to 14..18) +
        (QueryFieldCount to 12..18) +
        (RootQueryFieldCount to 6..8) +
        (NestedQueryFieldCount to 8..10) +
        (NestedQueryScalarFieldWeight to 1.0) +
        (QueryScalarFieldWeight to 0.15) +
        (ObjectOutputFieldWeight to 0.15) +
        (FieldArgumentWeight to 0.1) +
        (ExplicitFieldResolverWeight to 0.025) +
        (ListTypeWeight to 0.4) +
        (ListValueSize to 2..2) +
        (NullableTypeWeight to 0.2) +
        (NullValueWeight to 0.05) +
        (ErrorValueWeight to 0.02) +
        (DuplicateSelectionWeight to 0.1) +
        (AliasWeight to 0.25) +
        (ResolverFragmentsEnabled to true) +
        (ResolverFragmentWeight to 0.9) +
        (ResolverFragmentDepth to 8) +
        (ResolverQueryFragmentsEnabled to true) +
        (ResolverQueryFragmentWeight to 0.2) +
        (ResolverFragmentSelectionCount to 1..1) +
        (ResolverFragmentLongTailWeight to 0.1) +
        (ResolverFragmentLongTailSelectionCount to 10..35) +
        (ResolverFragmentArgumentFieldWeight to 1.0) +
        (NodeResolversEnabled to true) +
        (SelectiveNodeResolversEnabled to true) +
        (NodeObjectWeight to 0.3) +
        (ResolverFromArgumentVariablesEnabled to true) +
        (ResolverFromProviderVariablesEnabled to true) +
        (ResolverFromQueryFieldVariablesEnabled to true) +
        (ResolverVariableSingletonCoercionEnabled to true) +
        (ResolverVariablesEnabled to true) +
        (ResolverVariableWeight to 0.95) +
        (ResolverVariableCount to 3..6) +
        (ResolverNestedProviderPathWeight to 0.9) +
        (ResolverFromFieldProviderPathLength to 2..5) +
        (ResolverFromFieldVariableUseDepth to 1..6) +
        (ResolverFromFieldPassiveUseWeight to 0.25) +
        (ResolverFromFieldVariableOwnerLimit to 12) +
        (ResolverFromFieldVariableOwnerUseWeight to 1.0)

/**
 * Produces random overhead queries against the fixed corpus pair.
 */
fun resolverBenchmarkOverheadQueryConfig(): Config =
    resolverBenchmarkCorpusSearchConfig() +
        (MinimumSelectionDepth to 8) +
        (MaxSelectionDepth to 10) +
        (RootQueryFieldCount to 6..8)
