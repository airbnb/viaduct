package viaduct.tenant.codegen.graphql.schema

import graphql.schema.idl.SchemaParser
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.test.SchemaDiff

class ScopedSchemaFilterTest {
    companion object {
        private val schemaString = """
            directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION
            type Query @scope(to: ["*"]) {
                f1: Int
                f2: ObjectOutOfScope
            }
            extend type Query @scope(to: ["c"]) {
                f3: String
            }
            extend type Query @scope(to: ["a"]) {
                f4: Int
            }
            enum EnumInScope @scope(to: ["b", "c"]) {
                V1
            }
            extend enum EnumInScope @scope(to: ["c"]) {
                V2
            }
            type ObjectInScope implements InterfaceInScope @scope(to: ["b", "c"]) {
                f1: EnumInScope
            }
            extend type ObjectInScope implements InterfaceImplementationOutOfScope @scope(to: ["c"]) {
                f2: EnumInScope
            }
            type ObjectOutOfScope implements InterfaceInScope @scope(to: ["c"]) {
                f1: EnumInScope
            }
            union UnionInScope @scope(to: ["a", "b", "c"]) = Query | ObjectOutOfScope
            extend union UnionInScope @scope(to: ["b"]) = ObjectInScope
            interface InterfaceInScope @scope(to: ["b", "c"]) {
                f1: EnumInScope
            }
            interface InterfaceImplementationOutOfScope @scope(to: ["b", "c", "d"]) {
                f2: EnumInScope
            }
        """.trimIndent()

        private val filteredSchemaString = """
            directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION
            type Query @scope(to: ["*"]) {
                f1: Int
            }
            extend type Query @scope(to: ["a"]) {
                f4: Int
            }
            enum EnumInScope @scope(to: ["b", "c"]) {
                V1
            }
            type ObjectInScope implements InterfaceInScope @scope(to: ["b", "c"]) {
                f1: EnumInScope
            }
            union UnionInScope @scope(to: ["a", "b", "c"]) = Query
            extend union UnionInScope @scope(to: ["b"]) = ObjectInScope
            interface InterfaceInScope @scope(to: ["b", "c"]) {
                f1: EnumInScope
            }
            interface InterfaceImplementationOutOfScope @scope(to: ["b", "c", "d"]) {
                f2: EnumInScope
            }
        """.trimIndent()
    }

    @Test
    fun `test scopes filter`() {
        val schema = loadSchema(schemaString)
        val scopedSchema = schema.filter(ScopedSchemaFilter(setOf("a", "b")))
        val expectedScopedSchema = loadSchema(filteredSchemaString)
        SchemaDiff(expectedScopedSchema, scopedSchema)
    }

    private fun loadSchema(schema: String) = ViaductSchema.fromTypeDefinitionRegistry(SchemaParser().parse(schema))

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }
}
