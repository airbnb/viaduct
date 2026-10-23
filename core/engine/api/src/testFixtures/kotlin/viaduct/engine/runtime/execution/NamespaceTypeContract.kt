package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData

interface NamespaceTypeContract : QueryExecutionContract {
    val schemaSDL get() =
        """
        type Listings @namespaceType {
            availableRoomTypes: [RoomType] @resolver
            pricing: ListingsPricing
        }
        type ListingsPricing @namespaceType {
            currencyOptions: [String] @resolver
        }
        type RoomType {
            id: ID!
            description: String
        }
        extend type Query {
            listings: Listings
        }
        """.trimIndent()

    fun createTestModule() =
        EngineTestModule(schemaSDL) {
            field("Listings" to "availableRoomTypes") {
                resolver {
                    fn { _, _, _, _, _ ->
                        listOf(
                            createEngineObjectData(
                                schema.schema.getObjectType("RoomType"),
                                mapOf("id" to "1", "description" to "Entire place")
                            ),
                            createEngineObjectData(
                                schema.schema.getObjectType("RoomType"),
                                mapOf("id" to "2", "description" to "Private room")
                            )
                        )
                    }
                }
            }
            field("ListingsPricing" to "currencyOptions") {
                resolver {
                    fn { _, _, _, _, _ -> listOf("USD", "EUR", "GBP") }
                }
            }
        }

    @Test
    fun `simple case`() {
        assertQuery(
            createTestModule(),
            "{ listings { availableRoomTypes { id description } } }",
            """{"data": {"listings": {"availableRoomTypes": [{"id": "1", "description": "Entire place"}, {"id": "2", "description": "Private room"}]}}}""",
        )
    }

    @Test
    fun `nested namespace types`() {
        assertQuery(
            createTestModule(),
            "{ listings { pricing { currencyOptions } } }",
            """{"data": {"listings": {"pricing": {"currencyOptions": ["USD", "EUR", "GBP"]}}}}""",
        )
    }
}
