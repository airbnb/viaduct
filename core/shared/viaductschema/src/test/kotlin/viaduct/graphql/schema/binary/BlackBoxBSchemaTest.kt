package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import viaduct.graphql.schema.test.TestSchemas

/**
 * Black-box tests for BSchema binary round-trip.
 *
 * These tests verify that all TestSchemas can be:
 * 1. Parsed from SDL
 * 2. Encoded to BSchema binary format
 * 3. Decoded back
 * 4. Compared to the original using SchemaDiff
 *
 * This ensures the binary format correctly preserves all GraphQL schema semantics.
 * Tests are grouped by GraphQL definition kind - each kind runs as one test that
 * exercises all schemas of that kind.
 */
class BlackBoxBSchemaTest {
    @Test
    @DisplayName("DIRECTIVE schemas")
    fun `BSchema round-trip for directive schemas`() {
        assertAll(
            TestSchemas.DIRECTIVE.map { schema ->
                Executable { assertRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("ENUM schemas")
    fun `BSchema round-trip for enum schemas`() {
        assertAll(
            TestSchemas.ENUM.map { schema ->
                Executable { assertRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("INPUT schemas")
    fun `BSchema round-trip for input schemas`() {
        assertAll(
            TestSchemas.INPUT.map { schema ->
                Executable { assertRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("INTERFACE schemas")
    fun `BSchema round-trip for interface schemas`() {
        assertAll(
            TestSchemas.INTERFACE.map { schema ->
                Executable { assertRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("OBJECT schemas")
    fun `BSchema round-trip for object schemas`() {
        assertAll(
            TestSchemas.OBJECT.map { schema ->
                Executable { assertRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("SCALAR schemas")
    fun `BSchema round-trip for scalar schemas`() {
        assertAll(
            TestSchemas.SCALAR.map { schema ->
                Executable { assertRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("UNION schemas")
    fun `BSchema round-trip for union schemas`() {
        assertAll(
            TestSchemas.UNION.map { schema ->
                Executable { assertRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("ROOT schemas")
    fun `BSchema round-trip for root schemas`() {
        assertAll(
            TestSchemas.ROOT.map { schema ->
                Executable { assertRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("COMPLEX schemas")
    fun `BSchema round-trip for complex schemas`() {
        assertAll(
            TestSchemas.COMPLEX.map { schema ->
                Executable { assertRoundTrip(schema.fullSdl) }
            }
        )
    }
}
