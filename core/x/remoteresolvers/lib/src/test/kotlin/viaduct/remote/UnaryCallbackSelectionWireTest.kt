@file:Suppress("ForbiddenImport")

package viaduct.remote

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.deferred.ThreadLocalCoroutineContextManager
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.SelectionsRegistry

/** Exercises unary query and mutation callbacks with serialized selections. */
class UnaryCallbackSelectionWireTest {
    private val schema = MockSchema.mk(
        """
        extend type Query { user(id: ID!): User }
        extend type Mutation { updateUser(id: ID!, name: String): User }
        type User {
            id: ID!
            name: String!
        }
        """.trimIndent()
    )

    @AfterEach
    fun tearDown() {
        ContextRegistry.clear()
        SelectionsRegistry.clear()
    }

    @Test
    fun `unary query callback reconstructs selections and variables without a handle`() =
        runBlocking {
            val context = RecordingContext(ContextMocks(schema).engineExecutionContext)
            val result = executeUnaryCallback(
                context = context,
                selectionType = "Query",
                selectionText = "user(id: \$id) { id name }",
                variables = mapOf("id" to "user:1"),
                options = ResolveSelectionSetOptions.DEFAULT,
            )

            val call = context.calls.single()
            assertEquals("Query", call.type)
            assertEquals("user:1", call.variables["id"])
            assertTrue(call.selectionSet.containsField("Query", "user"))
            assertEquals(Engine.OperationType.QUERY, call.operationType)
            assertUserResult(result, "user", "user:1", "Alice")
            assertEquals(0, SelectionsRegistry.size)
        }

    @Test
    fun `unary mutation callback preserves mutation operation without a handle`() =
        runBlocking {
            val context = RecordingContext(ContextMocks(schema).engineExecutionContext)
            val result = executeUnaryCallback(
                context = context,
                selectionType = "Mutation",
                selectionText = "updateUser(id: \$id, name: \$name) { id name }",
                variables = mapOf("id" to "user:2", "name" to "Bob"),
                options = ResolveSelectionSetOptions.MUTATION,
            )

            val call = context.calls.single()
            assertEquals("Mutation", call.type)
            assertEquals("user:2", call.variables["id"])
            assertEquals("Bob", call.variables["name"])
            assertTrue(call.selectionSet.containsField("Mutation", "updateUser"))
            assertEquals(Engine.OperationType.MUTATION, call.operationType)
            assertUserResult(result, "updateUser", "user:2", "Bob")
            assertEquals(0, SelectionsRegistry.size)
        }

    private suspend fun executeUnaryCallback(
        context: RecordingContext,
        selectionType: String,
        selectionText: String,
        variables: Map<String, Any?>,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync {
        val serverName = "unary-callback-${System.nanoTime()}"
        val server = InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(EngineCallbackServiceImpl())
            .build()
            .start()
        val channel = InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build()
        val contextHandle = ContextRegistry.register(context, currentCoroutineContext())
        try {
            val selectionSet = context.engineSelectionSetFactory.engineSelectionSet(
                selectionType,
                selectionText,
                variables,
            )
            val remoteContext = UnaryRemoteEngineExecutionContext(
                delegate = null,
                callbackChannel = channel,
                contextHandle = contextHandle,
                localSchema = schema,
            )
            return remoteContext.resolveSelectionSet(selectionSet, options)
        } finally {
            ContextRegistry.unregister(contextHandle)
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    private suspend fun assertUserResult(
        result: EngineObjectData.Sync,
        rootField: String,
        expectedId: String,
        expectedName: String,
    ) {
        val user = result.fetch(rootField) as EngineObjectData
        assertEquals(expectedId, user.fetch("id"))
        assertEquals(expectedName, user.fetch("name"))
    }

    private class RecordingContext(
        delegate: EngineExecutionContext,
    ) : EngineExecutionContext by delegate {
        val calls = mutableListOf<Call>()

        override suspend fun resolveSelectionSet(
            selectionSet: EngineSelectionSet,
            options: ResolveSelectionSetOptions,
        ): EngineObjectData.Sync {
            // Fails when callback resolution runs without the engine's thread-local context.
            ThreadLocalCoroutineContextManager.INSTANCE.getCurrentCoroutineContext()
            calls.add(
                Call(
                    type = selectionSet.type,
                    variables = selectionSet.variables,
                    selectionSet = selectionSet,
                    operationType = options.operationType,
                )
            )
            val userType = requireNotNull(fullSchema.schema.getObjectType("User"))
            val user = EngineObjectDataBuilder.from(userType)
                .put("id", selectionSet.variables["id"] ?: "user:1")
                .put("name", selectionSet.variables["name"] ?: "Alice")
                .build()
            if (selectionSet.type == "User") return user

            val rootType = requireNotNull(fullSchema.schema.getObjectType(selectionSet.type))
            return EngineObjectDataBuilder.from(rootType)
                .put(if (selectionSet.type == "Query") "user" else "updateUser", user)
                .build()
        }
    }

    private data class Call(
        val type: String,
        val variables: Map<String, Any?>,
        val selectionSet: EngineSelectionSet,
        val operationType: Engine.OperationType,
    )
}
