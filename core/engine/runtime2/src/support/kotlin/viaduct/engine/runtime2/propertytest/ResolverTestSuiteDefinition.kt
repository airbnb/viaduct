package viaduct.engine.runtime2.propertytest

import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ExplicitFieldResolverWeight
import viaduct.engine.runtime2.arbitrary.FieldArgumentWeight
import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.InputListTypeWeight
import viaduct.engine.runtime2.arbitrary.InputObjectCount
import viaduct.engine.runtime2.arbitrary.InputObjectTypeWeight
import viaduct.engine.runtime2.arbitrary.MaxInputTypeDepth
import viaduct.engine.runtime2.arbitrary.NodeObjectWeight
import viaduct.engine.runtime2.arbitrary.NodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.NullableTypeWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverQueryFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariablesEnabled
import viaduct.engine.runtime2.arbitrary.RootFieldReferenceWeight
import viaduct.engine.runtime2.arbitrary.RootFieldReferencesEnabled
import viaduct.engine.runtime2.arbitrary.SelectiveNodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.SometimesPassiveFieldWeight
import viaduct.engine.runtime2.arbitrary.TestCaseCount

/** Generation inputs for a stored suite; changing any of them requires regenerating its resources. */
enum class ResolverTestSuiteDefinition(
    val id: String,
    val seed: Long,
    val counts: TestCaseCount,
    val config: Config,
    val fieldCheckerMode: GeneratedFieldCheckerMode = GeneratedFieldCheckerMode.NONE,
    val typeCheckerMode: GeneratedTypeCheckerMode = GeneratedTypeCheckerMode.NONE,
) {
    EMPTY_OBJECT_FRAGMENT(
        id = "empty-object-fragment",
        seed = 1L,
        counts = GENERATED_PROFILE_COUNTS,
        config =
            Config.default +
                (NodeResolversEnabled to false) +
                (ResolverFragmentsEnabled to false) +
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverVariablesEnabled to false),
    ),
    ROOT_FIELD_REFERENCE(
        id = "root-field-reference",
        seed = 1L,
        counts = GENERATED_PROFILE_COUNTS,
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
        seed = 1L,
        counts = GENERATED_PROFILE_COUNTS,
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
        seed = 1L,
        counts = GENERATED_PROFILE_COUNTS,
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
        seed = 1L,
        counts = GENERATED_PROFILE_COUNTS,
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
        seed = 1L,
        counts = GENERATED_PROFILE_COUNTS,
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
}

private val GENERATED_PROFILE_COUNTS = TestCaseCount(schemas = 10, registriesPerSchema = 3, queriesPerSchema = 5)
