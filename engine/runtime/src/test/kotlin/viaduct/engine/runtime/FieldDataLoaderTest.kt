package viaduct.engine.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.graphql.test.assertJson

@ExperimentalCoroutinesApi
class FieldDataLoaderTest {
    companion object {
        private val schemaSDL = """
            extend type Query {
                items: [Item!]!
            }
            type Item {
                id: ID!
                name: String
            }
        """.trimIndent()
    }

    @Test
    fun `batch field resolution clears field scope to prevent cross-field contamination`() {
        // Track field scope fragments and variables in the batch resolver
        var fragmentsInBatchResolver: Map<String, *>? = null
        var variablesInBatchResolver: Map<String, *>? = null

        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "items") {
                resolver {
                    fn { _, _, _, _, _ ->
                        // Return three items with different IDs to ensure batching
                        (1..3).map { i ->
                            mkEngineObjectData(
                                schema.schema.getObjectType("Item"),
                                mapOf("id" to i.toString())
                            )
                        }
                    }
                }
            }
            field("Item" to "name") {
                resolver {
                    objectSelections("id")
                    // Batch resolver - receives multiple selectors at once
                    fn { selectors, context ->
                        // Capture the field scope from the context during batch resolution
                        // Only capture on first call to avoid overwriting
                        if (fragmentsInBatchResolver == null) {
                            fragmentsInBatchResolver = context.fieldScope.fragments
                            variablesInBatchResolver = context.fieldScope.variables
                        }
                        selectors.associateWith { selector ->
                            // Return the batch size to prove batching happened
                            Result.success("name-${selector.objectValue.fetch("id")}-batch:${selectors.size}")
                        }
                    }
                }
            }
        }.runFeatureTest {
            // Run a query - field scope clearing happens in internalLoad during batch resolution
            val result = runQuery(
                """
                {
                    items {
                        id
                        name
                    }
                }
                """.trimIndent()
            )

            // Verify batching happened (all items should show batch:3)
            result.assertJson(
                """
                {
                  "data": {
                    "items": [
                      {"id": "1", "name": "name-1-batch:3"},
                      {"id": "2", "name": "name-2-batch:3"},
                      {"id": "3", "name": "name-3-batch:3"}
                    ]
                  }
                }
                """.trimIndent()
            )

            // Assert: The batch resolver should have received a context with CLEARED field scope
            assertTrue(fragmentsInBatchResolver != null, "Fragments map should have been captured")
            assertTrue(variablesInBatchResolver != null, "Variables map should have been captured")
            assertTrue(
                fragmentsInBatchResolver!!.isEmpty(),
                "Field scope fragments should be cleared for batch resolution"
            )
            assertTrue(
                variablesInBatchResolver!!.isEmpty(),
                "Field scope variables should be cleared for batch resolution"
            )
        }
    }
}
