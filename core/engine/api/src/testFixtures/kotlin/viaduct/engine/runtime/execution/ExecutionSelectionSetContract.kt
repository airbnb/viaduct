package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.graphql.test.assertJson
import viaduct.service.api.spi.mocks.MockFlagManager

interface ExecutionSelectionSetContract : QueryExecutionContract {
    @Test
    fun `legacy resolver selection set preserves named fragments`() {
        var receivedSelections: EngineSelectionSet? = null

        EngineTestModule(
            """
                extend type Query { item: Item }
                type Item { id: ID! }
            """.trimIndent()
        ) {
            field("Query" to "item") {
                resolver {
                    fn { _, _, _, selections, _ ->
                        receivedSelections = selections
                        createEngineObjectData(
                            schema.schema.getObjectType("Item"),
                            mapOf("id" to "item-1"),
                        )
                    }
                }
            }
        }.runContractTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                flagManager = MockFlagManager.Disabled,
            )
        ) {
            runQuery(
                """
                    query {
                      item { ...ItemFields }
                    }

                    fragment ItemFields on Item {
                      id
                    }
                """.trimIndent()
            ).assertJson("""{"data": {"item": {"id": "item-1"}}}""")
        }

        assertTrue(requireNotNull(receivedSelections).containsField("Item", "id"))
    }
}
