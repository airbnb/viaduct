package viaduct.graphql.schema.test

/**
 * Shared test schemas for round-trip and comparison tests across all ViaductSchema implementations.
 *
 * These are "black-box" schemas that test GraphQL semantics rather than implementation details.
 * Each schema should work correctly with BSchema, GJSchema, GJSchemaRaw, and FilteredSchema.
 *
 * Organization follows GraphQL definition kinds: DIRECTIVE, ENUM, INPUT, INTERFACE, OBJECT, SCALAR, UNION, ROOT.
 *
 * ## Deep Testing Patterns
 *
 * Based on SmallSchemaTest.kt, we test four patterns for default values on arguments/fields:
 * - nullableNoDefault: nullable, no default (effectiveDefault = null)
 * - nullableWithNonNullDefault: nullable with non-null default
 * - nullableWithNullDefault: nullable with explicit null default
 * - nonNullableWithDefault: non-nullable with default
 *
 * For applied directives, we test:
 * - All arguments provided explicitly
 * - No arguments provided (uses defaults/null)
 * - Input type arguments with nested defaults
 */

/**
 * Collection of black-box test schemas organized by GraphQL definition kind.
 */
object TestSchemas {
    /**
     * Base schema that includes built-in directives and scalars.
     *
     * This must be prepended to test schemas when using GJSchemaRaw because:
     * - GJSchemaRaw reads directly from TypeDefinitionRegistry and doesn't auto-add anything
     * - GraphQLSchema (used by GJSchema) auto-adds built-in directives, so we need them for comparison
     * - The BuiltinScalarsAnchor type ensures scalars are "used" so GraphQLSchema includes them
     */
    private val BASE_SCHEMA = """
        # Built-in directives (graphql-java adds these to GraphQLSchema)
        directive @include(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT
        directive @skip(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT
        directive @defer(if: Boolean! = true, label: String) on FRAGMENT_SPREAD | INLINE_FRAGMENT
        directive @experimental_disableErrorPropagation on QUERY | MUTATION | SUBSCRIPTION

        # Built-in scalars
        scalar Boolean
        scalar Float
        scalar ID
        scalar Int
        scalar String

        # Anchor type that uses all built-in scalars to ensure they're included in GraphQLSchema
        type BuiltinScalarsAnchor {
            _b: Boolean
            _f: Float
            _id: ID
            _i: Int
            _s: String
        }
    """.trimIndent()

    /**
     * A test schema with a name, primary GraphQL kind, and SDL content.
     *
     * @param gjIncompatible If non-null, explains why this schema is incompatible with
     *   graphql-java based tests due to graphql-java bugs or limitations. These schemas
     *   represent valid GraphQL that works correctly with BSchema but cannot be processed
     *   by graphql-java's SchemaGenerator or related APIs.
     */
    data class Case(
        val name: String,
        val kind: String,
        val sdl: String,
        val gjIncompatible: String? = null
    ) {
        /** The SDL with built-in directives and scalars prepended. */
        val fullSdl: String get() = "$BASE_SCHEMA\n$sdl"
    }

    // ===================================================================================
    // DIRECTIVE - Directive definitions and applied directives
    // ===================================================================================

    val DIRECTIVE: List<Case> = listOf(
        // Basic directive definitions
        Case(
            "repeatable with multiple locations",
            "DIRECTIVE",
            """
            directive @tag(name: String!) repeatable on FIELD_DEFINITION | OBJECT | INTERFACE

            type Query { user: String }
            """.trimIndent()
        ),
        Case(
            "multiple arguments with defaults",
            "DIRECTIVE",
            """
            directive @cache(maxAge: Int = 3600, scope: String = "PUBLIC") on FIELD_DEFINITION

            type Query { field: String }
            """.trimIndent()
        ),
        Case(
            "non-repeatable single location",
            "DIRECTIVE",
            """
            directive @auth on FIELD_DEFINITION

            type Query { field: String }
            """.trimIndent()
        ),
        Case(
            "no arguments",
            "DIRECTIVE",
            """
            directive @internal on FIELD_DEFINITION

            type Query { field: String }
            """.trimIndent()
        ),
        // Deep pattern: Four default value patterns (from SmallSchemaTest)
        Case(
            "deep - four default value patterns on directive args",
            "DIRECTIVE",
            """
            directive @scalarArgs(
                nullableNoDefault: String
                nullableWithNonNullDefault: String = "hello"
                nullableWithNullDefault: String = null
                nonNullableWithDefault: String! = "required"
            ) on OBJECT

            type Query { field: String }
            """.trimIndent()
        ),
        // Deep pattern: Input type as directive argument
        Case(
            "deep - input type argument with nested defaults",
            "DIRECTIVE",
            """
            input InputWithDefaults {
                nullableNoDefault: String
                nullableWithNonNullDefault: String = "inputHello"
                nullableWithNullDefault: String = null
                nonNullableWithDefault: String! = "inputRequired"
            }

            directive @inputArg(arg: InputWithDefaults) on OBJECT

            type Query { field: String }
            """.trimIndent()
        ),
        // Applied directives on different schema elements
        Case(
            "applied - on type definition",
            "DIRECTIVE",
            """
            directive @typeTag(name: String!) on OBJECT

            type TaggedUser @typeTag(name: "legacy") {
                name: String
            }

            type Query { user: TaggedUser }
            """.trimIndent()
        ),
        Case(
            "applied - on field definition",
            "DIRECTIVE",
            """
            type User {
                oldName: String @deprecated(reason: "Use displayName")
                displayName: String
            }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "applied - on enum value",
            "DIRECTIVE",
            """
            enum Status {
                ACTIVE
                INACTIVE @deprecated(reason: "Use ARCHIVED")
                ARCHIVED
            }

            type Query { status: Status }
            """.trimIndent()
        ),
        Case(
            "applied - on input field",
            "DIRECTIVE",
            """
            input UserInput {
                oldEmail: String @deprecated(reason: "Use email field")
                email: String
            }

            type Query { createUser(input: UserInput): String }
            """.trimIndent()
        ),
        Case(
            "applied - on field argument",
            "DIRECTIVE",
            """
            type Query {
                user(oldId: ID @deprecated(reason: "Use id parameter"), id: ID): String
            }
            """.trimIndent()
        ),
        Case(
            "applied - on scalar type",
            "DIRECTIVE",
            """
            directive @scalarTag(format: String!) on SCALAR

            scalar MyDateTime @scalarTag(format: "ISO8601")

            type Query { created: MyDateTime }
            """.trimIndent()
        ),
        Case(
            "applied - on interface field",
            "DIRECTIVE",
            """
            interface Node {
                id: ID!
                legacyId: String @deprecated(reason: "Use id field")
            }

            type User implements Node {
                id: ID!
                legacyId: String @deprecated(reason: "Use id field")
                name: String
            }

            type Query { node: Node }
            """.trimIndent()
        ),
        // Complex default values
        Case(
            "definition with list default",
            "DIRECTIVE",
            """
            directive @tag(labels: [String!]! = ["default", "untagged"]) on FIELD_DEFINITION

            type User {
                name: String @tag
                email: String @tag(labels: ["contact", "pii"])
            }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "definition with input object default",
            "DIRECTIVE",
            """
            input CacheConfig {
                maxAge: Int!
                scope: String!
            }

            directive @cache(config: CacheConfig = {maxAge: 3600, scope: "PUBLIC"}) on FIELD_DEFINITION

            type User {
                name: String @cache
                email: String @cache(config: {maxAge: 300, scope: "PRIVATE"})
            }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "definition with nested list default",
            "DIRECTIVE",
            """
            directive @matrix(grid: [[Int!]!]! = [[1, 2], [3, 4]]) on FIELD_DEFINITION

            type Query {
                defaultMatrix: String @matrix
                customMatrix: String @matrix(grid: [[5, 6, 7], [8, 9, 10]])
            }
            """.trimIndent()
        ),
        // Multiple applied directives
        Case(
            "multiple directives on single element",
            "DIRECTIVE",
            """
            directive @auth(requires: String!) on FIELD_DEFINITION

            type User {
                secretData: String @auth(requires: "ADMIN") @deprecated(reason: "Moving to new API")
            }

            type Query { user: User }
            """.trimIndent()
        ),
        // Deep pattern: All args provided vs none provided (from SmallSchemaTest)
        Case(
            "deep - applied directive all args provided",
            "DIRECTIVE",
            """
            directive @scalarArgs(
                nullableNoDefault: String
                nullableWithNonNullDefault: String = "hello"
                nullableWithNullDefault: String = null
                nonNullableWithDefault: String! = "required"
            ) on OBJECT

            type ScalarArgsAllProvided @scalarArgs(
                nullableNoDefault: "explicit1"
                nullableWithNonNullDefault: "explicit2"
                nullableWithNullDefault: "explicit3"
                nonNullableWithDefault: "explicit4"
            ) {
                stub: String
            }

            type Query { obj: ScalarArgsAllProvided }
            """.trimIndent()
        ),
        Case(
            "deep - applied directive no args provided",
            "DIRECTIVE",
            """
            directive @scalarArgs(
                nullableNoDefault: String
                nullableWithNonNullDefault: String = "hello"
                nullableWithNullDefault: String = null
                nonNullableWithDefault: String! = "required"
            ) on OBJECT

            type ScalarArgsNoneProvided @scalarArgs {
                stub: String
            }

            type Query { obj: ScalarArgsNoneProvided }
            """.trimIndent()
        ),
        // Deep pattern: Input type arg - all fields vs empty object
        Case(
            "deep - input arg all fields provided",
            "DIRECTIVE",
            """
            input InputWithDefaults {
                nullableNoDefault: String
                nullableWithNonNullDefault: String = "inputHello"
                nullableWithNullDefault: String = null
                nonNullableWithDefault: String! = "inputRequired"
            }

            directive @inputArg(arg: InputWithDefaults) on OBJECT

            type InputArgAllProvided @inputArg(arg: {
                nullableNoDefault: "inputExplicit1"
                nullableWithNonNullDefault: "inputExplicit2"
                nullableWithNullDefault: "inputExplicit3"
                nonNullableWithDefault: "inputExplicit4"
            }) {
                stub: String
            }

            type Query { obj: InputArgAllProvided }
            """.trimIndent()
        ),
        Case(
            "deep - input arg empty object",
            "DIRECTIVE",
            """
            input InputWithDefaults {
                nullableNoDefault: String
                nullableWithNonNullDefault: String = "inputHello"
                nullableWithNullDefault: String = null
                nonNullableWithDefault: String! = "inputRequired"
            }

            directive @inputArg(arg: InputWithDefaults) on OBJECT

            type InputArgNoneProvided @inputArg(arg: {}) {
                stub: String
            }

            type Query { obj: InputArgNoneProvided }
            """.trimIndent()
        ),
        // Argument reconstruction scenarios
        Case(
            "applied - argument omission matching default",
            "DIRECTIVE",
            """
            directive @cache(maxAge: Int = 3600) on FIELD_DEFINITION

            type Query {
                defaultCache: String @cache
                explicitDefault: String @cache(maxAge: 3600)
                customCache: String @cache(maxAge: 600)
            }
            """.trimIndent()
        ),
        Case(
            "applied - null for nullable no default",
            "DIRECTIVE",
            """
            directive @tag(label: String) on FIELD_DEFINITION

            type Query {
                explicitNull: String @tag(label: null)
                withLabel: String @tag(label: "important")
            }
            """.trimIndent()
        ),
        Case(
            "applied - mixed argument scenarios",
            "DIRECTIVE",
            """
            directive @config(
                required: String!
                withDefault: Int = 100
                nullable: String
            ) on FIELD_DEFINITION

            type Query {
                allExplicit: String @config(required: "must-provide", withDefault: 200, nullable: "value")
                minimal: String @config(required: "must-provide")
                partialExplicit: String @config(required: "must-provide", withDefault: 50)
            }
            """.trimIndent()
        ),
        Case(
            "null default vs nullable no default",
            "DIRECTIVE",
            """
            directive @withNullDefault(value: String = null) on FIELD_DEFINITION
            directive @nullableNoDefault(value: String) on FIELD_DEFINITION

            type Query {
                nullDefault1: String @withNullDefault
                nullDefault2: String @withNullDefault(value: null)
                nullDefault3: String @withNullDefault(value: "specified")
                noDefault1: String @nullableNoDefault(value: null)
                noDefault2: String @nullableNoDefault(value: "specified")
            }
            """.trimIndent()
        ),
        // Repeatable directive
        Case(
            "repeatable with argument variations",
            "DIRECTIVE",
            """
            directive @tag(name: String!, priority: Int = 0) repeatable on FIELD_DEFINITION

            type Query {
                field: String @tag(name: "first") @tag(name: "second", priority: 10) @tag(name: "third")
            }
            """.trimIndent()
        ),
        // Directive applied to directive argument (non-circular)
        Case(
            "directive on directive argument",
            "DIRECTIVE",
            """
            directive @meta(info: String!) on ARGUMENT_DEFINITION
            directive @validated(min: Int @meta(info: "minimum value")) on FIELD_DEFINITION

            type Query { field(value: Int): String @validated(min: 0) }
            """.trimIndent()
        ),
        // Missing directive locations (all 18 GraphQL locations should be representable)
        Case(
            "location - query-side executable",
            "DIRECTIVE",
            """
            directive @client on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT

            type Query { field: String }
            """.trimIndent()
        ),
        Case(
            "location - operation level",
            "DIRECTIVE",
            """
            directive @auth on QUERY | MUTATION | SUBSCRIPTION

            type Query { field: String }
            """.trimIndent()
        ),
        Case(
            "location - fragment definition",
            "DIRECTIVE",
            """
            directive @trackFragment on FRAGMENT_DEFINITION

            type Query { field: String }
            """.trimIndent()
        ),
        Case(
            "location - schema",
            "DIRECTIVE",
            """
            directive @schemaVersion(version: String!) on SCHEMA

            type Query { field: String }
            """.trimIndent()
        ),
        Case(
            "location - variable definition",
            "DIRECTIVE",
            """
            directive @validate on VARIABLE_DEFINITION

            type Query { field: String }
            """.trimIndent()
        ),
        Case(
            "location - argument definition",
            "DIRECTIVE",
            """
            directive @constraint(max: Int) on ARGUMENT_DEFINITION

            type Query { field(limit: Int @constraint(max: 100)): String }
            """.trimIndent()
        ),
        Case(
            "location - input field definition",
            "DIRECTIVE",
            """
            directive @sensitive on INPUT_FIELD_DEFINITION

            input UserInput { password: String @sensitive }

            type Query { createUser(input: UserInput): String }
            """.trimIndent()
        ),
        Case(
            "location - enum value",
            "DIRECTIVE",
            """
            directive @internal on ENUM_VALUE

            enum Status {
                PUBLIC
                INTERNAL @internal
            }

            type Query { status: Status }
            """.trimIndent()
        )
    )

    // ===================================================================================
    // ENUM - Enum type definitions
    // ===================================================================================

    val ENUM: List<Case> = listOf(
        Case(
            "simple enum",
            "ENUM",
            """
            enum Status { ACTIVE, INACTIVE, PENDING }

            type Query { status: Status }
            """.trimIndent()
        ),
        Case(
            "enum with deprecated value",
            "ENUM",
            """
            enum Status {
                ACTIVE
                INACTIVE @deprecated(reason: "Use ARCHIVED")
                ARCHIVED
            }

            type Query { status: Status }
            """.trimIndent()
        ),
        Case(
            "enum as field type",
            "ENUM",
            """
            enum SortOrder { ASC, DESC }

            type Query {
                users(sortOrder: SortOrder = ASC): [String]
            }
            """.trimIndent()
        ),
        Case(
            "enum as input default",
            "ENUM",
            """
            enum Status { ACTIVE, INACTIVE, PENDING }

            input UserInput {
                status: Status = ACTIVE
                fallbackStatus: Status = INACTIVE
            }

            type Query { createUser(input: UserInput): String }
            """.trimIndent()
        ),
        Case(
            "enum with custom applied directive",
            "ENUM",
            """
            directive @tag(name: String!) on ENUM

            enum Status @tag(name: "core") {
                ACTIVE
                INACTIVE
            }

            type Query { status: Status }
            """.trimIndent()
        ),
        Case(
            "enum with extension",
            "ENUM",
            """
            enum Status { ACTIVE }

            extend enum Status { INACTIVE, PENDING }
            extend enum Status { ARCHIVED, DELETED }

            type Query { status: Status }
            """.trimIndent()
        ),
        Case(
            "enum with empty base and values in extension",
            "ENUM",
            """
            enum Status

            extend enum Status { ACTIVE, INACTIVE }

            type Query { status: Status }
            """.trimIndent()
        ),
        Case(
            "enum with directives on extension values",
            "ENUM",
            """
            enum Status { ACTIVE }

            extend enum Status {
                OLD_INACTIVE @deprecated(reason: "Use INACTIVE")
                INACTIVE
            }

            type Query { status: Status }
            """.trimIndent()
        ),
        Case(
            "enum with directives on extension itself",
            "ENUM",
            """
            directive @tag(name: String!) repeatable on ENUM

            enum Status @tag(name: "core") { ACTIVE }

            extend enum Status @tag(name: "legacy") { INACTIVE }

            type Query { status: Status }
            """.trimIndent()
        ),
        Case(
            "unused enum",
            "ENUM",
            """
            enum UnusedStatus { A, B, C }

            type Query { field: String }
            """.trimIndent()
        )
    )

    // ===================================================================================
    // INPUT - Input type definitions with default value patterns
    // ===================================================================================

    val INPUT: List<Case> = listOf(
        Case(
            "simple input",
            "INPUT",
            """
            input Simple { i: Int }

            type Query { field(input: Simple): String }
            """.trimIndent()
        ),
        // Type wrapper variations
        Case(
            "non-null wrapper",
            "INPUT",
            """
            input Simple { i: Int! }

            type Query { field(input: Simple): String }
            """.trimIndent()
        ),
        Case(
            "list wrapper",
            "INPUT",
            """
            input Simple { i: [Int] }

            type Query { field(input: Simple): String }
            """.trimIndent()
        ),
        Case(
            "list wrapper combinations",
            "INPUT",
            """
            input Simple {
                ll: [[Int]]
                lbl: [[Int]]!
                lblb: [[Int]!]!
                lblbb: [[Int!]!]!
                lllll: [[[[[Int]]]]]
            }

            type Query { field(input: Simple): String }
            """.trimIndent()
        ),
        Case(
            "list depth 6",
            "INPUT",
            """
            input Simple {
                depth6: [[[[[[Int]]]]]]
                depth6NonNull: [[[[[[Int!]!]!]!]!]!]!
            }

            type Query { field(input: Simple): String }
            """.trimIndent()
        ),
        // Deep pattern: Four default value patterns (from SmallSchemaTest)
        Case(
            "deep - four default value patterns",
            "INPUT",
            """
            input InputWithDefaults {
                nullableNoDefault: String
                nullableWithNonNullDefault: String = "hello"
                nullableWithNullDefault: String = null
                nonNullableWithDefault: String! = "required"
            }

            type Query { field(input: InputWithDefaults): String }
            """.trimIndent()
        ),
        // Deep pattern: Integral scalars with null defaults (Long, Short, Byte)
        Case(
            "deep - integral scalars with null defaults",
            "INPUT",
            """
            scalar Long
            scalar Short
            scalar Byte

            input TestInputWithIntegrals {
                nullableLongNoDefault: Long
                nullableShortNoDefault: Short
                nullableByteNoDefault: Byte
                longWithDefault: Long = "1000"
                shortWithDefault: Short = 100
                byteWithDefault: Byte = 10
            }

            type Query { field(input: TestInputWithIntegrals): String }
            """.trimIndent()
        ),
        // Scalar defaults
        Case(
            "simple scalar defaults",
            "INPUT",
            """
            input UserInput {
                name: String = "Anonymous"
                age: Int = 0
                active: Boolean = true
                score: Float = 3.14
                id: ID = "default-id"
            }

            type Query { createUser(input: UserInput): String }
            """.trimIndent()
        ),
        Case(
            "viaduct scalar defaults",
            "INPUT",
            """
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

            type Query { field(input: ScalarDefaults): String }
            """.trimIndent()
        ),
        // List defaults
        Case(
            "list defaults",
            "INPUT",
            """
            input FilterInput {
                tags: [String!] = ["default", "test"]
                ids: [Int!]! = [1, 2, 3]
                scores: [Float] = [1.5, 2.5, 3.5]
            }

            type Query { filter(input: FilterInput): String }
            """.trimIndent()
        ),
        Case(
            "empty list default",
            "INPUT",
            """
            input FilterInput {
                tags: [String!] = []
                ids: [Int] = []
            }

            type Query { filter(input: FilterInput): String }
            """.trimIndent()
        ),
        Case(
            "nested list defaults",
            "INPUT",
            """
            input MatrixInput {
                matrix: [[Int!]!] = [[1, 2], [3, 4]]
                empty2D: [[String]] = [[]]
            }

            type Query { field(input: MatrixInput): String }
            """.trimIndent()
        ),
        // Input object defaults
        Case(
            "nested input object default",
            "INPUT",
            """
            input PointInput {
                x: Int!
                y: Int!
            }

            input ShapeInput {
                center: PointInput = { x: 0, y: 0 }
                radius: Float = 1.0
            }

            type Query { draw(shape: ShapeInput): String }
            """.trimIndent()
        ),
        Case(
            "empty object default",
            "INPUT",
            """
            input PointInput {
                x: Int = 0
                y: Int = 0
            }

            input ShapeInput {
                center: PointInput = {}
                name: String = "default"
            }

            type Query { draw(shape: ShapeInput): String }
            """.trimIndent()
        ),
        // Null defaults
        Case(
            "null defaults",
            "INPUT",
            """
            input UserInput {
                middleName: String = null
                phone: String = null
                age: Int = null
            }

            type Query { createUser(input: UserInput): String }
            """.trimIndent()
        ),
        Case(
            "nulls in list defaults",
            "INPUT",
            """
            input FilterInput {
                tags: [String] = ["tag1", null, "tag3"]
                ids: [Int] = [1, null, 3]
            }

            type Query { filter(input: FilterInput): String }
            """.trimIndent()
        ),
        Case(
            "nulls in nested object defaults",
            "INPUT",
            """
            input PointInput {
                x: Int
                y: Int
                z: Int
            }

            input ShapeInput {
                center: PointInput = { x: 0, y: null, z: 10 }
                label: String = null
            }

            type Query { draw(shape: ShapeInput): String }
            """.trimIndent()
        ),
        // Special cases
        Case(
            "all empty types",
            "INPUT",
            """
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

            type Query { field(input: AllEmptyTypes): String }
            """.trimIndent()
        ),
        Case(
            "complex nested defaults",
            "INPUT",
            """
            input FilterInput {
                tags: [String!] = ["tag1", "tag2"]
                count: Int = 10
            }

            input SearchInput {
                query: String = "default search"
                filters: [FilterInput!] = [{ tags: ["a"], count: 5 }]
                limit: Int = 20
            }

            type Query { search(input: SearchInput): String }
            """.trimIndent()
        ),
        // Extensions
        Case(
            "input with extension",
            "INPUT",
            """
            input UserInput { id: ID! }

            extend input UserInput {
                name: String!
                email: String
            }

            extend input UserInput {
                age: Int
                active: Boolean = true
            }

            type Query { createUser(input: UserInput): String }
            """.trimIndent()
        ),
        Case(
            "input with empty base and fields in extension",
            "INPUT",
            """
            input UserInput

            extend input UserInput {
                name: String!
                email: String
            }

            type Query { createUser(input: UserInput): String }
            """.trimIndent()
        ),
        Case(
            "input with complex defaults in extensions",
            "INPUT",
            """
            input SearchInput { query: String! }

            extend input SearchInput {
                tags: [String!] = ["default"]
                limit: Int = 100
            }

            type Query { search(input: SearchInput): String }
            """.trimIndent()
        ),
        // Value edge cases (semantic requirements all implementations must support)
        Case(
            "negative number defaults",
            "INPUT",
            """
            input Numbers {
                negativeInt: Int = -42
                negativeFloat: Float = -3.14
                zeroInt: Int = 0
                zeroFloat: Float = 0.0
            }

            type Query { field(input: Numbers): String }
            """.trimIndent()
        ),
        Case(
            "empty string vs non-empty",
            "INPUT",
            """
            input StringInput {
                emptyValue: String = ""
                normalValue: String = "hello"
            }

            type Query { field(input: StringInput): String }
            """.trimIndent()
        ),
        Case(
            "special characters in defaults",
            "INPUT",
            """
            input SpecialChars {
                withUnicode: String = "Hello 世界"
                withEscapes: String = "line1\nline2"
                withQuotes: String = "She said \"hello\""
            }

            type Query { field(input: SpecialChars): String }
            """.trimIndent()
        ),
        Case(
            "nulls in list defaults consolidated",
            "INPUT",
            """
            input ListWithNulls {
                values: [Int] = [1, null, 3]
                nested: [[Int]] = [[1], null, [2, null]]
            }

            type Query { field(input: ListWithNulls): String }
            """.trimIndent()
        ),
        Case(
            "list of objects with null elements",
            "INPUT",
            """
            input PointInput { x: Int!, y: Int! }

            input PathInput {
                points: [PointInput] = [{ x: 0, y: 0 }, null, { x: 10, y: 10 }]
            }

            type Query { field(input: PathInput): String }
            """.trimIndent()
        ),
        Case(
            "three-level nested defaults",
            "INPUT",
            """
            input Level3 { value: Int! }
            input Level2 { level3: Level3!, name: String }
            input Level1 {
                level2: Level2 = { level3: { value: 42 }, name: "test" }
            }

            type Query { field(input: Level1): String }
            """.trimIndent()
        )
    )

    // ===================================================================================
    // INTERFACE - Interface type definitions
    // ===================================================================================

    val INTERFACE: List<Case> = listOf(
        Case(
            "interface with implementor",
            "INTERFACE",
            """
            interface Node { id: ID! }

            type User implements Node { id: ID!, name: String }

            type Query { node: Node }
            """.trimIndent()
        ),
        Case(
            "interface with multiple implementors",
            "INTERFACE",
            """
            interface Node { id: ID! }

            type User implements Node { id: ID!, name: String }
            type Post implements Node { id: ID!, title: String }

            type Query { node(id: ID!): Node }
            """.trimIndent()
        ),
        Case(
            "interface with no implementors",
            "INTERFACE",
            """
            interface Node { id: ID! }

            type Query { node: Node }
            """.trimIndent()
        ),
        Case(
            "interface extends interface",
            "INTERFACE",
            """
            interface Node { id: ID! }

            interface NamedNode implements Node { id: ID!, name: String }

            type User implements NamedNode & Node { id: ID!, name: String, email: String }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "interface with field arguments",
            "INTERFACE",
            """
            interface Node {
                id: ID!
                metadata(key: String!): String
            }

            type User implements Node {
                id: ID!
                metadata(key: String!): String
                name: String
            }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "interface with field argument defaults",
            "INTERFACE",
            """
            interface Paginated {
                items(first: Int = 10, after: String): [String!]!
            }

            type SearchResult implements Paginated {
                items(first: Int = 10, after: String): [String!]!
            }

            type Query { search: SearchResult }
            """.trimIndent()
        ),
        Case(
            "interface with applied directive",
            "INTERFACE",
            """
            directive @tag(name: String!) on INTERFACE

            interface Node @tag(name: "core") { id: ID! }

            type User implements Node { id: ID!, name: String }

            type Query { node: Node }
            """.trimIndent()
        ),
        Case(
            "mutually recursive interfaces",
            "INTERFACE",
            """
            interface A { b: B }
            interface B { a: A }

            type ImplA implements A { b: B }
            type ImplB implements B { a: A }

            type Query { a: A, b: B }
            """.trimIndent()
        ),
        // Extensions
        Case(
            "interface with extension",
            "INTERFACE",
            """
            interface Node { id: ID! }

            extend interface Node { createdAt: String }
            extend interface Node { updatedAt: String }

            type User implements Node {
                id: ID!
                createdAt: String
                updatedAt: String
            }

            type Query { node: Node }
            """.trimIndent()
        ),
        Case(
            "interface with empty base and fields in extension",
            "INTERFACE",
            """
            interface Node

            extend interface Node { id: ID! }

            type User implements Node { id: ID!, name: String }

            type Query { node: Node }
            """.trimIndent()
        ),
        Case(
            "interface implementing another via extension",
            "INTERFACE",
            """
            interface Node { id: ID! }
            interface Named { name: String }

            extend interface Named implements Node { id: ID! }

            type User implements Named & Node { id: ID!, name: String }

            type Query { node: Node }
            """.trimIndent()
        )
    )

    // ===================================================================================
    // OBJECT - Object type definitions
    // ===================================================================================

    val OBJECT: List<Case> = listOf(
        Case(
            "simple object",
            "OBJECT",
            """
            type Foo { i: Int }

            type Query { foo: Foo }
            """.trimIndent()
        ),
        Case(
            "object with multiple fields",
            "OBJECT",
            """
            type User {
                id: ID!
                name: String
                email: String
                age: Int
            }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "object implementing interface",
            "OBJECT",
            """
            interface Node { id: ID! }

            type User implements Node { id: ID!, name: String }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "object implementing multiple interfaces",
            "OBJECT",
            """
            interface Node { id: ID! }
            interface Timestamped { createdAt: String }

            type User implements Node & Timestamped { id: ID!, createdAt: String, name: String }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "object with field arguments",
            "OBJECT",
            """
            type User {
                id: ID!
                posts(limit: Int = 10, offset: Int = 0): [String!]!
                comments(filter: String): [String!]
            }

            type Query { user: User }
            """.trimIndent()
        ),
        // Deep pattern: Field arguments with four default patterns
        Case(
            "deep - field with four argument default patterns",
            "OBJECT",
            """
            type Test {
                hello(
                    nullableNoDefault: String
                    nullableWithNonNullDefault: String = "world"
                    nullableWithNullDefault: String = null
                    nonNullableWithDefault: String! = "required"
                ): String
            }

            type Query { test: Test }
            """.trimIndent()
        ),
        // Field argument defaults
        Case(
            "field argument with scalar defaults",
            "OBJECT",
            """
            type Query {
                user(id: ID = "123", limit: Int = 10, includeInactive: Boolean = false): String
                search(query: String = "default search", maxResults: Float = 100.0): [String]
            }
            """.trimIndent()
        ),
        Case(
            "field argument with enum default",
            "OBJECT",
            """
            enum SortOrder { ASC, DESC }

            type Query { users(sortOrder: SortOrder = ASC): [String] }
            """.trimIndent()
        ),
        Case(
            "field argument with list default",
            "OBJECT",
            """
            type Query { search(tags: [String!] = ["all"], ids: [Int] = [1, 2, 3]): String }
            """.trimIndent()
        ),
        Case(
            "field argument with input object default",
            "OBJECT",
            """
            input FilterInput {
                minValue: Int!
                maxValue: Int!
            }

            type Query { getValues(filter: FilterInput = { minValue: 0, maxValue: 100 }): [Int] }
            """.trimIndent()
        ),
        Case(
            "field argument with null default",
            "OBJECT",
            """
            type Query { user(nickname: String = null, age: Int = null): String }
            """.trimIndent()
        ),
        // Applied directives
        Case(
            "object with applied directive",
            "OBJECT",
            """
            directive @typeTag(name: String!) on OBJECT

            type User @typeTag(name: "entity") {
                id: ID!
                name: String
            }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "field with applied directive",
            "OBJECT",
            """
            type User {
                id: ID!
                oldName: String @deprecated(reason: "Use displayName")
                displayName: String
            }

            type Query { user: User }
            """.trimIndent()
        ),
        // Recursive types
        Case(
            "mutually recursive types",
            "OBJECT",
            """
            type User {
                id: ID!
                posts: [Post]
            }

            type Post {
                id: ID!
                author: User
            }

            type Query { user: User, post: Post }
            """.trimIndent()
        ),
        // Extensions
        Case(
            "object with extension",
            "OBJECT",
            """
            type User { id: ID! }

            extend type User { name: String, email: String }
            extend type User { age: Int, active: Boolean }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "object with empty base and fields in extension",
            "OBJECT",
            """
            type User

            extend type User { id: ID!, name: String }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "object with interface added via extension",
            "OBJECT",
            """
            interface Node { id: ID! }
            interface Named { name: String }

            type User implements Node { id: ID! }

            extend type User implements Named { name: String }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "object with directives on extension",
            "OBJECT",
            """
            directive @tag(name: String!) repeatable on OBJECT

            type User @tag(name: "core") { id: ID! }

            extend type User @tag(name: "profile") { name: String }
            extend type User @tag(name: "contact") { email: String }

            type Query { user: User }
            """.trimIndent()
        )
    )

    // ===================================================================================
    // SCALAR - Custom scalar definitions
    // ===================================================================================

    val SCALAR: List<Case> = listOf(
        Case(
            "simple custom scalar",
            "SCALAR",
            """
            scalar DateTime

            type Query { timestamp: DateTime }
            """.trimIndent()
        ),
        Case(
            "custom scalar with applied directive",
            "SCALAR",
            """
            directive @scalarTag(format: String!) on SCALAR

            scalar CustomDateTime @scalarTag(format: "RFC3339")

            type Query { timestamp: CustomDateTime }
            """.trimIndent()
        ),
        Case(
            "multiple custom scalars",
            "SCALAR",
            """
            scalar Date
            scalar DateTime
            scalar Time
            scalar Long
            scalar Json

            type Query {
                date: Date
                dateTime: DateTime
                time: Time
                bigNumber: Long
                data: Json
            }
            """.trimIndent()
        ),
        Case(
            "integral scalars",
            "SCALAR",
            """
            scalar Long
            scalar Short
            scalar Byte

            type Query {
                longField: Long
                shortField: Short
                byteField: Byte
            }
            """.trimIndent()
        ),
        Case(
            "scalar used in defaults",
            "SCALAR",
            """
            scalar Long
            scalar Date

            type Query {
                getData(
                    longParam: Long = "1000000"
                    dateParam: Date = "2024-12-31"
                ): String
            }
            """.trimIndent()
        )
    )

    // ===================================================================================
    // UNION - Union type definitions
    // ===================================================================================

    val UNION: List<Case> = listOf(
        Case(
            "union with multiple members",
            "UNION",
            """
            type Cat { meow: String }
            type Dog { bark: String }

            union Pet = Cat | Dog

            type Query { pet: Pet }
            """.trimIndent()
        ),
        Case(
            "union with single member",
            "UNION",
            """
            type User { name: String }

            union SearchResult = User

            type Query { search: SearchResult }
            """.trimIndent()
        ),
        Case(
            "multiple unions",
            "UNION",
            """
            type Cat { meow: String }
            type Dog { bark: String }
            type Bird { chirp: String }

            union Pet = Cat | Dog
            union Animal = Cat | Dog | Bird

            type Query { pet: Pet, animal: Animal }
            """.trimIndent()
        ),
        Case(
            "union with applied directive",
            "UNION",
            """
            directive @tag(name: String!) on UNION

            type Cat { meow: String }
            type Dog { bark: String }

            union Pet @tag(name: "animals") = Cat | Dog

            type Query { pet: Pet }
            """.trimIndent()
        ),
        Case(
            "unused union",
            "UNION",
            """
            type A { a: String }
            type B { b: String }

            union UnusedUnion = A | B

            type Query { field: String }
            """.trimIndent()
        ),
        // Extensions
        Case(
            "union with extension",
            "UNION",
            """
            type Cat { meow: String }
            type Dog { bark: String }
            type Bird { chirp: String }

            union Pet = Cat

            extend union Pet = Dog
            extend union Pet = Bird

            type Query { pet: Pet }
            """.trimIndent()
        ),
        Case(
            "union with empty base and members in extension",
            "UNION",
            """
            type Cat { meow: String }
            type Dog { bark: String }

            union Pet

            extend union Pet = Cat | Dog

            type Query { pet: Pet }
            """.trimIndent(),
            gjIncompatible = "graphql-java rejects empty union base definitions"
        ),
        Case(
            "union with directives on extension",
            "UNION",
            """
            directive @tag(name: String!) repeatable on UNION

            type Cat { meow: String }
            type Dog { bark: String }

            union Pet @tag(name: "animals") = Cat

            extend union Pet @tag(name: "extended") = Dog

            type Query { pet: Pet }
            """.trimIndent()
        )
    )

    // ===================================================================================
    // ROOT - Root type configurations
    // ===================================================================================

    val ROOT: List<Case> = listOf(
        Case(
            "standard root names",
            "ROOT",
            """
            type Query { field: String }
            type Mutation { field: String }
            type Subscription { field: String }
            """.trimIndent()
        ),
        Case(
            "query only",
            "ROOT",
            """
            type Query { field: String }
            """.trimIndent()
        ),
        Case(
            "custom query name",
            "ROOT",
            """
            schema { query: MyCustomQuery }

            type MyCustomQuery { field: String }
            """.trimIndent()
        ),
        Case(
            "custom query and mutation",
            "ROOT",
            """
            schema {
                query: MyQuery
                mutation: MyMutation
            }

            type MyQuery { field: String }
            type MyMutation { field: String }
            """.trimIndent()
        ),
        Case(
            "all custom root names",
            "ROOT",
            """
            schema {
                query: MyCustomQuery
                mutation: MyCustomMutation
                subscription: MyCustomSubscription
            }

            type MyCustomQuery { field: String }
            type MyCustomMutation { field: String }
            type MyCustomSubscription { field: String }
            """.trimIndent()
        ),
        Case(
            "custom query and subscription",
            "ROOT",
            """
            schema {
                query: QueryRoot
                subscription: SubscriptionRoot
            }

            type QueryRoot { field: String }
            type SubscriptionRoot { field: String }
            """.trimIndent()
        ),
        Case(
            "complex schema with custom roots",
            "ROOT",
            """
            schema {
                query: Q
                mutation: M
            }

            type Q {
                user(id: ID!): User
                posts: [Post]
            }

            type M {
                createUser(name: String!): User
                deleteUser(id: ID!): Boolean
            }

            type User {
                id: ID!
                name: String
                posts: [Post]
            }

            type Post {
                id: ID!
                title: String
                author: User
            }
            """.trimIndent()
        )
    )

    // ===================================================================================
    // COMPLEX - Complex schemas testing multiple features together
    // ===================================================================================

    val COMPLEX: List<Case> = listOf(
        Case(
            "complex hierarchy with interfaces unions objects",
            "COMPLEX",
            """
            interface Node { id: ID! }
            interface Named { name: String }

            type User implements Node & Named { id: ID!, name: String, email: String }
            type Post implements Node { id: ID!, title: String }

            union SearchResult = User | Post

            type Query { search: SearchResult, node: Node }
            """.trimIndent()
        ),
        Case(
            "complex schema with all extensions",
            "COMPLEX",
            """
            directive @tag(name: String!) repeatable on OBJECT

            interface Node { id: ID! }
            extend interface Node { createdAt: String }

            type User implements Node @tag(name: "base") { id: ID!, createdAt: String }
            extend type User @tag(name: "profile") { name: String, oldEmail: String @deprecated(reason: "Use email") }
            extend type User @tag(name: "contact") { email: String, phone(format: String = "E164"): String }

            enum Status { ACTIVE }
            extend enum Status { INACTIVE, PENDING }

            input UserInput { name: String }
            extend input UserInput { email: String, active: Boolean = true }

            type Post { id: ID! }

            union Content = User
            extend union Content = Post

            type Query {
                user: User
                status: Status
                createUser(input: UserInput): User
                content: Content
            }
            """.trimIndent()
        ),
        Case(
            "deep - comprehensive default value patterns",
            "COMPLEX",
            """
            directive @scalarArgs(
                nullableNoDefault: String
                nullableWithNonNullDefault: String = "hello"
                nullableWithNullDefault: String = null
                nonNullableWithDefault: String! = "required"
            ) on OBJECT

            input InputWithDefaults {
                nullableNoDefault: String
                nullableWithNonNullDefault: String = "inputHello"
                nullableWithNullDefault: String = null
                nonNullableWithDefault: String! = "inputRequired"
            }

            directive @inputArg(arg: InputWithDefaults) on OBJECT

            type AllArgsProvided @scalarArgs(
                nullableNoDefault: "explicit1"
                nullableWithNonNullDefault: "explicit2"
                nullableWithNullDefault: "explicit3"
                nonNullableWithDefault: "explicit4"
            ) {
                stub: String
            }

            type NoArgsProvided @scalarArgs {
                stub: String
            }

            type InputArgAllFields @inputArg(arg: {
                nullableNoDefault: "inputExplicit1"
                nullableWithNonNullDefault: "inputExplicit2"
                nullableWithNullDefault: "inputExplicit3"
                nonNullableWithDefault: "inputExplicit4"
            }) {
                stub: String
            }

            type InputArgEmpty @inputArg(arg: {}) {
                stub: String
            }

            type Test {
                hello(
                    nullableNoDefault: String
                    nullableWithNonNullDefault: String = "world"
                    nullableWithNullDefault: String = null
                    nonNullableWithDefault: String! = "argRequired"
                ): String
            }

            type Query {
                allArgs: AllArgsProvided
                noArgs: NoArgsProvided
                inputAll: InputArgAllFields
                inputEmpty: InputArgEmpty
                test: Test
            }
            """.trimIndent()
        ),
        Case(
            "deep - custom scalars comprehensive",
            "COMPLEX",
            """
            # Custom scalars can use any GraphQL value literal syntax.
            # This test verifies that a single custom scalar type can accept
            # all value syntaxes, and that these work in all contexts:
            # input field defaults, field argument defaults, directive argument
            # defaults, and applied directive arguments.

            scalar CustomScalar

            # Directive with defaults using all value syntaxes for CustomScalar
            directive @customScalarDefaults(
                intLikeArg: CustomScalar = 42
                floatLikeArg: CustomScalar = 3.14
                stringLikeArg: CustomScalar = "default"
                boolLikeArg: CustomScalar = true
                enumLikeArg: CustomScalar = DEFAULT_VALUE
                objectLikeArg: CustomScalar = {nested: {deep: true}, list: [1, 2]}
                arrayLikeArg: CustomScalar = ["a", "b", "c"]
            ) on FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ARGUMENT_DEFINITION

            # Input type testing all value syntaxes as field defaults
            input CustomScalarInput {
                intLikeField: CustomScalar = 100
                floatLikeField: CustomScalar = 2.718
                stringLikeField: CustomScalar = "input default"
                boolLikeField: CustomScalar = false
                enumLikeField: CustomScalar = INPUT_DEFAULT
                objectLikeField: CustomScalar = {type: "input", active: true}
                arrayLikeField: CustomScalar = [10, 20, 30]
            }

            type Query {
                # Field arguments with custom scalar defaults
                withArgDefaults(
                    intLikeArg: CustomScalar = 999
                    floatLikeArg: CustomScalar = 0.5
                    stringLikeArg: CustomScalar = "arg default"
                    boolLikeArg: CustomScalar = true
                    enumLikeArg: CustomScalar = ARG_DEFAULT
                    objectLikeArg: CustomScalar = {source: "argument"}
                    arrayLikeArg: CustomScalar = [{id: 1}, {id: 2}]
                ): String

                # Field with directive applied using all value syntaxes
                withAppliedDirective: String @customScalarDefaults(
                    intLikeArg: 123
                    floatLikeArg: 9.99
                    stringLikeArg: "applied"
                    boolLikeArg: false
                    enumLikeArg: APPLIED_VALUE
                    objectLikeArg: {applied: true, count: 5}
                    arrayLikeArg: ["x", "y", "z"]
                )

                # Test input type with all custom scalar fields
                processInput(input: CustomScalarInput): String
            }
            """.trimIndent()
        ),
        // Comprehensive extension tests (from RECS.md recommendations)
        Case(
            "all empty bases content in extensions",
            "COMPLEX",
            """
            interface Node
            extend interface Node { id: ID! }

            type User
            extend type User implements Node { id: ID!, name: String }

            enum Status
            extend enum Status { ACTIVE, INACTIVE }

            input UserInput
            extend input UserInput { name: String! }

            type Post { id: ID! }

            union Result
            extend union Result = User | Post

            type Query { user: User, status: Status, createUser(input: UserInput): Result, node: Node }
            """.trimIndent(),
            gjIncompatible = "graphql-java rejects empty union base definitions"
        ),
        Case(
            "multiple types multiple extensions",
            "COMPLEX",
            """
            directive @tag(name: String!) repeatable on OBJECT | ENUM | INPUT_OBJECT

            enum Status @tag(name: "enum-base") { ACTIVE }
            extend enum Status @tag(name: "enum-ext1") { INACTIVE }
            extend enum Status @tag(name: "enum-ext2") { PENDING }

            type User @tag(name: "type-base") { id: ID! }
            extend type User @tag(name: "type-ext1") { name: String }
            extend type User @tag(name: "type-ext2") { email: String }

            input UserInput @tag(name: "input-base") { name: String }
            extend input UserInput @tag(name: "input-ext1") { email: String }
            extend input UserInput @tag(name: "input-ext2") { age: Int = 0 }

            type Query { user: User, status: Status, createUser(input: UserInput): User }
            """.trimIndent()
        ),
        // Three-extension tests: verify members and directives land in correct extensions
        Case(
            "three extensions - enum with members and directives",
            "COMPLEX",
            """
            directive @tag(name: String!) repeatable on ENUM

            enum Status @tag(name: "base") { BASE_VALUE }
            extend enum Status @tag(name: "ext1") { EXT1_VALUE }
            extend enum Status @tag(name: "ext2") { EXT2_VALUE }

            type Query { status: Status }
            """.trimIndent()
        ),
        Case(
            "three extensions - object with fields and directives",
            "COMPLEX",
            """
            directive @tag(name: String!) repeatable on OBJECT

            type User @tag(name: "base") { baseField: String }
            extend type User @tag(name: "ext1") { ext1Field: String }
            extend type User @tag(name: "ext2") { ext2Field: String }

            type Query { user: User }
            """.trimIndent()
        ),
        Case(
            "three extensions - interface with fields and directives",
            "COMPLEX",
            """
            directive @tag(name: String!) repeatable on INTERFACE

            interface Node @tag(name: "base") { baseField: ID! }
            extend interface Node @tag(name: "ext1") { ext1Field: String }
            extend interface Node @tag(name: "ext2") { ext2Field: String }

            type User implements Node {
                baseField: ID!
                ext1Field: String
                ext2Field: String
            }

            type Query { node: Node }
            """.trimIndent()
        ),
        Case(
            "three extensions - input with fields and directives",
            "COMPLEX",
            """
            directive @tag(name: String!) repeatable on INPUT_OBJECT

            input UserInput @tag(name: "base") { baseField: String }
            extend input UserInput @tag(name: "ext1") { ext1Field: String }
            extend input UserInput @tag(name: "ext2") { ext2Field: String }

            type Query { createUser(input: UserInput): String }
            """.trimIndent()
        ),
        Case(
            "three extensions - union with members and directives",
            "COMPLEX",
            """
            directive @tag(name: String!) repeatable on UNION

            type BaseType { base: String }
            type Ext1Type { ext1: String }
            type Ext2Type { ext2: String }

            union Result @tag(name: "base") = BaseType
            extend union Result @tag(name: "ext1") = Ext1Type
            extend union Result @tag(name: "ext2") = Ext2Type

            type Query { result: Result }
            """.trimIndent()
        )
    )

    // ===================================================================================
    // ALL - Combined list of all test schemas
    // ===================================================================================

    val ALL: List<Case> = DIRECTIVE + ENUM + INPUT + INTERFACE + OBJECT + SCALAR + UNION + ROOT + COMPLEX

    /**
     * Get schemas filtered by kind.
     */
    fun byKind(kind: String): List<Case> = ALL.filter { it.kind == kind }
}
