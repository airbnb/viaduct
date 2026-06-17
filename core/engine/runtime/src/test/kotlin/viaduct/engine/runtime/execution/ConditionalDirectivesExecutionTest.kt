package viaduct.engine.runtime.execution

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest

class ConditionalDirectivesExecutionTest {
    @Test
    fun `skipped inline fragment may contain only spread to pruned fragment`() {
        val hiddenValueResolverCalls = AtomicInteger()

        EngineTestModule(
            """
            extend type Query {
                item: Item
            }

            type Item {
                id: ID!
                hiddenValue: String
            }
            """.trimIndent()
        ) {
            fieldWithValue(
                "Query" to "item",
                createEngineObjectData(
                    schema.schema.getObjectType("Item"),
                    mapOf("id" to "item-1")
                )
            )

            field("Item" to "hiddenValue") {
                resolver {
                    fn { _, _, _, _, _ ->
                        hiddenValueResolverCalls.incrementAndGet()
                        "should not be resolved"
                    }
                }
            }
        }.runFeatureTest {
            runQuery(
                """
                query {
                  item {
                    id
                    ... on Item @skip(if: true) {
                      ... HiddenItemFields
                    }
                  }
                }

                fragment HiddenItemFields on Item {
                  hiddenValue
                }
                """.trimIndent()
            ).assertJson("""{"data": {"item": {"id": "item-1"}}}""")

            assertEquals(0, hiddenValueResolverCalls.get())
        }
    }
}
