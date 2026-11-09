@file:OptIn(viaduct.apiannotations.InternalApi::class)

package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.graphql.scopes.SchemaScopingMode
import viaduct.graphql.scopes.SchemaView
import viaduct.graphql.scopes.ScopedSchemaBuilder
import viaduct.graphql.test.assertJson

interface OperationValidationContract : QueryExecutionContract {
    private fun operationValidationModule() =
        EngineTestModule(
            """
            directive @bypassPolicyCheck on FIELD

            extend type Query @scope(to: ["public","private"]) {
                f1: Int
            }

            extend type Query @scope(to: ["private"]) {
                f2: Int
            }
            """
        ) {
            fieldWithValue("Query" to "f1", 1)
            fieldWithValue("Query" to "f2", 2)
        }

    @Test
    fun `valid full schema query`() {
        operationValidationModule().runContractTest {
            runQuery("{ f1 f2 }")
                .assertJson("""{ "data": {"f1": 1, "f2": 2} }""")
        }
    }

    @Test
    fun `full schema accepts internal bypassPolicyCheck directive`() {
        operationValidationModule().runContractTest {
            runQuery("{ f1 @bypassPolicyCheck }")
                .assertJson("""{ "data": {"f1": 1} }""")
        }
    }

    @Test
    fun `invalid full schema query`() {
        operationValidationModule().runContractTest {
            val result = runQuery("{ f1 f2 f3 }")
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("FieldUndefined@[f3]"))
        }
    }

    @Test
    fun `invalid scoped schema query`() {
        val bootstrapper = operationValidationModule()
        val publicSchema = EngineSchema(
            ScopedSchemaBuilder(
                inputSchema = bootstrapper.fullSchema.schema,
                additionalVisitorConstructors = emptyList(),
                scopingMode = SchemaScopingMode.ScopeAware(setOf("public", "private")),
            ).build(SchemaView.Scoped(setOf("public"))).filtered
        )

        bootstrapper.runContractTest(schema = publicSchema) {
            val result = runQuery("{ f1 f2 }")
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("FieldUndefined@[f2]"))
        }
    }

    @Test
    fun `scoped schema rejects internal bypassPolicyCheck directive`() {
        val bootstrapper = operationValidationModule()
        val publicSchema = EngineSchema(
            ScopedSchemaBuilder(
                inputSchema = bootstrapper.fullSchema.schema,
                additionalVisitorConstructors = emptyList(),
                scopingMode = SchemaScopingMode.ScopeAware(setOf("public", "private")),
            ).build(SchemaView.Scoped(setOf("public"))).filtered
        )

        bootstrapper.runContractTest(schema = publicSchema) {
            val result = runQuery("{ f1 @bypassPolicyCheck }")
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("Unknown directive"))
        }
    }

    @Test
    fun `internal scoped schema accepts bypassPolicyCheck directive`() {
        val bootstrapper = operationValidationModule()
        val internalSchema = EngineSchema(
            ScopedSchemaBuilder(
                inputSchema = bootstrapper.fullSchema.schema,
                additionalVisitorConstructors = emptyList(),
                scopingMode = SchemaScopingMode.ScopeAware(setOf("public", "private")),
            ).buildScopedFull(setOf("public")).filtered
        )

        bootstrapper.runContractTest(schema = internalSchema) {
            runQuery("{ f1 @bypassPolicyCheck }")
                .assertJson("""{ "data": {"f1": 1} }""")
        }
    }
}
