package viaduct.engine.runtime.execution

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData

interface ConditionalDirectivesExecutionContract : QueryExecutionContract {
    val schemaSDL get() =
        """
        extend type Query {
            item: Item
        }

        type Item {
            id: ID!
            hiddenValue: String
        }
        """.trimIndent()

    fun createTestModule(hiddenValueResolverCalls: AtomicInteger) =
        EngineTestModule(schemaSDL) {
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
        }

    @Test
    fun `skipped inline fragment may contain only spread to pruned fragment`() {
        val hiddenValueResolverCalls = AtomicInteger()

        assertQuery(
            createTestModule(hiddenValueResolverCalls),
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
            """.trimIndent(),
            """{"data": {"item": {"id": "item-1"}}}""",
        )

        assertEquals(0, hiddenValueResolverCalls.get())
    }
}
