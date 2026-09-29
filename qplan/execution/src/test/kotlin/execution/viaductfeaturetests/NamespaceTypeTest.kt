package execution.viaductfeaturetests

// core/engine/runtime/src/test/kotlin/viaduct/engine/runtime/NamespaceTypeTest.kt
// Copied 2 out of 2 tests as of 2026-09-01

import execution.testing.runQPlanFeatureTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.graphql.test.assertJson

@ExperimentalCoroutinesApi
class NamespaceTypeTest {
    companion object {
        private val schema = """
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
    }

    private fun bootstrapper() =
        EngineTestModule(schema) {
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
        bootstrapper().runQPlanFeatureTest {
            runQuery("{ listings { availableRoomTypes { id description } } }")
                .assertJson("""{"data": {"listings": {"availableRoomTypes": [{"id": "1", "description": "Entire place"}, {"id": "2", "description": "Private room"}]}}}""")
        }
    }

    @Test
    fun `nested namespace types`() {
        bootstrapper().runQPlanFeatureTest {
            runQuery("{ listings { pricing { currencyOptions } } }")
                .assertJson("""{"data": {"listings": {"pricing": {"currencyOptions": ["USD", "EUR", "GBP"]}}}}""")
        }
    }
}
