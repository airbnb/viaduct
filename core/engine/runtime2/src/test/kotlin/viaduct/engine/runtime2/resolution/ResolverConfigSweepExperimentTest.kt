@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.coverage.ConfigSweepVariant
import viaduct.engine.runtime2.coverage.render
import viaduct.engine.runtime2.coverage.sweepConfigs
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

@Tag("IntegrationTest")
@Tag("Rfc271CoverageExperiment")
class ResolverConfigSweepExperimentTest : CorrectResolutionPostTestPolicy, ResolutionDispatcherResource {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)

    @Test
    fun `sweeping generator configs shows which interactions each variant reaches`(): Unit =
        runBlocking {
            val schemas = System.getenv(SCHEMAS_ENV)?.toInt() ?: DEFAULT_SCHEMAS
            val variants = System.getenv(VARIANTS_ENV)?.let(ConfigSweepVariant::parseAll) ?: DEFAULT_VARIANTS
            val suite = System.getenv(SUITE_ENV)?.let(ResolverTestSuiteDefinition::byId) ?: ResolverTestSuiteDefinition.OBJECT_FRAGMENT

            val results = sweepConfigs(suite, TestCaseCount(schemas, queriesPerSchema = 5), variants)

            println(StringBuilder().also { results.render(it) })
            assertTrue(results.all { it.cases > 0 })
        }

    companion object {
        const val SUITE_ENV = "VIADUCT_RUNTIME2_SWEEP_SUITE"
        const val SCHEMAS_ENV = "VIADUCT_RUNTIME2_SWEEP_SCHEMAS"
        const val VARIANTS_ENV = "VIADUCT_RUNTIME2_SWEEP_VARIANTS"
        const val DEFAULT_SCHEMAS = 50

        private const val BINDINGS = "resolverVariablesEnabled:true;resolverFromArgumentVariablesEnabled:true;resolverVariableSingletonCoercionEnabled:true"
        private const val DENSE = "$BINDINGS;resolverVariableWeight:1.0;resolverVariableCount:2..4"
        private const val MIXED_SHAPE = "$DENSE;schemaObjectCount:3..4;objectFieldCount:3..4;queryFieldCount:4..4;rootQueryFieldCount:10..10"
        private const val LISTS = "$MIXED_SHAPE;listTypeWeight:0.5;resolverNestedProviderPathWeight:1.0"
        private const val ERRORS = "$LISTS;errorValueWeight:0.25;nullValueWeight:0.25"
        private const val OWNERS = "$ERRORS;resolverFromFieldVariableOwnerUseWeight:0.5;resolverFromFieldProviderArgumentVariableWeight:0.5"

        val DEFAULT_VARIANTS =
            listOf(
                ConfigSweepVariant("base", ""),
                ConfigSweepVariant("bindings", BINDINGS),
                ConfigSweepVariant("dense", DENSE),
                ConfigSweepVariant("mixed-shape", MIXED_SHAPE),
                ConfigSweepVariant("lists", LISTS),
                ConfigSweepVariant("errors", ERRORS),
                ConfigSweepVariant("owners", OWNERS),
            )
    }
}
