package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Test

/**
 * Tests for GraphQL type extensions encoding/decoding in binary schema format.
 *
 * These tests verify that extensions are correctly encoded and decoded, including:
 * - Fields, enum values, and members spread across multiple extensions
 * - Applied directives on extensions themselves and their members
 * - Default values in extended input fields and arguments
 * - Source locations on extensions
 * - Empty base definitions with all content in extensions
 * - Implemented interfaces added via extensions
 */
class ExtensionsTest {
    // ========================================================================
    // Object Type Extensions - Fields
    // ========================================================================

    @Test
    fun `Object type with empty base and fields in extension`() {
        val sdl = """
            type User

            extend type User {
                id: ID!
                name: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Object type with fields spread across multiple extensions`() {
        val sdl = """
            type User {
                id: ID!
            }

            extend type User {
                name: String
                email: String
            }

            extend type User {
                age: Int
                active: Boolean
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Object type with fields with arguments in extensions`() {
        val sdl = """
            type User {
                id: ID!
            }

            extend type User {
                posts(limit: Int = 10, offset: Int = 0): [String!]!
                comments(filter: String): [String!]
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Object type with empty base and all fields with arguments in extension`() {
        val sdl = """
            type SearchResult

            extend type SearchResult {
                items(first: Int!, after: String): [String!]!
                total(includeArchived: Boolean = false): Int!
            }

            type Query {
                search: SearchResult
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Object Type Extensions - Interfaces
    // ========================================================================

    @Test
    fun `Object type with interface in base and additional interface in extension`() {
        val sdl = """
            interface Node {
                id: ID!
            }

            interface Timestamped {
                createdAt: String
            }

            type User implements Node {
                id: ID!
            }

            extend type User implements Timestamped {
                createdAt: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Object type with empty base and interfaces only in extension`() {
        val sdl = """
            interface Node {
                id: ID!
            }

            interface Named {
                name: String
            }

            type User

            extend type User implements Node & Named {
                id: ID!
                name: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Object type with interfaces spread across multiple extensions`() {
        val sdl = """
            interface Node {
                id: ID!
            }

            interface Named {
                name: String
            }

            interface Timestamped {
                createdAt: String
            }

            type User implements Node {
                id: ID!
            }

            extend type User implements Named {
                name: String
            }

            extend type User implements Timestamped {
                createdAt: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Object Type Extensions - Applied Directives
    // ========================================================================

    @Test
    fun `Object type with applied directive on base definition`() {
        val sdl = """
            type User @deprecated(reason: "Use Person instead") {
                id: ID!
            }

            extend type User {
                name: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Object type with applied directive on extension`() {
        val sdl = """
            directive @tag(name: String!) repeatable on OBJECT

            type User {
                id: ID!
            }

            extend type User @tag(name: "legacy") {
                oldField: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Object type with applied directives on multiple extensions`() {
        val sdl = """
            directive @tag(name: String!) repeatable on OBJECT

            type User @tag(name: "core") {
                id: ID!
            }

            extend type User @tag(name: "profile") {
                name: String
            }

            extend type User @tag(name: "contact") {
                email: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Object type with applied directives on extension fields`() {
        val sdl = """
            type User {
                id: ID!
            }

            extend type User {
                oldEmail: String @deprecated(reason: "Use email")
                email: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Object type with applied directives on field arguments in extension`() {
        val sdl = """
            type User {
                id: ID!
            }

            extend type User {
                posts(oldLimit: Int @deprecated(reason: "Use first"), first: Int): [String!]
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Interface Type Extensions
    // ========================================================================

    @Test
    fun `Interface with empty base and fields in extension`() {
        val sdl = """
            interface Node

            extend interface Node {
                id: ID!
            }

            type User implements Node {
                id: ID!
                name: String
            }

            type Query {
                node: Node
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Interface with fields spread across multiple extensions`() {
        val sdl = """
            interface Node {
                id: ID!
            }

            extend interface Node {
                createdAt: String
            }

            extend interface Node {
                updatedAt: String
            }

            type User implements Node {
                id: ID!
                createdAt: String
                updatedAt: String
            }

            type Query {
                node: Node
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Interface implementing another interface via extension`() {
        val sdl = """
            interface Node {
                id: ID!
            }

            interface Named {
                name: String
            }

            extend interface Named implements Node {
                id: ID!
            }

            type User implements Named & Node {
                id: ID!
                name: String
            }

            type Query {
                node: Node
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Interface with empty base implementing interfaces only in extension`() {
        val sdl = """
            interface Base {
                id: ID!
            }

            interface Derived

            extend interface Derived implements Base {
                id: ID!
                name: String
            }

            type User implements Derived & Base {
                id: ID!
                name: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Interface with applied directives on extensions`() {
        val sdl = """
            directive @tag(name: String!) repeatable on INTERFACE

            interface Node @tag(name: "core") {
                id: ID!
            }

            extend interface Node @tag(name: "extended") {
                version: Int
            }

            type User implements Node {
                id: ID!
                version: Int
            }

            type Query {
                node: Node
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Interface with fields with arguments in extensions`() {
        val sdl = """
            interface Paginated {
                total: Int
            }

            extend interface Paginated {
                items(first: Int = 10, after: String): [String!]!
            }

            type SearchResult implements Paginated {
                total: Int
                items(first: Int = 10, after: String): [String!]!
            }

            type Query {
                search: SearchResult
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Enum Type Extensions
    // ========================================================================

    @Test
    fun `Enum with empty base and values in extension`() {
        val sdl = """
            enum Status

            extend enum Status {
                ACTIVE
                INACTIVE
            }

            type Query {
                status: Status
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Enum with values spread across multiple extensions`() {
        val sdl = """
            enum Status {
                ACTIVE
            }

            extend enum Status {
                INACTIVE
                PENDING
            }

            extend enum Status {
                ARCHIVED
                DELETED
            }

            type Query {
                status: Status
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Enum with applied directives on values in extensions`() {
        val sdl = """
            enum Status {
                ACTIVE
            }

            extend enum Status {
                OLD_INACTIVE @deprecated(reason: "Use INACTIVE")
                INACTIVE
            }

            type Query {
                status: Status
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Enum with applied directives on extensions themselves`() {
        val sdl = """
            directive @tag(name: String!) repeatable on ENUM

            enum Status @tag(name: "core") {
                ACTIVE
            }

            extend enum Status @tag(name: "legacy") {
                INACTIVE
            }

            type Query {
                status: Status
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Enum with empty values in some extensions`() {
        val sdl = """
            directive @tag(name: String!) repeatable on ENUM

            enum Status {
                ACTIVE
            }

            extend enum Status @tag(name: "extended")

            extend enum Status {
                INACTIVE
            }

            type Query {
                status: Status
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Input Type Extensions
    // ========================================================================

    @Test
    fun `Input type with empty base and fields in extension`() {
        val sdl = """
            input UserInput

            extend input UserInput {
                name: String!
                email: String
            }

            type Query {
                createUser(input: UserInput): String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Input type with fields spread across multiple extensions`() {
        val sdl = """
            input UserInput {
                id: ID!
            }

            extend input UserInput {
                name: String!
                email: String
            }

            extend input UserInput {
                age: Int
                active: Boolean = true
            }

            type Query {
                createUser(input: UserInput): String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Input type with default values in extension fields`() {
        val sdl = """
            input FilterInput {
                limit: Int = 10
            }

            extend input FilterInput {
                offset: Int = 0
                sortBy: String = "createdAt"
            }

            type Query {
                search(filter: FilterInput): [String!]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Input type with complex default values in extensions`() {
        val sdl = """
            input SearchInput {
                query: String!
            }

            extend input SearchInput {
                tags: [String!] = ["default"]
                limit: Int = 100
            }

            type Query {
                search(input: SearchInput): [String!]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Input type with applied directives on extension fields`() {
        val sdl = """
            input UserInput {
                id: ID!
            }

            extend input UserInput {
                oldEmail: String @deprecated(reason: "Use email")
                email: String
            }

            type Query {
                createUser(input: UserInput): String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Input type with applied directives on extensions themselves`() {
        val sdl = """
            directive @tag(name: String!) repeatable on INPUT_OBJECT

            input UserInput @tag(name: "core") {
                id: ID!
            }

            extend input UserInput @tag(name: "profile") {
                name: String
            }

            type Query {
                createUser(input: UserInput): String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Union Type Extensions
    // ========================================================================

    @Test
    fun `Union with empty base and members in extension`() {
        val sdl = """
            type Cat {
                meow: String
            }

            type Dog {
                bark: String
            }

            union Pet

            extend union Pet = Cat | Dog

            type Query {
                pet: Pet
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Union with members spread across multiple extensions`() {
        val sdl = """
            type Cat {
                meow: String
            }

            type Dog {
                bark: String
            }

            type Bird {
                chirp: String
            }

            union Pet = Cat

            extend union Pet = Dog

            extend union Pet = Bird

            type Query {
                pet: Pet
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Union with applied directives on extensions`() {
        val sdl = """
            directive @tag(name: String!) repeatable on UNION

            type Cat {
                meow: String
            }

            type Dog {
                bark: String
            }

            union Pet @tag(name: "animals") = Cat

            extend union Pet @tag(name: "extended") = Dog

            type Query {
                pet: Pet
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Union with empty members in some extensions`() {
        val sdl = """
            directive @tag(name: String!) repeatable on UNION

            type Cat {
                meow: String
            }

            union Pet = Cat

            extend union Pet @tag(name: "metadata-only")

            type Query {
                pet: Pet
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    // ========================================================================
    // Complex Multi-Extension Scenarios
    // ========================================================================

    @Test
    fun `Multiple types with multiple extensions each`() {
        val sdl = """
            enum Status {
                ACTIVE
            }

            extend enum Status {
                INACTIVE
            }

            type User {
                id: ID!
            }

            extend type User {
                name: String
            }

            extend type User {
                email: String
            }

            input UserInput {
                name: String
            }

            extend input UserInput {
                email: String
            }

            type Query {
                user: User
                createUser(input: UserInput): User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Extensions with all features - fields, interfaces, directives`() {
        val sdl = """
            directive @tag(name: String!) repeatable on OBJECT

            interface Node {
                id: ID!
            }

            interface Named {
                name: String
            }

            type User implements Node @tag(name: "base") {
                id: ID!
            }

            extend type User implements Named @tag(name: "extended") {
                name: String
                oldEmail: String @deprecated(reason: "Use email")
            }

            extend type User @tag(name: "contact") {
                email: String
                phone(format: String = "E164"): String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `All base definitions empty with content only in extensions`() {
        val sdl = """
            interface Node

            extend interface Node {
                id: ID!
            }

            type User

            extend type User implements Node {
                id: ID!
                name: String
            }

            enum Status

            extend enum Status {
                ACTIVE
                INACTIVE
            }

            input UserInput

            extend input UserInput {
                name: String!
            }

            union Result

            extend union Result = User

            type Query {
                user: User
                status: Status
                createUser(input: UserInput): Result
                node: Node
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Many extensions with sparse content distribution`() {
        val sdl = """
            directive @tag(name: String!) repeatable on OBJECT

            type Product @tag(name: "v1") {
                id: ID!
            }

            extend type Product @tag(name: "v2")

            extend type Product {
                name: String
            }

            extend type Product @tag(name: "v3")

            extend type Product {
                price: Float
            }

            extend type Product @tag(name: "v4")

            extend type Product {
                inStock: Boolean
            }

            type Query {
                product: Product
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }
}
