package viaduct.graphql.schema.graphqljava

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.test.SchemaDiff

/**
 * Tests for default value conversion in ToGraphQLSchema.
 *
 * These tests focus on the toGraphQLValue() function and default value handling
 * throughout the conversion process.
 */
class ToGraphQLSchemaDefaultValueTest {
    // ==================== STRING VALUES ====================

    @Test
    fun `argument with empty string default`() {
        val sdl = """type Query { foo(arg: String = ""): String }"""
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `argument with string containing special characters`() {
        val sdl = """type Query { foo(arg: String = "hello\nworld"): String }"""
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `argument with unicode string default`() {
        val sdl = """type Query { foo(arg: String = "héllo wörld"): String }"""
        verifyDefaultValueRoundTrip(sdl)
    }

    // ==================== INT VALUES ====================

    @Test
    fun `argument with zero int default`() {
        val sdl = """type Query { foo(arg: Int = 0): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `argument with positive int default`() {
        val sdl = """type Query { foo(arg: Int = 42): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `argument with negative int default`() {
        val sdl = """type Query { foo(arg: Int = -100): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `argument with large int default`() {
        val sdl = """type Query { foo(arg: Int = 2147483647): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    // ==================== FLOAT VALUES ====================

    @Test
    fun `argument with zero float default`() {
        val sdl = """type Query { foo(arg: Float = 0.0): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Float"))
    }

    @Test
    fun `argument with positive float default`() {
        val sdl = """type Query { foo(arg: Float = 3.14159): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Float"))
    }

    @Test
    fun `argument with negative float default`() {
        val sdl = """type Query { foo(arg: Float = -2.5): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Float"))
    }

    @Test
    fun `argument with scientific notation float default`() {
        val sdl = """type Query { foo(arg: Float = 1.5e10): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Float"))
    }

    // ==================== BOOLEAN VALUES ====================

    @Test
    fun `argument with true boolean default`() {
        val sdl = """type Query { foo(arg: Boolean = true): String }"""
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `argument with false boolean default`() {
        val sdl = """type Query { foo(arg: Boolean = false): String }"""
        verifyDefaultValueRoundTrip(sdl)
    }

    // ==================== NULL VALUES ====================

    @Test
    fun `argument with null default`() {
        val sdl = """type Query { foo(arg: String = null): String }"""
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `argument with null default for nullable type`() {
        val sdl = """type Query { foo(arg: Int = null): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `input field with null default`() {
        val sdl = """
            input Config { value: String = null }
            type Query { foo(config: Config): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl)
    }

    // ==================== LIST VALUES ====================

    @Test
    fun `argument with empty list default`() {
        val sdl = """type Query { foo(arg: [String] = []): String }"""
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `argument with single element list default`() {
        val sdl = """type Query { foo(arg: [String] = ["one"]): String }"""
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `argument with multiple element list default`() {
        val sdl = """type Query { foo(arg: [String] = ["a", "b", "c"]): String }"""
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `argument with int list default`() {
        val sdl = """type Query { foo(arg: [Int] = [1, 2, 3, 4, 5]): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `argument with nested list default`() {
        val sdl = """type Query { foo(arg: [[Int]] = [[1, 2], [3, 4]]): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `argument with list containing null`() {
        val sdl = """type Query { foo(arg: [String] = ["a", null, "c"]): String }"""
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `argument with deeply nested list default`() {
        val sdl = """type Query { foo(arg: [[[Int]]] = [[[1, 2], [3]], [[4]]]): String }"""
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    // ==================== ENUM VALUES ====================

    @Test
    fun `argument with enum default value`() {
        val sdl = """
            enum Priority { LOW, MEDIUM, HIGH }
            type Query { foo(arg: Priority = MEDIUM): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `input field with enum default value`() {
        val sdl = """
            enum Status { PENDING, ACTIVE }
            input Config { status: Status = PENDING }
            type Query { foo(config: Config): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `argument with list of enum defaults`() {
        val sdl = """
            enum Tag { A, B, C }
            type Query { foo(tags: [Tag] = [A, B]): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl)
    }

    // ==================== OBJECT VALUES (INPUT OBJECTS) ====================

    @Test
    fun `argument with simple object default`() {
        val sdl = """
            input Point { x: Int, y: Int }
            type Query { foo(point: Point = { x: 0, y: 0 }): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `argument with object default with string field`() {
        val sdl = """
            input Config { name: String }
            type Query { foo(config: Config = { name: "default" }): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `argument with object default with multiple fields`() {
        val sdl = """
            input User {
                name: String
                age: Int
                active: Boolean
            }
            type Query { foo(user: User = { name: "test", age: 25, active: true }): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `argument with nested object default`() {
        val sdl = """
            input Inner { value: Int }
            input Outer { inner: Inner }
            type Query { foo(arg: Outer = { inner: { value: 42 } }): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `argument with object containing list field default`() {
        val sdl = """
            input Config { values: [Int] }
            type Query { foo(config: Config = { values: [1, 2, 3] }): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `argument with object containing null field default`() {
        val sdl = """
            input Config { optional: String }
            type Query { foo(config: Config = { optional: null }): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `deeply nested object defaults`() {
        val sdl = """
            input Level3 { value: String }
            input Level2 { level3: Level3 }
            input Level1 { level2: Level2 }
            type Query { foo(arg: Level1 = { level2: { level3: { value: "deep" } } }): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl)
    }

    // ==================== INPUT FIELD DEFAULTS ====================

    @Test
    fun `input field with string default`() {
        val sdl = """
            input Config { name: String = "unnamed" }
            type Query { foo(config: Config): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `input field with int default`() {
        val sdl = """
            input Config { count: Int = 0 }
            type Query { foo(config: Config): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `input field with list default`() {
        val sdl = """
            input Config { tags: [String] = [] }
            type Query { foo(config: Config): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl)
    }

    @Test
    fun `input field with nested input default`() {
        val sdl = """
            input Inner { x: Int = 0 }
            input Outer { inner: Inner = { x: 10 } }
            type Query { foo(config: Outer): String }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `multiple input fields with defaults`() {
        val sdl = """
            input Pagination {
                page: Int = 1
                pageSize: Int = 20
                sortBy: String = "id"
                ascending: Boolean = true
            }
            type Query { items(pagination: Pagination): [String] }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    // ==================== MIXED COMPLEX DEFAULTS ====================

    @Test
    fun `complex schema with various default types`() {
        val sdl = """
            enum SortOrder { ASC, DESC }

            input Range {
                min: Int = 0
                max: Int = 100
            }

            input Filter {
                search: String = ""
                range: Range = { min: 0, max: 50 }
                tags: [String] = []
                active: Boolean = true
            }

            input Pagination {
                limit: Int = 10
                offset: Int = 0
                sortOrder: SortOrder = ASC
            }

            type Query {
                items(
                    filter: Filter = { search: "", active: true },
                    pagination: Pagination = { limit: 20 }
                ): [String]
            }
        """.trimIndent()
        verifyDefaultValueRoundTrip(sdl, setOf("Int"))
    }

    // ==================== DIRECT VALUE CONVERSION TESTS ====================

    @Test
    fun `toGraphQLSchema preserves StringValue argument default`() {
        val sdl = """type Query { foo(arg: String = "test"): String }"""
        val schema = parseAndConvert(sdl)

        val arg = schema.queryType.getFieldDefinition("foo").getArgument("arg")
        arg.hasSetDefaultValue() shouldBe true

        val defaultValue = arg.argumentDefaultValue.value
        defaultValue.shouldBeInstanceOf<StringValue>()
        (defaultValue as StringValue).value shouldBe "test"
    }

    @Test
    fun `toGraphQLSchema preserves IntValue argument default`() {
        val sdl = """type Query { foo(arg: Int = 42): String }"""
        val schema = parseAndConvert(sdl, setOf("Int"))

        val arg = schema.queryType.getFieldDefinition("foo").getArgument("arg")
        arg.hasSetDefaultValue() shouldBe true

        val defaultValue = arg.argumentDefaultValue.value
        defaultValue.shouldBeInstanceOf<IntValue>()
        (defaultValue as IntValue).value shouldBe BigInteger.valueOf(42)
    }

    @Test
    fun `toGraphQLSchema preserves FloatValue argument default`() {
        val sdl = """type Query { foo(arg: Float = 3.14): String }"""
        val schema = parseAndConvert(sdl, setOf("Float"))

        val arg = schema.queryType.getFieldDefinition("foo").getArgument("arg")
        arg.hasSetDefaultValue() shouldBe true

        val defaultValue = arg.argumentDefaultValue.value
        defaultValue.shouldBeInstanceOf<FloatValue>()
        (defaultValue as FloatValue).value shouldBe BigDecimal("3.14")
    }

    @Test
    fun `toGraphQLSchema preserves BooleanValue argument default`() {
        val sdl = """type Query { foo(arg: Boolean = true): String }"""
        val schema = parseAndConvert(sdl)

        val arg = schema.queryType.getFieldDefinition("foo").getArgument("arg")
        arg.hasSetDefaultValue() shouldBe true

        val defaultValue = arg.argumentDefaultValue.value
        defaultValue.shouldBeInstanceOf<BooleanValue>()
        (defaultValue as BooleanValue).isValue shouldBe true
    }

    @Test
    fun `toGraphQLSchema preserves NullValue argument default`() {
        val sdl = """type Query { foo(arg: String = null): String }"""
        val schema = parseAndConvert(sdl)

        val arg = schema.queryType.getFieldDefinition("foo").getArgument("arg")
        arg.hasSetDefaultValue() shouldBe true

        val defaultValue = arg.argumentDefaultValue.value
        defaultValue.shouldBeInstanceOf<NullValue>()
    }

    @Test
    fun `toGraphQLSchema preserves ArrayValue argument default`() {
        val sdl = """type Query { foo(arg: [Int] = [1, 2, 3]): String }"""
        val schema = parseAndConvert(sdl, setOf("Int"))

        val arg = schema.queryType.getFieldDefinition("foo").getArgument("arg")
        arg.hasSetDefaultValue() shouldBe true

        val defaultValue = arg.argumentDefaultValue.value
        defaultValue.shouldBeInstanceOf<ArrayValue>()
        val arrayValue = defaultValue as ArrayValue
        arrayValue.values.size shouldBe 3
        arrayValue.values.map { (it as IntValue).value.toInt() } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `toGraphQLSchema preserves ObjectValue argument default`() {
        val sdl = """
            input Point { x: Int, y: Int }
            type Query { foo(point: Point = { x: 10, y: 20 }): String }
        """.trimIndent()
        val schema = parseAndConvert(sdl, setOf("Int"))

        val arg = schema.queryType.getFieldDefinition("foo").getArgument("point")
        arg.hasSetDefaultValue() shouldBe true

        val defaultValue = arg.argumentDefaultValue.value
        defaultValue.shouldBeInstanceOf<ObjectValue>()
        val objectValue = defaultValue as ObjectValue
        objectValue.objectFields.size shouldBe 2

        val xField = objectValue.objectFields.find { it.name == "x" }
        xField.shouldNotBeNull()
        (xField.value as IntValue).value shouldBe BigInteger.valueOf(10)

        val yField = objectValue.objectFields.find { it.name == "y" }
        yField.shouldNotBeNull()
        (yField.value as IntValue).value shouldBe BigInteger.valueOf(20)
    }

    @Test
    fun `toGraphQLSchema preserves input field default value`() {
        val sdl = """
            input Config { limit: Int = 100 }
            type Query { foo(config: Config): String }
        """.trimIndent()
        val schema = parseAndConvert(sdl, setOf("Int"))

        val configType = schema.getTypeAs<graphql.schema.GraphQLInputObjectType>("Config")
        val limitField = configType.getFieldDefinition("limit")
        limitField.hasSetDefaultValue() shouldBe true

        val defaultValue = limitField.inputFieldDefaultValue.value
        defaultValue.shouldBeInstanceOf<IntValue>()
        (defaultValue as IntValue).value shouldBe BigInteger.valueOf(100)
    }

    // ==================== HELPER METHODS ====================

    companion object {
        private fun verifyDefaultValueRoundTrip(
            sdl: String,
            scalarsNeeded: Set<String> = emptySet(),
        ) {
            val tdr = SchemaParser().parse(sdl)
            val expectedGraphQLSchema = SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
            val expectedViaductSchema = GJSchema.fromSchema(expectedGraphQLSchema)

            val actualGraphQLSchema = expectedViaductSchema.toGraphQLSchema(scalarsNeeded)
            val actualViaductSchema = GJSchema.fromSchema(actualGraphQLSchema)

            // Verify structural equivalence
            val schemaDiffResult = SchemaDiff(expectedViaductSchema, actualViaductSchema).diff()
            schemaDiffResult.assertEmpty()

            // Verify runtime properties (default values, deprecation)
            val extraDiffResult = GraphQLSchemaExtraDiff(expectedGraphQLSchema, actualGraphQLSchema).diff()
            extraDiffResult.assertEmpty()
        }

        private fun parseAndConvert(
            sdl: String,
            scalarsNeeded: Set<String> = emptySet(),
        ): graphql.schema.GraphQLSchema {
            val tdr = SchemaParser().parse(sdl)
            val originalSchema = SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
            val viaductSchema = GJSchema.fromSchema(originalSchema)
            return viaductSchema.toGraphQLSchema(scalarsNeeded)
        }
    }
}
