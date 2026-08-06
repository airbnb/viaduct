package viaduct.java.runtime.bridge

import graphql.Scalars
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.ViaductSchema
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.GraphQLObject
import viaduct.service.api.spi.GlobalIDCodec

class GRTConverterTest {
    private class RootReferenceObject(ref: RootFieldReference) :
        ObjectBase(null, ref),
        GraphQLObject

    @Test
    fun `convertResult passes root field references directly to the engine`() {
        val reference = mockk<RootFieldReference>()

        val result = convertResult(RootReferenceObject(reference), null)

        assertSame(reference, result)
    }

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

        assertSame(schema, result.schema)
        assertSame(codec, result.globalIDCodec)
        assertSame(classFinder, result.classFinder)
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

        assertEquals("TestArguments", result.name)
        assertEquals(1, result.fields.size)
        assertEquals("id", result.fields[0].name)
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

        assertEquals("Query_greeting_Arguments", result.name)
        assertEquals(1, result.fields.size)
        assertEquals("name", result.fields[0].name)
    }

    @Test
    fun `buildArgumentsInputType from class name throws for invalid name format`() {
        val context = mockk<InternalContext>()

        assertThrows<IllegalArgumentException> {
            buildArgumentsInputType(NoUnderscoreArguments::class.java, context)
        }
    }
}

private abstract class TestArguments : Arguments

private abstract class Query_greeting_Arguments : Arguments

@Suppress("ClassName")
private abstract class NoUnderscoreArguments : Arguments
