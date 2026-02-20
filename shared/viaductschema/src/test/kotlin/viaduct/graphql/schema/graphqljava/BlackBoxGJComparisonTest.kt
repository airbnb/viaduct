package viaduct.graphql.schema.graphqljava

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import viaduct.graphql.schema.checkViaductSchemaInvariants
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.graphql.schema.test.TestSchemas
import viaduct.invariants.FailureCollector

/**
 * Black-box tests comparing GJSchema and GJSchemaRaw implementations.
 *
 * These tests verify that both GJ implementations produce equivalent ViaductSchema
 * views when created from the same SDL. This catches any inconsistencies in how
 * the two implementations wrap graphql-java types.
 *
 * Tests are grouped by GraphQL definition kind - each kind runs as one test that
 * exercises all compatible schemas of that kind.
 */
class BlackBoxGJComparisonTest {
    companion object {
        private fun filterCompatible(schemas: List<TestSchemas.Case>): List<TestSchemas.Case> = schemas.filter { it.gjIncompatible == null }
    }

    private fun assertGJImplementationsAgree(fullSdl: String) {
        val registry = SchemaParser().parse(fullSdl)

        // GJSchemaRaw: directly from TypeDefinitionRegistry
        val gjSchemaRaw = gjSchemaRawFromRegistry(registry)

        // GJSchema: from GraphQLSchema (which is built from the registry)
        val graphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        val gjSchema = gjSchemaFromSchema(graphQLSchema)

        // Both should produce equivalent ViaductSchema views
        val checker = FailureCollector()
        checkViaductSchemaInvariants(gjSchemaRaw, checker)
        GJSchemaCheck(gjSchema, graphQLSchema, checker)
        SchemaDiff(gjSchemaRaw, gjSchema, checker).diff()
        checker.assertEmpty("\n")
    }

    @Test
    @DisplayName("DIRECTIVE schemas")
    fun `GJ comparison for directive schemas`() {
        assertAll(
            filterCompatible(TestSchemas.DIRECTIVE).map { schema ->
                Executable { assertGJImplementationsAgree(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("ENUM schemas")
    fun `GJ comparison for enum schemas`() {
        assertAll(
            filterCompatible(TestSchemas.ENUM).map { schema ->
                Executable { assertGJImplementationsAgree(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("INPUT schemas")
    fun `GJ comparison for input schemas`() {
        assertAll(
            filterCompatible(TestSchemas.INPUT).map { schema ->
                Executable { assertGJImplementationsAgree(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("INTERFACE schemas")
    fun `GJ comparison for interface schemas`() {
        assertAll(
            filterCompatible(TestSchemas.INTERFACE).map { schema ->
                Executable { assertGJImplementationsAgree(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("OBJECT schemas")
    fun `GJ comparison for object schemas`() {
        assertAll(
            filterCompatible(TestSchemas.OBJECT).map { schema ->
                Executable { assertGJImplementationsAgree(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("SCALAR schemas")
    fun `GJ comparison for scalar schemas`() {
        assertAll(
            filterCompatible(TestSchemas.SCALAR).map { schema ->
                Executable { assertGJImplementationsAgree(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("UNION schemas")
    fun `GJ comparison for union schemas`() {
        assertAll(
            filterCompatible(TestSchemas.UNION).map { schema ->
                Executable { assertGJImplementationsAgree(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("ROOT schemas")
    fun `GJ comparison for root schemas`() {
        assertAll(
            filterCompatible(TestSchemas.ROOT).map { schema ->
                Executable { assertGJImplementationsAgree(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("COMPLEX schemas")
    fun `GJ comparison for complex schemas`() {
        assertAll(
            filterCompatible(TestSchemas.COMPLEX).map { schema ->
                Executable { assertGJImplementationsAgree(schema.fullSdl) }
            }
        )
    }
}
