@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.missingresolver.disabled

import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource

/**
 * Tests that resolver completeness validation can be disabled for tests
 * that intentionally omit resolver implementations.
 */
class ValidationCanBeDisabledFeatureAppTest : ValidationCanBeDisabledContractTest() {
    override val validateResolverCompleteness = false

    override fun moduleConfigSources(): List<ModuleConfigSource> = emptyList()
}
