package viaduct.tenant.runtime.select

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.createEngineSelectionSetFactory
import viaduct.engine.api.mocks.variables
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.executioncontext.ExecutionContextTestSchema
import viaduct.tenant.runtime.executioncontext.Foo
import viaduct.tenant.runtime.executioncontext.Query

@ExperimentalCoroutinesApi
class SelectionSetFactoryImplTest : Assertions() {
    @Test
    fun `selectionsOn -- simple`() {
        val emptyEngineSelectionSet = mockk<viaduct.engine.api.EngineSelectionSet> {
            every { isTransitivelyEmpty() } returns true
        }
        val factory = SelectionSetFactoryImpl(
            mockk {
                every {
                    engineSelectionSet(any(), any(), any())
                } returns emptyEngineSelectionSet
            },
            GlobalIDCodecDefault,
        )

        val ss = factory.selectionsOn(Foo.Reflection, "id", emptyMap())
        assertSame(emptyEngineSelectionSet, (ss as SelectionSetImpl<*>).engineSelectionSet)
    }

    @Test
    fun `selectionsOn -- normalizes variables`() {
        val emptyEngineSelectionSet = mockk<viaduct.engine.api.EngineSelectionSet> {
            every { isTransitivelyEmpty() } returns true
        }
        val variables = slot<Map<String, Any?>>()
        val factory = SelectionSetFactoryImpl(
            mockk {
                every {
                    engineSelectionSet(any(), any(), capture(variables))
                } returns emptyEngineSelectionSet
            },
            GlobalIDCodecDefault,
        )

        factory.selectionsOn(Foo.Reflection, "id", mapOf("status" to TestStatus.ACTIVE))

        assertEquals(mapOf("status" to "ACTIVE"), variables.captured)
    }

    private fun mk() =
        SelectionSetFactoryImpl(
            createEngineSelectionSetFactory(ExecutionContextTestSchema.schema),
            GlobalIDCodecDefault,
        )

    @Test
    fun `selectionsOn -- no variables`() {
        val factory = mk()
        val ss = factory.selectionsOn(Query.Reflection, "__typename", emptyMap())
        assertTrue(ss.contains(Query.Fields.__typename))
        val inner = (ss as SelectionSetImpl).engineSelectionSet
        assertTrue(inner.variables().isEmpty())
    }

    @Test
    fun `selectionsOn -- variables`() {
        val factory = mk()
        val ss = factory.selectionsOn(Query.Reflection, "__typename", mapOf("var" to true))
        assertTrue(ss.contains(Query.Fields.__typename))
        val inner = (ss as SelectionSetImpl).engineSelectionSet
        assertEquals(mapOf("var" to true), inner.variables())
    }

    @Test
    fun `selectionsOn - multiple selection sets with one named Main`() {
        val factory = mk()
        val ss = factory.selectionsOn(
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

        assertTrue(ss.contains(Foo.Fields.id))
        assertTrue(ss.contains(Foo.Fields.fooSelf))
        assertTrue(ss.contains(Foo.Fields.fooId))

        val subSelections = ss.selectionSetFor(Foo.Fields.fooSelf)
        assertTrue(subSelections.contains(Foo.Fields.fooId))
    }

    @Test
    fun `selectionsOn - skipped fields preserve engine emptiness`() {
        val factory = mk()
        val selectionSet = factory.selectionsOn(
            Foo.Reflection,
            "__typename @skip(if:true)".trimIndent(),
            emptyMap()
        )
        val result = (selectionSet as SelectionSetImpl<*>).engineSelectionSet.isTransitivelyEmpty()
        assertTrue(result)
    }

    @Test
    fun `selectionsOn - conditional directives that don't depend on variable are evaluated eagerly`() {
        val factory = mk()

        val selectionsSkip = factory.selectionsOn(
            Foo.Reflection,
            "id fooSelf @skip(if: true) { fooId } fooId @include(if: false)",
            emptyMap()
        )

        assertTrue(selectionsSkip.contains(Foo.Fields.id))
        assertFalse(selectionsSkip.contains(Foo.Fields.fooSelf))
        assertFalse(selectionsSkip.contains(Foo.Fields.fooId))

        val selectionsInclude = factory.selectionsOn(
            Foo.Reflection,
            "id fooSelf @include(if: true) { fooId } fooId @skip(if: false)",
            emptyMap()
        )

        assertTrue(selectionsInclude.contains(Foo.Fields.id))
        assertTrue(selectionsInclude.contains(Foo.Fields.fooSelf))
        assertTrue(selectionsInclude.contains(Foo.Fields.fooId))
    }

    @Test
    fun `selectionsOn - conditional directives that depend on available variables can be evaluated`() {
        val factory = mk()

        val selectionsSkipTrue = factory.selectionsOn(
            Foo.Reflection,
            "id fooSelf @skip(if: \$skipIt) { fooId }",
            mapOf("skipIt" to true)
        )

        assertTrue(selectionsSkipTrue.contains(Foo.Fields.id))
        assertFalse(selectionsSkipTrue.contains(Foo.Fields.fooSelf))

        val selectionsSkipFalse = factory.selectionsOn(
            Foo.Reflection,
            "id fooSelf @skip(if: \$skipIt) { fooId }",
            mapOf("skipIt" to false)
        )

        assertTrue(selectionsSkipFalse.contains(Foo.Fields.id))
        assertTrue(selectionsSkipFalse.contains(Foo.Fields.fooSelf))

        val selectionsIncludeTrue = factory.selectionsOn(
            Foo.Reflection,
            "id fooSelf @include(if: \$includeIt) { fooId }",
            mapOf("includeIt" to true)
        )

        assertTrue(selectionsIncludeTrue.contains(Foo.Fields.id))
        assertTrue(selectionsIncludeTrue.contains(Foo.Fields.fooSelf))

        val selectionsIncludeFalse = factory.selectionsOn(
            Foo.Reflection,
            "id fooSelf @include(if: \$includeIt) { fooId }",
            mapOf("includeIt" to false)
        )

        assertTrue(selectionsIncludeFalse.contains(Foo.Fields.id))
        assertFalse(selectionsIncludeFalse.contains(Foo.Fields.fooSelf))
    }

    @Test
    fun `selectionsOn - variable with no value in variables map keeps selection`() {
        val factory = mk()
        val ss = factory.selectionsOn(
            Foo.Reflection,
            "id fooSelf @skip(if: \$undefinedVariable) { fooId }",
            emptyMap()
        )

        assertTrue(ss.contains(Foo.Fields.id))
        assertTrue(ss.contains(Foo.Fields.fooSelf))

        val inner = (ss as SelectionSetImpl).engineSelectionSet
        assertTrue(inner.variables().isEmpty())
    }

    private enum class TestStatus {
        ACTIVE,
    }
}
