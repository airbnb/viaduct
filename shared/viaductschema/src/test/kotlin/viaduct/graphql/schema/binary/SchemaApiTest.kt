package viaduct.graphql.schema.binary

import graphql.schema.idl.SchemaParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw

/**
 * Tests for public API methods on binary schema types.
 *
 * These tests verify that public API methods like enum value lookup,
 * field lookup, and toString methods work correctly on the decoded
 * binary schema.
 */
class SchemaApiTest {
    @Test
    fun `Enum value lookup by name`() {
        val sdl = """
            enum Status { ACTIVE, INACTIVE, PENDING }
            type Query { status: Status }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val enumType = bschema.types["Status"] as ViaductSchema.Enum
        val activeValue = enumType.value("ACTIVE")
        assertNotNull(activeValue)
        assertEquals("ACTIVE", activeValue!!.name)
        assertNull(enumType.value("NONEXISTENT"))
    }

    @Test
    fun `Enum value returns null for non-existent values`() {
        val sdl = """
            enum Color { RED, GREEN, BLUE }
            type Query { color: Color }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val enumType = bschema.types["Color"] as ViaductSchema.Enum
        assertNull(enumType.value("YELLOW"))
        assertNull(enumType.value(""))
        assertNull(enumType.value("red")) // Case sensitive
    }

    @Test
    fun `Field lookup by name`() {
        val sdl = """
            type Query {
                field1: String
                field2: Int
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val queryType = bschema.types["Query"] as ViaductSchema.Object
        val field1 = queryType.field("field1")
        assertNotNull(field1)
        assertEquals("field1", field1!!.name)
        assertNull(queryType.field("nonexistent"))
    }

    @Test
    fun `Field lookup returns null for non-existent fields`() {
        val sdl = """
            type User {
                id: ID
                name: String
            }
            type Query { user: User }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val userType = bschema.types["User"] as ViaductSchema.Object
        assertNull(userType.field("email"))
        assertNull(userType.field(""))
    }

    @Test
    fun `toString methods produce valid output`() {
        val sdl = """
            enum Status { ACTIVE }
            union Pet = Query
            interface Node { id: ID }
            type Query implements Node { id: ID, status: Status }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val enumType = bschema.types["Status"]!!
        assert(enumType.toString().contains("Status"))

        val unionType = bschema.types["Pet"]!!
        assert(unionType.toString().contains("Pet"))

        val interfaceType = bschema.types["Node"]!!
        assert(interfaceType.toString().contains("Node"))

        val objectType = bschema.types["Query"]!!
        assert(objectType.toString().contains("Query"))
    }

    @Test
    fun `Enum value toString produces valid output`() {
        val sdl = """
            enum Status { ACTIVE, INACTIVE }
            type Query { status: Status }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val enumType = bschema.types["Status"] as ViaductSchema.Enum
        val activeValue = enumType.value("ACTIVE")!!
        assert(activeValue.toString().contains("ACTIVE"))
    }

    @Test
    fun `Field toString produces valid output`() {
        val sdl = """
            type Query {
                user(id: ID!): String
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val queryType = bschema.types["Query"] as ViaductSchema.Object
        val field = queryType.field("user")!!
        assert(field.toString().contains("user"))
    }

    @Test
    fun `Directive toString produces valid output`() {
        val sdl = """
            directive @deprecated(reason: String = "No longer supported") on FIELD_DEFINITION

            type Query {
                field: String
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val directive = bschema.directives["deprecated"]!!
        assert(directive.toString().contains("deprecated"))
    }

    @Test
    fun `Field lookup by path with single element`() {
        val sdl = """
            type User {
                name: String
                email: String
            }
            type Query { user: User }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val userType = bschema.types["User"] as ViaductSchema.Record
        val field = userType.field(listOf("name"))
        assertNotNull(field)
        assertEquals("name", field!!.name)
    }

    @Test
    fun `Field lookup by path with nested fields`() {
        val sdl = """
            type Address {
                street: String
                city: String
            }
            type User {
                name: String
                address: Address
            }
            type Query { user: User }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val userType = bschema.types["User"] as ViaductSchema.Record
        val field = userType.field(listOf("address", "street"))
        assertNotNull(field)
        assertEquals("street", field!!.name)
    }

    @Test
    fun `Field lookup by path throws exception for non-existent path`() {
        val sdl = """
            type Address {
                street: String
            }
            type User {
                name: String
                address: Address
            }
            type Query { user: User }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val userType = bschema.types["User"] as ViaductSchema.Record
        assertThrows<IllegalArgumentException> {
            userType.field(listOf("address", "nonexistent"))
        }
        assertThrows<IllegalArgumentException> {
            userType.field(listOf("nonexistent"))
        }
    }

    @Test
    fun `Field lookup by empty path throws exception`() {
        val sdl = """
            type Query {
                field: String
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        val queryType = bschema.types["Query"] as ViaductSchema.Record
        assertThrows<IllegalArgumentException> {
            queryType.field(emptyList())
        }
    }

    @Test
    fun `Access root operation type definitions`() {
        val sdl = """
            type Query {
                hello: String
            }

            type Mutation {
                update: String
            }

            type Subscription {
                onChange: String
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        // Test queryTypeDef
        assertNotNull(bschema.queryTypeDef)
        assertEquals("Query", bschema.queryTypeDef?.name)

        // Test mutationTypeDef
        assertNotNull(bschema.mutationTypeDef)
        assertEquals("Mutation", bschema.mutationTypeDef?.name)

        // Test subscriptionTypeDef
        assertNotNull(bschema.subscriptionTypeDef)
        assertEquals("Subscription", bschema.subscriptionTypeDef?.name)
    }

    @Test
    fun `Root operation type definitions return null when not present`() {
        val sdl = """
            type Query {
                hello: String
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(ByteArrayInputStream(tmp.toByteArray()))

        assertNotNull(bschema.queryTypeDef)
        assertNull(bschema.mutationTypeDef)
        assertNull(bschema.subscriptionTypeDef)
    }
}
