package viaduct.engine.api

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViaductSchemaTest {
    @Test
    fun `mutation namespace type returns true for namespace type reachable from mutation root`() {
        val schema = mkSchema(
            """
            type Query { name: String }
            type Mutation { namespace: MutationNamespace }
            type MutationNamespace @namespaceType { mutate: String }
            """.trimIndent()
        )

        assertTrue(schema.isMutationNamespaceType("MutationNamespace"))
    }

    @Test
    fun `mutation namespace type returns true for nested namespace type reachable from mutation root`() {
        val schema = mkSchema(
            """
            type Query { name: String }
            type Mutation { namespace: MutationNamespace }
            type MutationNamespace @namespaceType { nested: NestedMutationNamespace }
            type NestedMutationNamespace @namespaceType { mutate: String }
            """.trimIndent()
        )

        assertTrue(schema.isMutationNamespaceType("MutationNamespace"))
        assertTrue(schema.isMutationNamespaceType("NestedMutationNamespace"))
    }

    @Test
    fun `mutation namespace type returns false for query namespace type`() {
        val schema = mkSchema(
            """
            type Query { namespace: QueryNamespace }
            type QueryNamespace @namespaceType { field: String }
            type Mutation { mutate: String }
            """.trimIndent()
        )

        assertFalse(schema.isMutationNamespaceType("QueryNamespace"))
    }

    @Test
    fun `mutation namespace type returns false for mutation field type without namespace directive`() {
        val schema = mkSchema(
            """
            type Query { name: String }
            type Mutation { result: MutationResult }
            type MutationResult { id: ID }
            """.trimIndent()
        )

        assertFalse(schema.isMutationNamespaceType("MutationResult"))
    }

    @Test
    fun `mutation namespace type returns false when schema has no mutation root`() {
        val schema = mkSchema(
            """
            type Query { namespace: QueryNamespace }
            type QueryNamespace @namespaceType { field: String }
            """.trimIndent()
        )

        assertFalse(schema.isMutationNamespaceType("QueryNamespace"))
    }

    private fun mkSchema(sdl: String): ViaductSchema {
        val registry = SchemaParser().parse("directive @namespaceType on OBJECT\n$sdl")
        return ViaductSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(registry))
    }
}
