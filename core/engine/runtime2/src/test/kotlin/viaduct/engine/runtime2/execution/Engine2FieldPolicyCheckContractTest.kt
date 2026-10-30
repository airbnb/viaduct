package viaduct.engine.runtime2.execution

import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.runtime.execution.FieldPolicyCheckContract
import viaduct.engine.runtime2.execution.testing.runQPlanFeatureTest

class Engine2FieldPolicyCheckContractTest : FieldPolicyCheckContract, Engine2QueryExecutionContract {
    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Runtime2 resolves values and field checks concurrently, so field denial does not suppress a referenced node's type checker"
    )
    override fun `field should fail even if node passes`() {
        super<FieldPolicyCheckContract>.`field should fail even if node passes`()
    }

    @Engine2Alternate(original = "field should fail even if node passes")
    fun `ALTERNATIVE field should fail even if node passes`() {
        val typeCheckPassed = CompletableDeferred<Unit>()
        val personType = MockSchema.mk(schemaSDL).schema.getObjectType("CanNotAccessPerson")

        EngineTestModule(schemaSDL) {
            field("Query" to "canNotAccessType") {
                resolver { fn { _, _, _, _, ctx -> ctx.createNodeReference("someId", personType) } }
                checker {
                    fn { _, _ ->
                        typeCheckPassed.await()
                        throw RuntimeException("This field is not accessible")
                    }
                }
            }
            type("CanNotAccessPerson") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        personType,
                        mapOf("id" to id, "name" to "john", "ssn" to "social security number")
                    )
                }
                checker { fn { _, _ -> typeCheckPassed.complete(Unit) } }
            }
        }.runQPlanFeatureTest {
            val result = runQueryWithTimeout("query { canNotAccessType { name ssn } }", timeoutMillis = 5_000)
            assertNull(result.getData<Any?>())
            assertEquals(1, result.errors.size)
            assertEquals(listOf("canNotAccessType"), result.errors.single().path)
            assertTrue(result.errors.single().message.contains("This field is not accessible"))
        }

        assertTrue(typeCheckPassed.isCompleted)
    }
}
