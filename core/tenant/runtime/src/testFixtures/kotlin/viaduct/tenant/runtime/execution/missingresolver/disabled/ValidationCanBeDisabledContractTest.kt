package viaduct.tenant.runtime.execution.missingresolver.disabled

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

/**
 * Contract test that verifies resolver completeness validation can be disabled.
 *
 * Extend this class, set `validateResolverCompleteness = false`, and provide
 * no resolver implementations to verify that the runtime allows intentionally
 * incomplete resolver sets.
 */
@TestSchema(
    """
    extend type Query {
      unimplemented: String @resolver
    }
    """
)
abstract class ValidationCanBeDisabledContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `validation can be disabled for intentionally incomplete tests`() {
        // Should not throw — validation is disabled
        tryBuildViaductService()
        // Query returns null since there's no resolver, but that's expected here
        val result = execute(query = "{ unimplemented }")
        assertThat(result.getData()).isNotNull()
    }
}
