package viaduct.tenant.runtime.execution.missingresolver.field

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.api.testing.featureapp.MissingResolverImplementationException

/**
 * Contract test that verifies a clear error message is produced when a
 * @resolver-declared field is missing its @Resolver implementation class.
 *
 * Extend this class and provide only the "implemented" resolver — the
 * "forgotten" resolver is intentionally missing.
 */
@TestSchema(
    """
    extend type Query {
      implemented: String! @resolver
      forgotten: String! @resolver
    }
    """
)
abstract class MissingFieldResolverContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `missing field resolver produces a clear error message`() {
        val exception = assertThrows<MissingResolverImplementationException> {
            tryBuildViaductService()
        }
        assertThat(exception.message)
            .contains("Query.forgotten")
            .contains("@Resolver")
    }
}
