package viaduct.tenant.runtime.execution.missingresolver.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.engine.runtime.tenantloading.MissingResolversException

/**
 * Contract test that verifies a clear error message is produced when a
 * @resolver-declared node type is missing its @Resolver implementation class.
 *
 * Extend this class and provide only the field resolver — the node resolver
 * is intentionally missing.
 */
@TestSchema(
    """
    type Widget implements Node @resolver {
      id: ID!
      label: String!
    }

    extend type Query {
      widget(id: String!): Widget! @resolver
    }
    """
)
abstract class MissingNodeResolverContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `missing node resolver produces a clear error message`() {
        val failure = assertThrows<RuntimeException> {
            tryBuildViaductService()
        }
        val exception = assertInstanceOf(MissingResolversException::class.java, failure.cause?.cause)
        assertEquals(listOf("Widget"), exception.missingNodeResolvers)
        assertEquals(emptyList<String>(), exception.missingFieldResolvers)
    }
}
