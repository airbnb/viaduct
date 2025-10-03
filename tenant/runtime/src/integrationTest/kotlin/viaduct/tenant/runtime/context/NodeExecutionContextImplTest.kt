@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.context

import graphql.schema.GraphQLObjectType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.NodeReferenceFactory
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.mocks.MockGlobalIDCodec
import viaduct.api.mocks.MockInternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Query as QueryType
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.mocks.mkRawSelectionSetFactory
import viaduct.engine.api.mocks.variables
import viaduct.tenant.runtime.globalid.GlobalIDImpl
import viaduct.tenant.runtime.globalid.GlobalIdFeatureAppTest
import viaduct.tenant.runtime.globalid.Query
import viaduct.tenant.runtime.globalid.User
import viaduct.tenant.runtime.internal.NodeReferenceFactoryImpl
import viaduct.tenant.runtime.select.SelectionSetFactoryImpl
import viaduct.tenant.runtime.select.SelectionSetImpl

class NodeExecutionContextImplTest {
    private val userId = GlobalIDImpl(User.Reflection, "123")
    private val queryObject = mockk<Query>()
    private val rawSelectionSetFactory = mkRawSelectionSetFactory(GlobalIdFeatureAppTest.schema)

    private val engineExecutionContext = mockk<EngineExecutionContext> {
        every { fullSchema } returns GlobalIdFeatureAppTest.schema
        every { createNodeEngineObjectData(any(), any()) } returns mockk {
            every { graphQLObjectType } returns mockk()
        }
    }

    private fun mk(
        userId: GlobalID<User> = this.userId,
        selectionSet: SelectionSet<User> = mockk<SelectionSet<User>>(),
        queryLoader: SelectionsLoader<QueryType> = SelectionsLoader.const(queryObject),
        selectionSetFactory: SelectionSetFactory = SelectionSetFactoryImpl(rawSelectionSetFactory),
        nodeReferenceFactory: NodeReferenceFactory = NodeReferenceFactoryImpl {
                id: String,
                graphQLObjectType: GraphQLObjectType,
            ->
            engineExecutionContext.createNodeEngineObjectData(id, graphQLObjectType)
        }
    ) = NodeExecutionContextImpl(
        ResolverExecutionContextImpl(
            MockInternalContext(GlobalIdFeatureAppTest.schema, MockGlobalIDCodec()),
            queryLoader,
            selectionSetFactory,
            nodeReferenceFactory
        ),
        userId,
        selectionSet
    )

    @Test
    fun properties() {
        val ctx = mk()
        assertEquals(userId, ctx.id)
    }

    @Test
    fun selectionsFor() {
        val ctx = mk()
        val ss = ctx.selectionsFor(Query.Reflection, "__typename", mapOf("var" to true))
        assertTrue(ss.contains(Query.Reflection.Fields.__typename))
        val inner = (ss as SelectionSetImpl).rawSelectionSet
        assertEquals(mapOf("var" to true), inner.variables())
    }

    @Test
    fun query(): Unit =
        runBlocking {
            val ctx = mk()
            ctx.selectionsFor(Query.Reflection, "__typename").also {
                assertTrue(it.contains(Query.Reflection.Fields.__typename))

                ctx.query(it).also { result ->
                    assertEquals(queryObject, result)
                }
            }
        }

    @Test
    fun nodeFor() {
        val ctx = mk()
        ctx.nodeFor(userId)
    }
}
