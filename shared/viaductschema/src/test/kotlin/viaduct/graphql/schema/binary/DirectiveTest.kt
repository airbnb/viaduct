package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Test

/**
 * Tests for directive definition and applied directive encoding/decoding in binary schema format.
 *
 * These tests verify that:
 * 1. Directive definitions (including repeatability, locations, arguments) are correctly encoded
 * 2. Applied directives (directives applied to schema elements) are correctly encoded
 * 3. Complex default values in directive arguments are correctly encoded
 */
class DirectiveTest {
    @Test
    fun `Directive with repeatable and multiple locations`() {
        val sdl = """
            directive @tag(name: String!) repeatable on FIELD_DEFINITION | OBJECT | INTERFACE

            type Query {
                user: String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Directive with multiple arguments and defaults`() {
        val sdl = """
            directive @cache(maxAge: Int = 3600, scope: String = "PUBLIC") on FIELD_DEFINITION

            type Query {
                field: String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Non-repeatable directive with single location`() {
        val sdl = """
            directive @auth on FIELD_DEFINITION

            type Query {
                field: String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Directive with no arguments`() {
        val sdl = """
            directive @skip on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT

            type Query {
                field: String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Multiple directives with various configurations`() {
        val sdl = """
            directive @auth on FIELD_DEFINITION
            directive @cache(maxAge: Int = 3600) on FIELD_DEFINITION
            directive @tag(name: String!) repeatable on FIELD_DEFINITION | OBJECT

            type Query {
                field: String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Group A: Applied Directives on Different Schema Elements
    // ========================================================================

    @Test
    fun `Type definition with applied directive`() {
        val sdl = """
            type OldUser @deprecated(reason: "Use User instead") {
                name: String
            }

            type Query {
                user: OldUser
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Field definition with applied directive`() {
        val sdl = """
            type User {
                oldName: String @deprecated(reason: "Use displayName")
                displayName: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Enum value with applied directive`() {
        val sdl = """
            enum Status {
                ACTIVE
                INACTIVE @deprecated(reason: "Use ARCHIVED")
                ARCHIVED
            }

            type Query {
                status: Status
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Input field with applied directive`() {
        val sdl = """
            input UserInput {
                oldEmail: String @deprecated(reason: "Use email field")
                email: String
            }

            type Query {
                createUser(input: UserInput): String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Field argument with applied directive`() {
        val sdl = """
            type Query {
                user(oldId: ID @deprecated(reason: "Use id parameter"), id: ID): String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Group B: Argument Count Variations
    // ========================================================================

    @Test
    fun `Applied directive with zero arguments`() {
        val sdl = """
            directive @internal on FIELD_DEFINITION

            type User {
                internalField: String @internal
                publicField: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Applied directive with multiple arguments`() {
        val sdl = """
            directive @auth(
                role: String!
                scope: String!
                resource: String!
            ) on FIELD_DEFINITION

            type User {
                adminData: String @auth(
                    role: "ADMIN"
                    scope: "read:users"
                    resource: "user_data"
                )
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Group C: Complex Default Values in Directive Definitions
    // ========================================================================

    @Test
    fun `Directive definition with list default value`() {
        val sdl = """
            directive @tag(
                labels: [String!]! = ["default", "untagged"]
            ) on FIELD_DEFINITION

            type User {
                name: String @tag
                email: String @tag(labels: ["contact", "pii"])
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Directive definition with input object default value`() {
        val sdl = """
            input CacheConfig {
                maxAge: Int!
                scope: String!
            }

            directive @cache(
                config: CacheConfig = {maxAge: 3600, scope: "PUBLIC"}
            ) on FIELD_DEFINITION

            type User {
                name: String @cache
                email: String @cache(config: {maxAge: 300, scope: "PRIVATE"})
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Directive definition with nested list default value`() {
        val sdl = """
            directive @matrix(
                grid: [[Int!]!]! = [[1, 2], [3, 4]]
            ) on FIELD_DEFINITION

            type Query {
                defaultMatrix: String @matrix
                customMatrix: String @matrix(grid: [[5, 6, 7], [8, 9, 10]])
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Group D: Multiple Directives and Complex Arguments
    // ========================================================================

    @Test
    fun `Multiple applied directives on single element`() {
        val sdl = """
            directive @auth(requires: String!) on FIELD_DEFINITION

            type User {
                secretData: String @auth(requires: "ADMIN") @deprecated(reason: "Moving to new API")
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Applied directive with list and input object arguments`() {
        val sdl = """
            input Scope {
                resource: String!
                action: String!
            }

            directive @auth(
                roles: [String!]!
                scopes: [Scope!]!
            ) on FIELD_DEFINITION

            type User {
                adminData: String @auth(
                    roles: ["ADMIN", "SUPERUSER"]
                    scopes: [{resource: "users", action: "read"}]
                )
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Group E: Coverage Gap Tests - Scalars and Interfaces
    // ========================================================================

    @Test
    fun `Scalar type with applied directive`() {
        val sdl = """
            scalar MyNewScalar @deprecated(reason: "Use ISO8601 string instead")

            type Query {
                created: MyNewScalar
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Interface field with applied directive`() {
        val sdl = """
            interface Node {
                id: ID!
                legacyId: String @deprecated(reason: "Use id field")
            }

            type User implements Node {
                id: ID!
                legacyId: String @deprecated(reason: "Use id field")
                name: String
            }

            type Query {
                node: Node
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Interface field argument with applied directive`() {
        val sdl = """
            interface Paginated {
                items(limit: Int @deprecated(reason: "Use first"), first: Int): [String!]!
            }

            type SearchResult implements Paginated {
                items(limit: Int @deprecated(reason: "Use first"), first: Int): [String!]!
            }

            type Query {
                search: SearchResult
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Directive with explicit null default value`() {
        val sdl = """
            directive @metadata(value: String = null) on FIELD_DEFINITION

            type Query {
                field1: String @metadata
                field2: String @metadata(value: "custom")
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Applied directive with null argument value`() {
        val sdl = """
            directive @tag(label: String) on FIELD_DEFINITION

            type Query {
                field: String @tag(label: null)
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Group F: Applied Directive Argument Reconstruction Tests
    // ========================================================================
    // These tests verify the four scenarios for applied directive arguments:
    // 1. Argument explicitly specified → Specified value
    // 2. Argument not explicitly specified but has default → Default from definition
    // 3. Argument not explicitly specified, no default, nullable → Null
    // 4. Other (required, no default) → Nothing, key not defined

    @Test
    fun `Applied directive omits argument matching default value`() {
        // Tests scenario 2: argument has default, value matches default
        // Encoder should omit this argument, decoder reconstructs from definition
        val sdl = """
            directive @cache(maxAge: Int = 3600) on FIELD_DEFINITION

            type Query {
                # Uses default value - encoder omits, decoder reconstructs
                defaultCache: String @cache
                # Explicit value matching default - encoder should omit this too
                explicitDefault: String @cache(maxAge: 3600)
                # Different value - must be encoded
                customCache: String @cache(maxAge: 600)
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Applied directive omits null for nullable argument without default`() {
        // Tests scenario 3: nullable argument, no default, value is null
        // Encoder should omit this argument, decoder reconstructs null
        val sdl = """
            directive @tag(label: String) on FIELD_DEFINITION

            type Query {
                # Explicitly passes null - encoder can omit since nullable with no default
                explicitNull: String @tag(label: null)
                # Non-null value must be encoded
                withLabel: String @tag(label: "important")
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Applied directive with required argument must be encoded`() {
        // Tests scenario 4: required argument with no default
        // These must always be encoded - decoder cannot reconstruct
        val sdl = """
            directive @require(permission: String!) on FIELD_DEFINITION

            type Query {
                # Required argument must always be encoded
                protected: String @require(permission: "admin")
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Applied directive with mixed argument scenarios`() {
        // Tests all four scenarios in a single directive
        val sdl = """
            directive @config(
                required: String!          # Scenario 4: required, no default
                withDefault: Int = 100     # Scenario 2: has default
                nullable: String           # Scenario 3: nullable, no default
            ) on FIELD_DEFINITION

            type Query {
                # Uses all three arguments explicitly
                allExplicit: String @config(required: "must-provide", withDefault: 200, nullable: "value")
                # Omits withDefault (matches default) and nullable (null)
                minimal: String @config(required: "must-provide")
                # Provides non-default for withDefault, null for nullable
                partialExplicit: String @config(required: "must-provide", withDefault: 50)
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Applied directive with complex default values omitted correctly`() {
        // Tests that complex default values (lists, objects) are correctly omitted
        val sdl = """
            input Settings {
                enabled: Boolean!
                threshold: Int!
            }

            directive @feature(
                tags: [String!]! = ["default"]
                settings: Settings = {enabled: true, threshold: 10}
            ) on FIELD_DEFINITION

            type Query {
                # Omits both arguments - uses defaults
                defaultFeature: String @feature
                # Provides custom tags, omits settings
                customTags: String @feature(tags: ["custom", "special"])
                # Provides both
                fullyCustom: String @feature(tags: ["x"], settings: {enabled: false, threshold: 5})
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Applied directive null default vs nullable no default`() {
        // Tests distinction between explicit null default and nullable with no default
        val sdl = """
            directive @withNullDefault(value: String = null) on FIELD_DEFINITION
            directive @nullableNoDefault(value: String) on FIELD_DEFINITION

            type Query {
                # Directive with null default - @withNullDefault has default value of null
                nullDefault1: String @withNullDefault
                nullDefault2: String @withNullDefault(value: null)
                nullDefault3: String @withNullDefault(value: "specified")
                # Directive with nullable, no default
                noDefault1: String @nullableNoDefault(value: null)
                noDefault2: String @nullableNoDefault(value: "specified")
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Multiple applied directives with arguments omitted`() {
        // Tests that argument omission works correctly with multiple applied directives
        val sdl = """
            directive @cache(maxAge: Int = 3600) on FIELD_DEFINITION
            directive @log(level: String = "INFO") on FIELD_DEFINITION
            directive @auth(role: String!) on FIELD_DEFINITION

            type Query {
                # Multiple directives, each with different argument scenarios
                field: String @cache @log @auth(role: "admin")
                # Override some defaults
                field2: String @cache(maxAge: 600) @log(level: "DEBUG") @auth(role: "user")
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Repeatable directive with arguments omitted`() {
        // Tests argument omission with repeatable directives
        val sdl = """
            directive @tag(
                name: String!
                priority: Int = 0
            ) repeatable on FIELD_DEFINITION

            type Query {
                # Multiple applications of same directive
                field: String @tag(name: "first") @tag(name: "second", priority: 10) @tag(name: "third")
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Group G: Circular Directive Dependencies
    // ========================================================================
    // The GraphQL spec allows circular directive dependencies where @A's argument
    // has @B applied and @B's argument has @A applied. These tests verify that
    // the binary encoding supports such circular dependencies.

    @Test
    fun `Circular directive dependency - mutual reference`() {
        // @foo has an argument with @bar applied, and @bar has an argument with @foo applied
        val sdl = """
            directive @foo(x: Int @bar(y: 1)) on FIELD_DEFINITION
            directive @bar(y: Int @foo(x: 2)) on FIELD_DEFINITION

            type Query {
                field: String @foo(x: 10) @bar(y: 20)
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Circular directive dependency - self-reference`() {
        // @recursive has an argument that applies @recursive to itself
        val sdl = """
            directive @recursive(depth: Int @recursive(depth: 0)) on FIELD_DEFINITION

            type Query {
                field: String @recursive(depth: 5)
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Circular directive dependency with defaults`() {
        // Circular directives with default values
        val sdl = """
            directive @alpha(value: Int = 100 @beta(value: 1)) on FIELD_DEFINITION
            directive @beta(value: Int = 200 @alpha(value: 2)) on FIELD_DEFINITION

            type Query {
                # Uses all defaults
                defaultField: String @alpha @beta
                # Overrides some values
                customField: String @alpha(value: 50) @beta(value: 60)
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Three-way circular directive dependency`() {
        // A -> B -> C -> A cycle
        val sdl = """
            directive @dirA(x: Int @dirB(y: 1)) on FIELD_DEFINITION
            directive @dirB(y: Int @dirC(z: 2)) on FIELD_DEFINITION
            directive @dirC(z: Int @dirA(x: 3)) on FIELD_DEFINITION

            type Query {
                field: String @dirA(x: 10) @dirB(y: 20) @dirC(z: 30)
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Circular directive dependency with complex argument types`() {
        // Circular directives with more complex argument types
        val sdl = """
            directive @config(
                name: String!
                enabled: Boolean = true @setting(key: "default")
            ) on FIELD_DEFINITION

            directive @setting(
                key: String!
                priority: Int = 0 @config(name: "setting", enabled: false)
            ) on FIELD_DEFINITION

            type Query {
                field: String @config(name: "main") @setting(key: "test", priority: 5)
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }
}
