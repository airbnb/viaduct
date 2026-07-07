package viaduct.engine.api

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLTypeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.SchemaFactory
import viaduct.graphql.utils.DefaultSchemaFactory

class ViaductSchemaTest {
    @Test
    fun `mutation namespace type returns true for namespace type reachable from mutation root`() {
        val schema = mkSchema(
            """
            extend type Query { name: String }
            extend type Mutation { namespace: MutationNamespace }
            type MutationNamespace @namespaceType { mutate: String }
            """.trimIndent()
        )

        assertTrue(schema.isMutationNamespaceType("MutationNamespace"))
    }

    @Test
    fun `mutation namespace type returns true for nested namespace type reachable from mutation root`() {
        val schema = mkSchema(
            """
            extend type Query { name: String }
            extend type Mutation { namespace: MutationNamespace }
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
            extend type Query { namespace: QueryNamespace }
            type QueryNamespace @namespaceType { field: String }
            extend type Mutation { mutate: String }
            """.trimIndent()
        )

        assertFalse(schema.isMutationNamespaceType("QueryNamespace"))
    }

    @Test
    fun `mutation namespace type returns false for mutation field type without namespace directive`() {
        val schema = mkSchema(
            """
            extend type Query { name: String }
            extend type Mutation { result: MutationResult }
            type MutationResult { id: ID }
            """.trimIndent()
        )

        assertFalse(schema.isMutationNamespaceType("MutationResult"))
    }

    @Test
    fun `mutation namespace type returns false when schema has no mutation root`() {
        val schema = mkSchema(
            """
            extend type Query { namespace: QueryNamespace }
            type QueryNamespace @namespaceType { field: String }
            """.trimIndent()
        )

        assertFalse(schema.isMutationNamespaceType("QueryNamespace"))
    }

    @Test
    fun `parent field helper identifies fields marked with parent directive`() {
        val schema = mkSchema(
            """
            extend type Query { user: User }
            type Company { name: String }
            type User { parent: Company @parent, name: String }
            """.trimIndent()
        )

        assertTrue(schema.isParentField("User", "parent"))
        assertFalse(schema.isParentField("User", "name"))
        assertEquals("Company", schema.parentFieldType("User", "parent")?.let { (GraphQLTypeUtil.unwrapAll(it) as GraphQLNamedType).name })
    }

    @Test
    fun `parent field helper supports fields declared on interfaces`() {
        val schema = mkSchema(
            """
            extend type Query { user: User }
            type Company { name: String }
            interface Entity { parent: Company @parent }
            type User implements Entity { parent: Company, name: String }
            """.trimIndent()
        )

        assertTrue(schema.isParentField("Entity", "parent"))
        assertEquals("Company", schema.parentFieldType("Entity", "parent")?.let { (GraphQLTypeUtil.unwrapAll(it) as GraphQLNamedType).name })
    }

    private fun mkSchema(sdl: String): ViaductSchema {
        return SchemaFactory().fromSdl(sdl)
    }

    private fun ViaductSchema.isParentField(
        parentTypeName: String,
        fieldName: String,
    ): Boolean = parentFieldDefinition(parentTypeName, fieldName) != null

    private fun ViaductSchema.parentFieldType(
        parentTypeName: String,
        fieldName: String,
    ): GraphQLOutputType? = parentFieldDefinition(parentTypeName, fieldName)?.type

    private fun ViaductSchema.parentFieldDefinition(
        parentTypeName: String,
        fieldName: String,
    ): GraphQLFieldDefinition? =
        (schema.getType(parentTypeName) as? GraphQLFieldsContainer)
            ?.getFieldDefinition(fieldName)
            ?.takeIf { it.hasAppliedDirective(DefaultSchemaFactory.DefaultDirective.PARENT.directiveName) }
}
