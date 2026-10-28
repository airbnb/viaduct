package viaduct.engine.runtime2.propertytest

import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.DuplicateSelectionWeight
import viaduct.engine.runtime2.arbitrary.ErrorValueWeight
import viaduct.engine.runtime2.arbitrary.ExplicitFieldResolverWeight
import viaduct.engine.runtime2.arbitrary.FieldArgumentCount
import viaduct.engine.runtime2.arbitrary.FieldArgumentWeight
import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.ImplementationArgumentDefaultWeight
import viaduct.engine.runtime2.arbitrary.InputListTypeWeight
import viaduct.engine.runtime2.arbitrary.InputObjectCount
import viaduct.engine.runtime2.arbitrary.InputObjectTypeWeight
import viaduct.engine.runtime2.arbitrary.ListTypeWeight
import viaduct.engine.runtime2.arbitrary.ListValueSize
import viaduct.engine.runtime2.arbitrary.MaxInputTypeDepth
import viaduct.engine.runtime2.arbitrary.NodeObjectWeight
import viaduct.engine.runtime2.arbitrary.NodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.NullValueWeight
import viaduct.engine.runtime2.arbitrary.NullableTypeWeight
import viaduct.engine.runtime2.arbitrary.ObjectFieldCount
import viaduct.engine.runtime2.arbitrary.ParentFieldsEnabled
import viaduct.engine.runtime2.arbitrary.QueryFieldCount
import viaduct.engine.runtime2.arbitrary.QueryScalarFieldWeight
import viaduct.engine.runtime2.arbitrary.RecursiveOutputEdgesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverArgumentErrorWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentArgumentFieldWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentDepth
import viaduct.engine.runtime2.arbitrary.ResolverFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentNestedPathWeight
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldProviderArgumentVariableWeight
import viaduct.engine.runtime2.arbitrary.ResolverFromObjectFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromProviderVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromQueryFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverNestedProviderPathWeight
import viaduct.engine.runtime2.arbitrary.ResolverQueryFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverQueryFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariableCount
import viaduct.engine.runtime2.arbitrary.ResolverVariableSingletonCoercionEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariableWeight
import viaduct.engine.runtime2.arbitrary.ResolverVariablesEnabled
import viaduct.engine.runtime2.arbitrary.RootFieldReferenceWeight
import viaduct.engine.runtime2.arbitrary.RootFieldReferencesEnabled
import viaduct.engine.runtime2.arbitrary.RootQueryFieldCount
import viaduct.engine.runtime2.arbitrary.SchemaObjectCount
import viaduct.engine.runtime2.arbitrary.SelectiveNodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.SometimesPassiveFieldWeight
import viaduct.engine.runtime2.arbitrary.TestCaseCount

/**
 * Generation inputs for a stored suite; changing any of them requires regenerating its resources.
 * [profile] is the replay profile name, shared by suites that differ only in a family's configuration.
 */
enum class ResolverTestSuiteDefinition(
    val id: String,
    val config: Config,
    val seed: Long = 1L,
    val counts: TestCaseCount = GENERATED_PROFILE_COUNTS,
    val fieldCheckerMode: GeneratedFieldCheckerMode = GeneratedFieldCheckerMode.NONE,
    val typeCheckerMode: GeneratedTypeCheckerMode = GeneratedTypeCheckerMode.NONE,
    val profile: String = id,
) {
    EMPTY_OBJECT_FRAGMENT(
        id = "empty-object-fragment",
        config =
            Config.default +
                (NodeResolversEnabled to false) +
                (ResolverFragmentsEnabled to false) +
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverVariablesEnabled to false),
    ),
    ROOT_FIELD_REFERENCE(
        id = "root-field-reference",
        config =
            Config.default +
                (NodeResolversEnabled to false) +
                (ResolverFragmentsEnabled to false) +
                (ResolverQueryFragmentsEnabled to false) +
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverVariablesEnabled to false) +
                (RootFieldReferencesEnabled to true) +
                (RootFieldReferenceWeight to 0.25),
    ),
    SOMETIMES_PASSIVE(
        id = "sometimes-passive",
        config =
            Config.default +
                (FieldArgumentWeight to 0.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (NodeResolversEnabled to false) +
                (ResolverFragmentsEnabled to false) +
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverVariablesEnabled to false) +
                (SometimesPassiveFieldWeight to 1.0),
    ),
    NODE(
        id = "node",
        config =
            Config.default +
                (FieldArgumentWeight to 1.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (NodeResolversEnabled to true) +
                (NodeObjectWeight to 0.35) +
                (ResolverFragmentsEnabled to false) +
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverVariablesEnabled to false),
    ),
    SELECTIVE_NODE(
        id = "selective-node",
        config =
            Config.default +
                (FieldArgumentWeight to 1.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (NodeResolversEnabled to true) +
                (SelectiveNodeResolversEnabled to true) +
                (NodeObjectWeight to 0.35) +
                (ResolverFragmentsEnabled to false) +
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverVariablesEnabled to false),
    ),
    OBJECT_FRAGMENT(
        id = "object-fragment",
        config =
            Config.default +
                (FieldArgumentWeight to 1.0) +
                (InputListTypeWeight to 0.0) +
                (InputObjectCount to 2..3) +
                (InputObjectTypeWeight to 0.6) +
                (MaxInputTypeDepth to 2) +
                (NullableTypeWeight to 1.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (NodeResolversEnabled to false) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverVariablesEnabled to false),
    ),
    QUERY_FRAGMENT(
        id = "query-fragment",
        config = QUERY_FRAGMENT_CONFIG,
    ),
    RESOLUTION_QUERY_FRAGMENT(
        id = "resolution-query-fragment",
        config =
            QUERY_FRAGMENT_CONFIG +
                (ResolverVariablesEnabled to true) +
                (ResolverFromQueryFieldVariablesEnabled to true) +
                RESOLUTION_CONFIG_OVERRIDES,
        profile = "query-fragment",
    ),
    OBJECT_FRAGMENT_FROM_ARGUMENT(
        id = "object-fragment-from-argument",
        config = FROM_ARGUMENT_CONFIG,
    ),
    RESOLUTION_OBJECT_FRAGMENT_FROM_ARGUMENT(
        id = "resolution-object-fragment-from-argument",
        config = FROM_ARGUMENT_CONFIG + RESOLUTION_CONFIG_OVERRIDES,
        profile = "object-fragment-from-argument",
    ),
    RESOLUTION_OBJECT_FRAGMENT_FROM_OBJECT_FIELD(
        id = "resolution-object-fragment-from-object-field",
        config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.5) +
                (ExplicitFieldResolverWeight to 1.0) +
                (NodeResolversEnabled to false) +
                (QueryFieldCount to 6..6) +
                (RootQueryFieldCount to 10..10) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverNestedProviderPathWeight to 1.0) +
                (ResolverFromFieldProviderArgumentVariableWeight to 1.0) +
                (ResolverVariableCount to 2..4) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariablesEnabled to true) +
                RESOLUTION_CONFIG_OVERRIDES,
        profile = "object-fragment-from-object-field",
    ),
    RESOLUTION_FROM_PROVIDER(
        id = "resolution-from-provider",
        config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 1.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (NodeResolversEnabled to false) +
                (QueryFieldCount to 6..6) +
                (RootQueryFieldCount to 10..10) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFragmentArgumentFieldWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverFromProviderVariablesEnabled to true) +
                (ResolverVariableCount to 2..4) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariablesEnabled to false) +
                RESOLUTION_CONFIG_OVERRIDES,
        profile = "from-provider",
    ),
    RESOLUTION_MIXED_VARIABLES(
        id = "resolution-mixed-variables",
        config =
            Config.default +
                (SchemaObjectCount to 3..4) +
                (ObjectFieldCount to 3..4) +
                (FieldArgumentWeight to 1.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (NodeResolversEnabled to false) +
                (QueryFieldCount to 4..4) +
                (RootQueryFieldCount to 10..10) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariablesEnabled to true) +
                RESOLUTION_CONFIG_OVERRIDES,
        profile = "mixed-variables",
    ),
    FEATURE_INTERACTION(
        id = "feature-interaction",
        counts = FEATURE_INTERACTION_PROFILE_COUNTS,
        config = FEATURE_INTERACTION_CONFIG,
    ),
    RESOLUTION_FEATURE_INTERACTION(
        id = "resolution-feature-interaction",
        counts = FEATURE_INTERACTION_PROFILE_COUNTS,
        config = FEATURE_INTERACTION_CONFIG + RESOLUTION_CONFIG_OVERRIDES,
        profile = "feature-interaction",
    ),
    RESOLVER23_FIELD_CHECKER_SUCCESS(
        id = "resolver23-field-checker-success",
        seed = 424242L,
        config = fieldCheckerConfig(runtime = false),
        fieldCheckerMode = GeneratedFieldCheckerMode.SUCCESS,
    ),
    RESOLVER23_FIELD_CHECKER_DENIAL(
        id = "resolver23-field-checker-denial",
        seed = 424242L,
        config = fieldCheckerConfig(runtime = false),
        fieldCheckerMode = GeneratedFieldCheckerMode.DENIAL,
    ),
    RESOLVER23_FIELD_CHECKER_MIXED(
        id = "resolver23-field-checker-mixed",
        seed = 424242L,
        config = fieldCheckerConfig(runtime = false),
        fieldCheckerMode = GeneratedFieldCheckerMode.MIXED,
    ),
    RESOLVER23_FIELD_CHECKER_PASSIVE(
        id = "resolver23-field-checker-passive",
        config = passiveFieldCheckerConfig(runtime = false),
        fieldCheckerMode = GeneratedFieldCheckerMode.SUCCESS,
    ),
    RESOLVER23_FIELD_CHECKER_ROOT_REFERENCE(
        id = "resolver23-field-checker-root-reference",
        seed = 424242L,
        config = rootReferenceFieldCheckerConfig(runtime = false),
        fieldCheckerMode = GeneratedFieldCheckerMode.SUCCESS,
    ),
    RESOLVER23_TYPE_CHECKER_SUCCESS(
        id = "resolver23-type-checker-success",
        config = typeCheckerConfig(runtime = false),
        fieldCheckerMode = GeneratedFieldCheckerMode.MIXED,
        typeCheckerMode = GeneratedTypeCheckerMode.SUCCESS,
    ),
    RESOLVER23_TYPE_CHECKER_DENIAL(
        id = "resolver23-type-checker-denial",
        config = typeCheckerConfig(runtime = false),
        fieldCheckerMode = GeneratedFieldCheckerMode.MIXED,
        typeCheckerMode = GeneratedTypeCheckerMode.DENIAL,
    ),
    RESOLVER23_TYPE_CHECKER_MIXED(
        id = "resolver23-type-checker-mixed",
        config = typeCheckerConfig(runtime = false),
        fieldCheckerMode = GeneratedFieldCheckerMode.MIXED,
        typeCheckerMode = GeneratedTypeCheckerMode.MIXED,
    ),
    RESOLUTION_FIELD_CHECKER_SUCCESS(
        id = "resolution-field-checker-success",
        seed = 424242L,
        config = fieldCheckerConfig(runtime = true),
        fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_SUCCESS,
    ),
    RESOLUTION_FIELD_CHECKER_DENIAL(
        id = "resolution-field-checker-denial",
        seed = 424242L,
        config = fieldCheckerConfig(runtime = true),
        fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_DENIAL,
    ),
    RESOLUTION_FIELD_CHECKER_MIXED(
        id = "resolution-field-checker-mixed",
        seed = 424242L,
        config = fieldCheckerConfig(runtime = true),
        fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_MIXED,
    ),
    RESOLUTION_FIELD_CHECKER_PASSIVE(
        id = "resolution-field-checker-passive",
        config = passiveFieldCheckerConfig(runtime = true),
        fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_SUCCESS,
    ),
    RESOLUTION_FIELD_CHECKER_ROOT_REFERENCE(
        id = "resolution-field-checker-root-reference",
        seed = 424242L,
        config = rootReferenceFieldCheckerConfig(runtime = true),
        fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_SUCCESS,
    ),
    RESOLUTION_TYPE_CHECKER_SUCCESS(
        id = "resolution-type-checker-success",
        config = typeCheckerConfig(runtime = true),
        fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_MIXED,
        typeCheckerMode = GeneratedTypeCheckerMode.RUNTIME_SUCCESS,
    ),
    RESOLUTION_TYPE_CHECKER_DENIAL(
        id = "resolution-type-checker-denial",
        config = typeCheckerConfig(runtime = true),
        fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_MIXED,
        typeCheckerMode = GeneratedTypeCheckerMode.RUNTIME_DENIAL,
    ),
    RESOLUTION_TYPE_CHECKER_MIXED(
        id = "resolution-type-checker-mixed",
        config = typeCheckerConfig(runtime = true),
        fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_MIXED,
        typeCheckerMode = GeneratedTypeCheckerMode.RUNTIME_MIXED,
    ),
    CONSTRUCTION_WITNESS(
        id = "construction-witness",
        seed = 1L,
        counts = TestCaseCount(schemas = 12, registriesPerSchema = 2, queriesPerSchema = 4),
        config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.8) +
                (ExplicitFieldResolverWeight to 0.8) +
                (DuplicateSelectionWeight to 0.8) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (NodeResolversEnabled to false),
    ),
    LIST_PASSIVE_DEEPENING(
        id = "list-passive-deepening",
        seed = 817_206L,
        counts = TestCaseCount(schemas = 12, registriesPerSchema = 2, queriesPerSchema = 4),
        config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.0) +
                (ExplicitFieldResolverWeight to 0.8) +
                (DuplicateSelectionWeight to 0.8) +
                (ListTypeWeight to 1.0) +
                (ListValueSize to 2..3) +
                (NullValueWeight to 0.0) +
                (ErrorValueWeight to 0.0) +
                (RecursiveOutputEdgesEnabled to false) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (NodeResolversEnabled to false),
    ),
    RESOLVER_MUTATION(
        id = "resolver-mutation",
        seed = 303_303L,
        counts = TestCaseCount(schemas = 12, registriesPerSchema = 3, queriesPerSchema = 5),
        config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.9) +
                (ExplicitFieldResolverWeight to 0.85) +
                (DuplicateSelectionWeight to 1.0) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (NodeResolversEnabled to false),
    ),
    ;

    companion object {
        /** The stored [profile] suite generated with [config] and checker modes; a family whose configuration has none must add one. */
        fun forProfile(
            profile: String,
            config: Config,
            fieldCheckerMode: GeneratedFieldCheckerMode = GeneratedFieldCheckerMode.NONE,
            typeCheckerMode: GeneratedTypeCheckerMode = GeneratedTypeCheckerMode.NONE,
        ): ResolverTestSuiteDefinition {
            val resolved = config.resolvedValues()
            return entries.singleOrNull { definition ->
                definition.profile == profile &&
                    definition.fieldCheckerMode == fieldCheckerMode &&
                    definition.typeCheckerMode == typeCheckerMode &&
                    definition.config.resolvedValues() == resolved
            } ?: throw IllegalArgumentException(
                "No stored $profile suite matches this generator config; add a ResolverTestSuiteDefinition and run materializeResolverTestSuites",
            )
        }
    }
}

private val GENERATED_PROFILE_COUNTS = TestCaseCount(schemas = 10, registriesPerSchema = 3, queriesPerSchema = 5)

private val FEATURE_INTERACTION_PROFILE_COUNTS = TestCaseCount(schemas = 20, registriesPerSchema = 3, queriesPerSchema = 5)

private val RESOLUTION_CONFIG_OVERRIDES = Config.default + (ResolverVariableSingletonCoercionEnabled to true)

private val QUERY_FRAGMENT_CONFIG =
    Config.default +
        (FieldArgumentWeight to 1.0) +
        (ExplicitFieldResolverWeight to 1.0) +
        (NodeResolversEnabled to false) +
        (ResolverFragmentsEnabled to true) +
        (ResolverFragmentWeight to 1.0) +
        (ResolverFromArgumentVariablesEnabled to true) +
        (ResolverQueryFragmentsEnabled to true) +
        (ResolverVariableWeight to 1.0) +
        (ResolverVariablesEnabled to false) +
        (ResolverFromQueryFieldVariablesEnabled to false)

private val FROM_ARGUMENT_CONFIG =
    Config.default +
        (FieldArgumentWeight to 1.0) +
        (InputObjectCount to 1..2) +
        (InputObjectTypeWeight to 0.5) +
        (RootQueryFieldCount to 10..10) +
        (ExplicitFieldResolverWeight to 1.0) +
        (NodeResolversEnabled to false) +
        (ResolverFragmentsEnabled to true) +
        (ResolverFragmentWeight to 1.0) +
        (ResolverFromArgumentNestedPathWeight to 1.0) +
        (ResolverFromArgumentVariablesEnabled to true) +
        (ResolverVariableWeight to 1.0) +
        (ResolverVariablesEnabled to false)

private val FEATURE_INTERACTION_CONFIG =
    Config.default +
        (ImplementationArgumentDefaultWeight to 1.0) +
        (SchemaObjectCount to 3..4) +
        (QueryFieldCount to 4..6) +
        (RootQueryFieldCount to 10..10) +
        (FieldArgumentWeight to 1.0) +
        (ExplicitFieldResolverWeight to 1.0) +
        (NodeResolversEnabled to true) +
        (NodeObjectWeight to 0.35) +
        (ResolverFragmentsEnabled to true) +
        (ResolverFragmentWeight to 1.0) +
        (ResolverFromArgumentVariablesEnabled to true) +
        (ResolverVariableWeight to 1.0) +
        (ResolverVariablesEnabled to false)

private fun resolutionOverrides(runtime: Boolean): Config = if (runtime) RESOLUTION_CONFIG_OVERRIDES else Config.default

private fun fieldCheckerConfig(runtime: Boolean): Config =
    Config.default +
        (FieldArgumentWeight to 1.0) +
        (ExplicitFieldResolverWeight to 1.0) +
        (NodeResolversEnabled to false) +
        (ParentFieldsEnabled to true) +
        (ResolverArgumentErrorWeight to 0.0) +
        (ResolverFragmentsEnabled to true) +
        (ResolverFragmentWeight to 1.0) +
        (ResolverFragmentDepth to if (runtime) 1 else 2) +
        (ResolverQueryFragmentsEnabled to true) +
        (ResolverQueryFragmentWeight to 1.0) +
        (ResolverFromArgumentNestedPathWeight to 1.0) +
        (ResolverFromArgumentVariablesEnabled to true) +
        (ResolverVariableWeight to 1.0) +
        (ResolverVariablesEnabled to runtime) +
        (ResolverFromObjectFieldVariablesEnabled to runtime) +
        (ResolverFromQueryFieldVariablesEnabled to runtime) +
        (ResolverFromProviderVariablesEnabled to runtime) +
        (SometimesPassiveFieldWeight to 0.25) +
        resolutionOverrides(runtime)

private fun passiveFieldCheckerConfig(runtime: Boolean): Config =
    Config.default +
        (FieldArgumentWeight to 0.0) +
        (ExplicitFieldResolverWeight to 1.0) +
        (NodeResolversEnabled to false) +
        (ResolverFragmentsEnabled to false) +
        (ResolverQueryFragmentsEnabled to false) +
        (ResolverFromArgumentVariablesEnabled to false) +
        (ResolverVariablesEnabled to false) +
        (SometimesPassiveFieldWeight to 1.0) +
        resolutionOverrides(runtime)

private fun rootReferenceFieldCheckerConfig(runtime: Boolean): Config =
    Config.default +
        (NodeResolversEnabled to false) +
        (ResolverFragmentsEnabled to false) +
        (ResolverQueryFragmentsEnabled to false) +
        (ResolverFromArgumentVariablesEnabled to false) +
        (ResolverVariablesEnabled to false) +
        (RootFieldReferencesEnabled to true) +
        (RootFieldReferenceWeight to 0.25) +
        resolutionOverrides(runtime)

private fun typeCheckerConfig(runtime: Boolean): Config =
    Config.default +
        (SchemaObjectCount to 4..6) +
        (ObjectFieldCount to 4..6) +
        (QueryFieldCount to 6..8) +
        (QueryScalarFieldWeight to 0.35) +
        (ExplicitFieldResolverWeight to 0.65) +
        (ResolverFragmentWeight to 0.8) +
        (NodeResolversEnabled to false) +
        (ResolverFragmentsEnabled to true) +
        (ResolverFragmentDepth to 1) +
        (ResolverQueryFragmentsEnabled to true) +
        (ResolverVariablesEnabled to runtime) +
        (ResolverFromArgumentVariablesEnabled to false) +
        (ResolverFromProviderVariablesEnabled to runtime) +
        (ResolverFromObjectFieldVariablesEnabled to runtime) +
        (ResolverFromQueryFieldVariablesEnabled to runtime) +
        (FieldArgumentCount to if (runtime) 2..3 else FieldArgumentCount.default) +
        (ResolverVariableCount to if (runtime) 1..1 else ResolverVariableCount.default) +
        (ResolverVariableWeight to 1.0) +
        (SometimesPassiveFieldWeight to 0.25) +
        resolutionOverrides(runtime)
