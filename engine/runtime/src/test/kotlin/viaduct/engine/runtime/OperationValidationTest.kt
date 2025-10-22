package viaduct.engine.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.graphql.scopes.ScopedSchemaBuilder

class OperationValidationTest {
    private val testSchema = """
        extend type Query @scope(to: ["public","private"]) {
            f1: Int
        }

        extend type Query @scope(to: ["private"]) {
            f2: Int
        }
        """

    private val bootstrapper = MockTenantModuleBootstrapper(testSchema) {
        fieldWithValue("Query" to "f1", 1)
        fieldWithValue("Query" to "f2", 2)
    }

    @Test
    fun `valid full schema query`() {
        bootstrapper.runFeatureTest {
            runQuery("{ f1 f2 }")
                .assertJson("""{ "data": {"f1": 1, "f2": 2} }""")
        }
    }

    @Test
    fun `invalid full schema query`() {
        bootstrapper.runFeatureTest {
            val result = runQuery("{ f1 f2 f3 }")
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("FieldUndefined@[f3]"))
        }
    }

    @Test
    fun `invalid scoped schema query`() {
        val publicSchema = ViaductSchema(
            ScopedSchemaBuilder(
                inputSchema = bootstrapper.fullSchema.schema,
                additionalVisitorConstructors = emptyList(),
                validScopes = sortedSetOf("public", "private")
            ).applyScopes(setOf("public")).filtered
        )

        bootstrapper.runFeatureTest(schema = publicSchema) {
            val result = runQuery("{ f1 f2 }")
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("FieldUndefined@[f2]"))
        }
    }
}
