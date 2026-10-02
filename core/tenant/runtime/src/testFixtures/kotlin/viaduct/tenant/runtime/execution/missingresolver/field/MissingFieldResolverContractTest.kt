package viaduct.tenant.runtime.execution.missingresolver.field

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.engine.runtime.tenantloading.MissingResolversException

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
        val failure = assertThrows<RuntimeException> {
            tryBuildViaductService()
        }
        val exception = assertInstanceOf(MissingResolversException::class.java, failure.cause?.cause)
        assertEquals(listOf("Query.forgotten"), exception.missingFieldResolvers)
        assertEquals(emptyList<String>(), exception.missingNodeResolvers)
    }
}
