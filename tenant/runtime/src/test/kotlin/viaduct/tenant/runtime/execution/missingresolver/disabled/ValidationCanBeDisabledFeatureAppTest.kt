package viaduct.tenant.runtime.execution.missingresolver.disabled

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Tests that resolver completeness validation can be disabled for tests
 * that intentionally omit resolver implementations.
 */
class ValidationCanBeDisabledFeatureAppTest : FeatureAppTestBase() {
    override val validateResolverCompleteness = false

    override var sdl = """
        | #START_SCHEMA
        | extend type Query {
        |   unimplemented: String @resolver
        | }
        | #END_SCHEMA
    """.trimMargin()

    // Intentionally no resolver — validation is disabled

    @Test
    fun `validation can be disabled for intentionally incomplete tests`() {
        // Should not throw — validation is disabled
        tryBuildViaductService()
        // Query returns null since there's no resolver, but that's expected here
        val result = execute(query = "{ unimplemented }")
        assertThat(result.getData()).isNotNull()
    }
}
