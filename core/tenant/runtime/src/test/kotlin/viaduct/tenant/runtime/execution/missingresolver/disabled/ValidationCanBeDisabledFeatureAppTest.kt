@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.missingresolver.disabled

/**
 * Tests that resolver completeness validation can be disabled for tests
 * that intentionally omit resolver implementations.
 */
class ValidationCanBeDisabledFeatureAppTest : ValidationCanBeDisabledContractTest() {
    override val validateResolverCompleteness = false

    // Intentionally no resolver — validation is disabled
}
