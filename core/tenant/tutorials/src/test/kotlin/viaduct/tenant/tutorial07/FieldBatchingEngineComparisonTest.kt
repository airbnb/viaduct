@file:Suppress("ForbiddenImport")

package viaduct.tenant.tutorial07

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

class FieldBatchingEngineComparisonTest : SimpleBatchResolverContractTest() {
    @Test
    fun `engines preserve tenant responses while field batching combines department lookups`() =
        runBlocking {
            val oneQuery = "query One(\$id: String!) { user(id: \$id) { id name department } }"
            val conditionalQuery = "query Conditional(\$show: Boolean!) { users { name department @include(if: \$show) } }"
            val cases = listOf(
                QueryCase("Names", "query Names { users { id name } }"),
                QueryCase("One", oneQuery, mapOf("id" to "user-1")),
                QueryCase("Missing", oneQuery, mapOf("id" to "unknown")),
                QueryCase("List", "query List { users { id name department } }"),
                QueryCase("Mixed", "query Mixed { users { name department } singleUser: user(id: \"user-2\") { department } }"),
                QueryCase("Aliases", "query Aliases { left: users { id department } right: users { name department } }"),
                QueryCase("Conditional true", conditionalQuery, mapOf("show" to true)),
                QueryCase("Conditional false", conditionalQuery, mapOf("show" to false)),
            )
            val modes = linkedMapOf(
                "Runtime" to emptySet<FlagManager.Flag>(),
                "Runtime2 batching off" to setOf(FlagManager.Flags.ENGINE2_ENABLED),
                "Runtime2 batching on" to setOf(FlagManager.Flags.ENGINE2_ENABLED, FlagManager.Flags.ENGINE2_BATCHING),
            )
            val referenceResponses = mutableMapOf<String, Map<String, Any?>?>()

            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                for ((mode, flags) in modes) {
                    val batches = ConcurrentLinkedQueue<Int>()
                    val instrumentation = object : ViaductResolverInstrumentation {
                        override fun onBatchResolverInvocation(
                            metadata: ResolverMetadata,
                            batchSize: Int,
                            state: ViaductResolverInstrumentation.InstrumentationState?,
                        ) {
                            if (metadata.name == SimpleBatchResolverFeatureAppTest.User_DepartmentResolver::class.java.name) batches.add(batchSize)
                        }
                    }
                    val service = viaductBuilder
                        .withFlagManager(MockFlagManager(flags))
                        .withResolverInstrumentation(instrumentation)
                        .build()

                    for (case in cases) {
                        batches.clear()
                        val result = withTimeout(5_000) {
                            service.executeAsync(
                                ExecutionInput.create(case.query, variables = case.variables),
                                SchemaId.Base,
                                dispatcher.executor,
                            ).await()
                        }
                        val batchSizes = batches.toList()
                        val label = "$mode / ${case.name}"
                        println("$label: department batch-size histogram=${batchSizes.groupingBy { it }.eachCount().toSortedMap()}")
                        assertTrue(result.errors.isEmpty(), "$label errors: ${result.errors}")
                        assertTrue(result.isDataPresent, "$label must contain data")
                        if (mode == "Runtime") {
                            referenceResponses[case.name] = result.getData()
                        } else {
                            assertEquals(referenceResponses.getValue(case.name), result.getData(), "$label response differs from Runtime")
                        }
                        if (mode == "Runtime2 batching off") {
                            assertTrue(batchSizes.all { it == 1 }, "$label must use singleton calls: $batchSizes")
                        }
                        when (case.name) {
                            "Names", "Missing", "Conditional false" -> assertTrue(batchSizes.isEmpty(), "$label must not resolve departments")
                            "List" -> {
                                assertEquals(
                                    listOf(
                                        mapOf("id" to "user-1", "name" to "Alice Johnson", "department" to "Engineering"),
                                        mapOf("id" to "user-2", "name" to "Bob Smith", "department" to "Marketing"),
                                        mapOf("id" to "user-3", "name" to "Carol Williams", "department" to "Engineering"),
                                    ),
                                    result.getData()?.get("users"),
                                    "$label must route each user's department correctly",
                                )
                                assertEquals(3, batchSizes.sum(), "$label must resolve three departments")
                                if (mode != "Runtime2 batching off") {
                                    assertTrue(batchSizes.any { it > 1 }, "$label must form a multi-item tenant call: $batchSizes")
                                }
                            }
                        }
                    }
                }
            }
        }

    private data class QueryCase(
        val name: String,
        val query: String,
        val variables: Map<String, Any?> = emptyMap(),
    )
}
