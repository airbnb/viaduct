package viaduct.tenant.runtime.execution.batchresolver.tenantexceptionpassthrough

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Contract test for batch resolver normalization when a resolver returns a [viaduct.errors.TenantUsageException]
 * inside a [viaduct.api.FieldValue].
 */
@TestSchema(
    """
    extend type Query {
      item(id: ID! @idOf(type: "Item")): Item @resolver
    }

    type Item implements Node @resolver(isSelective: true, isBatching: true) {
      id: ID!
    }
    """
)
@Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
abstract class TenantExceptionWrappingContractTest : KotlinFeatureAppTestContractBase() {
    protected abstract fun setNodeBatchShouldReturnTenantException(enabled: Boolean)

    protected abstract fun setNodeBatchShouldReturnErroneousFieldException(enabled: Boolean)

    @Test
    fun `TenantUsageException from node batch resolver is wrapped as TenantResolverException`() {
        setNodeBatchShouldReturnTenantException(true)
        try {
            val itemId = GlobalIDCodecDefault.serialize("Item", "1")
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
            assertEquals("Item", extensions["resolvers"])
            assertEquals("false", extensions["isFrameworkError"])
            assertEquals("viaduct.errors.TenantUsageException", extensions["fullyQualifiedErrorClass"])
        } finally {
            setNodeBatchShouldReturnTenantException(false)
        }
    }

    @Test
    fun `ErroneousFieldException from node batch resolver propagates field errors to the response`() {
        setNodeBatchShouldReturnErroneousFieldException(true)
        try {
            val itemId = GlobalIDCodecDefault.serialize("Item", "1")
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
            val error = result.errors[0]
            // The FieldError message must surface in the GraphQL response
            assertEquals("upstream error", error.message)
            // ErroneousFieldException must not be wrapped in TenantResolverException —
            // presence of "resolvers" in extensions indicates wrapping occurred
            assertNull(
                error.extensions["resolvers"],
                "ErroneousFieldException must not be wrapped in TenantResolverException"
            )
        } finally {
            setNodeBatchShouldReturnErroneousFieldException(false)
        }
    }
}
