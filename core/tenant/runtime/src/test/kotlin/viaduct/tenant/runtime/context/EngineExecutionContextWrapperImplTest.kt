@file:OptIn(ExperimentalApi::class)

package viaduct.tenant.runtime.context

import graphql.schema.GraphQLObjectType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.mocks.MockType
import viaduct.api.reflect.RootObjectField
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.ViaductSchema
import viaduct.errors.FrameworkException
import viaduct.tenant.runtime.FakeObject

class EngineExecutionContextWrapperImplTest {
    private val graphqlObjectType = GraphQLObjectType.newObject().name("Foo").build()

    private val fooType = object : Type<Object> {
        override val name: String = "Foo"
        override val kcls = FakeObject::class
    }

    private val queryType = object : Type<Query> {
        override val name: String = "Query"
        override val kcls = Query::class
    }

    @Suppress("UNCHECKED_CAST")
    private val fieldNoArgs = object : RootObjectField<Query, Object, Arguments.NoArguments> {
        override val pathFromQueryRoot: List<String> = listOf("foo")
        override val type: Type<Object> = fooType
        override val name: String = "foo"
        override val containingType: Type<Query> = queryType
    }

    private fun mockSchema(): ViaductSchema =
        mockk {
            every { schema.getObjectType("Foo") } returns graphqlObjectType
        }

    private fun mockCtx(schema: ViaductSchema = mockSchema()): InternalContext =
        mockk(relaxed = true) {
            every { this@mockk.schema } returns schema
        }

    private fun mockRootFieldRef(): MockRootFieldRef =
        mockk(relaxed = true) {
            every { type } returns graphqlObjectType
        }

    @Test
    fun `rootFieldRef with NoArguments calls createRootFieldReference with emptyMap`() {
        val ctx = mockCtx()
        val rootFieldRef = mockRootFieldRef()
        val eec = mockk<EngineExecutionContext> {
            every { createRootFieldReference(listOf("foo"), graphqlObjectType, emptyMap()) } returns rootFieldRef
        }

        val wrapper = EngineExecutionContextWrapperImpl(eec)
        val result = wrapper.rootFieldRef(ctx, fieldNoArgs, Arguments.NoArguments)

        assertNotNull(result)
        verify { eec.createRootFieldReference(listOf("foo"), graphqlObjectType, emptyMap()) }
    }

    @Test
    fun `rootFieldRef with InputLikeBase passes inputData through unchanged`() {
        val userType = MockType.mkNodeObject("User")
        val inputData = mapOf("key" to GlobalID(userType, "1234"))
        val args = mockk<TestInputArgs> {
            every { this@mockk.inputData } returns inputData
        }

        @Suppress("UNCHECKED_CAST")
        val fieldWithArgs = object : RootObjectField<Query, Object, TestInputArgs> {
            override val pathFromQueryRoot: List<String> = listOf("bar", "create")
            override val type: Type<Object> = fooType
            override val name: String = "create"
            override val containingType: Type<Query> = queryType
        }

        val ctx = mockCtx()
        val rootFieldRef = mockRootFieldRef()
        val eec = mockk<EngineExecutionContext> {
            every { createRootFieldReference(listOf("bar", "create"), graphqlObjectType, inputData) } returns rootFieldRef
        }

        val wrapper = EngineExecutionContextWrapperImpl(eec)
        val result = wrapper.rootFieldRef(ctx, fieldWithArgs, args)

        assertNotNull(result)
        verify { eec.createRootFieldReference(listOf("bar", "create"), graphqlObjectType, inputData) }
    }

    @Test
    fun `rootFieldRef with unsupported Arguments type throws FrameworkException`() {
        val unsupportedArgs = object : Arguments {}

        @Suppress("UNCHECKED_CAST")
        val fieldWithArgs = object : RootObjectField<Query, Object, Arguments> {
            override val pathFromQueryRoot: List<String> = listOf("baz")
            override val type: Type<Object> = fooType
            override val name: String = "baz"
            override val containingType: Type<Query> = queryType
        }

        val ctx = mockCtx()
        val eec = mockk<EngineExecutionContext>()

        val wrapper = EngineExecutionContextWrapperImpl(eec)

        assertThrows<FrameworkException> {
            wrapper.rootFieldRef(ctx, fieldWithArgs, unsupportedArgs)
        }
    }
}

interface MockRootFieldRef : RootFieldReference, EngineObjectData

abstract class TestInputArgs : InputLikeBase(), Arguments
