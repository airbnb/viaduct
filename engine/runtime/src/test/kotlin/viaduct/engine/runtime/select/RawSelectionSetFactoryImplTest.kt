package viaduct.engine.runtime.select

import graphql.execution.ExecutionStepInfo
import graphql.execution.MergedField
import graphql.language.Field
import graphql.language.SelectionSet
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLList
import graphql.schema.GraphQLOutputType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductDataFetchingEnvironment
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.select.loader.SelectTestSchemaFixture

class RawSelectionSetFactoryImplTest {
    private val factory = RawSelectionSetFactoryImpl(
        ViaductSchema(SelectTestSchemaFixture.schema)
    )

    @Test
    fun `empty`() {
        val ss = factory.rawSelectionSet(
            "Query",
            "__typename @skip(if:true)",
            emptyMap()
        )
        assertTrue(ss.isEmpty())
    }

    @Test
    fun `throws on empty selections string`() {
        assertThrows<IllegalArgumentException> {
            factory.rawSelectionSet("Query", "", emptyMap())
        }
    }

    @Test
    fun `throws on unknown type`() {
        assertThrows<IllegalArgumentException> {
            factory.rawSelectionSet("UnknownType", "__typename", emptyMap())
        }
    }

    @Test
    fun `throws on unparseasble selections`() {
        assertThrows<IllegalArgumentException> {
            factory.rawSelectionSet("Query", "{", emptyMap())
        }
    }

    @Test
    fun `create from String`() {
        val ss = factory.rawSelectionSet(
            "Foo",
            "id fooSelf { fooId }",
            emptyMap()
        )
        assertEquals("Foo", ss.type)
        assertTrue(ss.containsField("Foo", "id"))
        assertTrue(ss.containsField("Foo", "fooSelf"))
        assertFalse(ss.containsField("Foo", "__typename"))
        assertTrue(ss.selectionSetForField("Foo", "fooSelf").containsField("Foo", "fooId"))
    }

    @Test
    fun `create from DataFetchingEnvironment`() {
        for (listDepth in 0..2) {
            var type: GraphQLOutputType = SelectTestSchemaFixture.schema.getObjectType("Foo")
            repeat(listDepth) {
                type = GraphQLList.list(type)
            }
            val fieldExecutionScope = mockk<EngineExecutionContext.FieldExecutionScope>()
            every { fieldExecutionScope.fragments }.returns(emptyMap())
            every { fieldExecutionScope.variables }.returns(emptyMap())
            val engineExecutionContext = mockk<EngineExecutionContext>()
            every { engineExecutionContext.fieldScope }.returns(fieldExecutionScope)
            val env = mockk<ViaductDataFetchingEnvironment>()
            val envField = Field(
                "field",
                SelectionSet(
                    listOf(
                        Field("id"),
                        Field(
                            "fooSelf",
                            SelectionSet(
                                listOf(
                                    Field("fooId")
                                )
                            )
                        )
                    )
                )
            )
            every { env.mergedField }.returns(MergedField.newMergedField(envField).build())
            every { env.engineExecutionContext }.returns(engineExecutionContext)
            every { env.executionStepInfo }.returns(
                ExecutionStepInfo.newExecutionStepInfo()
                    .type(type)
                    .build()
            )
            val ss = factory.rawSelectionSet(env)
            assertNotNull(ss)
            ss!!

            assertEquals("Foo", ss.type)
            assertTrue(ss.containsField("Foo", "id"))
            assertTrue(ss.containsField("Foo", "fooSelf"))
            assertFalse(ss.containsField("Foo", "__typename"))
            assertTrue(ss.selectionSetForField("Foo", "fooSelf").containsField("Foo", "fooId"))
        }
    }

    @Test
    fun `create from DataFetchingEnvironment -- not composite`() {
        val env = mockk<DataFetchingEnvironment>()
        every { env.executionStepInfo }.returns(
            ExecutionStepInfo.newExecutionStepInfo()
                .type(
                    SelectTestSchemaFixture.schema.getTypeAs("ID")
                )
                .build()
        )
        assertNull(factory.rawSelectionSet(env))
    }

    @Test
    fun `create from ParsedSelections`() {
        val ss = factory.rawSelectionSet(
            SelectionsParser.parse("Query", "__typename"),
            emptyMap()
        )
        assertEquals("Query", ss.type)
        assertTrue(ss.containsField("Query", "__typename"))
    }

    @Test
    fun `create from ParsedSelections -- with variables`() {
        val ss = factory.rawSelectionSet(
            SelectionsParser.parse("Query", "__typename @skip(if:${'$'}skipIf)"),
            mapOf("skipIf" to true)
        )
        assertEquals("Query", ss.type)
        assertFalse(ss.containsField("Query", "__typename"))
    }

    @Test
    fun `create from ParsedSelections -- not in schema`() {
        val parsed = SelectionsParser.parse("Other", "__typename")
        assertThrows<IllegalArgumentException> {
            factory.rawSelectionSet(parsed, emptyMap())
        }
    }
}
