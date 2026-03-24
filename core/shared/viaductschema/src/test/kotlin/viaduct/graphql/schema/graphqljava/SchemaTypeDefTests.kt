package viaduct.graphql.schema.graphqljava

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

/**
 * Tests for hasCustomSchema property on ViaductSchema implementations.
 */
class SchemaTypeDefTests {
    private val customRootTypesSdl = """
        schema {
            query: CustomQuery
            mutation: CustomMutation
            subscription: CustomSubscription
        }
        type CustomQuery { shops: [Shop] }
        type CustomMutation { createShop(name: String!): Shop }
        type CustomSubscription { shopCreated: Shop }
        type Shop { id: ID!, name: String! }
    """.trimIndent()

    private val standardNamesWithSchemaSdl = """
        schema {
            query: Query
            mutation: Mutation
            subscription: Subscription
        }
        type Query { shops: [Shop] }
        type Mutation { createShop(name: String!): Shop }
        type Subscription { shopCreated: Shop }
        type Shop { id: ID!, name: String! }
    """.trimIndent()

    private val noSchemaBlockSdl = """
        type Query { shops: [Shop] }
        type Mutation { createShop(name: String!): Shop }
        type Subscription { shopCreated: Shop }
        type Shop { id: ID!, name: String! }
    """.trimIndent()

    // ==================== GJSchemaRaw Tests ====================

    @Test
    fun `GJSchemaRaw hasCustomSchema is true for custom root type names`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(readTypes(customRootTypesSdl))

        assertTrue(schema.hasCustomSchema, "hasCustomSchema should be true for custom root types")
        assertEquals("CustomQuery", schema.queryTypeDef?.name)
        assertEquals("CustomMutation", schema.mutationTypeDef?.name)
        assertEquals("CustomSubscription", schema.subscriptionTypeDef?.name)
    }

    @Test
    fun `GJSchemaRaw hasCustomSchema is false for standard names with explicit schema block`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(readTypes(standardNamesWithSchemaSdl))

        assertFalse(schema.hasCustomSchema, "hasCustomSchema should be false for standard names even with schema block")
        assertEquals("Query", schema.queryTypeDef?.name)
        assertEquals("Mutation", schema.mutationTypeDef?.name)
        assertEquals("Subscription", schema.subscriptionTypeDef?.name)
    }

    @Test
    fun `GJSchemaRaw hasCustomSchema is false without explicit schema block`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(readTypes(noSchemaBlockSdl))

        assertFalse(schema.hasCustomSchema, "hasCustomSchema should be false when no schema block is present")
    }

    // ==================== GJSchema Tests ====================

    @Test
    fun `GJSchema hasCustomSchema is true for custom root type names`() {
        val schema = ViaductSchema.fromGraphQLSchema(readTypes(customRootTypesSdl))

        assertTrue(schema.hasCustomSchema, "hasCustomSchema should be true for custom root types")
        assertEquals("CustomQuery", schema.queryTypeDef?.name)
        assertEquals("CustomMutation", schema.mutationTypeDef?.name)
        assertEquals("CustomSubscription", schema.subscriptionTypeDef?.name)
    }

    @Test
    fun `GJSchema hasCustomSchema is false for standard names with explicit schema block`() {
        val schema = ViaductSchema.fromGraphQLSchema(readTypes(standardNamesWithSchemaSdl))

        assertFalse(schema.hasCustomSchema, "hasCustomSchema should be false for standard names even with schema block")
        assertEquals("Query", schema.queryTypeDef?.name)
        assertEquals("Mutation", schema.mutationTypeDef?.name)
        assertEquals("Subscription", schema.subscriptionTypeDef?.name)
    }

    @Test
    fun `GJSchema hasCustomSchema is false without explicit schema block`() {
        val schema = ViaductSchema.fromGraphQLSchema(readTypes(noSchemaBlockSdl))

        assertFalse(schema.hasCustomSchema, "hasCustomSchema should be false when no schema block is present")
    }
}
