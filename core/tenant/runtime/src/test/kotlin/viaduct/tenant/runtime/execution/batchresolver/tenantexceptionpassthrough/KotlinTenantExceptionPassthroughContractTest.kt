@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.batchresolver.tenantexceptionpassthrough

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.errors.TenantUsageException
import viaduct.tenant.runtime.execution.batchresolver.tenantexceptionpassthrough.resolverbases.ItemResolvers
import viaduct.tenant.runtime.execution.batchresolver.tenantexceptionpassthrough.resolverbases.NodeResolvers
import viaduct.tenant.runtime.execution.batchresolver.tenantexceptionpassthrough.resolverbases.QueryResolvers

class KotlinTenantExceptionPassthroughContractTest : TenantExceptionPassthroughContractTest() {
    enum class FieldBatchMode {
        NORMAL,
        TENANT_EXCEPTION,
        RUNTIME_EXCEPTION,
        NON_FIELD_VALUE,
        TOO_FEW_RESULTS,
    }

    override fun createItemGlobalId(internalId: String): String = createGlobalIdString(Item.Reflection, internalId)

    override fun setFieldBatchShouldReturnTenantException(enabled: Boolean) {
        Item_NameResolver.mode = if (enabled) FieldBatchMode.TENANT_EXCEPTION else FieldBatchMode.NORMAL
    }

    override fun setNodeBatchShouldReturnTenantException(enabled: Boolean) {
        ItemResolver.shouldReturnTenantException = enabled
    }

    @Resolver
    class Query_ItemResolver : QueryResolvers.Item() {
        override suspend fun resolve(ctx: Context): Item = ctx.nodeFor(ctx.arguments.id)
    }

    @Resolver(objectValueFragment = "fragment _ on Item { id }")
    class Item_NameResolver : ItemResolvers.Name() {
        companion object {
            var mode = FieldBatchMode.NORMAL
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String>> =
            when (mode) {
                FieldBatchMode.NORMAL -> contexts.map { FieldValue.ofValue("ok") }
                FieldBatchMode.TENANT_EXCEPTION -> contexts.map {
                    FieldValue.ofError(TenantUsageException("field api misuse"))
                }
                FieldBatchMode.RUNTIME_EXCEPTION -> contexts.map {
                    FieldValue.ofError(RuntimeException("unexpected field batch failure"))
                }
                FieldBatchMode.NON_FIELD_VALUE -> contexts.map { "not-a-field-value" } as List<FieldValue<String>>
                FieldBatchMode.TOO_FEW_RESULTS -> listOf(FieldValue.ofValue("ok"))
            }
    }

    class ItemResolver : NodeResolvers.Item() {
        companion object {
            var shouldReturnTenantException = false
        }

        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Item>> =
            contexts.map { ctx ->
                if (shouldReturnTenantException) {
                    FieldValue.ofError(TenantUsageException("node api misuse"))
                } else {
                    FieldValue.ofValue(
                        Item.Builder(ctx)
                            .id(ctx.id)
                            .build()
                    )
                }
            }
    }

    @Test
    fun `RuntimeException from exceptional FieldValue surfaces as a field error`() {
        Item_NameResolver.mode = FieldBatchMode.RUNTIME_EXCEPTION
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
            val error = result.errors.single()
            assertTrue(error.message.contains("unexpected field batch failure"))
            assertEquals("java.lang.RuntimeException", error.extensions["fullyQualifiedErrorClass"])
        } finally {
            Item_NameResolver.mode = FieldBatchMode.NORMAL
        }
    }

    @Test
    fun `field batch resolver results must be FieldValue instances`() {
        Item_NameResolver.mode = FieldBatchMode.NON_FIELD_VALUE
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
            val error = result.errors.single()
            assertTrue(error.message.contains("Unexpected result type that is not a FieldValue"))
            assertFalse(error.extensions.containsKey("resolvers"))
        } finally {
            Item_NameResolver.mode = FieldBatchMode.NORMAL
        }
    }

    @Test
    fun `field batch resolver must return one result per selector`() {
        Item_NameResolver.mode = FieldBatchMode.TOO_FEW_RESULTS
        try {
            val firstItemId = createItemGlobalId("1")
            val secondItemId = createItemGlobalId("2")
            val result = execute(
                """
                query {
                    first: item(id: "$firstItemId") {
                        id
                        name
                    }
                    second: item(id: "$secondItemId") {
                        id
                        name
                    }
                }
                """.trimIndent()
            )

            assertEquals(2, result.errors.size)
            result.errors.forEach { error ->
                assertTrue(error.message.contains("was given a batch of size 2 but returned 1 elements"))
                assertEquals("viaduct.errors.TenantUsageException", error.extensions["fullyQualifiedErrorClass"])
                assertFalse(error.extensions.containsKey("resolvers"))
            }
        } finally {
            Item_NameResolver.mode = FieldBatchMode.NORMAL
        }
    }
}
