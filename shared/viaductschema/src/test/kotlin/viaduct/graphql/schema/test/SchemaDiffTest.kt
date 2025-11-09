package viaduct.graphql.schema.test

import graphql.parser.MultiSourceReader
import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.graphqljava.GJSchemaRaw

class SchemaDiffTest {
    @Test
    fun `should detect sourceLocation disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                MultiSourceReader.newMultiSourceReader()
                    .string("scalar Foo", "file1.graphql")
                    .build()
            )
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                MultiSourceReader.newMultiSourceReader()
                    .string("scalar Foo", "file2.graphql")
                    .build()
            )
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("SOURCE_LOCATION_AGREES"))
    }

    @Test
    fun `should detect missing type`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("scalar Foo\nscalar Bar")
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("scalar Foo")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("SAME_TYPE_NAMES"))
    }

    @Test
    fun `should detect type kind disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("scalar Foo")
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("enum Foo { A B }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("DEF_CLASS_AGREE"))
    }

    @Test
    fun `should detect enum value disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("enum Status { ACTIVE INACTIVE PENDING }")
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("enum Status { ACTIVE PENDING }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        // Enums generate multiple errors including SAME_ENUM_VALUE_NAMES
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("SAME_ENUM_VALUE_NAMES") })
    }

    @Test
    fun `should detect field name disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("type User { id: ID! name: String email: String }")
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("type User { id: ID! email: String }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        // Types with fields generate multiple errors including SAME_FIELD_NAMES
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("SAME_FIELD_NAMES") })
    }

    @Test
    fun `should detect field argument name disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("type Query { user(id: ID!): String }")
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("type Query { user(userId: ID!): String }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("SAME_ARG_NAMES"))
    }

    @Test
    fun `should detect field argument type disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("type Query { user(id: ID!): String }")
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("type Query { user(id: String!): String }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("ARG_TYPE_AGREE"))
    }

    @Test
    fun `should detect input field name disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("input UserInput { name: String age: Int email: String }")
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("input UserInput { name: String email: String }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        // Inputs with fields generate multiple errors including SAME_FIELD_NAMES
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("SAME_FIELD_NAMES") })
    }

    @Test
    fun `should detect interface implementation disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                interface Node { id: ID! }
                interface Named { name: String }
                interface Timestamped { createdAt: String }
                type User implements Node & Named & Timestamped { id: ID! name: String createdAt: String }
                """.trimIndent()
            )
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                interface Node { id: ID! }
                interface Named { name: String }
                interface Timestamped { createdAt: String }
                type User implements Node & Named { id: ID! name: String createdAt: String }
                """.trimIndent()
            )
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        // Types with fields and interfaces generate multiple errors including SAME_SUPER_NAMES
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("SAME_SUPER_NAMES") })
    }

    @Test
    fun `should detect union member disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                type Cat { meow: String }
                type Dog { bark: String }
                type Bird { chirp: String }
                union Pet = Cat | Dog | Bird
                """.trimIndent()
            )
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                type Cat { meow: String }
                type Dog { bark: String }
                type Bird { chirp: String }
                union Pet = Cat | Dog
                """.trimIndent()
            )
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        // Union types generate multiple errors including SAME_UNION_NAMES
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("SAME_UNION_NAMES") })
    }

    @Test
    fun `should detect directive name disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                directive @deprecated(reason: String) on FIELD_DEFINITION
                directive @auth on FIELD_DEFINITION
                type Query { field: String }
                """.trimIndent()
            )
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                directive @deprecated(reason: String) on FIELD_DEFINITION
                directive @authorized on FIELD_DEFINITION
                type Query { field: String }
                """.trimIndent()
            )
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("SAME_DIRECTIVE_NAMES"))
    }

    @Test
    fun `should detect directive argument name disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                directive @deprecated(reason: String) on FIELD_DEFINITION
                type Query { field: String }
                """.trimIndent()
            )
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                directive @deprecated(message: String) on FIELD_DEFINITION
                type Query { field: String }
                """.trimIndent()
            )
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("SAME_DIRECTIVE_ARG_NAMES"))
    }

    @Test
    fun `should detect input field default value disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("input UserInput { name: String age: Int = 18 }")
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("input UserInput { name: String age: Int = 21 }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        // Input fields generate multiple errors including DEFAULT_VALUES_AGREE
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("DEFAULT_VALUES_AGREE") })
    }

    @Test
    fun `should detect field argument default value disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("type Query { field: String users(limit: Int = 10): [String] }")
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("type Query { field: String users(limit: Int = 20): [String] }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        // Types with fields generate multiple errors including DEFAULT_VALUES_AGREE
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("DEFAULT_VALUES_AGREE") })
    }

    @Test
    fun `should detect hasDefault disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("input UserInput { name: String age: Int = 18 }")
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse("input UserInput { name: String age: Int }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        // Input fields generate multiple errors including HAS_DEFAULTS_AGREE
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("HAS_DEFAULTS_AGREE") })
    }

    @Test
    fun `should detect applied directive disagreement on field`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                directive @auth on FIELD_DEFINITION
                type Query { field: String @auth }
                """.trimIndent()
            )
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                directive @auth on FIELD_DEFINITION
                type Query { field: String }
                """.trimIndent()
            )
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("SAME_DIRECTIVE_NAMES"))
    }

    @Test
    fun `should detect applied directive argument value disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                directive @deprecated(reason: String) on FIELD_DEFINITION
                type Query { field: String @deprecated(reason: "old") }
                """.trimIndent()
            )
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                """
                directive @deprecated(reason: String) on FIELD_DEFINITION
                type Query { field: String @deprecated(reason: "obsolete") }
                """.trimIndent()
            )
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("ARG_VALUE_AGREES"))
    }
}
