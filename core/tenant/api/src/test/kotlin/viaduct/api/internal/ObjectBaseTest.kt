@file:Suppress("ForbiddenImport")

package viaduct.api.internal

import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.api.mocks.testGlobalId
import viaduct.api.testschema.ApiTestSchema
import viaduct.api.testschema.E1
import viaduct.api.testschema.I1
import viaduct.api.testschema.O1
import viaduct.api.testschema.O2
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RootFieldReference
import viaduct.errors.FrameworkException
import viaduct.errors.TenantException
import viaduct.errors.TenantUsageException
import viaduct.errors.UnsetFieldException

@OptIn(ExperimentalCoroutinesApi::class)
class ObjectBaseTest {
    private val gqlSchema = ApiTestSchema.schema
    private val internalContext = MockInternalContext.create(gqlSchema, "viaduct.api.testschema")
    private val executionContext = internalContext.executionContext

    // Use viaduct.api.testschema.E1 for the actual E1 value. This is for a regression test and represents a different version (classic)
    // of the GRT for the same GraphQL enum type.
    private enum class BadE1 { A }

    @Test
    fun `basic test with builder`(): Unit =
        runBlocking {
            val o1 =
                O1.Builder(executionContext)
                    .stringField("hello")
                    .objectField(
                        O2.Builder(executionContext)
                            .intField(1)
                            .build()
                    )
                    .enumField(E1.A)
                    .build()

            assertEquals("hello", o1.getStringField())
            assertEquals(1, o1.getObjectField()!!.getIntField())
            assertEquals(E1.A, o1.getEnumField())
            assertThrows<TenantUsageException> {
                runBlocking {
                    o1.getObjectField()!!.getObjectField()
                }
            }
            assertInstanceOf(EngineObjectData::class.java, (o1.__engineObject as EngineObjectData).fetch("objectField"))
        }

    @Test
    fun `fetch -- aliased scalar`(): Unit =
        runBlocking {
            val o1 = O1(
                internalContext,
                EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                    .put("aliasedStringField", "ALIASED")
                    .build()
            )
            assertEquals("ALIASED", o1.getStringField("aliasedStringField"))
        }

    @Test
    fun `fetch -- aliased object field`(): Unit =
        runBlocking {
            val o1 = O1(
                internalContext,
                EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                    .put(
                        "aliasedObjectField",
                        EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                            .put("aliasedIntField", 42)
                            .build()
                    )
                    .build()
            )
            assertEquals(42, o1.getObjectField("aliasedObjectField")?.getIntField("aliasedIntField"))
        }

    @Test
    fun `fetch -- aliased list of objects`(): Unit =
        runBlocking {
            val o1 = O1(
                internalContext,
                EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                    .put(
                        "aliasedListField",
                        listOf(
                            listOf(
                                EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                                    .put("intField", 42)
                                    .build()
                            )
                        )
                    )
                    .build()
            )
            assertEquals(42, o1.getListField("aliasedListField")?.get(0)?.get(0)?.getIntField())
        }

    @Test
    fun `test list of object with builder`(): Unit =
        runBlocking {
            val o1 =
                O1.Builder(executionContext)
                    .listField(
                        listOf(
                            null,
                            listOf(
                                O2.Builder(executionContext)
                                    .intField(5)
                                    .build(),
                                null
                            )
                        )
                    )
                    .build()

            val listField = o1.getListField()!!
            assertEquals(null, listField[0])
            val innerList = listField[1]!!
            assertEquals(5, innerList[0]!!.getIntField())
            assertEquals(null, innerList[1])
        }

    @Test
    fun `test wrap scalar and object`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("stringField", "hi")
                        .put(
                            "objectField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                                .put("intField", 1)
                                .build()
                        )
                        .build()
                )
            assertEquals("hi", o1.getStringField())
            assertEquals(1, o1.getObjectField()!!.getIntField())
        }

    @Test
    fun `test wrap scalar - wrong type string`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("stringField", 1)
                        .build()
                )
            val exception = assertThrows<FrameworkException> {
                runBlocking {
                    o1.getStringField()
                }
            }
            assertTrue(exception.message!!.contains("O1.stringField"))
            assertEquals("Expected a String input, but it was a 'Integer'", exception.cause!!.message)
        }

    @Test
    fun `test wrap scalar - wrong type int`(): Unit =
        runBlocking {
            val o2 =
                O2(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                        .put("intField", "hi")
                        .build()
                )
            val exception = assertThrows<FrameworkException> {
                runBlocking {
                    o2.getIntField()
                }
            }
            assertTrue(exception.message!!.contains("O2.intField"))
            assertEquals("Expected a value that can be converted to type 'Int' but it was a 'String'", exception.cause!!.message)
        }

    @Test
    fun `test wrap enum`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("enumField", "A")
                        .build()
                )
            assertEquals(E1.A, o1.getEnumField())
        }

    @Test
    fun `test wrap enum - different enum type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("enumField", BadE1.A)
                        .build()
                )
            assertEquals(E1.A, o1.getEnumField())
        }

    @Test
    fun `test wrap enum - wrong value`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("enumField", 1)
                        .build()
                )
            val exception = assertThrows<FrameworkException> {
                runBlocking {
                    o1.getEnumField()
                }
            }
            assertTrue(exception.message!!.contains("O1.enumField"))
            assertTrue(exception.cause!!.message!!.contains("No enum constant"))
        }

    @Test
    fun `test wrap enum - wrong type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "enumField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                                .put("intField", 1)
                                .build()
                        )
                        .build()
                )
            val exception = assertThrows<FrameworkException> {
                runBlocking {
                    o1.getEnumField()
                }
            }
            assertTrue(exception.message!!.contains("O1.enumField"))
            assertTrue(exception.cause!!.message!!.contains("No enum constant"))
        }

    @Test
    fun `test enum schema version skew - string value accepted for runtime schema`(): Unit =
        runBlocking {
            // This test verifies schema version skew tolerance where:
            // 1. Runtime GraphQL schema has enum value "C" (new deployment)
            // 2. Compiled Java enum E1 only has values A and B
            // 3. String value "C" should be accepted by dynamic builder API
            //    because it's valid in runtime schema, even though E1.valueOf("C") would fail

            // Create a schema with enum E1 having additional value "C" not in compiled enum
            val schemaWithNewEnumValue = ApiTestSchema.createSchema(
                """
                enum E1 {
                  A
                  B
                  C
                }
                type O1 {
                  id: ID!
                  enumField: E1
                }
                """.trimIndent()
            )
            val contextWithNewEnum = MockInternalContext.create(schemaWithNewEnumValue, "viaduct.api.testschema")

            // Test that string value "C" is accepted via dynamic builder API
            // The value exists in runtime schema but not in compiled E1 enum
            val o1 =
                O1(
                    contextWithNewEnum,
                    EngineObjectDataBuilder.from(schemaWithNewEnumValue.schema.getObjectType("O1"))
                        .put("enumField", "C") // String value for version skew tolerance
                        .build()
                )

            // The enum field should be readable and return "C" as a string
            // (Note: getEnumField() will try to convert to E1 enum, which will fail)
            // This test verifies the dynamic builder accepts the value
            val engineData = o1.__engineObject as EngineObjectData
            assertEquals("C", engineData.fetch("enumField"))
        }

    @Test
    fun `test wrap interface`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "interfaceField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("I1"))
                                .put("commonField", "from I1")
                                .build()
                        )
                        .build()
                )
            assertEquals("from I1", (o1.getInterfaceField() as I1).getCommonField())
        }

    @Test
    fun `test wrap interface - wrong object type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "interfaceField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                                .put("commonField", "from I1")
                                .build()
                        )
                        .build()
                )
            val exception = assertThrows<FrameworkException> {
                runBlocking {
                    o1.getInterfaceField()
                }
            }
            assertTrue(exception.message!!.contains("O1.interfaceField"))
            assertEquals("Expected value to be a subtype of I0, got O2", exception.cause!!.message)
        }

    @Test
    fun `test wrap interface - wrong type not EngineObjectData`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("interfaceField", "hi")
                        .build()
                )
            val exception = assertThrows<TenantUsageException> {
                runBlocking {
                    o1.getInterfaceField()
                }
            }
            assertEquals("Expected value to be an instance of EngineObjectData, got hi", exception.message)
        }

    @Test
    fun `test wrap object - wrong type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "objectField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("I1"))
                                .put("commonField", "from I1")
                                .build()
                        )
                        .build()
                )
            val exception = assertThrows<FrameworkException> {
                runBlocking {
                    o1.getObjectField()
                }
            }
            assertTrue(exception.message!!.contains("O1.objectField"))
            assertEquals("Expected value with GraphQL type O2, got I1", exception.cause!!.message)
        }

    @Test
    fun `test wrap list - wrong base type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "listField",
                            listOf(
                                listOf(
                                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("I1"))
                                        .put("commonField", "from I1")
                                        .build()
                                )
                            )
                        )
                        .build()
                )
            val exception = assertThrows<FrameworkException> {
                runBlocking {
                    o1.getListField()
                }
            }
            assertTrue(exception.message!!.contains("O1.listField"))
            assertEquals("Expected value with GraphQL type O2, got I1", exception.cause!!.message)
        }

    @Test
    fun `test wrap list - wrong type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "listField",
                            listOf(
                                EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("I1"))
                                    .put("commonField", "from I1")
                                    .build()
                            )
                        )
                        .build()
                )
            val exception = assertThrows<TenantUsageException> {
                runBlocking {
                    o1.getListField()
                }
            }
            assertTrue(exception.message!!.contains("Got non-list value"))
        }

    @Test
    fun `test wrap list - wrong with null`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "listFieldNonNullBaseType",
                            listOf(
                                null
                            )
                        )
                        .build()
                )
            val exception = assertThrows<TenantUsageException> {
                runBlocking {
                    o1.getListFieldNonNullBaseType()
                }
            }
            assertEquals("Got null value for non-null type [O2!]!", exception.message)
        }

    @Test
    fun `test wrap list - wrong with null base type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "listFieldNonNullBaseType",
                            listOf(
                                listOf(null)
                            )
                        )
                        .build()
                )
            val exception = assertThrows<TenantUsageException> {
                runBlocking {
                    o1.getListFieldNonNullBaseType()
                }
            }
            assertEquals("Got null value for non-null type O2!", exception.message)
        }

    @Test
    fun `test wrapping - framework error null value`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "objectField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                                .put("intField", null)
                                .build()
                        )
                        .build()
                )
            val objectField = o1.getObjectField()!!
            val exception = assertThrows<TenantUsageException> {
                runBlocking {
                    objectField.getIntField()
                }
            }
            assertEquals("Got null value for non-null type Int!", exception.message)
        }

    @Test
    fun `test unwrapping - framework errors`(): Unit =
        runBlocking {
            val builder = BuggyBuilder()
            val e1 = assertThrows<TenantUsageException> { builder.intField(null) }
            assertEquals("Got null builder value for non-null type Int!", e1.message)
            val e2 = assertThrows<TenantUsageException> { builder.objectField(4) }
            assertEquals("Expected ObjectBase or EngineObjectData for builder value, got 4", e2.message)
        }

    @Test
    fun `test unwrapping - wrong object type in list element is caught despite type erasure`(): Unit =
        runBlocking {
            val o1 = O1.Builder(executionContext).stringField("hello").build()
            val builder = BuggyO1Builder()
            // listField is [[O2]], but we pass O1 as the inner list element.
            // JVM type erasure means List<O1> and List<O2> look identical at runtime,
            // so the type check must happen inside unwrapObject, not via generics.
            val e = assertThrows<TenantUsageException> {
                builder.listField(listOf(listOf(o1)))
            }
            assertEquals("Expected object of type O2 for builder value, got O1", e.message)
        }

    @Test
    fun `test wrap - backing data`(): Unit =
        runBlocking {
            var o2 =
                O2(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                        .put("backingDataField", "abc")
                        .build()
                )
            assertEquals("abc", o2.get("backingDataField", String::class))

            o2 =
                O2(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                        .put("backingDataField", "abc")
                        .build()
                )
            val exception = assertThrows<TenantUsageException> {
                o2.get(
                    "backingDataField",
                    Int::class
                )
            }
            assertTrue(exception.message!!.contains("Expected backing data value to be of type Int, got String"))
        }

    @Test
    fun `test wrap - list backing data`(): Unit =
        runBlocking {
            var o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("backingDataList", listOf(1))
                        .build()
                )
            assertEquals(listOf(1), o1.get("backingDataList", Int::class))

            o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("backingDataList", listOf(1))
                        .build()
                )
            val exception = assertThrows<TenantUsageException> { o1.get("backingDataList", String::class) }
            assertTrue(exception.message!!.contains("Expected backing data value to be of type String, got Int"))
        }

    private abstract inner class NR : NodeReference {
        override val type = gqlSchema.schema.getObjectType("O1")
    }

    private inner class MockRootFieldRef(typeName: String = "O1") : RootFieldReference, EngineObjectData {
        override val rootFieldPath = listOf("_factories", "create")
        override val type = gqlSchema.schema.getObjectType(typeName)
        override val args = emptyMap<String, Any?>()

        override suspend fun fetch(selection: String): Any? = error("should not be called")

        override suspend fun fetchOrNull(selection: String): Any? = error("should not be called")

        override suspend fun fetchSelections(): Iterable<String> = error("should not be called")
    }

    @Test
    fun `test noderef - fetch`(): Unit =
        runBlocking {
            val o1 = O1(
                internalContext,
                object : NR() {
                    override val id = O1.Reflection.testGlobalId("foo")
                }
            )
            val globalId = o1.getId()
            assertEquals("O1", globalId.type.name)
            assertEquals("foo", globalId.internalID)
            assertThrows<FrameworkException> { o1.get("thisFieldDoesNotExist", String::class) }
            assertThrows<UnsetFieldException> { o1.getStringField() }
        }

    @Test
    fun `test rootFieldRef - field access throws immediately`(): Unit =
        runBlocking {
            val o1 = O1(internalContext, MockRootFieldRef())
            val exception = assertThrows<UnsetFieldException> { o1.getStringField() }
            assertTrue(exception.message!!.contains("rootFieldRef"))
        }

    @Test
    fun `test various exceptions`(): Unit =
        runBlocking {
            val o11 = O1(
                internalContext,
                object : NR() {
                    override val id get() = ExceptionsForTesting.throwTenantException("foo")
                }
            )
            val e11 = runCatching { o11.getId() }.exceptionOrNull()!!
            assertInstanceOf(TenantException::class.java, e11)
            assertEquals("foo", e11.message)

            val o12 = O1(
                internalContext,
                object : NR() {
                    override val id get() = ExceptionsForTesting.throwFrameworkException("foo")
                }
            )
            assertEquals(
                "foo",
                assertThrows<FrameworkException> { o12.getId() }.message
            )

            val o13 = O1(
                internalContext,
                object : NR() {
                    override val id get() = throw RuntimeException("foo")
                }
            )
            val e13 = runCatching { o13.getId() }.exceptionOrNull()!!
            assertInstanceOf(FrameworkException::class.java, e13)
            assertEquals("foo", e13.cause!!.message)
        }

    inner class BuggyBuilder : ObjectBase.Builder<O2>(internalContext, gqlSchema.schema.getObjectType("O2"), null) {
        fun intField(v: Any?): BuggyBuilder {
            putInternal("intField", v)
            return this
        }

        fun objectField(v: Any?): BuggyBuilder {
            putInternal("objectField", v)
            return this
        }

        override fun build() = O2(__context, buildEngineObjectData())
    }

    inner class BuggyO1Builder : ObjectBase.Builder<O1>(internalContext, gqlSchema.schema.getObjectType("O1"), null) {
        fun listField(v: Any?): BuggyO1Builder {
            putInternal("listField", v)
            return this
        }

        override fun build() = O1(__context, buildEngineObjectData())
    }

    private class TestObject(
        context: InternalContext,
        engineObject: EngineObject
    ) : ObjectBase(context, engineObject), viaduct.api.types.Object {
        suspend fun getIntField(): Int = fetch("intField", Int::class, null)

        suspend fun getIntFieldOrNull(): Int? = fetchOrNull("intField", Int::class, null)

        suspend fun getArgumentedField(): String? = fetch("argumentedField", String::class, null)

        suspend fun getArgumentedFieldOrNull(): String? = fetchOrNull("argumentedField", String::class, null)

        // toBuilder implementation that would normally be provided by codegen
        fun toBuilder(): Builder =
            Builder(
                __context,
                __engineObject.type,
                toBuilderEOD()
            )

        class Builder(
            context: InternalContext,
            type: GraphQLObjectType,
            baseEngineObjectData: EngineObjectData.Sync? = null
        ) : ObjectBase.Builder<TestObject>(context, type, baseEngineObjectData) {
            constructor(context: viaduct.api.context.ExecutionContext) : this(
                context.internal,
                context.internal.schema.schema.getObjectType("O2"), // Use existing O2 type
                null
            )

            fun intField(value: Int): Builder {
                putInternal("intField", value)
                return this
            }

            fun argumentedField(value: String?): Builder {
                putInternal("argumentedField", value)
                return this
            }

            override fun build() = TestObject(__context, buildEngineObjectData())
        }
    }

    @Nested
    inner class ToBuilderTests {
        @Test
        fun `toBuilder preserves unmodified fields`() =
            runBlocking {
                val original = TestObject.Builder(executionContext)
                    .intField(42)
                    .argumentedField("hello")
                    .build()

                val updated = original.toBuilder()
                    .intField(99)
                    .build()

                assertEquals(99, updated.getIntField())
                assertEquals("hello", updated.getArgumentedField())
            }

        @Test
        fun `toBuilder allows multiple field overrides`() =
            runBlocking {
                val original = TestObject.Builder(executionContext)
                    .intField(1)
                    .argumentedField("original")
                    .build()

                val updated = original.toBuilder()
                    .intField(2)
                    .argumentedField("updated")
                    .build()

                assertEquals(2, updated.getIntField())
                assertEquals("updated", updated.getArgumentedField())
            }

        @Test
        fun `toBuilder throws on NodeReference`() {
            val nodeRef = object : NodeReference {
                override val type = gqlSchema.schema.getObjectType("O2")
                override val id = "O2:test-id"
            }
            val testObject = TestObject(internalContext, nodeRef)

            val exception = assertThrows<TenantUsageException> {
                testObject.toBuilder()
            }

            assertTrue(exception.message!!.contains("Cannot call toBuilder()"))
            assertTrue(exception.message!!.contains("NodeReference"))
        }

        @Test
        fun `toBuilder throws on RootFieldReference`() {
            val testObject = TestObject(internalContext, MockRootFieldRef("O2"))

            val exception = assertThrows<TenantUsageException> {
                testObject.toBuilder()
            }

            assertTrue(exception.message!!.contains("Cannot call toBuilder()"))
            assertTrue(exception.message!!.contains("RootFieldReference"))
        }

        @Test
        fun `toBuilder with no overrides creates equivalent object`() =
            runBlocking {
                val original = TestObject.Builder(executionContext)
                    .intField(123)
                    .argumentedField("test")
                    .build()

                val copy = original.toBuilder().build()

                assertEquals(original.getIntField(), copy.getIntField())
                assertEquals(original.getArgumentedField(), copy.getArgumentedField())
            }

        @Test
        fun `fetchOrNull returns value on success`() =
            runBlocking {
                val o = TestObject.Builder(executionContext)
                    .intField(42)
                    .argumentedField("hello")
                    .build()

                assertEquals(42, o.getIntFieldOrNull())
                assertEquals("hello", o.getArgumentedFieldOrNull())
            }

        @Test
        fun `fetchOrNull rethrows UnsetFieldException`() =
            runBlocking {
                // intField is not set on the builder, so fetch() throws UnsetFieldException
                val o = TestObject.Builder(executionContext).build()

                assertThrows<UnsetFieldException> { runBlocking { o.getIntField() } }
                assertThrows<UnsetFieldException> { runBlocking { o.getIntFieldOrNull() } }
            }

        @Test
        fun `fetchOrNull rethrows UnsetFieldException from NodeReference`() =
            runBlocking {
                val nodeRef = object : NodeReference {
                    override val type = gqlSchema.schema.getObjectType("O2")
                    override val id get() = throw RuntimeException("boom")
                }
                val o = TestObject(internalContext, nodeRef)

                // fetch() on a NodeReference for a non-id field throws UnsetFieldException
                assertThrows<UnsetFieldException> { runBlocking { o.getIntFieldOrNull() } }
            }

        @Test
        fun `chained toBuilder calls work correctly`() =
            runBlocking {
                val v1 = TestObject.Builder(executionContext)
                    .intField(1)
                    .argumentedField("v1")
                    .build()

                val v2 = v1.toBuilder()
                    .intField(2)
                    .build()

                val v3 = v2.toBuilder()
                    .argumentedField("v3")
                    .build()

                // v3 should have argumentedField from v3, intField from v2
                assertEquals(2, v3.getIntField())
                assertEquals("v3", v3.getArgumentedField())

                // v2 should be unchanged
                assertEquals(2, v2.getIntField())
                assertEquals("v1", v2.getArgumentedField())

                // v1 should be unchanged
                assertEquals(1, v1.getIntField())
                assertEquals("v1", v1.getArgumentedField())
            }
    }
}
