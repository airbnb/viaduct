@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.context

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.globalid.GlobalIDCodec
import viaduct.api.internal.NodeReferenceFactory
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.mocks.MockGlobalIDCodec
import viaduct.api.mocks.MockInternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.Object
import viaduct.api.types.Query as QueryType
import viaduct.engine.api.mocks.mkRawSelectionSetFactory
import viaduct.engine.api.mocks.variables
import viaduct.tenant.runtime.select.Foo
import viaduct.tenant.runtime.select.Query
import viaduct.tenant.runtime.select.SelectTestFeatureAppTest
import viaduct.tenant.runtime.select.SelectionSetFactoryImpl
import viaduct.tenant.runtime.select.SelectionSetImpl

class FieldExecutionContextImplTest {
    private object Obj : Object

    private object Q : QueryType

    private object Args : Arguments

    private val queryObject = mockk<Query>()

    private fun mk(
        obj: Object = Obj,
        query: QueryType = Q,
        args: Arguments = Args,
        globalIDCodec: GlobalIDCodec = MockGlobalIDCodec(),
        selectionSet: SelectionSet<*> = SelectionSet.NoSelections,
        queryLoader: SelectionsLoader<QueryType> = SelectionsLoader.const(queryObject),
        selectionSetFactory: SelectionSetFactory =
            SelectionSetFactoryImpl(mkRawSelectionSetFactory(SelectTestFeatureAppTest.schema)),
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
    fun properties(): Unit =
        runBlocking {
            val ctx = mk()
            assertEquals(Obj, ctx.objectValue)
            assertEquals(Q, ctx.queryValue)
            assertEquals(Args, ctx.arguments)
            assertEquals(SelectionSet.NoSelections, ctx.selections())
        }

    @Test
    fun `selectionsFor -- no variables`() {
        val ctx = mk()
        val ss = ctx.selectionsFor(Query.Reflection, "__typename")
        assertTrue(ss.contains(Query.Reflection.Fields.__typename))
        val inner = (ss as SelectionSetImpl).rawSelectionSet
        assertTrue(inner.variables().isEmpty())
    }

    @Test
    fun `selectionsFor -- variables`() {
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
    fun `selectionsFor - multiple selection sets with one named Main`() {
        val ctx = mk()
        val ss = ctx.selectionsFor(
            Foo.Reflection,
            """
                fragment Main on Foo {
                  id
                  fooSelf { fooId }
                  ...Other
                }
                fragment Other on Foo {
                  fooId
                }
            """.trimIndent(),
            emptyMap()
        )

        assertTrue(ss.contains(Foo.Reflection.Fields.id))
        assertTrue(ss.contains(Foo.Reflection.Fields.fooSelf))
        assertTrue(ss.contains(Foo.Reflection.Fields.fooId))

        val subSelections = ss.selectionSetFor(Foo.Reflection.Fields.fooSelf)
        assertTrue(subSelections.contains(Foo.Reflection.Fields.fooId))
    }

    @Test
    fun `selectionsFor - no selection set on provided type throws a null pointer exception when isEmpty is triggered`() {
        val ctx = mk()
        val selectionSet = ctx.selectionsFor(
            Foo.Reflection,
            "__typename @skip(if:true)".trimIndent(),
            emptyMap()
        )
        val result = selectionSet.isEmpty()
        assertTrue(result)
    }

    @Test
    fun `selectionsFor - conditional directives that don't depend on variable are evaluated eagerly`() {
        val ctx = mk()

        val selectionsSkip = ctx.selectionsFor(
            Foo.Reflection,
            "id fooSelf @skip(if: true) { fooId } fooId @include(if: false)",
            emptyMap()
        )

        assertTrue(selectionsSkip.contains(Foo.Reflection.Fields.id))
        assertFalse(selectionsSkip.contains(Foo.Reflection.Fields.fooSelf))
        assertFalse(selectionsSkip.contains(Foo.Reflection.Fields.fooId))

        val selectionsInclude = ctx.selectionsFor(
            Foo.Reflection,
            "id fooSelf @include(if: true) { fooId } fooId @skip(if: false)",
            emptyMap()
        )

        assertTrue(selectionsInclude.contains(Foo.Reflection.Fields.id))
        assertTrue(selectionsInclude.contains(Foo.Reflection.Fields.fooSelf))
        assertTrue(selectionsInclude.contains(Foo.Reflection.Fields.fooId))
    }

    @Test
    fun `selectionsFor - conditional directives that depend on available variables can be evaluated`() {
        val ctx = mk()

        val selectionsSkipTrue = ctx.selectionsFor(
            Foo.Reflection,
            "id fooSelf @skip(if: \$skipIt) { fooId }",
            mapOf("skipIt" to true)
        )

        assertTrue(selectionsSkipTrue.contains(Foo.Reflection.Fields.id))
        assertFalse(selectionsSkipTrue.contains(Foo.Reflection.Fields.fooSelf))

        val selectionsSkipFalse = ctx.selectionsFor(
            Foo.Reflection,
            "id fooSelf @skip(if: \$skipIt) { fooId }",
            mapOf("skipIt" to false)
        )

        assertTrue(selectionsSkipFalse.contains(Foo.Reflection.Fields.id))
        assertTrue(selectionsSkipFalse.contains(Foo.Reflection.Fields.fooSelf))

        val selectionsIncludeTrue = ctx.selectionsFor(
            Foo.Reflection,
            "id fooSelf @include(if: \$includeIt) { fooId }",
            mapOf("includeIt" to true)
        )

        assertTrue(selectionsIncludeTrue.contains(Foo.Reflection.Fields.id))
        assertTrue(selectionsIncludeTrue.contains(Foo.Reflection.Fields.fooSelf))

        val selectionsIncludeFalse = ctx.selectionsFor(
            Foo.Reflection,
            "id fooSelf @include(if: \$includeIt) { fooId }",
            mapOf("includeIt" to false)
        )

        assertTrue(selectionsIncludeFalse.contains(Foo.Reflection.Fields.id))
        assertFalse(selectionsIncludeFalse.contains(Foo.Reflection.Fields.fooSelf))
    }

    @Test
    fun `selectionsFor - variable with no value in variables map keeps selection`() {
        val ctx = mk()
        val ss = ctx.selectionsFor(
            Foo.Reflection,
            "id fooSelf @skip(if: \$undefinedVariable) { fooId }",
            emptyMap()
        )

        assertTrue(ss.contains(Foo.Reflection.Fields.id))
        assertTrue(ss.contains(Foo.Reflection.Fields.fooSelf))

        val inner = (ss as SelectionSetImpl).rawSelectionSet
        assertTrue(inner.variables().isEmpty())
    }
}
