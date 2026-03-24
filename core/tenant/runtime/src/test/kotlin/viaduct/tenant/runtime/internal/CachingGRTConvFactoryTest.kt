@file:OptIn(InternalApi::class)
@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.internal

import graphql.Scalars.GraphQLString
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.api.internal.GRTConvFactory
import viaduct.api.internal.InternalContext
import viaduct.api.internal.KeyMapping
import viaduct.api.mocks.MockInternalContext
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineSelectionSet
import viaduct.engine.api.select.SelectionsParser
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.IR

class CachingGRTConvFactoryTest {
    private val schema = MockSchema.mk("extend type Query { o1: O1 }\ntype O1 { name: String }")
    private val ctx = MockInternalContext(schema)

    private fun mkConv() = Conv<Any?, IR.Value>({ IR.Value.Null }, { null })

    private fun mkUnderlying(): GRTConvFactory =
        object : GRTConvFactory {
            override fun create(
                internalCtx: InternalContext,
                type: GraphQLType,
                selectionSet: EngineSelectionSet?,
                keyMapping: KeyMapping?
            ): Conv<Any?, IR.Value> = mkConv()

            override fun createForInputField(
                internalCtx: InternalContext,
                field: GraphQLInputObjectField
            ): Conv<Any?, IR.Value> = mkConv()
        }

    private fun namedType(name: String): GraphQLObjectType = GraphQLObjectType.newObject().name(name).build()

    private fun inputField(name: String): GraphQLInputObjectField =
        GraphQLInputObjectField.newInputObjectField()
            .name(name)
            .type(GraphQLString)
            .build()

    @Test
    fun `create without selection set is cached by type name`() {
        val factory = CachingGRTConvFactory(mkUnderlying())
        val type = namedType("O1")
        val conv1 = factory.create(ctx, type, selectionSet = null)
        val conv2 = factory.create(ctx, type, selectionSet = null)

        assertSame(conv1, conv2)
    }

    @Test
    fun `create with selection set is not cached`() {
        val factory = CachingGRTConvFactory(mkUnderlying())
        val type = namedType("O1")

        val ss = createEngineSelectionSet(SelectionsParser.parse("O1", "name"), schema, emptyMap())
        val conv1 = factory.create(ctx, type, selectionSet = ss)
        val conv2 = factory.create(ctx, type, selectionSet = ss)
        assertNotSame(conv1, conv2)
    }

    @Test
    fun `separate factory instances have independent caches`() {
        val type = namedType("O1")
        val underlying = mkUnderlying()
        val conv1 = CachingGRTConvFactory(underlying).create(ctx, type, null)
        val conv2 = CachingGRTConvFactory(underlying).create(ctx, type, null)
        assertNotSame(conv1, conv2)
    }

    @Test
    fun `input field conv is cached`() {
        val factory = CachingGRTConvFactory(mkUnderlying())
        val field = inputField("x")
        assertSame(factory.createForInputField(ctx, field), factory.createForInputField(ctx, field))
    }

    @Test
    fun `different input fields are cached independently`() {
        val conv1 = CachingGRTConvFactory(mkUnderlying()).createForInputField(ctx, inputField("x"))
        val conv2 = CachingGRTConvFactory(mkUnderlying()).createForInputField(ctx, inputField("y"))
        assertNotSame(conv1, conv2)
    }

    @Test
    fun `different keyMappings produce separate cache entries`() {
        val factory = CachingGRTConvFactory(mkUnderlying())
        val type = namedType("O1")
        val conv1 = factory.create(ctx, type, selectionSet = null, keyMapping = KeyMapping.FieldNameToFieldName)
        val conv2 = factory.create(ctx, type, selectionSet = null, keyMapping = KeyMapping.FieldNameToSelection)
        assertNotSame(conv1, conv2)
    }

    @Test
    fun `concurrent access does not throw`(): Unit =
        runBlocking {
            val factory = CachingGRTConvFactory(mkUnderlying())
            val type = namedType("O1")

            val deferreds = List(100) {
                async {
                    factory.create(ctx, type, selectionSet = null)
                }
            }

            val convs = deferreds.awaitAll()
            assertEquals(1, convs.distinct().size)
        }
}
