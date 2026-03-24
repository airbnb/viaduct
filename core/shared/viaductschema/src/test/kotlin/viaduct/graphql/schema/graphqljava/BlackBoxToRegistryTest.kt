package viaduct.graphql.schema.graphqljava

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import viaduct.graphql.schema.checkViaductSchemaInvariants
import viaduct.graphql.schema.graphqljava.extensions.TypeDefinitionRegistryOptions
import viaduct.graphql.schema.graphqljava.extensions.toRegistry
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.graphql.schema.test.TestSchemas
import viaduct.invariants.FailureCollector

/**
 * Black-box tests for toRegistry round-trip.
 *
 * These tests verify that ViaductSchema.toRegistry() preserves schema semantics:
 * SDL → TypeDefinitionRegistry → GJSchemaRaw → toRegistry → GJSchemaRaw → Compare
 *
 * Uses GJSchemaRaw exclusively to avoid graphql-java validation bugs that would
 * incorrectly reject valid GraphQL SDL (e.g., empty union base definitions,
 * interface implementation via extension).
 *
 * Tests are grouped by GraphQL definition kind - each kind runs as one test that
 * exercises all schemas of that kind.
 */
class BlackBoxToRegistryTest {
    private fun assertToRegistryRoundTrip(fullSdl: String) {
        val registry = SchemaParser().parse(fullSdl)

        // Create GJSchemaRaw from registry (no graphql-java validation)
        val originalSchema = gjSchemaRawFromRegistry(registry)

        // Convert to TDRegistry
        val roundTrippedRegistry = originalSchema.toRegistry(TypeDefinitionRegistryOptions.NO_STUBS)

        // Create GJSchemaRaw from round-tripped registry
        val roundTrippedSchema = gjSchemaRawFromRegistry(roundTrippedRegistry)

        // Compare original vs round-tripped
        val checker = FailureCollector()
        checkViaductSchemaInvariants(originalSchema, checker)
        checkViaductSchemaInvariants(roundTrippedSchema, checker)
        SchemaDiff(originalSchema, roundTrippedSchema, checker).diff()
        checker.assertEmpty("\n")
    }

    @Test
    @DisplayName("DIRECTIVE schemas")
    fun `toRegistry round-trip for directive schemas`() {
        assertAll(
            TestSchemas.DIRECTIVE.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("ENUM schemas")
    fun `toRegistry round-trip for enum schemas`() {
        assertAll(
            TestSchemas.ENUM.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("INPUT schemas")
    fun `toRegistry round-trip for input schemas`() {
        assertAll(
            TestSchemas.INPUT.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("INTERFACE schemas")
    fun `toRegistry round-trip for interface schemas`() {
        assertAll(
            TestSchemas.INTERFACE.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("OBJECT schemas")
    fun `toRegistry round-trip for object schemas`() {
        assertAll(
            TestSchemas.OBJECT.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("SCALAR schemas")
    fun `toRegistry round-trip for scalar schemas`() {
        assertAll(
            TestSchemas.SCALAR.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("UNION schemas")
    fun `toRegistry round-trip for union schemas`() {
        assertAll(
            TestSchemas.UNION.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("ROOT schemas")
    fun `toRegistry round-trip for root schemas`() {
        assertAll(
            TestSchemas.ROOT.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("COMPLEX schemas")
    fun `toRegistry round-trip for complex schemas`() {
        assertAll(
            TestSchemas.COMPLEX.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }
}
