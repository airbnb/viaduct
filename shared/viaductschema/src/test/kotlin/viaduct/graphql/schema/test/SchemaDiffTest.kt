package viaduct.graphql.schema.test

import graphql.language.ArrayValue
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.parser.MultiSourceReader
import graphql.schema.idl.SchemaParser
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.graphqljava.GJSchemaRaw

class SchemaDiffTest {
    @Test
    fun `should detect sourceLocation disagreement`() {
        val expectedSchema = GJSchemaRaw.fromRegistry(
            SchemaParser().parse(
                MultiSourceReader
                    .newMultiSourceReader()
                    .string("scalar Foo", "file1.graphql")
                    .build()
            )
        )

        val actualSchema = GJSchemaRaw.fromRegistry(
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

    // Tests for areNodesEqual with integral scalar types
    private val integralScalarsSDL = """
        scalar Byte
        scalar Short
        scalar Int
        scalar Long
        type Query { field: String }
    """.trimIndent()

    @Test
    fun `areNodesEqual should compare IntValue and StringValue for Byte scalar`() {
        val schema = GJSchemaRaw.fromRegistry(SchemaParser().parse(integralScalarsSDL))
        val byteType = schema.types["Byte"]!!.asTypeExpr()
        val diff = SchemaDiff(schema, schema)

        val intValue = IntValue.of(42)
        val stringValue = StringValue("42")

        assertTrue(diff.areNodesEqual(intValue, stringValue, byteType))
        assertTrue(diff.areNodesEqual(stringValue, intValue, byteType))
    }

    @Test
    fun `areNodesEqual should compare IntValue and StringValue for Short scalar`() {
        val schema = GJSchemaRaw.fromRegistry(SchemaParser().parse(integralScalarsSDL))
        val shortType = schema.types["Short"]!!.asTypeExpr()
        val diff = SchemaDiff(schema, schema)

        val intValue = IntValue.of(1000)
        val stringValue = StringValue("1000")

        assertTrue(diff.areNodesEqual(intValue, stringValue, shortType))
        assertTrue(diff.areNodesEqual(stringValue, intValue, shortType))
    }

    @Test
    fun `areNodesEqual should compare IntValue and StringValue for Long scalar`() {
        val schema = GJSchemaRaw.fromRegistry(SchemaParser().parse(integralScalarsSDL))
        val longType = schema.types["Long"]!!.asTypeExpr()
        val diff = SchemaDiff(schema, schema)

        val intValue = IntValue(BigInteger.valueOf(9223372036854775807L))
        val stringValue = StringValue("9223372036854775807")

        assertTrue(diff.areNodesEqual(intValue, stringValue, longType))
        assertTrue(diff.areNodesEqual(stringValue, intValue, longType))
    }

    @Test
    fun `areNodesEqual should detect different integral values`() {
        val schema = GJSchemaRaw.fromRegistry(SchemaParser().parse(integralScalarsSDL))
        val intType = schema.types["Int"]!!.asTypeExpr()
        val diff = SchemaDiff(schema, schema)

        val intValue1 = IntValue.of(42)
        val intValue2 = IntValue.of(43)
        val stringValue = StringValue("43")

        assertFalse(diff.areNodesEqual(intValue1, intValue2, intType))
        assertFalse(diff.areNodesEqual(intValue1, stringValue, intType))
    }

    @Test
    fun `areNodesEqual should compare single-level lists of integral values`() {
        val schema = GJSchemaRaw.fromRegistry(SchemaParser().parse(integralScalarsSDL))
        val listType = schema.toTypeExpr("!!", "Long")
        val diff = SchemaDiff(schema, schema)

        val intValues = ArrayValue(
            listOf(
                IntValue.of(10),
                IntValue.of(20),
                IntValue.of(30)
            )
        )

        val stringValues = ArrayValue(
            listOf(
                StringValue("10"),
                StringValue("20"),
                StringValue("30")
            )
        )

        assertTrue(diff.areNodesEqual(intValues, stringValues, listType))
        assertTrue(diff.areNodesEqual(stringValues, intValues, listType))
    }

    @Test
    fun `areNodesEqual should detect different list lengths`() {
        val schema = GJSchemaRaw.fromRegistry(SchemaParser().parse(integralScalarsSDL))
        val listType = schema.toTypeExpr("!!", "Int")
        val diff = SchemaDiff(schema, schema)

        val list1 = ArrayValue(
            listOf(
                IntValue.of(10),
                IntValue.of(20)
            )
        )

        val list2 = ArrayValue(
            listOf(
                IntValue.of(10),
                IntValue.of(20),
                IntValue.of(30)
            )
        )

        assertFalse(diff.areNodesEqual(list1, list2, listType))
    }

    @Test
    fun `areNodesEqual should detect different list elements`() {
        val schema = GJSchemaRaw.fromRegistry(SchemaParser().parse(integralScalarsSDL))
        val listType = schema.toTypeExpr("!!", "Long")
        val diff = SchemaDiff(schema, schema)

        val list1 = ArrayValue(
            listOf(
                IntValue.of(10),
                IntValue.of(20)
            )
        )

        val list2 = ArrayValue(
            listOf(
                IntValue.of(10),
                IntValue.of(21)
            )
        )

        assertFalse(diff.areNodesEqual(list1, list2, listType))
    }

    @Test
    fun `areNodesEqual should compare nested lists of integral values`() {
        val schema = GJSchemaRaw.fromRegistry(SchemaParser().parse(integralScalarsSDL))
        val nestedListType = schema.toTypeExpr("!!!", "Byte")
        val diff = SchemaDiff(schema, schema)

        val innerList1a = ArrayValue(
            listOf(
                IntValue.of(1),
                IntValue.of(2)
            )
        )

        val innerList1b = ArrayValue(
            listOf(
                IntValue.of(3),
                IntValue.of(4)
            )
        )

        val outerList1 = ArrayValue(
            listOf(
                innerList1a,
                innerList1b
            )
        )

        val innerList2a = ArrayValue(
            listOf(
                StringValue("1"),
                StringValue("2")
            )
        )

        val innerList2b = ArrayValue(
            listOf(
                StringValue("3"),
                StringValue("4")
            )
        )

        val outerList2 = ArrayValue(
            listOf(
                innerList2a,
                innerList2b
            )
        )

        assertTrue(diff.areNodesEqual(outerList1, outerList2, nestedListType))
    }

    @Test
    fun `areNodesEqual should detect different nested list depths`() {
        val schema = GJSchemaRaw.fromRegistry(SchemaParser().parse(integralScalarsSDL))
        val nestedListType = schema.toTypeExpr("!!!", "Short")
        val diff = SchemaDiff(schema, schema)

        val innerList1a = ArrayValue(
            listOf(
                IntValue.of(1)
            )
        )

        val innerList1b = ArrayValue(
            listOf(
                IntValue.of(2),
                IntValue.of(3)
            )
        )

        val outerList1 = ArrayValue(
            listOf(
                innerList1a,
                innerList1b
            )
        )

        val innerList2a = ArrayValue(
            listOf(
                StringValue("1")
            )
        )

        val innerList2b = ArrayValue(
            listOf(
                StringValue("2")
            )
        )

        val outerList2 = ArrayValue(
            listOf(
                innerList2a,
                innerList2b
            )
        )

        assertFalse(diff.areNodesEqual(outerList1, outerList2, nestedListType))
    }

    @Test
    fun `areNodesEqual should handle empty lists`() {
        val schema = GJSchemaRaw.fromRegistry(SchemaParser().parse(integralScalarsSDL))
        val listType = schema.toTypeExpr("!!", "Int")
        val diff = SchemaDiff(schema, schema)

        val emptyList1 = ArrayValue(emptyList())
        val emptyList2 = ArrayValue(emptyList())

        assertTrue(diff.areNodesEqual(emptyList1, emptyList2, listType))
    }

    @Test
    fun `areNodesEqual should handle null values`() {
        val schema = GJSchemaRaw.fromRegistry(SchemaParser().parse(integralScalarsSDL))
        val intType = schema.types["Int"]!!.asTypeExpr()
        val diff = SchemaDiff(schema, schema)

        assertTrue(diff.areNodesEqual(null, null, intType))
        assertFalse(diff.areNodesEqual(null, IntValue.of(42), intType))
        assertFalse(diff.areNodesEqual(IntValue.of(42), null, intType))
    }
}
