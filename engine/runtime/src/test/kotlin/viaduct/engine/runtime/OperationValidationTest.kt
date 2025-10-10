package viaduct.engine.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.api.mocks.toViaductBuilder
import viaduct.service.api.SchemaId
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.toScopeConfig

class OperationValidationTest {
    val testSchema = """
        extend type Query @scope(to: ["public","private"]) {
            f1: Int
        }

        extend type Query @scope(to: ["private"]) {
            f2: Int
        }
        """

    val publicSchemaId = SchemaId.Scoped("public", setOf("public"))
    val viaductBuilder = MockTenantModuleBootstrapper(testSchema) {
        fieldWithValue("Query" to "f1", 1)
        fieldWithValue("Query" to "f2", 2)
    }.toViaductBuilder()
        .withSchemaConfiguration(
            SchemaConfiguration.fromSdl(
                testSchema,
                scopes = setOf(publicSchemaId.toScopeConfig())
            )
        )

    @Test
    fun `valid full schema query`() {
        viaductBuilder.build().runFeatureTest {
            viaduct.runQuery(SchemaId.Full, "{ f1 f2 }")
                .assertJson("""{ "data": {"f1": 1, "f2": 2} }""")
        }
    }

    @Test
    fun `invalid full schema query`() {
        viaductBuilder.build().runFeatureTest {
            val result = viaduct.runQuery(SchemaId.Full, "{ f1 f2 f3 }")
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("FieldUndefined@[f3]"))
        }
    }

    @Test
    fun `invalid scoped schema query`() {
        viaductBuilder.build().runFeatureTest {
            val result = viaduct.runQuery(publicSchemaId, "{ f1 f2 }")
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("FieldUndefined@[f2]"))
        }
    }
}
