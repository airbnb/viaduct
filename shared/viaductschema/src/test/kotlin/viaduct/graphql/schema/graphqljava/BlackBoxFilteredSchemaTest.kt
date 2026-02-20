package viaduct.graphql.schema.graphqljava

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.checkViaductSchemaInvariants
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.graphql.schema.test.TestSchemas
import viaduct.invariants.FailureCollector

/**
 * Black-box tests for FilteredSchema with identity filter.
 *
 * These tests verify that wrapping a ViaductSchema in a FilteredSchema with
 * NoopSchemaFilter (identity filter) preserves all schema content. This ensures
 * FilteredSchema correctly delegates to the underlying schema when nothing is filtered.
 *
 * Tests are grouped by GraphQL definition kind - each kind runs as one test that
 * exercises all schemas of that kind.
 */
class BlackBoxFilteredSchemaTest {
    private fun assertFilteredSchemaPreservesContent(fullSdl: String) {
        val registry = SchemaParser().parse(fullSdl)
        val original = ViaductSchema.fromTypeDefinitionRegistry(registry)

        val filtered = original.filter(NoopSchemaFilter())

        // Verify invariants on the filtered schema
        val checker = FailureCollector()
        checkViaductSchemaInvariants(filtered, checker)

        // Compare filtered schema with original
        SchemaDiff(original, filtered, checker).diff()
        checker.assertEmpty("\n")
    }

    @Test
    @DisplayName("DIRECTIVE schemas")
    fun `FilteredSchema preserves directive schemas`() {
        assertAll(
            TestSchemas.DIRECTIVE.map { schema ->
                Executable { assertFilteredSchemaPreservesContent(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("ENUM schemas")
    fun `FilteredSchema preserves enum schemas`() {
        assertAll(
            TestSchemas.ENUM.map { schema ->
                Executable { assertFilteredSchemaPreservesContent(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("INPUT schemas")
    fun `FilteredSchema preserves input schemas`() {
        assertAll(
            TestSchemas.INPUT.map { schema ->
                Executable { assertFilteredSchemaPreservesContent(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("INTERFACE schemas")
    fun `FilteredSchema preserves interface schemas`() {
        assertAll(
            TestSchemas.INTERFACE.map { schema ->
                Executable { assertFilteredSchemaPreservesContent(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("OBJECT schemas")
    fun `FilteredSchema preserves object schemas`() {
        assertAll(
            TestSchemas.OBJECT.map { schema ->
                Executable { assertFilteredSchemaPreservesContent(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("SCALAR schemas")
    fun `FilteredSchema preserves scalar schemas`() {
        assertAll(
            TestSchemas.SCALAR.map { schema ->
                Executable { assertFilteredSchemaPreservesContent(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("UNION schemas")
    fun `FilteredSchema preserves union schemas`() {
        assertAll(
            TestSchemas.UNION.map { schema ->
                Executable { assertFilteredSchemaPreservesContent(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("ROOT schemas")
    fun `FilteredSchema preserves root schemas`() {
        assertAll(
            TestSchemas.ROOT.map { schema ->
                Executable { assertFilteredSchemaPreservesContent(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("COMPLEX schemas")
    fun `FilteredSchema preserves complex schemas`() {
        assertAll(
            TestSchemas.COMPLEX.map { schema ->
                Executable { assertFilteredSchemaPreservesContent(schema.fullSdl) }
            }
        )
    }
}
