package viaduct.graphql.schema.test

import graphql.parser.MultiSourceReader
import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.graphqljava.gjSchemaRawFromRegistry
import viaduct.graphql.schema.toTypeExpr

class SchemaDiffTest {
    @Test
    fun `should detect sourceLocation disagreement`() {
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse(
                MultiSourceReader
                    .newMultiSourceReader()
                    .string("scalar Foo", "file1.graphql")
                    .build()
            )
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse(
                MultiSourceReader
                    .newMultiSourceReader()
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("scalar Foo\nscalar Bar")
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("scalar Foo")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("SAME_TYPE_NAMES"))
    }

    @Test
    fun `should detect type kind disagreement`() {
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("scalar Foo")
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("enum Foo { A B }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("DEF_CLASS_AGREE"))
    }

    @Test
    fun `should detect enum value disagreement`() {
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("enum Status { ACTIVE INACTIVE PENDING }")
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("type User { id: ID! name: String email: String }")
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("type Query { user(id: ID!): String }")
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("type Query { user(userId: ID!): String }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("SAME_ARG_NAMES"))
    }

    @Test
    fun `should detect field argument type disagreement`() {
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("type Query { user(id: ID!): String }")
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("type Query { user(id: String!): String }")
        )

        val checker = SchemaDiff(expectedSchema, actualSchema).diff()
        val errors = checker.toListOfErrors()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("ARG_TYPE_AGREE"))
    }

    @Test
    fun `should detect input field name disagreement`() {
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("input UserInput { name: String age: Int email: String }")
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse(
                """
                interface Node { id: ID! }
                interface Named { name: String }
                interface Timestamped { createdAt: String }
                type User implements Node & Named & Timestamped { id: ID! name: String createdAt: String }
                """.trimIndent()
            )
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse(
                """
                type Cat { meow: String }
                type Dog { bark: String }
                type Bird { chirp: String }
                union Pet = Cat | Dog | Bird
                """.trimIndent()
            )
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse(
                """
                directive @deprecated(reason: String) on FIELD_DEFINITION
                directive @auth on FIELD_DEFINITION
                type Query { field: String }
                """.trimIndent()
            )
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse(
                """
                directive @deprecated(reason: String) on FIELD_DEFINITION
                type Query { field: String }
                """.trimIndent()
            )
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("input UserInput { name: String age: Int = 18 }")
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("type Query { field: String users(limit: Int = 10): [String] }")
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse("input UserInput { name: String age: Int = 18 }")
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse(
                """
                directive @auth on FIELD_DEFINITION
                type Query { field: String @auth }
                """.trimIndent()
            )
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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
        val expectedSchema = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse(
                """
                directive @deprecated(reason: String) on FIELD_DEFINITION
                type Query { field: String @deprecated(reason: "old") }
                """.trimIndent()
            )
        )

        val actualSchema = ViaductSchema.fromTypeDefinitionRegistry(
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

    // Tests for areValuesEqual with integral scalar types
    private val integralScalarsSDL = """
        scalar Byte
        scalar Short
        scalar Int
        scalar Long
        type Query { field: String }
    """.trimIndent()

    @Test
    fun `areValuesEqual should compare IntValue and StringValue for Byte scalar`() {
        val schema = gjSchemaRawFromRegistry(SchemaParser().parse(integralScalarsSDL))
        val byteType = schema.types["Byte"]!!.asTypeExpr()
        val diff = SchemaDiff(schema, schema)

        val intValue = ViaductSchema.IntLiteral.of("42")
        val stringValue = ViaductSchema.StringLiteral.of("42")

        assertTrue(diff.areValuesEqual(intValue, stringValue, byteType))
        assertTrue(diff.areValuesEqual(stringValue, intValue, byteType))
    }

    @Test
    fun `areValuesEqual should compare IntValue and StringValue for Short scalar`() {
        val schema = gjSchemaRawFromRegistry(SchemaParser().parse(integralScalarsSDL))
        val shortType = schema.types["Short"]!!.asTypeExpr()
        val diff = SchemaDiff(schema, schema)

        val intValue = ViaductSchema.IntLiteral.of("1000")
        val stringValue = ViaductSchema.StringLiteral.of("1000")

        assertTrue(diff.areValuesEqual(intValue, stringValue, shortType))
        assertTrue(diff.areValuesEqual(stringValue, intValue, shortType))
    }

    @Test
    fun `areValuesEqual should compare IntValue and StringValue for Long scalar`() {
        val schema = gjSchemaRawFromRegistry(SchemaParser().parse(integralScalarsSDL))
        val longType = schema.types["Long"]!!.asTypeExpr()
        val diff = SchemaDiff(schema, schema)

        val intValue = ViaductSchema.IntLiteral.of("9223372036854775807")
        val stringValue = ViaductSchema.StringLiteral.of("9223372036854775807")

        assertTrue(diff.areValuesEqual(intValue, stringValue, longType))
        assertTrue(diff.areValuesEqual(stringValue, intValue, longType))
    }

    @Test
    fun `areValuesEqual should detect different integral values`() {
        val schema = gjSchemaRawFromRegistry(SchemaParser().parse(integralScalarsSDL))
        val intType = schema.types["Int"]!!.asTypeExpr()
        val diff = SchemaDiff(schema, schema)

        val intValue1 = ViaductSchema.IntLiteral.of("42")
        val intValue2 = ViaductSchema.IntLiteral.of("43")
        val stringValue = ViaductSchema.StringLiteral.of("43")

        assertFalse(diff.areValuesEqual(intValue1, intValue2, intType))
        assertFalse(diff.areValuesEqual(intValue1, stringValue, intType))
    }

    @Test
    fun `areValuesEqual should compare single-level lists of integral values`() {
        val schema = gjSchemaRawFromRegistry(SchemaParser().parse(integralScalarsSDL))
        val listType = schema.toTypeExpr("!!", "Long")
        val diff = SchemaDiff(schema, schema)

        val intValues = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("10"),
                ViaductSchema.IntLiteral.of("20"),
                ViaductSchema.IntLiteral.of("30")
            )
        )

        val stringValues = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.StringLiteral.of("10"),
                ViaductSchema.StringLiteral.of("20"),
                ViaductSchema.StringLiteral.of("30")
            )
        )

        assertTrue(diff.areValuesEqual(intValues, stringValues, listType))
        assertTrue(diff.areValuesEqual(stringValues, intValues, listType))
    }

    @Test
    fun `areValuesEqual should detect different list lengths`() {
        val schema = gjSchemaRawFromRegistry(SchemaParser().parse(integralScalarsSDL))
        val listType = schema.toTypeExpr("!!", "Int")
        val diff = SchemaDiff(schema, schema)

        val list1 = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("10"),
                ViaductSchema.IntLiteral.of("20")
            )
        )

        val list2 = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("10"),
                ViaductSchema.IntLiteral.of("20"),
                ViaductSchema.IntLiteral.of("30")
            )
        )

        assertFalse(diff.areValuesEqual(list1, list2, listType))
    }

    @Test
    fun `areValuesEqual should detect different list elements`() {
        val schema = gjSchemaRawFromRegistry(SchemaParser().parse(integralScalarsSDL))
        val listType = schema.toTypeExpr("!!", "Long")
        val diff = SchemaDiff(schema, schema)

        val list1 = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("10"),
                ViaductSchema.IntLiteral.of("20")
            )
        )

        val list2 = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("10"),
                ViaductSchema.IntLiteral.of("21")
            )
        )

        assertFalse(diff.areValuesEqual(list1, list2, listType))
    }

    @Test
    fun `areValuesEqual should compare nested lists of integral values`() {
        val schema = gjSchemaRawFromRegistry(SchemaParser().parse(integralScalarsSDL))
        val nestedListType = schema.toTypeExpr("!!!", "Byte")
        val diff = SchemaDiff(schema, schema)

        val innerList1a = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("1"),
                ViaductSchema.IntLiteral.of("2")
            )
        )

        val innerList1b = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("3"),
                ViaductSchema.IntLiteral.of("4")
            )
        )

        val outerList1 = ViaductSchema.ListLiteral.of(
            listOf(
                innerList1a,
                innerList1b
            )
        )

        val innerList2a = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.StringLiteral.of("1"),
                ViaductSchema.StringLiteral.of("2")
            )
        )

        val innerList2b = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.StringLiteral.of("3"),
                ViaductSchema.StringLiteral.of("4")
            )
        )

        val outerList2 = ViaductSchema.ListLiteral.of(
            listOf(
                innerList2a,
                innerList2b
            )
        )

        assertTrue(diff.areValuesEqual(outerList1, outerList2, nestedListType))
    }

    @Test
    fun `areValuesEqual should detect different nested list depths`() {
        val schema = gjSchemaRawFromRegistry(SchemaParser().parse(integralScalarsSDL))
        val nestedListType = schema.toTypeExpr("!!!", "Short")
        val diff = SchemaDiff(schema, schema)

        val innerList1a = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("1")
            )
        )

        val innerList1b = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("2"),
                ViaductSchema.IntLiteral.of("3")
            )
        )

        val outerList1 = ViaductSchema.ListLiteral.of(
            listOf(
                innerList1a,
                innerList1b
            )
        )

        val innerList2a = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.StringLiteral.of("1")
            )
        )

        val innerList2b = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.StringLiteral.of("2")
            )
        )

        val outerList2 = ViaductSchema.ListLiteral.of(
            listOf(
                innerList2a,
                innerList2b
            )
        )

        assertFalse(diff.areValuesEqual(outerList1, outerList2, nestedListType))
    }

    @Test
    fun `areValuesEqual should handle empty lists`() {
        val schema = gjSchemaRawFromRegistry(SchemaParser().parse(integralScalarsSDL))
        val listType = schema.toTypeExpr("!!", "Int")
        val diff = SchemaDiff(schema, schema)

        val emptyList1 = ViaductSchema.ListLiteral.of(emptyList())
        val emptyList2 = ViaductSchema.ListLiteral.of(emptyList())

        assertTrue(diff.areValuesEqual(emptyList1, emptyList2, listType))
    }

    @Test
    fun `areValuesEqual should handle null values`() {
        val schema = gjSchemaRawFromRegistry(SchemaParser().parse(integralScalarsSDL))
        val intType = schema.types["Int"]!!.asTypeExpr()
        val diff = SchemaDiff(schema, schema)

        assertTrue(diff.areValuesEqual(null, null, intType))
        assertFalse(diff.areValuesEqual(null, ViaductSchema.IntLiteral.of("42"), intType))
        assertFalse(diff.areValuesEqual(ViaductSchema.IntLiteral.of("42"), null, intType))
    }
}
