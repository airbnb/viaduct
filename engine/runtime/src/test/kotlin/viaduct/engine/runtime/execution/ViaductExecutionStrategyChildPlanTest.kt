package viaduct.engine.runtime.execution

import graphql.execution.ExecutionStepInfo
import graphql.language.Field as GJField
import graphql.language.InlineFragment as GJInlineFragment
import graphql.language.SelectionSet as GJSelectionSet
import graphql.schema.DataFetcher
import graphql.schema.TypeResolver
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.dataloader.NextTickDispatcher
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeViaductModernGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.service.api.spi.FlagManager

/**
 * Tests for ViaductExecutionStrategy child plan functionality.
 *
 * This test class specifically covers child plan execution scenarios that occur
 * when Required Selection Sets (RSS) are configured for fields. Child plans are
 * sub-executions that fetch additional data needed for authorization or field
 * resolution logic.
 *
 * Test scenarios include:
 * - Child plans maintaining correct execution paths (not inheriting parent field paths)
 * - Query-type vs. Object-type child plan execution contexts
 * - Nested object types with RSS at multiple levels
 * - List fields with RSS for each item
 * - Interface and union types with RSS
 * - Mixed Query and Object type child plans in the same execution
 *
 * These tests ensure that the fix for child plan path handling (commit 589665aee1c7d)
 * works correctly across various GraphQL schema configurations.
 *
 * For general execution strategy tests, see ViaductExecutionStrategyTest.
 * For modern vs. classic strategy comparisons, see ViaductExecutionStrategyModernTest.
 */
@ExperimentalCoroutinesApi
class ViaductExecutionStrategyChildPlanTest {
    private val nextTickDispatcher = NextTickDispatcher(flagManager = FlagManager.disabled)

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `child plans execute with fresh root path and correct object type`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val childPlanExecutionStepInfos = ConcurrentLinkedQueue<ExecutionStepInfo>()
                val childPlanLatch = CountDownLatch(1)

                val sdl = """
                    type Query {
                        testEntity: TestEntity
                    }

                    type TestEntity {
                        id: ID!
                        name: String
                        details: String
                        childPlanField: String
                    }
                """

                val query = """
                    {
                        testEntity {
                            id
                            name
                            details
                            ...Foo
                        }
                    }
                    fragment Foo on TestEntity {
                        id
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "testEntity" to DataFetcher { _ ->
                            mapOf("id" to "123", "name" to "Test Entity")
                        }
                    ),
                    "TestEntity" to mapOf(
                        "id" to DataFetcher { env ->
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "name" to DataFetcher { env ->
                            env.getSource<Map<String, Any>>()!!["name"]
                        },
                        "details" to DataFetcher { _ ->
                            childPlanLatch.await()
                            "Entity details"
                        },
                        "childPlanField" to DataFetcher { env ->
                            childPlanLatch.countDown()
                            childPlanExecutionStepInfos.add(env.executionStepInfo)
                            null
                        }
                    )
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("TestEntity" to "details", "fragment Main on TestEntity { childPlanField ...Bar } fragment Bar on TestEntity { id }")
                    .build()

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry
                )

                val data = executionResult.getData<Map<String, Any?>>()
                assertNotNull(data)
                val testEntity = data["testEntity"] as Map<String, Any?>
                assertEquals("123", testEntity["id"])
                assertEquals("Test Entity", testEntity["name"])
                assertEquals("Entity details", testEntity["details"])
                assertNull(testEntity["childPlanField"])

                assertTrue(childPlanExecutionStepInfos.isNotEmpty(), "Expected child plan to be executed")
                val childStepInfo = childPlanExecutionStepInfos.first()
                assertEquals(listOf("testEntity", "childPlanField"), childStepInfo.path.toList())
                assertEquals("/testEntity/childPlanField", childStepInfo.path.toString(), "Child plan should execute with fresh root path")
                assertEquals("TestEntity", childStepInfo.objectType?.name, "Child plan should execute with TestEntity object type")
                assertNotNull(childStepInfo.field, "MergedField should be present")
                assertEquals("childPlanField", childStepInfo.field.singleField.name, "Child plan should execute for 'childPlanField' field")
                assertNotNull(childStepInfo.fieldDefinition, "Field definition should be present")
            }
        }
    }

    @Test
    fun `child plans for Query type use correct execution context`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                var childPlanObjectType: String? = null

                val sdl = """
                    type Query {
                        specialField: String
                        helperField: String
                    }
                """

                val query = """
                    {
                        specialField
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "specialField" to DataFetcher { env ->
                            childPlanObjectType = env.executionStepInfo.objectType?.name
                            "Special value"
                        },
                        "helperField" to DataFetcher { "Helper value" }
                    )
                )

                // Create a Query-level required selection set
                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("Query" to "specialField", "__typename")
                    .build()

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry
                )

                val data = executionResult.getData<Map<String, Any?>>()
                assertEquals("Special value", data["specialField"])
                assertEquals("Query", childPlanObjectType, "Expected Query type for Query-level child plan")
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `child plans for object field checkers use fresh root path - reproduces original bug`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val capturedStepInfos = ConcurrentLinkedQueue<ExecutionStepInfo>()
                val childPlanLatch = CountDownLatch(1)

                val sdl = """
                    type Query {
                        node: TestNode
                    }

                    type TestNode {
                        id: ID!
                        restrictedField: Details
                    }

                    type Details {
                        content: String
                    }
                """

                val query = """
                    {
                        node {
                            restrictedField {
                                content
                            }
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "node" to DataFetcher { _ ->
                            mapOf("id" to "node-123")
                        }
                    ),
                    "TestNode" to mapOf(
                        "id" to DataFetcher { env ->
                            capturedStepInfos.add(env.executionStepInfo)
                            childPlanLatch.countDown()
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "restrictedField" to DataFetcher { _ ->
                            // Wait for child plan to complete
                            childPlanLatch.await()
                            mapOf("content" to "Protected content")
                        }
                    ),
                    "Details" to mapOf(
                        "content" to DataFetcher { env ->
                            env.getSource<Map<String, Any>>()!!["content"]
                        }
                    )
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("TestNode" to "restrictedField", "id")
                    .build()

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry
                )

                val data = executionResult.getData<Map<String, Any?>>()
                assertNotNull(data)
                val node = data["node"] as Map<String, Any?>
                val restrictedField = node["restrictedField"] as Map<String, Any?>
                assertEquals("Protected content", restrictedField["content"])

                assertTrue(capturedStepInfos.isNotEmpty(), "Expected id to be fetched for checker RSS")
                val stepInfo = capturedStepInfos.first()
                val idPath = stepInfo.path.toString()
                assertEquals(
                    "/node/id",
                    idPath,
                    "Child plan should execute with fresh root path. " +
                        "Got '$idPath' but expected '/node/id'. " +
                        "This indicates the child plan incorrectly inherited the field's path."
                )
                assertEquals("TestNode", stepInfo.objectType?.name, "Child plan should execute with TestNode object type")
                assertEquals("id", stepInfo.field.singleField.name, "Child plan should execute for 'id' field")
                assertNotNull(stepInfo.fieldDefinition, "Field definition should be present")
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `mixed child plans - Query and Object types in same execution`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val capturedStepInfos = ConcurrentLinkedQueue<Pair<String, ExecutionStepInfo>>()
                // Wait for both child plans to complete before request finishes
                val childPlansLatch = CountDownLatch(2)

                val sdl = """
                    type Query {
                        item: Item
                        globalConfig: Config
                    }

                    type Item {
                        id: ID!
                        restricted: String
                    }

                    type Config {
                        value: String
                    }
                """

                val query = """
                    {
                        item {
                            restricted
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "item" to DataFetcher { _ ->
                            mapOf("id" to "item-123")
                        },
                        "globalConfig" to DataFetcher { env ->
                            capturedStepInfos.add("globalConfig" to env.executionStepInfo)
                            childPlansLatch.countDown()
                            mapOf("value" to "config-value")
                        }
                    ),
                    "Item" to mapOf(
                        "id" to DataFetcher { env ->
                            capturedStepInfos.add("item.id" to env.executionStepInfo)
                            childPlansLatch.countDown()
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "restricted" to DataFetcher { _ ->
                            // Wait for child plans to complete before returning
                            // This keeps the request alive until child plans finish
                            childPlansLatch.await()
                            "restricted-value"
                        }
                    ),
                    "Config" to mapOf(
                        "value" to DataFetcher { env ->
                            env.getSource<Map<String, Any>>()!!["value"]
                        }
                    )
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("Item" to "restricted", "id")
                    .fieldResolverEntryForType("Query", "Item" to "restricted", "globalConfig { value }")
                    .build()

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry
                )

                val data = executionResult.getData<Map<String, Any?>>()
                assertNotNull(data)
                val item = data["item"] as Map<String, Any?>
                assertEquals("restricted-value", item["restricted"])

                assertTrue(capturedStepInfos.size >= 2, "Expected both Object and Query child plans to execute")

                val itemStepInfo = capturedStepInfos.find { it.first == "item.id" }?.second
                assertNotNull(itemStepInfo, "Expected Item.id child plan to execute")
                assertEquals("/item/id", itemStepInfo!!.path.toString(), "Object-type child plan should use parent object path")
                assertEquals("Item", itemStepInfo.objectType?.name, "Object-type child plan should have Item object type")
                assertEquals("id", itemStepInfo.field.singleField.name, "Should be executing 'id' field")

                val queryStepInfo = capturedStepInfos.find { it.first == "globalConfig" }?.second
                assertNotNull(queryStepInfo, "Expected globalConfig child plan to execute")
                assertEquals("/globalConfig", queryStepInfo!!.path.toString(), "Query-type child plan should use root path")
                assertEquals("Query", queryStepInfo.objectType?.name, "Query-type child plan should have Query object type")
                assertEquals("globalConfig", queryStepInfo.field.singleField.name, "Should be executing 'globalConfig' field")
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `nested object types with RSS at multiple levels maintain correct paths`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val capturedStepInfos = ConcurrentLinkedQueue<Pair<String, ExecutionStepInfo>>()
                val childPlansLatch = CountDownLatch(3)

                val sdl = """
                    type Query {
                        root: Level1
                    }

                    type Level1 {
                        id: ID!
                        level2: Level2
                    }

                    type Level2 {
                        id: ID!
                        level3: Level3
                    }

                    type Level3 {
                        id: ID!
                        data: String
                    }
                """

                val query = """
                    {
                        root {
                            level2 {
                                level3 {
                                    data
                                }
                            }
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "root" to DataFetcher { _ ->
                            mapOf("id" to "l1-id")
                        }
                    ),
                    "Level1" to mapOf(
                        "id" to DataFetcher { env ->
                            capturedStepInfos.add("Level1.id" to env.executionStepInfo)
                            childPlansLatch.countDown()
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "level2" to DataFetcher { _ ->
                            mapOf("id" to "l2-id")
                        }
                    ),
                    "Level2" to mapOf(
                        "id" to DataFetcher { env ->
                            capturedStepInfos.add("Level2.id" to env.executionStepInfo)
                            childPlansLatch.countDown()
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "level3" to DataFetcher { _ ->
                            mapOf("id" to "l3-id")
                        }
                    ),
                    "Level3" to mapOf(
                        "id" to DataFetcher { env ->
                            capturedStepInfos.add("Level3.id" to env.executionStepInfo)
                            childPlansLatch.countDown()
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "data" to DataFetcher { _ ->
                            // Wait for all child plans to complete
                            childPlansLatch.await()
                            "final-data"
                        }
                    )
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("Level1" to "level2", "id")
                    .fieldResolverEntry("Level2" to "level3", "id")
                    .fieldResolverEntry("Level3" to "data", "id")
                    .build()

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry
                )

                val data = executionResult.getData<Map<String, Any?>>()
                assertNotNull(data)
                val root = data["root"] as Map<String, Any?>
                val level2 = root["level2"] as Map<String, Any?>
                val level3 = level2["level3"] as Map<String, Any?>
                assertEquals("final-data", level3["data"])

                assertTrue(capturedStepInfos.size >= 3, "Expected RSS at all three levels")

                val l1StepInfo = capturedStepInfos.find { it.first == "Level1.id" }?.second
                assertNotNull(l1StepInfo, "Expected Level1 child plan to execute")
                assertEquals("/root/id", l1StepInfo!!.path.toString(), "Level1 RSS should use parent object path")
                assertEquals("Level1", l1StepInfo.objectType?.name, "Level1 should have correct object type")
                assertEquals("id", l1StepInfo.field.singleField.name, "Should be executing 'id' field")

                val l2StepInfo = capturedStepInfos.find { it.first == "Level2.id" }?.second
                assertNotNull(l2StepInfo, "Expected Level2 child plan to execute")
                assertEquals("/root/level2/id", l2StepInfo!!.path.toString(), "Level2 RSS should use parent object path")
                assertEquals("Level2", l2StepInfo.objectType?.name, "Level2 should have correct object type")
                assertEquals("id", l2StepInfo.field.singleField.name, "Should be executing 'id' field")

                val l3StepInfo = capturedStepInfos.find { it.first == "Level3.id" }?.second
                assertNotNull(l3StepInfo, "Expected Level3 child plan to execute")
                assertEquals("/root/level2/level3/id", l3StepInfo!!.path.toString(), "Level3 RSS should use parent object path")
                assertEquals("Level3", l3StepInfo.objectType?.name, "Level3 should have correct object type")
                assertEquals("id", l3StepInfo.field.singleField.name, "Should be executing 'id' field")
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `list fields with RSS execute child plans with correct paths for each item`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val capturedStepInfos = ConcurrentLinkedQueue<Pair<String, ExecutionStepInfo>>()
                val childPlansLatch = CountDownLatch(3)

                val sdl = """
                    type Query {
                        items: [ListItem]
                    }

                    type ListItem {
                        id: ID!
                        restricted: String
                    }
                """

                val query = """
                    {
                        items {
                            restricted
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "items" to DataFetcher { _ ->
                            listOf(
                                mapOf("id" to "item-1"),
                                mapOf("id" to "item-2"),
                                mapOf("id" to "item-3")
                            )
                        }
                    ),
                    "ListItem" to mapOf(
                        "id" to DataFetcher { env ->
                            val id = env.getSource<Map<String, Any>>()!!["id"]
                            capturedStepInfos.add(id.toString() to env.executionStepInfo)
                            childPlansLatch.countDown()
                            id
                        },
                        "restricted" to DataFetcher { env ->
                            val id = env.getSource<Map<String, Any>>()!!["id"]
                            // Only wait on the last item to avoid deadlock
                            if (id == "item-3") {
                                childPlansLatch.await()
                            }
                            "restricted-$id"
                        }
                    )
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("ListItem" to "restricted", "id")
                    .build()

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry
                )

                val data = executionResult.getData<Map<String, Any?>>()
                assertNotNull(data)
                val items = data["items"] as List<Map<String, Any?>>
                assertEquals(3, items.size)
                assertEquals("restricted-item-1", items[0]["restricted"])
                assertEquals("restricted-item-2", items[1]["restricted"])
                assertEquals("restricted-item-3", items[2]["restricted"])

                assertEquals(3, capturedStepInfos.size, "Expected 3 captured step infos, got ${capturedStepInfos.size}")

                val item1StepInfo = capturedStepInfos.find { it.first == "item-1" }?.second
                assertNotNull(item1StepInfo, "Expected item-1 child plan to execute")
                assertEquals("/items[0]/id", item1StepInfo!!.path.toString(), "First list item RSS should have correct index path")
                assertEquals("ListItem", item1StepInfo.objectType?.name, "First list item should have ListItem object type")
                assertEquals("id", item1StepInfo.field.singleField.name, "Should be executing 'id' field")

                val item2StepInfo = capturedStepInfos.find { it.first == "item-2" }?.second
                assertNotNull(item2StepInfo, "Expected item-2 child plan to execute")
                assertEquals("/items[1]/id", item2StepInfo!!.path.toString(), "Second list item RSS should have correct index path")
                assertEquals("ListItem", item2StepInfo.objectType?.name, "Second list item should have ListItem object type")
                assertEquals("id", item2StepInfo.field.singleField.name, "Should be executing 'id' field")

                val item3StepInfo = capturedStepInfos.find { it.first == "item-3" }?.second
                assertNotNull(item3StepInfo, "Expected item-3 child plan to execute")
                assertEquals("/items[2]/id", item3StepInfo!!.path.toString(), "Third list item RSS should have correct index path")
                assertEquals("ListItem", item3StepInfo.objectType?.name, "Third list item should have ListItem object type")
                assertEquals("id", item3StepInfo.field.singleField.name, "Should be executing 'id' field")
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `interface types with RSS use correct parent type for child plans - inline fragments`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val capturedStepInfos = ConcurrentLinkedQueue<Pair<String, ExecutionStepInfo>>()
                val childPlanLatch = CountDownLatch(1)

                val sdl = """
                    type Query {
                        entity: Entity
                    }

                    interface Entity {
                        id: ID!
                    }

                    type User implements Entity {
                        id: ID!
                        name: String
                        restricted: String
                    }

                    type Admin implements Entity {
                        id: ID!
                        role: String
                        restricted: String
                    }
                """

                val query = """
                    {
                        entity {
                            ... on User {
                                restricted
                            }
                            ... on Admin {
                                restricted
                            }
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "entity" to DataFetcher { _ ->
                            mapOf("id" to "user-123", "__typename" to "User")
                        }
                    ),
                    "User" to mapOf(
                        "id" to DataFetcher { env ->
                            capturedStepInfos.add("User.id" to env.executionStepInfo)
                            childPlanLatch.countDown()
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "name" to DataFetcher { "John" },
                        "restricted" to DataFetcher {
                            childPlanLatch.await()
                            "user-restricted"
                        }
                    ),
                    "Admin" to mapOf(
                        "id" to DataFetcher { env ->
                            capturedStepInfos.add("Admin.id" to env.executionStepInfo)
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "role" to DataFetcher { "super" },
                        "restricted" to DataFetcher { "admin-restricted" }
                    )
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("User" to "restricted", "id")
                    .fieldResolverEntry("Admin" to "restricted", "id")
                    .build()

                val typeResolvers = mapOf(
                    "Entity" to TypeResolver { env ->
                        val obj = env.getObject<Map<String, Any>>()
                        val typename = obj["__typename"] as? String
                        env.schema.getObjectType(typename ?: "User")
                    }
                )

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    typeResolvers = typeResolvers,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry
                )

                val data = executionResult.getData<Map<String, Any?>>()
                assertNotNull(data)
                val entity = data["entity"] as Map<String, Any?>
                assertEquals("user-restricted", entity["restricted"])

                assertTrue(capturedStepInfos.isNotEmpty(), "Expected RSS to execute for concrete type")

                val userStepInfo = capturedStepInfos.find { it.first == "User.id" }?.second
                assertNotNull(userStepInfo, "Expected User child plan to execute")
                assertEquals("User", userStepInfo!!.objectType?.name, "Child plan should use concrete User type, not Entity interface")
                assertEquals("/entity/id", userStepInfo.path.toString(), "Child plan should have correct path")
                assertEquals("id", userStepInfo.field.singleField.name, "Child plan should execute for 'id' field")
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `interface types with RSS execute child plans for direct field selection`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val capturedTypes = ConcurrentLinkedQueue<Pair<String, String?>>()
                val childPlanLatch = CountDownLatch(1)

                val sdl = """
                    type Query {
                        entity: Entity
                    }

                    interface Entity {
                        id: ID!
                        restricted: String
                    }

                    type User implements Entity {
                        id: ID!
                        name: String
                        restricted: String
                    }

                    type Admin implements Entity {
                        id: ID!
                        role: String
                        restricted: String
                    }
                """

                val query = """
                    {
                        entity {
                            restricted
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "entity" to DataFetcher { _ ->
                            mapOf("id" to "user-456", "__typename" to "User")
                        }
                    ),
                    "User" to mapOf(
                        "id" to DataFetcher { env ->
                            val objectType = env.executionStepInfo.objectType?.name
                            capturedTypes.add("User.id" to objectType)
                            childPlanLatch.countDown()
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "name" to DataFetcher { "Jane" },
                        "restricted" to DataFetcher {
                            childPlanLatch.await()
                            "user-restricted-direct"
                        }
                    ),
                    "Admin" to mapOf(
                        "id" to DataFetcher { env ->
                            val objectType = env.executionStepInfo.objectType?.name
                            capturedTypes.add("Admin.id" to objectType)
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "role" to DataFetcher { "super" },
                        "restricted" to DataFetcher { "admin-restricted-direct" }
                    )
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("User" to "restricted", "id")
                    .fieldResolverEntry("Admin" to "restricted", "id")
                    .build()

                val typeResolvers = mapOf(
                    "Entity" to TypeResolver { env ->
                        val obj = env.getObject<Map<String, Any>>()
                        val typename = obj["__typename"] as? String
                        env.schema.getObjectType(typename ?: "User")
                    }
                )

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    typeResolvers = typeResolvers,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry
                )

                val data = executionResult.getData<Map<String, Any?>>()
                assertNotNull(data)
                val entity = data["entity"] as Map<String, Any?>
                assertEquals("user-restricted-direct", entity["restricted"])

                assertTrue(
                    capturedTypes.isNotEmpty(),
                    "Expected RSS child plan to execute when selecting field directly on interface.",
                )

                val userType = capturedTypes.find { it.first == "User.id" }?.second
                assertEquals(
                    "User",
                    userType,
                    "Child plan should use concrete User type when field selected directly on Entity interface"
                )
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `interface narrowing to intermediate interface skips child plans for non-implementing types`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val executionLog = ConcurrentLinkedQueue<String>()
                val userChildPlanLatch = CountDownLatch(1)

                val sdl = """
                    type Query {
                        entity(asUser: Boolean!): Entity!
                    }

                    interface Entity {
                        id: ID!
                        name: String
                    }

                    interface VerifiedEntity implements Entity {
                        id: ID!
                        name: String
                        restricted: String
                    }

                    type User implements VerifiedEntity & Entity {
                        id: ID!
                        name: String
                        restricted: String
                    }

                    type Admin implements Entity {
                        id: ID!
                        name: String
                    }
                """

                val query = """
                    query EntityQuery(${'$'}asUser: Boolean!) {
                        entity(asUser: ${'$'}asUser) {
                            ... on VerifiedEntity {
                                restricted
                            }
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "entity" to DataFetcher { env ->
                            val asUser = env.getArgument<Boolean>("asUser") ?: false
                            if (asUser) {
                                mapOf("__typename" to "User", "id" to "user-1", "name" to "Usery", "restricted" to "secret")
                            } else {
                                mapOf("__typename" to "Admin", "id" to "admin-9", "name" to "Boss")
                            }
                        }
                    ),
                    "User" to mapOf(
                        "id" to DataFetcher { env ->
                            executionLog.add("User:id")
                            userChildPlanLatch.countDown()
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "restricted" to DataFetcher { env ->
                            userChildPlanLatch.await()
                            env.getSource<Map<String, Any>>()!!["restricted"]
                        }
                    ),
                    "Admin" to mapOf(
                        "id" to DataFetcher { env ->
                            executionLog.add("Admin:id")
                            env.getSource<Map<String, Any>>()!!["id"]
                        }
                    )
                )

                val typeResolvers = mapOf(
                    "Entity" to TypeResolver { env ->
                        env.schema.getObjectType(env.getObject<Map<String, Any>>()!!["__typename"] as String)
                    },
                    "VerifiedEntity" to TypeResolver { env ->
                        env.schema.getObjectType(env.getObject<Map<String, Any>>()!!["__typename"] as String)
                    }
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("User" to "restricted", "id")
                    .build()

                // Test with User (implements VerifiedEntity) - child plan should execute
                executionLog.clear()
                val userResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    typeResolvers = typeResolvers,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry,
                    variables = mapOf("asUser" to true)
                )
                val userData = userResult.getData<Map<String, Any?>>()!!
                assertEquals("secret", (userData["entity"] as Map<String, Any?>)["restricted"])
                assertEquals(
                    listOf("User:id"),
                    executionLog.toList(),
                    "Child plan should execute for User which implements VerifiedEntity"
                )

                // Test with Admin (only implements Entity) - child plan should NOT execute
                executionLog.clear()
                val adminResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    typeResolvers = typeResolvers,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry,
                    variables = mapOf("asUser" to false)
                )
                val adminData = adminResult.getData<Map<String, Any?>>()!!
                assertNull(
                    (adminData["entity"] as Map<String, Any?>)["restricted"],
                    "Admin should not have restricted field since it doesn't implement VerifiedEntity"
                )
                assertEquals(
                    emptyList<String>(),
                    executionLog.toList(),
                    "No child plan should execute for Admin which only implements Entity"
                )
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `interface narrowing and broadening preserves correct child plan execution`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val executionLog = ConcurrentLinkedQueue<String>()
                val userChildPlanLatch = CountDownLatch(1)

                val sdl = """
                    type Query {
                        entity(asUser: Boolean!): Entity!
                    }

                    interface Entity {
                        id: ID!
                        name: String
                    }

                    interface VerifiedEntity implements Entity {
                        id: ID!
                        name: String
                        restricted: String
                    }

                    type User implements VerifiedEntity & Entity {
                        id: ID!
                        name: String
                        restricted: String
                    }

                    type Admin implements Entity {
                        id: ID!
                        name: String
                    }
                """

                val query = """
                    query EntityQuery(${'$'}asUser: Boolean!) {
                        entity(asUser: ${'$'}asUser) {
                            ... on VerifiedEntity {
                                restricted
                                ... on Entity {
                                    name
                                }
                            }
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "entity" to DataFetcher { env ->
                            val asUser = env.getArgument<Boolean>("asUser") ?: false
                            if (asUser) {
                                mapOf("__typename" to "User", "id" to "user-1", "name" to "Usery", "restricted" to "secret")
                            } else {
                                mapOf("__typename" to "Admin", "id" to "admin-9", "name" to "Boss")
                            }
                        }
                    ),
                    "User" to mapOf(
                        "id" to DataFetcher { env ->
                            executionLog.add("User:id")
                            userChildPlanLatch.countDown()
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "name" to DataFetcher { env ->
                            env.getSource<Map<String, Any>>()!!["name"]
                        },
                        "restricted" to DataFetcher { env ->
                            userChildPlanLatch.await()
                            env.getSource<Map<String, Any>>()!!["restricted"]
                        }
                    ),
                    "Admin" to mapOf(
                        "id" to DataFetcher { env ->
                            executionLog.add("Admin:id")
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "name" to DataFetcher { env ->
                            env.getSource<Map<String, Any>>()!!["name"]
                        }
                    )
                )

                val typeResolvers = mapOf(
                    "Entity" to TypeResolver { env ->
                        env.schema.getObjectType(env.getObject<Map<String, Any>>()!!["__typename"] as String)
                    },
                    "VerifiedEntity" to TypeResolver { env ->
                        env.schema.getObjectType(env.getObject<Map<String, Any>>()!!["__typename"] as String)
                    }
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("User" to "restricted", "id")
                    .build()

                // Test with User - should execute child plan and access both narrow and broad fields
                executionLog.clear()
                val userResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    typeResolvers = typeResolvers,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry,
                    variables = mapOf("asUser" to true)
                )
                val userData = userResult.getData<Map<String, Any?>>()!!
                val userEntity = userData["entity"] as Map<String, Any?>
                assertEquals("secret", userEntity["restricted"])
                assertEquals("Usery", userEntity["name"])
                assertEquals(
                    listOf("User:id"),
                    executionLog.toList(),
                    "Child plan should execute for User when narrowed to VerifiedEntity, even when broadened back to Entity"
                )

                // Test with Admin - should not execute child plan or access restricted field
                executionLog.clear()
                val adminResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    typeResolvers = typeResolvers,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry,
                    variables = mapOf("asUser" to false)
                )
                val adminData = adminResult.getData<Map<String, Any?>>()!!
                val adminEntity = adminData["entity"] as Map<String, Any?>
                assertNull(adminEntity["restricted"], "Admin should not have restricted field")
                assertNull(adminEntity["name"], "Admin should not have name field either since narrow failed")
                assertEquals(
                    emptyList<String>(),
                    executionLog.toList(),
                    "No child plan should execute for Admin when narrow to VerifiedEntity fails"
                )
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `type checker RSS executes for object type access`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val capturedCheckerPaths = ConcurrentLinkedQueue<String>()

                val sdl = """
                    type Query {
                        currentUser: User
                    }

                    type User {
                        id: ID!
                        name: String
                        email: String
                    }
                """

                val query = """
                    {
                        currentUser {
                            name
                            email
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "currentUser" to DataFetcher { _ ->
                            mapOf("id" to "user-789", "name" to "Bob", "email" to "bob@example.com")
                        }
                    ),
                    "User" to mapOf(
                        "id" to DataFetcher { env ->
                            val path = env.executionStepInfo.path.toString()
                            capturedCheckerPaths.add(path)
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "name" to DataFetcher { env ->
                            env.getSource<Map<String, Any>>()!!["name"]
                        },
                        "email" to DataFetcher { env ->
                            env.getSource<Map<String, Any>>()!!["email"]
                        }
                    )
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .typeCheckerEntry("User", "id")
                    .build()

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry,
                    typeCheckerDispatchers = mapOf("User" to CheckerDispatchers.success())
                )

                val data = executionResult.getData<Map<String, Any?>>()
                assertNotNull(data)
                val user = data["currentUser"] as Map<String, Any?>
                assertEquals("Bob", user["name"])
                assertEquals("bob@example.com", user["email"])

                assertTrue(
                    capturedCheckerPaths.isNotEmpty(),
                    "Expected type checker child plan to execute for User type access. " +
                        "Type-level RSS (via typeCheckerEntry) should run for the return type of currentUser field."
                )

                val checkerPath = capturedCheckerPaths.first()
                assertEquals(
                    "/currentUser/id",
                    checkerPath,
                    "Type checker child plan should execute with parent object path"
                )
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `type checker RSS on interface executes for all concrete types`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val capturedCheckerTypes = ConcurrentLinkedQueue<Pair<String, String?>>()

                val sdl = """
                    type Query {
                        entity: Entity
                    }

                    interface Entity {
                        id: ID!
                        commonField: String
                    }

                    type User implements Entity {
                        id: ID!
                        commonField: String
                        name: String
                    }

                    type Admin implements Entity {
                        id: ID!
                        commonField: String
                        role: String
                    }
                """

                val query = """
                    {
                        entity {
                            commonField
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "entity" to DataFetcher { _ ->
                            mapOf("id" to "user-999", "__typename" to "User", "commonField" to "common-value")
                        }
                    ),
                    "User" to mapOf(
                        "id" to DataFetcher { env ->
                            val objectType = env.executionStepInfo.objectType?.name
                            capturedCheckerTypes.add("User.id" to objectType)
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "commonField" to DataFetcher { env ->
                            env.getSource<Map<String, Any>>()!!["commonField"]
                        },
                        "name" to DataFetcher { "Alice" }
                    ),
                    "Admin" to mapOf(
                        "id" to DataFetcher { env ->
                            val objectType = env.executionStepInfo.objectType?.name
                            capturedCheckerTypes.add("Admin.id" to objectType)
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "commonField" to DataFetcher { env ->
                            env.getSource<Map<String, Any>>()!!["commonField"]
                        },
                        "role" to DataFetcher { "super" }
                    )
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .typeCheckerEntry("User", "id")
                    .typeCheckerEntry("Admin", "id")
                    .build()

                val typeResolvers = mapOf(
                    "Entity" to TypeResolver { env ->
                        val obj = env.getObject<Map<String, Any>>()
                        val typename = obj["__typename"] as? String
                        env.schema.getObjectType(typename ?: "User")
                    }
                )

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    typeResolvers = typeResolvers,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry,
                    typeCheckerDispatchers = mapOf(
                        "User" to CheckerDispatchers.success(),
                        "Admin" to CheckerDispatchers.success()
                    )
                )

                val data = executionResult.getData<Map<String, Any?>>()
                assertNotNull(data)
                val entity = data["entity"] as Map<String, Any?>
                assertEquals("common-value", entity["commonField"])

                assertTrue(
                    capturedCheckerTypes.isNotEmpty(),
                    "Expected type checker to execute for concrete type. " +
                        "With the bug fix (commit 258ba8ae8eda5), buildFieldTypeChildPlans should check " +
                        "all concrete types implementing the interface."
                )

                val userType = capturedCheckerTypes.find { it.first == "User.id" }?.second
                assertEquals(
                    "User",
                    userType,
                    "Type checker child plan should use concrete User type for interface Entity"
                )
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `type checker and field resolver RSS execute independently`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val capturedExecutions = ConcurrentLinkedQueue<Pair<String, String>>()

                val sdl = """
                    type Query {
                        profile: UserProfile
                    }

                    type UserProfile {
                        id: ID!
                        user: User
                    }

                    type User {
                        id: ID!
                        name: String
                        restrictedData: String
                    }
                """

                val query = """
                    {
                        profile {
                            user {
                                restrictedData
                            }
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "profile" to DataFetcher { _ ->
                            mapOf("id" to "profile-123")
                        }
                    ),
                    "UserProfile" to mapOf(
                        "id" to DataFetcher { env ->
                            val path = env.executionStepInfo.path.toString()
                            capturedExecutions.add("type-checker:UserProfile.id" to path)
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "user" to DataFetcher { _ ->
                            mapOf("id" to "user-456", "name" to "Charlie")
                        }
                    ),
                    "User" to mapOf(
                        "id" to DataFetcher { env ->
                            val path = env.executionStepInfo.path.toString()
                            capturedExecutions.add("type-checker:User.id" to path)
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "name" to DataFetcher { env ->
                            val path = env.executionStepInfo.path.toString()
                            capturedExecutions.add("field-resolver:User.name" to path)
                            env.getSource<Map<String, Any>>()!!["name"]
                        },
                        "restrictedData" to DataFetcher { "sensitive-data" }
                    )
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .typeCheckerEntry("UserProfile", "id")
                    .typeCheckerEntry("User", "id")
                    .fieldResolverEntry("User" to "restrictedData", "name")
                    .build()

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry,
                    typeCheckerDispatchers = mapOf(
                        "UserProfile" to CheckerDispatchers.success(),
                        "User" to CheckerDispatchers.success()
                    )
                )

                val data = executionResult.getData<Map<String, Any?>>()
                assertNotNull(data)
                val profile = data["profile"] as Map<String, Any?>
                val user = profile["user"] as Map<String, Any?>
                assertEquals("sensitive-data", user["restrictedData"])

                assertTrue(capturedExecutions.size >= 3, "Expected type checkers and field resolver to execute")

                val profileTypeChecker = capturedExecutions.find { it.first == "type-checker:UserProfile.id" }
                assertNotNull(profileTypeChecker, "UserProfile type checker should execute")
                assertEquals("/profile/id", profileTypeChecker!!.second)

                val userTypeChecker = capturedExecutions.find { it.first == "type-checker:User.id" }
                assertNotNull(userTypeChecker, "User type checker should execute")
                assertEquals("/profile/user/id", userTypeChecker!!.second)

                val fieldResolver = capturedExecutions.find { it.first == "field-resolver:User.name" }
                assertNotNull(fieldResolver, "User.restrictedData field resolver should execute")
                assertEquals("/profile/user/name", fieldResolver!!.second)
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `child plans for interface fields wrap selection set in inline fragment`() {
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val capturedSelectionSets = ConcurrentLinkedQueue<GJSelectionSet>()

                val sdl = """
                    interface Node {
                        id: ID!
                    }

                    type Query {
                        node: Node
                    }

                    type Foo implements Node {
                        id: ID!
                        foo: String
                        fooSpecific: String
                    }
                """

                val query = """
                    {
                        node {
                            id
                            ...on Foo {
                                foo
                            }
                        }
                    }
                """

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "node" to DataFetcher { _ ->
                            mapOf(
                                "id" to "node-1",
                                "foo" to "foo-value",
                                "fooSpecific" to "foo-specific-value"
                            )
                        }
                    ),
                    "Foo" to mapOf(
                        "id" to DataFetcher { env ->
                            env.getSource<Map<String, Any>>()!!["id"]
                        },
                        "foo" to DataFetcher { env ->
                            scopedFuture {
                                delay(10)
                                env.getSource<Map<String, Any>>()!!["foo"]
                            }
                        },
                        "fooSpecific" to DataFetcher { env ->
                            val parentStepInfo = checkNotNull(env.executionStepInfo.parent) {
                                "Expected parent ExecutionStepInfo when executing fooSpecific"
                            }
                            capturedSelectionSets.add(parentStepInfo.field.singleField.selectionSet)
                            env.getSource<Map<String, Any>>()!!["fooSpecific"]
                        }
                    )
                )

                val typeResolvers = mapOf(
                    "Node" to TypeResolver { env ->
                        env.schema.getObjectType("Foo")
                    }
                )

                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("Foo" to "foo", "fooSpecific")
                    .build()

                val executionResult = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    typeResolvers = typeResolvers,
                    query = query,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry
                )

                assertTrue(executionResult.errors.isEmpty(), "Expected no execution errors but got ${executionResult.errors}")
                assertTrue(capturedSelectionSets.isNotEmpty(), "Expected to capture selection set for interface child plan")

                val selectionSet = capturedSelectionSets.first()
                val inlineFragment = selectionSet.selections.single() as GJInlineFragment
                assertEquals("Foo", inlineFragment.typeCondition?.name)
                val nestedField = inlineFragment.selectionSet.selections.single() as GJField
                assertEquals("fooSpecific", nestedField.name)
            }
        }
    }
}
