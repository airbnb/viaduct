package viaduct.engine.runtime.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreInvalid

@ExperimentalCoroutinesApi
class RequiredSelectionsTest {
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
    fun `resolve mutation with explicit query`() {
        EngineTestModule("extend type Query { string1: String } extend type Mutation { string1: String }") {
            fieldWithValue("Query" to "string1", "InitialValue")
            field("Mutation" to "string1") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val selections = ctx.engineSelectionSetFactory.engineSelectionSet("Query", "string1", emptyMap())
                        val currentValue = ctx.query(selections).fetchAs<String>("string1")
                        "Mutated from: $currentValue"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("mutation { string1 }")
                .assertJson("{data: {string1: \"Mutated from: InitialValue\"}}")
        }
    }
}
