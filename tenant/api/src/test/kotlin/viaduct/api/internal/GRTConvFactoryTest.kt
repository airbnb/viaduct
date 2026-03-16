@file:OptIn(InternalApi::class)

package viaduct.api.internal

import graphql.schema.GraphQLInputObjectType
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.api.mocks.MockInternalContext
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.mocks.MockSchema
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.IR

class GRTConvFactoryTest {
    private val nullConv = Conv<Any?, IR.Value>({ IR.Value.Null }, { null })
    private val schema = MockSchema.mk(
        """
            extend type Query { x:Int }
            input Input { x:Int }
        """.trimIndent()
    )
    private val ctx = MockInternalContext(schema)

    @Test
    fun `const factory returns the same conv for create`() {
        val factory = GRTConvFactory.const(nullConv)
        assertSame(nullConv, factory.create(ctx, schema.schema.queryType))
    }

    @Test
    fun `const factory returns the same conv for createForInputField`() {
        val factory = GRTConvFactory.const(nullConv)
        val field = schema.schema
            .getTypeAs<GraphQLInputObjectType>("Input")
            .getField("x")
        assertSame(nullConv, factory.createForInputField(ctx, field))
    }
}
