@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.internal

import graphql.schema.GraphQLObjectType
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import strikt.api.expectThat
import strikt.assertions.isA
import viaduct.api.globalid.GlobalIDCodec
import viaduct.api.internal.InternalContext
import viaduct.api.mocks.MockGlobalID
import viaduct.api.mocks.MockGlobalIDCodec
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockType
import viaduct.api.types.NodeObject
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.NodeResolverDispatcherRegistry
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.TypeCheckerDispatcherRegistry
import viaduct.tenant.runtime.globalid.GlobalIDCodecImpl
import viaduct.tenant.runtime.globalid.GlobalIDImpl
import viaduct.tenant.runtime.globalid.GlobalIdFeatureAppTest
import viaduct.tenant.runtime.globalid.User

@OptIn(ExperimentalCoroutinesApi::class)
class NodeReferenceFactoryImplTest {
    @Test
    fun `nodeFor returns a Node Reference`(): Unit =
        runBlocking {
            val nodeResolverRegistryProvider = mockk<Provider<NodeResolverDispatcherRegistry>>()
            val nodeCheckerRegistryProvider = mockk<Provider<TypeCheckerDispatcherRegistry>>()
            every { nodeResolverRegistryProvider.get() } returns mockk()
            every { nodeCheckerRegistryProvider.get() } returns mockk()
            val schema = GlobalIdFeatureAppTest.schema
            val globalId = GlobalIDImpl(User.Reflection, "123")
            val factory = NodeReferenceFactoryImpl { _: String, objectType: GraphQLObjectType ->
                mockk {
                    every { graphQLObjectType } returns objectType
                }
            }

            val reflectionLoader = ReflectionLoaderImpl { TODO("unused") }
            val globalIDCodec = GlobalIDCodecImpl(reflectionLoader)
            val result = factory.nodeFor(globalId, InternalContextImpl(schema, globalIDCodec, reflectionLoader))
            expectThat(result.engineObjectData).isA<NodeReferenceEngineObjectData>()
        }

    private fun createMockInternalContext(globalIDCodec: GlobalIDCodec = MockGlobalIDCodec()): InternalContext = MockInternalContext(GlobalIdFeatureAppTest.schema, globalIDCodec)

    private fun createDefaultNodeEngineObjectData(
        globalIDImpl: GlobalIDImpl<out NodeObject>,
        resolver: () -> Unit = {},
        fetcher: () -> Any? = { "user" },
        graphqlObjectType: GraphQLObjectType = GlobalIdFeatureAppTest.schema.schema.getObjectType(globalIDImpl.type.name),
        globalIDCodec: GlobalIDCodec = MockGlobalIDCodec(),
    ): NodeEngineObjectData {
        return object : NodeEngineObjectData {
            override val id: String
                get() = globalIDCodec.serialize(globalIDImpl)

            override suspend fun resolveData(
                selections: RawSelectionSet,
                context: EngineExecutionContext
            ) {
                resolver()
            }

            override suspend fun fetch(selection: String): Any? {
                return fetcher()
            }

            override val graphQLObjectType: GraphQLObjectType
                get() = graphqlObjectType
        }
    }

    @Test
    fun `nodeFor - valid User type with proper constructor succeeds`() {
        val globalId = GlobalIDImpl(User.Reflection, "123")

        val nodeEngineObjectData = createDefaultNodeEngineObjectData(globalId)
        val nodeEngineObjectDataFactory: (String, GraphQLObjectType) -> NodeEngineObjectData = { _, _ ->
            nodeEngineObjectData
        }

        val factory = NodeReferenceFactoryImpl(nodeEngineObjectDataFactory)
        val internalContext = createMockInternalContext()

        val result = factory.nodeFor(globalId, internalContext)

        assertNotNull(result, "nodeFor should return a non-null result for valid NodeObject type")
        assertEquals(User::class, result::class, "Result should be an instance of User")
    }

    @Test
    fun `nodeFor - type name not found in schema, throws exception`() {
        val invalidNameUserType = MockType("TypeThatDoesNotExist", User::class)
        val globalId = GlobalIDImpl(invalidNameUserType, "123")

        val defaultNodeEngineObjectData = createDefaultNodeEngineObjectData(
            globalId,
            graphqlObjectType = GraphQLObjectType.newObject().name("FakeObject").build()
        )
        val nodeEngineObjectDataFactory: (String, GraphQLObjectType) -> NodeEngineObjectData = { _, _ ->
            defaultNodeEngineObjectData
        }

        val factory = NodeReferenceFactoryImpl(nodeEngineObjectDataFactory)
        val internalContext = createMockInternalContext()

        assertThrows<Exception> {
            factory.nodeFor(globalId, internalContext)
        }
    }

    @Test
    fun `nodeFor - type is invalid, throws exception for constructor not found`() {
        val userNameInvalidType = MockType("User", NodeObject::class)
        val globalId = GlobalIDImpl(userNameInvalidType, "123")
        val mockNodeEngineObjectData = createDefaultNodeEngineObjectData(globalId)
        val nodeEngineObjectDataFactory: (String, GraphQLObjectType) -> NodeEngineObjectData = { _, _ ->
            mockNodeEngineObjectData
        }

        val factory = NodeReferenceFactoryImpl(nodeEngineObjectDataFactory)
        val internalContext = createMockInternalContext()

        assertThrows<Exception> {
            factory.nodeFor(globalId, internalContext)
        }
    }

    @Test
    fun `nodeFor - user returned from function can get the id `() {
        val internalId = "123"
        val globalId = GlobalIDImpl(User.Reflection, internalId)
        val nodeEngineObjectDataFactory: (String, GraphQLObjectType) -> NodeEngineObjectData = { _, _ ->
            createDefaultNodeEngineObjectData(globalId)
        }

        val factory = NodeReferenceFactoryImpl(nodeEngineObjectDataFactory)
        val internalContext = createMockInternalContext()

        val user = factory.nodeFor(globalId, internalContext)

        runBlocking {
            val userInternalId = (user.getId() as MockGlobalID<User>).internalID
            assertEquals(internalId, userInternalId)
        }
    }
}
