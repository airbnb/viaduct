package viaduct.tenant.runtime.execution.batchresolver.tenantexceptionpassthrough

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

/**
 * Contract test for batch resolver passthrough behavior when a resolver returns a [TenantUsageException]
 * inside a [viaduct.api.FieldValue].
 */
@TestSchema(
    """
    extend type Query {
      item(id: ID! @idOf(type: "Item")): Item @resolver
    }

    type Item implements Node @resolver(isSelective: true) {
      id: ID!
      name: String @resolver
    }
    """
)
abstract class TenantExceptionPassthroughContractTest : KotlinFeatureAppTestContractBase() {
    protected abstract fun createItemGlobalId(internalId: String): String

    protected abstract fun setFieldBatchShouldReturnTenantException(enabled: Boolean)

    protected abstract fun setNodeBatchShouldReturnTenantException(enabled: Boolean)

    @Test
    fun `TenantUsageException from field batch resolver is not re-wrapped as TenantResolverException`() {
        setFieldBatchShouldReturnTenantException(true)
        try {
            val itemId = createItemGlobalId("1")
            val result = execute(
                """
                query {
                    item(id: "$itemId") {
                        id
                        name
                    }
                }
                """.trimIndent()
            )

            assertEquals(1, result.errors.size)
            val extensions = result.errors[0].extensions
            assertFalse(
                extensions.containsKey("resolvers"),
                "TenantUsageException must not be wrapped in TenantResolverException; presence of 'resolvers' indicates re-wrapping occurred"
            )
        } finally {
            setFieldBatchShouldReturnTenantException(false)
        }
    }

    @Test
    fun `TenantUsageException from node batch resolver is not re-wrapped as TenantResolverException`() {
        setNodeBatchShouldReturnTenantException(true)
        try {
            val itemId = createItemGlobalId("1")
            val result = execute(
                """
                query {
                    item(id: "$itemId") {
                        id
                    }
                }
                """.trimIndent()
            )

            assertEquals(1, result.errors.size)
            val extensions = result.errors[0].extensions
            assertFalse(
                extensions.containsKey("resolvers"),
                "TenantUsageException must not be wrapped in TenantResolverException; presence of 'resolvers' indicates re-wrapping occurred"
            )
            assertEquals("false", extensions["isFrameworkError"])
            assertEquals("viaduct.errors.TenantUsageException", extensions["fullyQualifiedErrorClass"])
        } finally {
            setNodeBatchShouldReturnTenantException(false)
        }
    }
}
