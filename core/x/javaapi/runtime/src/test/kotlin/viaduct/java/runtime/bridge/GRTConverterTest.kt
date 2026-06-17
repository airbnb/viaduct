package viaduct.java.runtime.bridge

import graphql.Scalars
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductSchema
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.types.Arguments
import viaduct.service.api.spi.GlobalIDCodec

class GRTConverterTest {
    @Test
    fun `buildInternalContext creates InternalContextImpl from engine context`() {
        val schema = mockk<ViaductSchema>()
        val codec = mockk<GlobalIDCodec>()
        val classFinder = mockk<ResolverClassFinder>()
        val engineCtx = mockk<EngineExecutionContext> {
            every { fullSchema } returns schema
            every { globalIDCodec } returns codec
        }

        val result: InternalContext = buildInternalContext(engineCtx, classFinder)

        assertThat(result.schema).isSameAs(schema)
        assertThat(result.globalIDCodec).isSameAs(codec)
        assertThat(result.classFinder).isSameAs(classFinder)
    }

    @Test
    fun `buildArgumentsInputType with resolverId parses type and field names`() {
        val argument = GraphQLArgument.newArgument()
            .name("id")
            .type(Scalars.GraphQLString)
            .build()
        val field = GraphQLFieldDefinition.newFieldDefinition()
            .name("person")
            .type(Scalars.GraphQLString)
            .argument(argument)
            .build()
        val objectType = GraphQLObjectType.newObject()
            .name("Query")
            .field(field)
            .build()
        val graphqlSchema = GraphQLSchema.newSchema()
            .query(objectType)
            .build()
        val viaductSchema = mockk<ViaductSchema> {
            every { schema } returns graphqlSchema
        }
        val context = mockk<InternalContext> {
            every { getSchema() } returns viaductSchema
        }

        val result = buildArgumentsInputType(
            TestArguments::class.java,
            "Query.person",
            context
        )

        assertThat(result.name).isEqualTo("TestArguments")
        assertThat(result.fields).hasSize(1)
        assertThat(result.fields[0].name).isEqualTo("id")
    }

    @Test
    fun `buildArgumentsInputType from class name convention parses correctly`() {
        val argument = GraphQLArgument.newArgument()
            .name("name")
            .type(Scalars.GraphQLString)
            .build()
        val field = GraphQLFieldDefinition.newFieldDefinition()
            .name("greeting")
            .type(Scalars.GraphQLString)
            .argument(argument)
            .build()
        val objectType = GraphQLObjectType.newObject()
            .name("Query")
            .field(field)
            .build()
        val graphqlSchema = GraphQLSchema.newSchema()
            .query(objectType)
            .build()
        val viaductSchema = mockk<ViaductSchema> {
            every { schema } returns graphqlSchema
        }
        val context = mockk<InternalContext> {
            every { getSchema() } returns viaductSchema
        }

        val result = buildArgumentsInputType(
            Query_greeting_Arguments::class.java,
            context
        )

        assertThat(result.name).isEqualTo("Query_greeting_Arguments")
        assertThat(result.fields).hasSize(1)
        assertThat(result.fields[0].name).isEqualTo("name")
    }

    @Test
    fun `buildArgumentsInputType from class name throws for invalid name format`() {
        val context = mockk<InternalContext>()

        assertThatThrownBy {
            buildArgumentsInputType(NoUnderscoreArguments::class.java, context)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

private abstract class TestArguments : Arguments

private abstract class Query_greeting_Arguments : Arguments

@Suppress("ClassName")
private abstract class NoUnderscoreArguments : Arguments
