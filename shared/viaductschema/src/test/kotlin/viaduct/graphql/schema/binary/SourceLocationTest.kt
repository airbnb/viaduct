package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Test

/**
 * These tests verify that source locations are correctly encoded and
 * decoded.
 *
 * We keep the schemas simple since we're testing SourceLocation, not
 * other features.
 */
class SourceLocationTest {
    @Test
    fun `Smoke test - MultiSourceReader with and without source locations`() {
        // Test that we can parse schemas with source locations
        // and merge them with schemas without source locations
        val sdlWithSource = listOf(
            Pair(builtins, "builtins.graphql"),
            Pair("enum Color { RED, GREEN }", "color.graphql")
        )
        val sdlWithoutSource = "enum Size { SMALL, LARGE }"

        assertRoundTrip(sdlWithSource, sdlWithoutSource)
    }

    @Test
    fun `Single source with regular filename`() {
        val sdl = listOf(
            Pair(builtins, "builtins.graphql"),
            Pair("enum Status { ACTIVE, INACTIVE }", "status.graphql")
        )
        assertRoundTrip(sdl)
    }

    @Test
    fun `Multiple different sources`() {
        val sdl = listOf(
            Pair(builtins, "builtins.graphql"),
            Pair("scalar DateTime", "datetime.graphql"),
            Pair("enum Priority { LOW, HIGH }", "priority.graphql"),
            Pair("scalar UUID", "uuid.graphql")
        )
        assertRoundTrip(sdl)
    }

    @Test
    fun `Empty string sourceName`() {
        // Empty string is different from null and should be preserved
        val sdl = listOf(
            Pair(builtins, "builtins.graphql"),
            Pair("enum Flag { ON, OFF }", ""), // Empty string source name
            Pair("scalar Timestamp", "timestamp.graphql")
        )
        assertRoundTrip(sdl)
    }

    @Test
    fun `Unicode filename with Japanese characters`() {
        // Note: GraphQL identifiers must be ASCII, but filenames can be Unicode
        val sdl = listOf(
            Pair(builtins, "builtins.graphql"),
            Pair("enum Color { RED, BLUE, GREEN }", "日本語.graphql")
        )
        assertRoundTrip(sdl)
    }

    @Test
    fun `Unicode filename with emojis`() {
        val sdl = listOf(
            Pair(builtins, "builtins.graphql"),
            Pair("enum Mood { HAPPY, SAD }", "emojis🎉🚀.graphql")
        )
        assertRoundTrip(sdl)
    }

    @Test
    fun `Unicode filename with French accents`() {
        // Note: GraphQL identifiers must be ASCII, but filenames can be Unicode
        val sdl = listOf(
            Pair(builtins, "builtins.graphql"),
            Pair("enum State { CREATED, MODIFIED }", "schéma-français.graphql")
        )
        assertRoundTrip(sdl)
    }

    @Test
    fun `Mixed Unicode filenames from multiple languages`() {
        // Note: GraphQL identifiers must be ASCII, but filenames can be Unicode
        val sdl = listOf(
            Pair(builtins, "builtins.graphql"),
            Pair("scalar DateTime", "时间-日期.graphql"), // Chinese
            Pair("enum Status { OK }", "статус.graphql"), // Russian (Cyrillic)
            Pair("scalar JSON", "données.graphql") // French
        )
        assertRoundTrip(sdl)
    }

    @Test
    fun `Mixed null and non-null locations`() {
        // Some types have source locations, others don't
        val sdlWithSource = listOf(
            Pair(builtins, "builtins.graphql"),
            Pair("enum Level { DEBUG, INFO }", "level.graphql")
        )
        val sdlWithoutSource = """
            enum Severity { LOW, MEDIUM, HIGH }
            scalar UUID
        """.trimIndent()

        assertRoundTrip(sdlWithSource, sdlWithoutSource)
    }

    @Test
    fun `Empty string and non-empty sources mixed`() {
        val sdlWithSource = listOf(
            Pair(builtins, "builtins.graphql"),
            Pair("enum Phase { ALPHA, BETA }", ""), // Empty string
            Pair("enum Stage { DEV, PROD }", "stage.graphql") // Non-empty
        )
        assertRoundTrip(sdlWithSource)
    }

    @Test
    fun `All types from same source`() {
        val sdl = listOf(
            Pair(
                """
                $builtins
                scalar Date
                enum Role { ADMIN, USER }
                scalar Timestamp
                """.trimIndent(),
                "schema.graphql"
            )
        )
        assertRoundTrip(sdl)
    }

    @Test
    fun `Long Unicode filename`() {
        // Test with a longer filename containing various multi-byte characters
        val longFilename = "这是一个非常长的文件名包含各种字符🌟αβγδ.graphql"
        val sdl = listOf(
            Pair(builtins, "builtins.graphql"),
            Pair("enum Test { A, B }", longFilename)
        )
        assertRoundTrip(sdl)
    }

    @Test
    fun `Extensions without source locations`() {
        // Extensions parsed without explicit source names should handle null locations
        val sdl = """
            type User {
                id: ID!
            }

            extend type User {
                name: String
            }

            extend type User {
                email: String
            }

            type Query {
                user: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }
}
