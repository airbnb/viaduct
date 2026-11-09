package viaduct.engine.runtime.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.Engine1QueryExecutionContract
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreInvalid

@ExperimentalCoroutinesApi
class RequiredSelectionsContractTest : RequiredSelectionsContract, Engine1QueryExecutionContract {
    @Test
    fun `unknown queryValueFragment field raises Engine1 validation exception`() {
        assertThrows<RequiredSelectionsAreInvalid> {
            EngineTestModule("extend type Query { existingField: String, result: String }") {
                fieldWithValue("Query" to "existingField", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("nonExistentField")
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    fun `wrong queryValueFragment type raises Engine1 validation exception`() {
        assertThrows<RequiredSelectionsAreInvalid> {
            EngineTestModule("extend type Query { field: String, result: String } extend type Mutation { dummy: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("... on Mutation { field }")
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    @Disabled("N/A: Engine1 rejects mutation resolver query selections")
    override fun `resolve mutation with queryValueFragment`() {
        super<RequiredSelectionsContract>.`resolve mutation with queryValueFragment`()
    }
}
