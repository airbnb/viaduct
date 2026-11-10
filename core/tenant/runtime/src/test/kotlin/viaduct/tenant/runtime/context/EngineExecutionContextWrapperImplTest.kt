@file:OptIn(ExperimentalApi::class)

package viaduct.tenant.runtime.context

import graphql.language.FragmentDefinition
import graphql.parser.Parser
import graphql.schema.GraphQLObjectType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.mocks.MockType
import viaduct.api.reflect.RootObjectField
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.Mutation
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.tenant.runtime.FakeMutation
import viaduct.tenant.runtime.FakeObject
import viaduct.tenant.runtime.FakeQuery
import viaduct.tenant.runtime.select.SelectionSetImpl

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

    private val mutationType = object : Type<Mutation> {
        override val name: String = "Mutation"
        override val kcls = Mutation::class
    }

    @Suppress("UNCHECKED_CAST")
    private val fieldNoArgs = object : RootObjectField<Query, Object, Arguments.NoArguments> {
        override val pathFromQueryRoot: List<String> = listOf("foo")
        override val type: Type<Object> = fooType
        override val name: String = "foo"
        override val containingType: Type<Query> = queryType
    }

    private fun mockSchema(): EngineSchema =
        mockk {
            every { schema.getObjectType("Foo") } returns graphqlObjectType
        }

    private fun mockCtx(schema: EngineSchema = mockSchema()): InternalContext =
        mockk(relaxed = true) {
            every { this@mockk.schema } returns schema
        }

    private fun mockRootFieldRef(): MockRootFieldRef =
        mockk(relaxed = true) {
            every { type } returns graphqlObjectType
        }

    @Test
    fun `rejects @defer in named fragments referenced by a query`() {
        val fragments = Parser.parse(
            "fragment Outer on Query { ...Inner } fragment Inner on Query { ... on Query @defer { __typename } }"
        ).getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
        val wrapper = EngineExecutionContextWrapperImpl(ContextMocks().engineExecutionContext, fragments)

        val error = assertThrows<TenantUsageException> {
            wrapper.selectionsForOperation(queryType, "query GetName { ...Outer }", emptyMap())
        }

        assertTrue(error.message!!.contains("@defer is not allowed in ctx.query or ctx.mutation operations."), error.message)
        assertTrue(error.message!!.contains("Found: @defer in fragment 'Inner' at 1:"), error.message)
    }

    @Test
    fun `rejects @defer in query selections even when the selection is skipped`() {
        val wrapper = EngineExecutionContextWrapperImpl(ContextMocks().engineExecutionContext)

        assertThrows<TenantUsageException> {
            wrapper.selectionsForOperation(
                queryType,
                "query GetName { ... on Query @skip(if: true) @defer { __typename } }",
                emptyMap()
            )
        }
    }

    @Test
    fun `ignores @defer in fragments not referenced by the query`() {
        val wrapper = EngineExecutionContextWrapperImpl(ContextMocks().engineExecutionContext)

        val selections = wrapper.selectionsForOperation(
            queryType,
            """
            query GetName { ...Visible }
            fragment Visible on Query { __typename }
            fragment Unused on Query { ... on Query @defer { __typename } }
            """.trimIndent(),
            emptyMap()
        ) as SelectionSetImpl<*>

        assertEquals("__typename", selections.engineSelectionSet.selections().single().fieldName)
    }

    @Test
    fun `rejects @defer in mutation selections`() {
        val schema = MockSchema.mk("extend type Query { empty: Int } extend type Mutation { createUser: User } type User { id: ID }")
        val wrapper = EngineExecutionContextWrapperImpl(ContextMocks(schema).engineExecutionContext)

        val error = assertThrows<TenantUsageException> {
            wrapper.selectionsForOperation(
                mutationType,
                "mutation CreateUser { createUser { ... on User @defer { id } } }",
                emptyMap()
            )
        }

        assertTrue(error.message!!.contains("@defer is not allowed in ctx.query or ctx.mutation operations."), error.message)
    }

    @Test
    fun `rejects @defer in named fragments referenced by a mutation`() {
        val schema = MockSchema.mk("extend type Query { empty: Int } extend type Mutation { createUser: User } type User { id: ID }")
        val fragments = Parser.parse(
            "fragment Outer on User { ...Inner } fragment Inner on User { ... on User @defer { id } }"
        ).getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
        val wrapper = EngineExecutionContextWrapperImpl(ContextMocks(schema).engineExecutionContext, fragments)

        val error = assertThrows<TenantUsageException> {
            wrapper.selectionsForOperation(mutationType, "mutation CreateUser { createUser { ...Outer } }", emptyMap())
        }

        assertTrue(error.message!!.contains("@defer is not allowed in ctx.query or ctx.mutation operations."), error.message)
        assertTrue(error.message!!.contains("Found: @defer in fragment 'Inner' at 1:"), error.message)
    }

    @Test
    fun `ignores @defer in fragments not referenced by the mutation`() {
        val schema = MockSchema.mk("extend type Query { empty: Int } extend type Mutation { createUser: User } type User { id: ID }")
        val wrapper = EngineExecutionContextWrapperImpl(ContextMocks(schema).engineExecutionContext)

        val selections = wrapper.selectionsForOperation(
            mutationType,
            """
            mutation CreateUser { createUser { id } }
            fragment Unused on User { ... on User @defer { id } }
            """.trimIndent(),
            emptyMap()
        ) as SelectionSetImpl<*>

        assertEquals("createUser", selections.engineSelectionSet.selections().single().fieldName)
    }

    @Test
    fun `query materializes the GRT from internal selection type metadata`(): Unit =
        runTest {
            val ctx = mockCtx()
            val engineSelections = mockk<EngineSelectionSet>()
            val data = mockk<EngineObjectData.Sync>()
            val eec = mockk<EngineExecutionContext> {
                coEvery { resolveSelectionSet(engineSelections, ResolveSelectionSetOptions.DEFAULT) } returns data
            }
            val selections = SelectionSetImpl(FakeQuery.Reflection, engineSelections)

            val result = EngineExecutionContextWrapperImpl(eec).query(ctx, selections)

            val query = assertInstanceOf(FakeQuery::class.java, result)
            assertSame(ctx, query.ctx)
            assertSame(data, query.data)
            assertFalse(query.__isRequiredSelectionInput)
        }

    @Test
    fun `query waits for engine selection materialization before returning its GRT`(): Unit =
        runTest {
            val ctx = mockCtx()
            val engineSelections = mockk<EngineSelectionSet>()
            val materializedData = CompletableDeferred<EngineObjectData.Sync>()
            val eec = mockk<EngineExecutionContext> {
                coEvery { resolveSelectionSet(engineSelections, ResolveSelectionSetOptions.DEFAULT) } coAnswers {
                    materializedData.await()
                }
            }
            val selections = SelectionSetImpl(FakeQuery.Reflection, engineSelections)

            val result = async(start = CoroutineStart.UNDISPATCHED) {
                EngineExecutionContextWrapperImpl(eec).query(ctx, selections)
            }

            assertFalse(result.isCompleted)
            val data = mockk<EngineObjectData.Sync>()
            materializedData.complete(data)
            assertSame(data, assertInstanceOf(FakeQuery::class.java, result.await()).data)
        }

    @Test
    fun `mutation materializes the GRT from internal selection type metadata`(): Unit =
        runTest {
            val ctx = mockCtx()
            val engineSelections = mockk<EngineSelectionSet>()
            val data = mockk<EngineObjectData.Sync>()
            val eec = mockk<EngineExecutionContext> {
                coEvery { resolveSelectionSet(engineSelections, ResolveSelectionSetOptions.MUTATION) } returns data
            }
            val selections = SelectionSetImpl(FakeMutation.Reflection, engineSelections)

            val result = EngineExecutionContextWrapperImpl(eec).mutation(ctx, selections)

            val mutation = assertInstanceOf(FakeMutation::class.java, result)
            assertSame(ctx, mutation.ctx)
            assertSame(data, mutation.data)
            assertFalse(mutation.__isRequiredSelectionInput)
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
