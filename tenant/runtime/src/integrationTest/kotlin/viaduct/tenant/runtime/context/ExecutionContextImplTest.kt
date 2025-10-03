@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.context

import graphql.schema.GraphQLObjectType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.reflect.KClass
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.globalid.GlobalID
import viaduct.api.globalid.GlobalIDCodec
import viaduct.api.internal.InternalContext
import viaduct.api.internal.NodeReferenceFactory
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.mocks.MockGlobalID
import viaduct.api.mocks.MockGlobalIDCodec
import viaduct.api.mocks.MockInternalContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.RawSelectionSet
import viaduct.tenant.runtime.globalid.GlobalIDCodecImpl
import viaduct.tenant.runtime.globalid.GlobalIDImpl
import viaduct.tenant.runtime.globalid.User
import viaduct.tenant.runtime.internal.NodeReferenceFactoryImpl
import viaduct.tenant.runtime.internal.ReflectionLoaderImpl
import viaduct.tenant.runtime.select.Foo
import viaduct.tenant.runtime.select.SelectTestFeatureAppTest
import viaduct.tenant.runtime.select.SelectionSetFactoryImpl

@ExperimentalCoroutinesApi
class ExecutionContextImplTest {
    private object Obj : Object

    private object Q : Query

    private object Args : Arguments

    private val queryObject = mockk<Query>()

    private fun mk(
        obj: Object = Obj,
        query: Query = Q,
        args: Arguments = Args,
        globalIDCodec: GlobalIDCodec = MockGlobalIDCodec(),
        selectionSet: SelectionSet<*> = SelectionSet.NoSelections,
        queryLoader: SelectionsLoader<Query> = SelectionsLoader.Companion.const(queryObject),
        selectionSetFactory: SelectionSetFactory = SelectionSetFactoryImpl(mockk()),
        nodeReferenceFactory: NodeReferenceFactory = mockk<NodeReferenceFactory>()
    ) = FieldExecutionContextImpl(
        ResolverExecutionContextImpl(
            MockInternalContext(SelectTestFeatureAppTest.schema, globalIDCodec),
            queryLoader,
            selectionSetFactory,
            nodeReferenceFactory
        ),
        obj,
        query,
        args,
        selectionSet,
    )

    @Test
    fun `globalIDFor - valid type and id returns GlobalID`() {
        val ctx = mk()

        val result = ctx.globalIDFor(User.Reflection, "123")

        assertEquals(User.Reflection, result.type)
        assertEquals("123", result.internalID)
        assertTrue(result is GlobalIDImpl)
    }

    @Test
    fun `globalIDFor - type kcls constructor mismatch should throw with reasonable error`() {
        val ctx = mk()

        @Suppress("UNCHECKED_CAST")
        val fakeTypeWithWrongKClass = object : Type<NodeObject> {
            override val name: String = "User"
            override val kcls: KClass<out NodeObject> = String::class as KClass<out NodeObject>
        }

        val exception = assertThrows<IllegalArgumentException> {
            ctx.globalIDFor(fakeTypeWithWrongKClass, "123")
        }

        assertTrue(
            exception.message?.contains("NodeObject") == true,
            "Error message should mention 'NodeObject': ${exception.message}"
        )
    }

    @Test
    fun `globalIDFor - empty internal id creates valid GlobalID`() {
        val ctx = mk()

        val result = ctx.globalIDFor(User.Reflection, "")

        assertEquals(User.Reflection, result.type)
        assertEquals("", result.internalID)
        assertTrue(result is GlobalIDImpl)
    }

    @Test
    fun `globalIDFor - special characters in internal id are preserved`() {
        val ctx = mk()

        val specialInternalId = "user:123%+test value!@#$%^&*()"

        val result = ctx.globalIDFor(User.Reflection, specialInternalId)

        assertEquals(User.Reflection, result.type)
        assertEquals(specialInternalId, result.internalID)
        assertTrue(result is GlobalIDImpl)
    }

    @Test
    fun `globalIDFor - unicode characters in internal id are preserved`() {
        val ctx = mk()

        val unicodeInternalId = "用户_测试_🚀_123"

        val result = ctx.globalIDFor(User.Reflection, unicodeInternalId)

        assertEquals(User.Reflection, result.type)
        assertEquals(unicodeInternalId, result.internalID)
        assertTrue(result is GlobalIDImpl)
    }

    @Test
    fun `globalIDFor - whitespace only internal id creates valid GlobalID`() {
        val ctx = mk()

        val whitespaceInternalId = "   \t\n  "

        val result = ctx.globalIDFor(User.Reflection, whitespaceInternalId)

        assertEquals(User.Reflection, result.type)
        assertEquals(whitespaceInternalId, result.internalID)
        assertTrue(result is GlobalIDImpl)
    }

    @Test
    fun `globalIDFor - very long internal id creates valid GlobalID`() {
        val ctx = mk()

        val longInternalId = "a".repeat(10000)

        val result = ctx.globalIDFor(User.Reflection, longInternalId)

        assertEquals(User.Reflection, result.type)
        assertEquals(longInternalId, result.internalID)
        assertEquals(10000, result.internalID.length)
        assertTrue(result is GlobalIDImpl)
    }

    @Test
    fun `globalIDFor - different types with same internal id produce different GlobalIDs`() {
        val ctx = mk()

        val internalId = "123"
        val userGlobalId = ctx.globalIDFor(User.Reflection, internalId)

        assertEquals(User.Reflection, userGlobalId.type)
        assertEquals(internalId, userGlobalId.internalID)
    }

    @Test
    fun `globalIDFor - same type and internal id produce equal GlobalIDs`() {
        val ctx = mk()

        val internalId = "123"
        val globalId1 = ctx.globalIDFor(User.Reflection, internalId)
        val globalId2 = ctx.globalIDFor(User.Reflection, internalId)

        assertEquals(globalId1, globalId2)
        assertEquals(globalId1.hashCode(), globalId2.hashCode())
        assertEquals(globalId1.type, globalId2.type)
        assertEquals(globalId1.internalID, globalId2.internalID)
    }

    @Test
    fun `globalIDStringFor - valid type and id returns serialized string`() {
        val mockGlobalIDCodec = mockk<GlobalIDCodec>()
        val ctx = mk(globalIDCodec = mockGlobalIDCodec)

        val expectedSerializedString = "encoded_user_123"

        every {
            mockGlobalIDCodec.serialize<User>(
                match { globalId ->
                    globalId.type == User.Reflection && globalId.internalID == "123"
                }
            )
        } returns expectedSerializedString

        val result = ctx.globalIDStringFor(User.Reflection, "123")

        assertEquals(expectedSerializedString, result)

        verify {
            mockGlobalIDCodec.serialize<User>(
                match { globalId ->
                    globalId.type == User.Reflection && globalId.internalID == "123"
                }
            )
        }
    }

    @Test
    fun `globalIDStringFor - internalID contains characters that require escaping`() {
        val reflectionLoader = ReflectionLoaderImpl { TODO("unused") }
        val realGlobalIDCodec = GlobalIDCodecImpl(reflectionLoader)
        val ctx = mk(globalIDCodec = realGlobalIDCodec)

        val internalIdWithSpecialChars = "user:123%+test value"

        val result = ctx.globalIDStringFor(User.Reflection, internalIdWithSpecialChars)

        assertNotNull(result)
        assertTrue(result.isNotEmpty())

        assertFalse(result.contains(":"))
        assertFalse(result.contains("%"))
        assertFalse(result.contains("+"))
        assertFalse(result.contains(" "))
    }

    @Test
    fun `globalIDStringFor - returned value can be decoded using GlobalIDCodecImpl`() {
        val reflectionLoader = ReflectionLoaderImpl { className ->
            when (className) {
                "User\$Reflection" -> User.Reflection::class
                else -> throw ClassNotFoundException("Class not found: $className")
            }
        }

        val realGlobalIDCodec = GlobalIDCodecImpl(reflectionLoader)
        val ctx = mk(globalIDCodec = realGlobalIDCodec)

        val originalInternalId = "user:123%+test value"

        val encodedString = ctx.globalIDStringFor(User.Reflection, originalInternalId)

        val decodedGlobalId: GlobalID<User> = realGlobalIDCodec.deserialize(encodedString)

        assertEquals(User.Reflection.name, decodedGlobalId.type.name)
        assertEquals(User.Reflection.kcls, decodedGlobalId.type.kcls)
        assertEquals(originalInternalId, decodedGlobalId.internalID)
    }

    @Test
    fun `globalIDStringFor - round trip encoding with various special characters`() {
        val reflectionLoader = ReflectionLoaderImpl { className ->
            when (className) {
                "User\$Reflection" -> User.Reflection::class
                else -> throw ClassNotFoundException("Class not found: $className")
            }
        }
        val realGlobalIDCodec = GlobalIDCodecImpl(reflectionLoader)
        val ctx = mk(globalIDCodec = realGlobalIDCodec)

        val testCases = listOf(
            "simple123",
            "user:with:colons",
            "percent%encoded",
            "plus+signs",
            "spaces in id",
            "symbols!@#$%^&*()",
            "unicode_测试_🚀",
            "mixed:123%test+with spaces&symbols",
            "",
            "   ",
        )

        testCases.forEach { originalInternalId ->
            val encodedString = ctx.globalIDStringFor(User.Reflection, originalInternalId)

            val decodedGlobalId: GlobalID<User> = realGlobalIDCodec.deserialize(encodedString)

            assertEquals(
                originalInternalId,
                decodedGlobalId.internalID,
                "Round-trip failed for internal ID: '$originalInternalId'"
            )
            assertEquals(User.Reflection.name, decodedGlobalId.type.name)
        }
    }

    @Test
    fun `nodeFor - exceptions from NodeReferenceFactory are propagated back to caller`() {
        val mockNodeReferenceFactory = mockk<NodeReferenceFactory>()
        val ctx = mk(nodeReferenceFactory = mockNodeReferenceFactory)

        val globalId = GlobalIDImpl(User.Reflection, "")

        val err = RuntimeException()
        every { mockNodeReferenceFactory.nodeFor<NodeObject>(any(), any()) } throws err

        assertSame(
            err,
            assertThrows<RuntimeException> {
                ctx.nodeFor(globalId)
            }
        )
    }

    @Test
    fun `nodeFor - data from NodeReferenceFactory are propagated back to caller`() {
        val mockNodeReferenceFactory = mockk<NodeReferenceFactory>()
        val ctx = mk(nodeReferenceFactory = mockNodeReferenceFactory)

        val globalId = GlobalIDImpl(User.Reflection, "")

        val result = mockk<User>()
        every { mockNodeReferenceFactory.nodeFor<NodeObject>(any(), any()) } returns result

        assertSame(
            result,
            ctx.nodeFor(globalId)
        )
    }

    private class FooType : Type<Foo> {
        override val name: String = "Foo"
        override val kcls: KClass<out Foo> = Foo::class
    }

    private fun createMockInternalContext(globalIDCodec: GlobalIDCodec = MockGlobalIDCodec()): InternalContext = MockInternalContext(SelectTestFeatureAppTest.schema, globalIDCodec)

    @Suppress("KotlinConstantConditions")
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `nodeFor - data from NodeReferenceFactory are the same as the result`() {
        val fooType = FooType()
        val globalId = GlobalIDImpl(fooType, "123")

        val nodeEngineObjectData = object : NodeEngineObjectData {
            override val id: String
                get() = MockGlobalIDCodec().serialize(globalId)

            override suspend fun resolveData(
                selections: RawSelectionSet,
                context: EngineExecutionContext
            ) {}

            override suspend fun fetch(selection: String): Any {
                return ""
            }

            override val graphQLObjectType: GraphQLObjectType
                get() = SelectTestFeatureAppTest.schema.schema.getObjectType(fooType.name)
        }

        val nodeEngineObjectDataFactory: (String, GraphQLObjectType) -> NodeEngineObjectData = { _, _ ->
            nodeEngineObjectData
        }

        val factory = NodeReferenceFactoryImpl(nodeEngineObjectDataFactory)
        val internalContext = createMockInternalContext()

        val resultFromFactory = factory.nodeFor(globalId, internalContext)

        assertNotNull(resultFromFactory, "nodeFor should return a non-null result for valid NodeObject type")
        assertEquals(Foo::class, resultFromFactory::class, "Result should be an instance of User")

        val ctx = mk(nodeReferenceFactory = factory)

        runBlocking {
            val idFromFactory = (resultFromFactory.getId() as MockGlobalID).internalID
            val idFromFieldExecutionContext = (ctx.nodeFor(globalId).getId() as MockGlobalID).internalID
            assertEquals(idFromFactory, idFromFieldExecutionContext)
        }
    }
}
