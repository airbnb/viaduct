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
import viaduct.engine.api.NodeReference
import viaduct.engine.api.NodeResolverDispatcherRegistry
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
            val factory = NodeReferenceGRTFactoryImpl { _: String, objectType: GraphQLObjectType ->
                mockk {
                    every { graphQLObjectType } returns objectType
                }
            }

            val reflectionLoader = ReflectionLoaderImpl { TODO("unused") }
            val globalIDCodec = GlobalIDCodecImpl(reflectionLoader)
            val result = factory.nodeFor(globalId, InternalContextImpl(schema, globalIDCodec, reflectionLoader))
            expectThat(result.engineObject).isA<NodeReference>()
        }

    private fun createMockInternalContext(globalIDCodec: GlobalIDCodec = MockGlobalIDCodec()): InternalContext = MockInternalContext(GlobalIdFeatureAppTest.schema, globalIDCodec)

    private fun createDefaultNodeReference(
        globalIDImpl: GlobalIDImpl<out NodeObject>,
        resolver: () -> Unit = {},
        fetcher: () -> Any? = { "user" },
        graphqlObjectType: GraphQLObjectType = GlobalIdFeatureAppTest.schema.schema.getObjectType(globalIDImpl.type.name),
        globalIDCodec: GlobalIDCodec = MockGlobalIDCodec(),
    ): NodeReference {
        return object : NodeReference {
            override val id: String
                get() = globalIDCodec.serialize(globalIDImpl)

            override val graphQLObjectType: GraphQLObjectType
                get() = graphqlObjectType
        }
    }

    @Test
    fun `nodeFor - valid User type with proper constructor succeeds`() {
        val globalId = GlobalIDImpl(User.Reflection, "123")

        val nodeEngineObjectData = createDefaultNodeReference(globalId)
        val nodeReferenceFactory: (String, GraphQLObjectType) -> NodeReference = { _, _ ->
            nodeEngineObjectData
        }

        val factory = NodeReferenceGRTFactoryImpl(nodeReferenceFactory)
        val internalContext = createMockInternalContext()

        val result = factory.nodeFor(globalId, internalContext)

        assertNotNull(result, "nodeFor should return a non-null result for valid NodeObject type")
        assertEquals(User::class, result::class, "Result should be an instance of User")
    }

    @Test
    fun `nodeFor - type name not found in schema, throws exception`() {
        val invalidNameUserType = MockType("TypeThatDoesNotExist", User::class)
        val globalId = GlobalIDImpl(invalidNameUserType, "123")

        val defaultNodeEngineObjectData = createDefaultNodeReference(
            globalId,
            graphqlObjectType = GraphQLObjectType.newObject().name("FakeObject").build()
        )
        val nodeReferenceFactory: (String, GraphQLObjectType) -> NodeReference = { _, _ ->
            createDefaultNodeReference(globalId, graphqlObjectType = GraphQLObjectType.newObject().name("FakeObject").build())
        }

        val factory = NodeReferenceGRTFactoryImpl(nodeReferenceFactory)
        val internalContext = createMockInternalContext()

        assertThrows<Exception> {
            factory.nodeFor(globalId, internalContext)
        }
    }

    @Test
    fun `nodeFor - type is invalid, throws exception for constructor not found`() {
        val userNameInvalidType = MockType("User", NodeObject::class)
        val globalId = GlobalIDImpl(userNameInvalidType, "123")
        val nodeReferenceFactory: (String, GraphQLObjectType) -> NodeReference = { _, _ ->
            createDefaultNodeReference(globalId)
        }

        val factory = NodeReferenceGRTFactoryImpl(nodeReferenceFactory)
        val internalContext = createMockInternalContext()

        assertThrows<Exception> {
            factory.nodeFor(globalId, internalContext)
        }
    }

    @Test
    fun `nodeFor - user returned from function can get the id `() {
        val internalId = "123"
        val globalId = GlobalIDImpl(User.Reflection, internalId)
        val nodeReferenceFactory: (String, GraphQLObjectType) -> NodeReference = { _, _ ->
            createDefaultNodeReference(globalId)
        }

        val factory = NodeReferenceGRTFactoryImpl(nodeReferenceFactory)
        val internalContext = createMockInternalContext()

        val user = factory.nodeFor(globalId, internalContext)

        runBlocking {
            val userInternalId = (user.getId() as MockGlobalID<User>).internalID
            assertEquals(internalId, userInternalId)
        }
    }
}
