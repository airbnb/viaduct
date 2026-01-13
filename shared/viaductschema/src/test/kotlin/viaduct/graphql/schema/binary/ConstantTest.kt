package viaduct.graphql.schema.binary

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw

/**
 * Tests for constant encoding and decoding in binary schema format.
 *
 * These tests verify that GraphQL default values on input fields, field arguments,
 * and directive arguments are correctly encoded to binary format and decoded back.
 */
class ConstantTest {
    // ========================================
    // Simple Scalar Default Values
    // ========================================

    @Test
    fun `Input field with simple scalar defaults`() {
        val sdl = """
            input UserInput {
                name: String = "Anonymous"
                age: Int = 0
                active: Boolean = true
                score: Float = 3.14
                id: ID = "default-id"
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Field argument with simple defaults`() {
        val sdl = """
            type Query {
                user(id: ID = "123", limit: Int = 10, includeInactive: Boolean = false): String
                search(query: String = "default search", maxResults: Float = 100.0): [String]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Directive argument with default`() {
        val sdl = """
            directive @deprecated(
                reason: String = "No longer supported"
            ) on FIELD_DEFINITION

            type Query {
                oldField: String
                anotherOldField: String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // Viaduct Scalar Types
    // ========================================

    // TODO: Byte and Short scalar types fail because ValueStringConverter treats them
    // as StringValue when they should be IntValue. These tests are commented out
    // until that's fixed.

    @Test
    fun `Input fields with Viaduct scalar types - Long and Date types`() {
        val sdl = """
            scalar Long
            scalar Date
            scalar DateTime
            scalar Time
            scalar Json

            input ScalarDefaults {
                longVal: Long = "9223372036854775807"
                dateVal: Date = "2024-01-15"
                dateTimeVal: DateTime = "2024-01-15T10:30:00Z"
                timeVal: Time = "10:30:00"
                jsonVal: Json = "{\"key\": \"value\"}"
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Field arguments with Viaduct scalar defaults - Long and Date types`() {
        val sdl = """
            scalar Long
            scalar Date
            scalar DateTime
            scalar Time

            type Query {
                getData(
                    longParam: Long = "1000000"
                    dateParam: Date = "2024-12-31"
                    dateTimeParam: DateTime = "2024-12-31T23:59:59Z"
                    timeParam: Time = "23:59:59"
                ): String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Field arguments with numeric literal defaults for Byte Short Int Long`() {
        val sdl = """
            scalar Byte
            scalar Short
            scalar Long

            type Query {
                field(
                    byteArg: Byte = 127
                    shortArg: Short = 32000
                    intArg: Int = 42
                    longArg: Long = 10000
                ): String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // Enum Default Values
    // ========================================

    @Test
    fun `Input field with enum default value`() {
        val sdl = """
            enum Status {
                ACTIVE
                INACTIVE
                PENDING
            }

            input UserInput {
                status: Status = ACTIVE
                fallbackStatus: Status = INACTIVE
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Field argument with enum default`() {
        val sdl = """
            enum SortOrder {
                ASC
                DESC
            }

            type Query {
                users(sortOrder: SortOrder = ASC): [String]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // List Default Values
    // ========================================

    @Test
    fun `Input field with list defaults`() {
        val sdl = """
            input FilterInput {
                tags: [String!] = ["default", "test"]
                ids: [Int!]! = [1, 2, 3]
                scores: [Float] = [1.5, 2.5, 3.5]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Input field with empty list default`() {
        val sdl = """
            input FilterInput {
                tags: [String!] = []
                ids: [Int] = []
                optionalItems: [Float!] = []
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Field argument with list default`() {
        val sdl = """
            type Query {
                search(tags: [String!] = ["all"], ids: [Int] = [1, 2, 3]): String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Nested list defaults`() {
        val sdl = """
            input MatrixInput {
                matrix: [[Int!]!] = [[1, 2], [3, 4]]
                empty2D: [[String]] = [[]]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // Input Object Default Values
    // ========================================

    @Test
    fun `Input field with nested input object default`() {
        val sdl = """
            input PointInput {
                x: Int!
                y: Int!
            }

            input ShapeInput {
                center: PointInput = { x: 0, y: 0 }
                radius: Float = 1.0
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Input field with empty object default`() {
        val sdl = """
            input PointInput {
                x: Int = 0
                y: Int = 0
            }

            input ShapeInput {
                center: PointInput = {}
                name: String = "default"
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Field argument with input object default`() {
        val sdl = """
            input FilterInput {
                minValue: Int!
                maxValue: Int!
            }

            type Query {
                getValues(filter: FilterInput = { minValue: 0, maxValue: 100 }): [Int]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // Null Default Values
    // ========================================

    @Test
    fun `Input field with null default`() {
        val sdl = """
            input UserInput {
                middleName: String = null
                phone: String = null
                age: Int = null
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Field argument with null default`() {
        val sdl = """
            type Query {
                user(nickname: String = null, age: Int = null): String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Null in list default values`() {
        val sdl = """
            input FilterInput {
                tags: [String] = ["tag1", null, "tag3"]
                ids: [Int] = [1, null, 3]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Null in nested object default values`() {
        val sdl = """
            input PointInput {
                x: Int
                y: Int
                z: Int
            }

            input ShapeInput {
                center: PointInput = { x: 0, y: null, z: 10 }
                label: String = null
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `List of objects with null elements`() {
        val sdl = """
            input PointInput {
                x: Int!
                y: Int!
            }

            input PathInput {
                points: [PointInput] = [{ x: 0, y: 0 }, null, { x: 10, y: 10 }]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // Complex Nested Default Values
    // ========================================

    @Test
    fun `Complex nested default values with mixed types`() {
        val sdl = """
            input FilterInput {
                tags: [String!] = ["tag1", "tag2"]
                count: Int = 10
            }

            input SearchInput {
                query: String = "default search"
                filters: [FilterInput!] = [{ tags: ["a"], count: 5 }]
                limit: Int = 20
                offset: Int = 0
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Multiple levels of nesting with defaults`() {
        val sdl = """
            input Level3 {
                value: Int!
            }

            input Level2 {
                level3: Level3!
                name: String
            }

            input Level1 {
                level2: Level2 = { level3: { value: 42 }, name: "test" }
                count: Int = 100
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // Special Cases: null, "", [], {}
    // ========================================

    // Modified version without nulls until the null handling bug is fixed
    @Test
    fun `Single input with empty string empty list and empty object defaults`() {
        val sdl = """
            input PointInput {
                x: Int = 0
                y: Int = 0
            }

            input SpecialDefaultsInput {
                emptyString: String = ""
                emptyList: [String] = []
                emptyObject: PointInput = {}
                regularString: String = "not empty"
                regularInt: Int = 42
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Empty string distinction`() {
        val sdl = """
            input StringInput {
                emptyValue: String = ""
                normalValue: String = "hello"
                anotherEmpty: String = ""
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Empty list and populated list`() {
        val sdl = """
            input ListInput {
                emptyList: [Int] = []
                populatedList: [Int] = [1, 2, 3]
                anotherEmptyList: [String!] = []
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Empty object and populated object`() {
        val sdl = """
            input PointInput {
                x: Int = 0
                y: Int = 0
            }

            input ObjectInput {
                emptyObject: PointInput = {}
                populatedObject: PointInput = { x: 10, y: 20 }
                anotherEmpty: PointInput = {}
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // Deduplication Tests
    // ========================================

    @Test
    fun `Multiple fields share same default value`() {
        val sdl = """
            input Config {
                timeout: Int = 30
                retryDelay: Int = 30
                maxRetries: Int = 3
                initialDelay: Int = 3
                defaultMessage: String = "default"
                fallbackMessage: String = "default"
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Same list default value reused`() {
        val sdl = """
            input Config {
                tags1: [String!] = ["a", "b", "c"]
                tags2: [String!] = ["a", "b", "c"]
                ids1: [Int] = [1, 2, 3]
                ids2: [Int] = [1, 2, 3]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // Mixed: Multiple Inputs and Types
    // ========================================

    @Test
    fun `Multiple inputs and types with various defaults`() {
        val sdl = """
            enum Status { ACTIVE, INACTIVE }

            scalar Date

            input AddressInput {
                street: String = "123 Main St"
                city: String = "Springfield"
                zip: String = null
            }

            input UserInput {
                name: String = "Anonymous"
                email: String
                age: Int = 18
                status: Status = ACTIVE
                address: AddressInput = { street: "Unknown", city: "Unknown" }
                tags: [String!] = []
            }

            type Query {
                getUser(
                    id: ID = "default-id"
                    includeInactive: Boolean = false
                    createdAfter: Date = "2020-01-01"
                ): String

                searchUsers(
                    query: String = ""
                    limit: Int = 10
                    filters: UserInput = { name: "Search" }
                ): [String]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Directive with multiple arguments having defaults`() {
        val sdl = """
            directive @cache(
                maxAge: Int = 3600
                scope: String = "PUBLIC"
                inheritMaxAge: Boolean = true
            ) on FIELD_DEFINITION

            type Query {
                fastField: String
                customField: String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // Edge Cases
    // ========================================

    @Test
    fun `Large default values`() {
        val sdl = """
            input LargeDefaults {
                longString: String = "This is a very long default string that contains many characters and might test the limits of string encoding in the binary format"
                largeInt: Int = 2147483647
                largeFloat: Float = 3.4028235e38
                largeList: [Int!] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Special characters in string defaults`() {
        val sdl = """
            input SpecialChars {
                withNewline: String = "line1\nline2"
                withTab: String = "col1\tcol2"
                withQuotes: String = "She said \"hello\""
                withBackslash: String = "path\\to\\file"
                withUnicode: String = "Hello 世界 🌍"
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Negative numbers as defaults`() {
        val sdl = """
            input Numbers {
                negativeInt: Int = -42
                negativeFloat: Float = -3.14
                zeroInt: Int = 0
                zeroFloat: Float = 0.0
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // CompoundDefaultValue Comparison Coverage Tests
    // ========================================

    @Test
    fun `Multiple compound values with same depth for comparison branches`() {
        val sdl = """
            input Config {
                list1: [Int!] = [1, 2, 3]
                list2: [String!] = ["a", "b", "c"]
                list3: [Float!] = [1.1, 2.2, 3.3]
                list4: [Boolean!] = [true, false]
                list5: [ID!] = ["id1", "id2"]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // All Empty/Null Types Together
    // ========================================

    @Test
    fun `Input field with null empty string empty list and empty object defaults`() {
        val sdl = """
            input PointInput {
                x: Int = 0
                y: Int = 0
            }

            input AllEmptyTypes {
                nullValue: String = null
                emptyString: String = ""
                emptyList: [String] = []
                emptyObject: PointInput = {}
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // Lists with Nulls at Various Nesting Levels
    // ========================================

    @Test
    fun `List with null elements at first level`() {
        val sdl = """
            input ListWithNulls {
                values: [Int] = [1, null, 3, null, 5]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Nested list with nulls at second level`() {
        val sdl = """
            input NestedListWithNulls {
                matrix: [[Int]] = [[1, 2], null, [3, null]]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Triple nested list with nulls at multiple levels`() {
        val sdl = """
            input DeepNestedListWithNulls {
                deep: [[[Int]]] = [[[1, 2], null, [3, null]], null]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Complex nested list with nulls everywhere`() {
        val sdl = """
            input ComplexNestedNulls {
                data: [[[String]]] = [[[null, "a"], ["b", null]], null, [null, ["c"]], []]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Mixed nulls in nested object lists`() {
        val sdl = """
            input ItemInput {
                id: Int!
                name: String
            }

            input MixedNullsInObjectLists {
                items: [[ItemInput]] = [[{ id: 1, name: "a" }, { id: 2 }], null, [null, { id: 3, name: null }]]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================
    // Exception Handling for Missing Defaults
    // ========================================

    @Test
    fun `Accessing default value when hasDefault is false throws exception for field argument`() {
        val sdl = """
            type Query {
                field(arg: String): String
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = java.io.ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(java.io.ByteArrayInputStream(tmp.toByteArray()))

        val queryType = bschema.types["Query"] as ViaductSchema.Object
        val field = queryType.fields.first { it.name == "field" }
        val arg = field.args.first()

        org.junit.jupiter.api.Assertions.assertEquals(false, arg.hasDefault)
        org.junit.jupiter.api.assertThrows<NoSuchElementException> {
            arg.defaultValue
        }
    }

    @Test
    fun `Accessing default value when hasDefault is false throws exception for directive argument`() {
        val sdl = """
            directive @example(arg: String) on FIELD_DEFINITION

            type Query {
                field: String
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = java.io.ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(java.io.ByteArrayInputStream(tmp.toByteArray()))

        val directive = bschema.directives["example"]!!
        val arg = directive.args.first()

        org.junit.jupiter.api.Assertions.assertEquals(false, arg.hasDefault)
        org.junit.jupiter.api.assertThrows<NoSuchElementException> {
            arg.defaultValue
        }
    }

    @Test
    fun `Accessing default value when hasDefault is false throws exception for input field`() {
        val sdl = """
            input UserInput {
                name: String
            }
            type Query {
                user(input: UserInput): String
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            GJSchemaRaw.fromRegistry(tdr)
        }

        val tmp = java.io.ByteArrayOutputStream()
        writeBSchema(schema, tmp)
        val bschema = readBSchema(java.io.ByteArrayInputStream(tmp.toByteArray()))

        val inputType = bschema.types["UserInput"] as ViaductSchema.Input
        val field = inputType.fields.first { it.name == "name" }

        org.junit.jupiter.api.Assertions.assertEquals(false, field.hasDefault)
        org.junit.jupiter.api.assertThrows<NoSuchElementException> {
            field.defaultValue
        }
    }
}
