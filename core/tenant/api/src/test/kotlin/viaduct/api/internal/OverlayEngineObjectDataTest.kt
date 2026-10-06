package viaduct.api.internal

import graphql.Scalars
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.ResolvedEngineObjectData

class OverlayEngineObjectDataTest {
    @Test
    fun `compatibility facade delegates sync and suspend access`() =
        runTest {
            val type = GraphQLObjectType.newObject().name("Object")
                .field(GraphQLFieldDefinition.newFieldDefinition().name("value").type(Scalars.GraphQLString))
                .build()
            val base = ResolvedEngineObjectData(type, mapOf("value" to "base", "inherited" to "base"))
            val overlay = ResolvedEngineObjectData(type, mapOf("value" to null))
            val combined = OverlayEngineObjectData(overlay, base)

            assertSame(type, combined.type)
            assertNull(combined.get("value"))
            assertNull(combined.getOrNull("value"))
            assertTrue(combined.isPresent("value"))
            assertEquals("base", combined.get("inherited"))
            assertEquals(setOf("value", "inherited"), combined.getSelections().toSet())
            assertNull(combined.fetch("value"))
            assertNull(combined.fetchOrNull("value"))
            assertEquals(setOf("value", "inherited"), combined.fetchSelections().toSet())
        }
}
