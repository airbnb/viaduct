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
import viaduct.engine.runtime2.arbitrary.NodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.NullableTypeWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariablesEnabled
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
    OBJECT_FRAGMENT(
        id = "object-fragment",
        seed = 1L,
        counts = TestCaseCount(schemas = 10, registriesPerSchema = 3, queriesPerSchema = 5),
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
