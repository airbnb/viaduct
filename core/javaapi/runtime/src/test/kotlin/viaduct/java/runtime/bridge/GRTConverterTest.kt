package viaduct.java.runtime.bridge

import graphql.Scalars
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
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
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.FullSchema
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.errors.TenantUsageException
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.GraphQLObject
import viaduct.java.api.types.NodeCompositeOutput
import viaduct.java.api.types.Query
import viaduct.service.api.spi.GlobalIDCodec

class GRTConverterTest {
    private val codec = mockk<GlobalIDCodec>()

    class QueryResult(context: InternalContext?, data: EngineObjectData.Sync, isRequiredSelectionInput: Boolean = false) :
        ObjectBase(context, data, isRequiredSelectionInput), Query {
        fun value(): ObjectBase? = fetchObject("value", null, CopyableObject::class.java) { context, data, isRequiredSelectionInput -> CopyableObject(data, context, isRequiredSelectionInput) }
    }

    @Test
    fun `composite descendants of explicit Java queries can be returned`() =
        runTest {
            val schema = MockSchema.mk(
                """
            extend type Query { value: CopyableObject }
            type CopyableObject { name: String }
                """.trimIndent()
            )
            val data = ResolvedEngineObjectData(schema.schema.getObjectType("CopyableObject"), mapOf("name" to "value"))
            val queryData = ResolvedEngineObjectData(schema.schema.queryType, mapOf("value" to data))
            val engineContext = object : EngineExecutionContext by ContextMocks(myFullSchema = schema).engineExecutionContext {
                override suspend fun resolveSelectionSet(
                    selectionSet: EngineSelectionSet,
                    options: ResolveSelectionSetOptions,
                ): EngineObjectData.Sync = queryData
            }
            val ctx = SimpleFieldExecutionContext(
                requestContext = null,
                engineExecutionContext = engineContext,
                coroutineScope = this,
            )

            val result = ctx.query("value { name }", emptyMap(), QueryResult::class.java).await()

            assertSame(data, convertResult(result.value(), schema.schema, codec))
        }

    private class RootReferenceObject(ref: RootFieldReference) :
        ObjectBase(null, ref),
        GraphQLObject

    @Test
    fun `convertResult passes root field references directly to the engine`() {
        val reference = mockk<RootFieldReference>()

        val result = convertResult(RootReferenceObject(reference), null, codec)

        assertSame(reference, result)
    }

    private class CopyableObject : ObjectBase {
        constructor(data: EngineObjectData.Sync, context: InternalContext? = null, isRequiredSelectionInput: Boolean = false) : super(context, data, isRequiredSelectionInput)

        constructor(data: Map<String, Any?>) : super(null, data)

        private constructor(base: ObjectBase, data: Map<String, Any?>) : super(null, base, data)

        fun copy(data: Map<String, Any?>) = CopyableObject(toBuilderBase(), data)

        fun child(): CopyableObject? = fetchObject("child", null, CopyableObject::class.java) { context, data, isRequiredSelectionInput -> CopyableObject(data, context, isRequiredSelectionInput) }
    }

    @Test
    fun `required selection objects cannot escape through returns nested maps or copies`() {
        val schema = MockSchema.mk(
            """
            extend type Query { value: CopyableObject }
            type CopyableObject { name: String, child: CopyableObject, items: [CopyableObject] }
            """.trimIndent()
        )
        val type = schema.schema.getObjectType("CopyableObject")
        val childData = ResolvedEngineObjectData(type, mapOf("name" to "child"))
        val data = ResolvedEngineObjectData(type, mapOf("name" to "original", "child" to childData))
        val context = InternalContextImpl(schema, codec)
        val input = CopyableObject(data, context, isRequiredSelectionInput = true)
        val expectedMessage = "Cannot use required-selection GRT 'CopyableObject' as output. " +
            "Build a new GRT or use ctx.query() with the output selections."

        val direct = assertThrows<TenantUsageException> { convertResult(input, schema.schema, codec) }
        assertEquals(expectedMessage, direct.message)
        val list = assertThrows<TenantUsageException> { convertResult(listOf(null, listOf(input)), schema.schema, codec) }
        assertEquals(expectedMessage, list.message)
        val nested = CopyableObject(mapOf("items" to listOf(null, input)))
        val error = assertThrows<TenantUsageException> { convertResult(nested, schema.schema, codec) }
        assertEquals(expectedMessage, error.message)
        assertThrows<TenantUsageException> { convertResult(input.child(), schema.schema, codec) }
        val copy = input.copy(mapOf("name" to "changed")).copy(emptyMap())
        assertThrows<TenantUsageException> { convertResult(copy, schema.schema, codec) }
        assertThrows<TenantUsageException> { convertResult(copy.child(), schema.schema, codec) }

        assertSame(data, convertResult(CopyableObject(data, context), schema.schema, codec))
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

        val result = convertResult(copy, null, codec) as EngineObjectData.Sync

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

        val result = convertResult(copy, schema, codec) as EngineObjectData.Sync
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
    fun `convertResult serializes typed IDs with the execution codec and preserves plain IDs`() {
        every { codec.serialize("User", "123") } returns "custom:user:123"
        every { codec.serialize("User", "456") } returns "custom:user:456"
        val first = GlobalIDImpl(typeFromName<NodeCompositeOutput>("User"), "123")
        val second = GlobalIDImpl(typeFromName<NodeCompositeOutput>("User"), "456")

        val result = convertResult(
            listOf(first, null, listOf(second, "plain-id")),
            null,
            codec,
        )

        assertEquals(listOf("custom:user:123", null, listOf("custom:user:456", "plain-id")), result)
        assertEquals("plain-id", convertResult("plain-id", null, codec))
        assertNull(convertResult(null, null, codec))
    }

    @Test
    fun `buildInternalContext creates InternalContextImpl from engine context`() {
        val schema = mockk<FullSchema>()
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
