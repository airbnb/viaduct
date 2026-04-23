package viaduct.engine.runtime.execution

import graphql.schema.DataFetcher
import graphql.schema.GraphQLCompositeType
import graphql.schema.TypeResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.LazyAbstractData
import viaduct.engine.runtime.NodeEngineObjectDataImpl
import viaduct.engine.runtime.NodeResolverDispatcher
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeViaductModernGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

@ExperimentalCoroutinesApi
class FieldResolverLazyAbstractDataTest {
    // This exercises FieldResolver.lazyAbstractFieldResolutionResult directly because
    // no current API produces a LazyAbstractData whose resolveData returns a
    // LazyEngineObjectData — AbstractRootFieldReference goes through resolveSelectionSet
    // which fully resolves node references before returning.
    @Test
    fun `LazyAbstractData resolving to a node reference should trigger node resolution`() =
        runExecutionTest {
            val sdl = """
                type Query { entity: Entity }
                interface Entity { id: ID!, name: String }
                type User implements Entity { id: ID!, name: String }
            """.trimIndent()

            val nodeResolverCalled = AtomicBoolean(false)
            val nodeResolverDone = CountDownLatch(1)

            val dispatcherRegistry = buildDispatcherRegistry { id, context ->
                nodeResolverCalled.set(true)
                val userType = context.fullSchema.schema.getObjectType("User")
                val result = createEngineObjectData(userType, mapOf("id" to id, "name" to "Alice"))
                nodeResolverDone.countDown()
                result
            }

            val resolvers = mapOf(
                "Query" to mapOf(
                    "entity" to DataFetcher { dfe ->
                        val schema = dfe.graphQLSchema
                        val entityType = schema.getType("Entity") as GraphQLCompositeType
                        val userType = schema.getObjectType("User")
                        object : LazyAbstractData {
                            override val type: GraphQLCompositeType = entityType

                            override suspend fun resolveData(
                                selections: EngineSelectionSet,
                                context: EngineExecutionContext,
                            ): EngineObjectData = NodeEngineObjectDataImpl("42", userType, dispatcherRegistry)
                        }
                    }
                ),
                "User" to mapOf(
                    "id" to DataFetcher { "42" },
                    "name" to DataFetcher { "Alice" }
                )
            )

            val typeResolvers = mapOf(
                "Entity" to TypeResolver { env -> env.schema.getObjectType("User") }
            )

            executeViaductModernGraphQL(
                sdl = sdl,
                resolvers = resolvers,
                query = "{ entity { id name } }",
                typeResolvers = typeResolvers,
                dispatcherRegistry = dispatcherRegistry
            )

            assertTrue(nodeResolverDone.await(5, TimeUnit.SECONDS)) {
                "Node resolver was never called — OER was not created in the pending state"
            }
            assertTrue(nodeResolverCalled.get()) {
                "Node resolver should have been triggered for the NodeEngineObjectDataImpl"
            }
        }

    private fun buildDispatcherRegistry(resolveBlock: suspend (id: String, context: EngineExecutionContext) -> EngineObjectData): DispatcherRegistry {
        val nodeRes = object : NodeResolverDispatcher {
            override val resolverMetadata = ResolverMetadata.forMock("user-node-resolver")

            override suspend fun resolve(
                id: String,
                selections: EngineSelectionSet,
                context: EngineExecutionContext
            ): EngineObjectData = resolveBlock(id, context)
        }
        return DispatcherRegistry.Impl(
            fieldResolverDispatchers = emptyMap(),
            nodeResolverDispatchers = mapOf("User" to nodeRes),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap()
        )
    }
}
