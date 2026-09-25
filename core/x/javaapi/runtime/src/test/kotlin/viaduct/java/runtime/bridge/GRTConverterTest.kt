package viaduct.java.runtime.bridge

import graphql.Scalars
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ObjectBase
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

    private class CopyableObject : ObjectBase {
        constructor(data: EngineObjectData.Sync) : super(null, data)

        constructor(data: Map<String, Any?>) : super(null, data)

        private constructor(base: ObjectBase, data: Map<String, Any?>) : super(null, base, data)

        fun copy(data: Map<String, Any?>) = CopyableObject(toBuilderBase(), data)
    }

    @Test
    fun `copy conversion preserves lazy engine reads and null overrides`() {
        val type = GraphQLObjectType.newObject().name("OriginalType").build()
        val originalData = ResolvedEngineObjectData(type, mapOf("name" to "Alice", "overridden" to "old"))
        val failure = IllegalStateException("unavailable selection")
        val lazyData = object : EngineObjectData.Sync by originalData {
            override fun getOrNull(selection: String): Any? {
                if (selection == "unavailable") throw failure
                return originalData.getOrNull(selection)
            }

            override fun getSelections(): Iterable<String> = error("Copy must not enumerate the base")
        }
        val original = CopyableObject(lazyData)
        val copy = original.copy(mapOf("overridden" to null)).copy(mapOf("added" to "new"))

        val result = convertResult(copy, null) as EngineObjectData.Sync

        assertSame(type, result.type)
        assertEquals("Alice", result.getOrNull("name"))
        assertNull(result.getOrNull("overridden"))
        assertTrue(result.isPresent("overridden"))
        assertEquals("new", result.getOrNull("added"))
        assertFalse(result.isPresent("missing"))
        assertSame(failure, assertThrows<IllegalStateException> { result.getOrNull("unavailable") })
        assertEquals("old", originalData.getOrNull("overridden"))
    }

    @Test
    fun `copy conversion includes base selections and recursively converts overrides`() {
        val type = GraphQLObjectType.newObject().name("CopyableObject")
            .field(GraphQLFieldDefinition.newFieldDefinition().name("name").type(Scalars.GraphQLString))
            .build()
        val schema = GraphQLSchema.newSchema().query(type).build()
        val nested = CopyableObject(mapOf("name" to "nested")).copy(mapOf("name" to "changed"))
        val reference = mockk<RootFieldReference>()
        val original = CopyableObject(mapOf("name" to "Alice", "nullable" to null))
        val copy = original.copy(mapOf("items" to listOf(nested, RootReferenceObject(reference), null)))

        val result = convertResult(copy, schema) as EngineObjectData.Sync
        val items = result.get("items") as List<*>

        assertEquals(setOf("name", "nullable", "items"), result.getSelections().toSet())
        assertTrue(result.isPresent("nullable"))
        assertNull(result.get("nullable"))
        assertFalse(result.isPresent("missing"))
        assertEquals("Alice", result.get("name"))
        assertEquals("changed", (items[0] as EngineObjectData.Sync).get("name"))
        assertSame(reference, items[1])
        assertNull(items[2])
    }

    @Test
    fun `buildInternalContext creates InternalContextImpl from engine context`() {
        val schema = mockk<EngineSchema>()
        val codec = mockk<GlobalIDCodec>()
        val engineCtx = mockk<EngineExecutionContext> {
            every { fullSchema } returns schema
            every { globalIDCodec } returns codec
        }

        val result: InternalContext = buildInternalContext(engineCtx)

        assertSame(schema, result.schema)
        assertSame(codec, result.globalIDCodec)
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
        val viaductSchema = mockk<EngineSchema> {
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
}

private abstract class TestArguments : Arguments
